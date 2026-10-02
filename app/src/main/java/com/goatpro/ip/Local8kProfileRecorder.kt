package com.goatpro.ip

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.CamcorderProfile
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Final public-API diagnostic for Samsung 8K.
 *
 * Uses the classic official QUALITY_8KUHD CamcorderProfile and applies it
 * through MediaRecorder.setProfile(profile), writing to a normal local MP4/3GP
 * file. No RTSP, pipes or CameraX are involved in this test.
 */
class Local8kProfileRecorder(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onProfileFound(
            cameraId: String,
            width: Int,
            height: Int,
            fps: Int,
            bitrate: Int,
            videoCodec: Int,
            fileFormat: Int
        )

        fun onStarted(file: File, width: Int, height: Int)
        fun onCompleted(
            file: File,
            actualWidth: Int,
            actualHeight: Int,
            durationMs: Long
        )
        fun onError(message: String)
        fun onStopped()
    }

    private val lock = Any()
    private val starting = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    private var recorder: MediaRecorder? = null
    private var recorderStarted = false
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var outputFile: File? = null
    private var startedAtMs = 0L
    private var configuredWidth = 0
    private var configuredHeight = 0

    fun isStarting(): Boolean = starting.get()
    fun isRunning(): Boolean = running.get()

    @Suppress("DEPRECATION")
    fun start(): Boolean {
        synchronized(lock) {
            if (starting.get() || running.get()) return true

            val manager =
                context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val candidate = manager.cameraIdList.firstNotNullOfOrNull { id ->
                val numericId = id.toIntOrNull()
                    ?: return@firstNotNullOfOrNull null
                val chars = runCatching {
                    manager.getCameraCharacteristics(id)
                }.getOrNull() ?: return@firstNotNullOfOrNull null

                if (
                    chars.get(CameraCharacteristics.LENS_FACING) !=
                        CameraCharacteristics.LENS_FACING_BACK
                ) {
                    return@firstNotNullOfOrNull null
                }

                if (
                    !CamcorderProfile.hasProfile(
                        numericId,
                        CamcorderProfile.QUALITY_8KUHD
                    )
                ) {
                    return@firstNotNullOfOrNull null
                }

                val profile = runCatching {
                    CamcorderProfile.get(
                        numericId,
                        CamcorderProfile.QUALITY_8KUHD
                    )
                }.getOrNull() ?: return@firstNotNullOfOrNull null

                if (
                    profile.videoFrameWidth != 7680 ||
                    profile.videoFrameHeight != 4320
                ) {
                    return@firstNotNullOfOrNull null
                }

                Pair(id, profile)
            }

            if (candidate == null) {
                listener.onError(
                    "O sistema não entregou um CamcorderProfile 8KUHD 7680×4320 para app de terceiros."
                )
                return false
            }

            val cameraId = candidate.first
            val profile = candidate.second

            listener.onProfileFound(
                cameraId = cameraId,
                width = profile.videoFrameWidth,
                height = profile.videoFrameHeight,
                fps = profile.videoFrameRate,
                bitrate = profile.videoBitRate,
                videoCodec = profile.videoCodec,
                fileFormat = profile.fileFormat
            )

            return try {
                val dir = File(
                    context.getExternalFilesDir(
                        Environment.DIRECTORY_MOVIES
                    ) ?: context.filesDir,
                    "GOAT-Cam"
                ).apply { mkdirs() }

                val file = File(
                    dir,
                    "GOAT-Cam-8K-Test-" +
                        System.currentTimeMillis() + ".mp4"
                )
                outputFile = file
                configuredWidth = profile.videoFrameWidth
                configuredHeight = profile.videoFrameHeight

                val localRecorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)

                    // Critical difference from prior builds: apply the entire
                    // official 8KUHD profile exactly as Android/Samsung reports it.
                    setProfile(profile)

                    setOutputFile(file.absolutePath)
                    setOrientationHint(90)
                    setOnErrorListener { _, what, extra ->
                        fail(
                            "MediaRecorder 8KUHD erro " +
                                what + "/" + extra
                        )
                    }
                    prepare()
                }

                recorder = localRecorder
                starting.set(true)

                val cameraThread = HandlerThread(
                    "goat-local-8k-profile"
                ).apply { start() }
                val cameraHandler = Handler(cameraThread.looper)
                thread = cameraThread
                handler = cameraHandler

                manager.openCamera(
                    cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            synchronized(lock) {
                                if (!starting.get()) {
                                    runCatching { camera.close() }
                                    return
                                }
                                cameraDevice = camera
                            }
                            createSession(
                                manager,
                                camera,
                                cameraId,
                                cameraHandler
                            )
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            fail(
                                "A câmera desconectou durante o teste 8K local."
                            )
                        }

                        override fun onError(
                            camera: CameraDevice,
                            error: Int
                        ) {
                            fail(
                                "Camera2 recusou o teste 8K local. Código " +
                                    error
                            )
                        }
                    },
                    cameraHandler
                )

                true
            } catch (ex: Exception) {
                stopLocked(deleteFailedFile = true)
                listener.onError(
                    "Falha ao preparar perfil 8KUHD: " +
                        (ex.message ?: ex.javaClass.simpleName)
                )
                false
            }
        }
    }

    private fun createSession(
        manager: CameraManager,
        camera: CameraDevice,
        cameraId: String,
        cameraHandler: Handler
    ) {
        val surface = recorder?.surface ?: run {
            fail("Surface do MediaRecorder 8KUHD indisponível.")
            return
        }

        val callback =
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(
                    session: CameraCaptureSession
                ) {
                    synchronized(lock) {
                        if (!starting.get()) {
                            runCatching { session.close() }
                            return
                        }
                        captureSession = session
                    }

                    try {
                        val request = camera.createCaptureRequest(
                            CameraDevice.TEMPLATE_RECORD
                        ).apply {
                            addTarget(surface)
                            set(
                                CaptureRequest.CONTROL_MODE,
                                CaptureRequest.CONTROL_MODE_AUTO
                            )
                            set(
                                CaptureRequest.CONTROL_CAPTURE_INTENT,
                                CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
                            )
                        }

                        session.setRepeatingRequest(
                            request.build(),
                            null,
                            cameraHandler
                        )

                        recorder?.start()
                        recorderStarted = true
                        startedAtMs = System.currentTimeMillis()
                        starting.set(false)
                        running.set(true)

                        val file = outputFile
                            ?: throw IllegalStateException(
                                "Arquivo 8K não foi criado."
                            )

                        listener.onStarted(
                            file,
                            configuredWidth,
                            configuredHeight
                        )

                        cameraHandler.postDelayed(
                            { completeAfterFiveSeconds() },
                            5_000L
                        )
                    } catch (ex: Exception) {
                        fail(
                            "Falha ao iniciar gravação 8KUHD: " +
                                (ex.message ?: ex.javaClass.simpleName)
                        )
                    }
                }

                override fun onConfigureFailed(
                    session: CameraCaptureSession
                ) {
                    fail(
                        "A câmera recusou a sessão local oficial " +
                            configuredWidth + "×" +
                            configuredHeight + "."
                    )
                }
            }

        val output = OutputConfiguration(surface)

        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                val cases = manager
                    .getCameraCharacteristics(cameraId)
                    .get(
                        CameraCharacteristics
                            .SCALER_AVAILABLE_STREAM_USE_CASES
                    ) ?: longArrayOf()

                val recordUseCase =
                    android.hardware.camera2.CameraMetadata
                        .SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD
                        .toLong()

                if (cases.contains(recordUseCase)) {
                    output.setStreamUseCase(recordUseCase)
                }
            }
        }

        val executor = Executor { command ->
            cameraHandler.post(command)
        }
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(output),
            executor,
            callback
        )
        camera.createCaptureSession(config)
    }

    private fun completeAfterFiveSeconds() {
        synchronized(lock) {
            if (!running.get()) return

            val file = outputFile
            val durationMs =
                (System.currentTimeMillis() - startedAtMs)
                    .coerceAtLeast(0L)

            val stopOk = runCatching {
                captureSession?.stopRepeating()
                captureSession?.abortCaptures()
                if (recorderStarted) {
                    recorder?.stop()
                }
            }.isSuccess

            recorderStarted = false
            running.set(false)
            starting.set(false)

            runCatching { captureSession?.close() }
            captureSession = null
            runCatching { cameraDevice?.close() }
            cameraDevice = null

            runCatching { recorder?.reset() }
            runCatching { recorder?.release() }
            recorder = null

            runCatching { thread?.quitSafely() }
            thread = null
            handler = null

            if (!stopOk || file == null || !file.exists()) {
                runCatching { file?.delete() }
                outputFile = null
                listener.onError(
                    "A gravação 8K local iniciou, mas não finalizou corretamente."
                )
                return
            }

            val dimensions = readVideoDimensions(file)
            val actualWidth = dimensions.first
            val actualHeight = dimensions.second
            outputFile = null

            listener.onCompleted(
                file,
                actualWidth,
                actualHeight,
                durationMs
            )
        }
    }

    fun stop() {
        synchronized(lock) {
            val wasActive = starting.get() || running.get()
            stopLocked(deleteFailedFile = true)
            if (wasActive) listener.onStopped()
        }
    }

    private fun fail(message: String) {
        synchronized(lock) {
            val wasActive = starting.get() || running.get()
            stopLocked(deleteFailedFile = true)
            if (wasActive) {
                listener.onError(message)
            }
        }
    }

    private fun stopLocked(deleteFailedFile: Boolean) {
        starting.set(false)
        running.set(false)

        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        captureSession = null

        runCatching { cameraDevice?.close() }
        cameraDevice = null

        if (recorderStarted) {
            runCatching { recorder?.stop() }
        }
        recorderStarted = false
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null

        runCatching { thread?.quitSafely() }
        thread = null
        handler = null

        if (deleteFailedFile) {
            runCatching { outputFile?.delete() }
        }
        outputFile = null
        configuredWidth = 0
        configuredHeight = 0
        startedAtMs = 0L
    }

    private fun readVideoDimensions(file: File): Pair<Int, Int> {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val width = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toIntOrNull() ?: 0
                Pair(
                    maxOf(width, height),
                    minOf(width, height)
                )
            } finally {
                retriever.release()
            }
        }.getOrDefault(Pair(0, 0))
    }
}
