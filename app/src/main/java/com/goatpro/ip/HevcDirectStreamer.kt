package com.goatpro.ip

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct Camera2 -> MediaCodec HEVC path used only for resolutions above 4K.
 *
 * This bypasses ImageAnalysis/NV21/JPEG so 8K frames do not travel through the
 * CPU-side conversion path used by the normal GOAT Cam stream.
 */
class HevcDirectStreamer(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onAccessUnit(
            data: ByteArray,
            presentationTimeUs: Long,
            keyFrame: Boolean,
            codecConfig: Boolean
        )

        fun onStarted(width: Int, height: Int, fps: Int, bitrate: Int)
        fun onError(message: String)
        fun onStopped()
    }

    private val lock = Any()
    private val running = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var drainThread: Thread? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var configuredWidth = 0
    private var configuredHeight = 0
    private var configuredFps = 0
    private var configuredBitrate = 0

    fun isRunning(): Boolean = running.get()
    fun isStarting(): Boolean = starting.get()

    fun supports(width: Int, height: Int, fps: Int = 10): Boolean {
        return try {
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            try {
                val caps = encoder.codecInfo
                    .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                val video = caps.videoCapabilities
                video.isSizeSupported(width, height) &&
                    (
                        runCatching {
                            video.areSizeAndRateSupported(
                                width,
                                height,
                                fps.toDouble()
                            )
                        }.getOrDefault(true) ||
                            video.isSizeSupported(width, height)
                        )
            } finally {
                encoder.release()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun start(
        logicalCameraId: String,
        physicalCameraId: String?,
        width: Int,
        height: Int,
        fps: Int = 10,
        bitrate: Int = recommendedBitrate(width, height, fps)
    ): Boolean {
        synchronized(lock) {
            if (running.get() || starting.get()) return true

            val safeFps = fps.coerceIn(5, 15)
            val safeBitrate = bitrate.coerceIn(8_000_000, 80_000_000)

            return try {
                val encoder =
                    MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                val caps = encoder.codecInfo
                    .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)

                // Do not reject experimental 8K only because codec
                // capabilities under-report the size. Some OEM stacks expose
                // first-party modes incompletely through public capability
                // tables. Configure the exact requested size and let the
                // codec/camera session accept or reject the real 7680x4320
                // path.
                val bitrateMode =
                    if (
                        caps.encoderCapabilities.isBitrateModeSupported(
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                        )
                    ) {
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    } else {
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    }

                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_HEVC,
                    width,
                    height
                ).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, safeBitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, safeFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
                    runCatching { setInteger(MediaFormat.KEY_PRIORITY, 0) }
                    runCatching {
                        setFloat(
                            MediaFormat.KEY_OPERATING_RATE,
                            safeFps.toFloat()
                        )
                    }
                    if (
                        Build.VERSION.SDK_INT >= 30 &&
                        caps.isFeatureSupported(
                            MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency
                        )
                    ) {
                        runCatching { setInteger("low-latency", 1) }
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        runCatching {
                            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                        }
                        runCatching {
                            setInteger(
                                "prepend-sps-pps-to-idr-frames",
                                1
                            )
                        }
                    }
                }

                encoder.configure(
                    format,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
                )
                val surface = encoder.createInputSurface()
                encoder.start()

                val thread = HandlerThread("goat-hevc-camera").apply {
                    start()
                }
                val handler = Handler(thread.looper)

                codec = encoder
                inputSurface = surface
                cameraThread = thread
                cameraHandler = handler
                configuredWidth = width
                configuredHeight = height
                configuredFps = safeFps
                configuredBitrate = safeBitrate
                starting.set(true)

                startDrainThread(encoder)
                openCamera(
                    logicalCameraId,
                    physicalCameraId,
                    handler
                )
                true
            } catch (ex: Exception) {
                stopLocked()
                listener.onError(
                    "Falha ao preparar HEVC: " +
                        (ex.message ?: ex.javaClass.simpleName)
                )
                false
            }
        }
    }

    fun requestKeyFrame() {
        val encoder = codec ?: return
        if (!running.get() && !starting.get()) return
        runCatching {
            val params = android.os.Bundle()
            params.putInt(
                MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,
                0
            )
            encoder.setParameters(params)
        }
    }

    fun stop() {
        synchronized(lock) {
            val wasActive = running.get() || starting.get()
            stopLocked()
            if (wasActive) listener.onStopped()
        }
    }

    private fun openCamera(
        logicalCameraId: String,
        physicalCameraId: String?,
        handler: Handler
    ) {
        val manager =
            context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        manager.openCamera(
            logicalCameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    synchronized(lock) {
                        if (!starting.get()) {
                            runCatching { camera.close() }
                            return
                        }
                        cameraDevice = camera
                    }
                    createCaptureSession(
                        manager,
                        camera,
                        logicalCameraId,
                        physicalCameraId,
                        handler
                    )
                }

                override fun onDisconnected(camera: CameraDevice) {
                    fail("Câmera desconectada durante o modo HEVC.")
                }

                override fun onError(
                    camera: CameraDevice,
                    error: Int
                ) {
                    fail("Erro Camera2 no modo HEVC: " + error)
                }
            },
            handler
        )
    }

    private fun createCaptureSession(
        manager: CameraManager,
        camera: CameraDevice,
        logicalCameraId: String,
        physicalCameraId: String?,
        handler: Handler
    ) {
        val surface = inputSurface ?: run {
            fail("Surface HEVC indisponível.")
            return
        }

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
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
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        )
                        set(
                            CaptureRequest.CONTROL_CAPTURE_INTENT,
                            CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
                        )

                        val characteristicId =
                            physicalCameraId ?: logicalCameraId
                        val ranges = runCatching {
                            manager.getCameraCharacteristics(characteristicId)
                                .get(
                                    CameraCharacteristics
                                        .CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                                )
                        }.getOrNull().orEmpty()

                        val targetFps = configuredFps
                        val range = ranges
                            .filter {
                                it.lower <= targetFps &&
                                    it.upper >= targetFps
                            }
                            .minWithOrNull(
                                compareBy<android.util.Range<Int>> {
                                    it.upper - it.lower
                                }.thenBy {
                                    kotlin.math.abs(it.upper - targetFps)
                                }
                            )

                        if (range != null) {
                            set(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                range
                            )
                        }
                    }

                    session.setRepeatingRequest(
                        request.build(),
                        null,
                        handler
                    )

                    starting.set(false)
                    running.set(true)
                    listener.onStarted(
                        configuredWidth,
                        configuredHeight,
                        configuredFps,
                        configuredBitrate
                    )
                } catch (ex: Exception) {
                    fail(
                        "Falha ao iniciar captura HEVC: " +
                            (ex.message ?: ex.javaClass.simpleName)
                    )
                }
            }

            override fun onConfigureFailed(
                session: CameraCaptureSession
            ) {
                fail(
                    "A câmera recusou a sessão " +
                        configuredWidth + "x" + configuredHeight +
                        " HEVC."
                )
            }
        }

        if (Build.VERSION.SDK_INT >= 28) {
            val output = OutputConfiguration(surface)

            if (physicalCameraId != null) {
                output.setPhysicalCameraId(physicalCameraId)
            }

            if (Build.VERSION.SDK_INT >= 33) {
                runCatching {
                    val supportedUseCases = manager
                        .getCameraCharacteristics(logicalCameraId)
                        .get(
                            CameraCharacteristics
                                .SCALER_AVAILABLE_STREAM_USE_CASES
                        )
                        .orEmpty()

                    if (
                        supportedUseCases.contains(
                            CameraMetadata
                                .SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD
                        )
                    ) {
                        output.setStreamUseCase(
                            CameraMetadata
                                .SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD
                        )
                    }
                }
            }

            val executor = Executor { command ->
                handler.post(command)
            }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(output),
                executor,
                callback
            )
            camera.createCaptureSession(config)
        } else {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(surface),
                callback,
                handler
            )
        }
    }

    private fun startDrainThread(encoder: MediaCodec) {
        drainThread = Thread {
            val info = MediaCodec.BufferInfo()
            while (
                (starting.get() || running.get()) &&
                codec === encoder
            ) {
                try {
                    when (
                        val index =
                            encoder.dequeueOutputBuffer(info, 10_000)
                    ) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = encoder.outputFormat
                            listOf("csd-0", "csd-1", "csd-2")
                                .forEach { key ->
                                    format.getByteBuffer(key)
                                        ?.let { buffer ->
                                            byteBufferToBytes(buffer)
                                        }
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { bytes ->
                                            listener.onAccessUnit(
                                                bytes,
                                                0L,
                                                false,
                                                true
                                            )
                                        }
                                }
                        }

                        else -> if (index >= 0) {
                            val output =
                                encoder.getOutputBuffer(index)
                            if (output != null && info.size > 0) {
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                output.get(bytes)

                                listener.onAccessUnit(
                                    bytes,
                                    info.presentationTimeUs,
                                    (
                                        info.flags and
                                            MediaCodec
                                                .BUFFER_FLAG_KEY_FRAME
                                        ) != 0,
                                    (
                                        info.flags and
                                            MediaCodec
                                                .BUFFER_FLAG_CODEC_CONFIG
                                        ) != 0
                                )
                            }
                            encoder.releaseOutputBuffer(index, false)
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }.apply {
            name = "goat-hevc-drain"
            isDaemon = true
            start()
        }
    }

    private fun byteBufferToBytes(buffer: ByteBuffer): ByteArray {
        val duplicate = buffer.duplicate()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }

    private fun fail(message: String) {
        synchronized(lock) {
            stopLocked()
        }
        listener.onError(message)
    }

    private fun stopLocked() {
        running.set(false)
        starting.set(false)

        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        captureSession = null

        runCatching { cameraDevice?.close() }
        cameraDevice = null

        val localCodec = codec
        codec = null
        runCatching { localCodec?.signalEndOfInputStream() }
        runCatching { localCodec?.stop() }
        runCatching { localCodec?.release() }

        runCatching { inputSurface?.release() }
        inputSurface = null

        runCatching { cameraThread?.quitSafely() }
        cameraThread = null
        cameraHandler = null
        drainThread = null

        configuredWidth = 0
        configuredHeight = 0
        configuredFps = 0
        configuredBitrate = 0
    }

    companion object {
        fun recommendedBitrate(
            width: Int,
            height: Int,
            fps: Int
        ): Int {
            val pixels = width.toLong() * height.toLong()
            return when {
                pixels >= 7680L * 4320L ->
                    if (fps >= 24) 45_000_000 else 36_000_000
                pixels > 3840L * 2160L -> 32_000_000
                else -> 24_000_000
            }
        }
    }
}
