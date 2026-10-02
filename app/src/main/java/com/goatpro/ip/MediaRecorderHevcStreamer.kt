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
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental 8K route that uses MediaRecorder's SURFACE instead of a
 * MediaCodec input surface. Samsung's native camera recording stack is closer
 * to this path and may accept combinations that a direct Camera2+MediaCodec
 * session rejects.
 *
 * MediaRecorder writes HEVC inside MPEG-2 TS to a pipe. A small TS/PES reader
 * extracts Annex-B HEVC access units and forwards them to the existing RTSP
 * H.265 server.
 */
class MediaRecorderHevcStreamer(
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

    private var recorder: MediaRecorder? = null
    private var recorderStarted = false
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var pipeRead: ParcelFileDescriptor? = null
    private var pipeWrite: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null

    private var configuredWidth = 0
    private var configuredHeight = 0
    private var configuredFps = 0
    private var configuredBitrate = 0

    fun isRunning(): Boolean = running.get()
    fun isStarting(): Boolean = starting.get()

    /**
     * MediaRecorder cannot force an immediate sync frame through a public API.
     * The RTSP client will receive the next encoder key frame.
     */
    fun requestKeyFrame() = Unit

    fun start(
        logicalCameraId: String,
        physicalCameraId: String?,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int
    ): Boolean {
        synchronized(lock) {
            if (running.get() || starting.get()) return true

            val safeFps = fps.coerceIn(5, 30)
            val safeBitrate = bitrate.coerceIn(8_000_000, 100_000_000)

            return try {
                val pipe = ParcelFileDescriptor.createPipe()
                pipeRead = pipe[0]
                pipeWrite = pipe[1]

                @Suppress("DEPRECATION")
                val localRecorder = MediaRecorder().apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_2_TS)
                    setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
                    setVideoSize(width, height)
                    setVideoFrameRate(safeFps)
                    setVideoEncodingBitRate(safeBitrate)
                    setOutputFile(pipeWrite!!.fileDescriptor)
                    setOnErrorListener { _, what, extra ->
                        fail(
                            "MediaRecorder HEVC erro " +
                                what + "/" + extra
                        )
                    }
                    prepare()
                }

                recorder = localRecorder
                configuredWidth = width
                configuredHeight = height
                configuredFps = safeFps
                configuredBitrate = safeBitrate
                starting.set(true)

                startTsReader()

                val thread = HandlerThread(
                    "goat-mediarecorder-8k-camera"
                ).apply { start() }
                val handler = Handler(thread.looper)
                cameraThread = thread
                cameraHandler = handler

                openCamera(
                    logicalCameraId,
                    physicalCameraId,
                    handler
                )
                true
            } catch (ex: Exception) {
                stopLocked()
                listener.onError(
                    "Falha ao preparar MediaRecorder 8K: " +
                        (ex.message ?: ex.javaClass.simpleName)
                )
                false
            }
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
                    fail(
                        "Câmera desconectada no MediaRecorder 8K."
                    )
                }

                override fun onError(
                    camera: CameraDevice,
                    error: Int
                ) {
                    fail(
                        "Erro Camera2/MediaRecorder 8K: " + error
                    )
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
        val surface = recorder?.surface ?: run {
            fail("Surface MediaRecorder 8K indisponível.")
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
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest
                                    .CONTROL_AF_MODE_CONTINUOUS_VIDEO
                            )
                            set(
                                CaptureRequest.CONTROL_CAPTURE_INTENT,
                                CaptureRequest
                                    .CONTROL_CAPTURE_INTENT_VIDEO_RECORD
                            )

                            val characteristicId =
                                physicalCameraId ?: logicalCameraId
                            val ranges = runCatching {
                                manager
                                    .getCameraCharacteristics(
                                        characteristicId
                                    )
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
                                        kotlin.math.abs(
                                            it.upper - targetFps
                                        )
                                    }
                                )

                            if (range != null) {
                                set(
                                    CaptureRequest
                                        .CONTROL_AE_TARGET_FPS_RANGE,
                                    range
                                )
                            }
                        }

                        session.setRepeatingRequest(
                            request.build(),
                            null,
                            handler
                        )

                        recorder?.start()
                        recorderStarted = true
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
                            "Falha ao iniciar MediaRecorder 8K: " +
                                (
                                    ex.message
                                        ?: ex.javaClass.simpleName
                                    )
                        )
                    }
                }

                override fun onConfigureFailed(
                    session: CameraCaptureSession
                ) {
                    fail(
                        "A câmera recusou a sessão MediaRecorder " +
                            configuredWidth + "x" +
                            configuredHeight + " HEVC."
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
                    val cases = manager
                        .getCameraCharacteristics(logicalCameraId)
                        .get(
                            CameraCharacteristics
                                .SCALER_AVAILABLE_STREAM_USE_CASES
                        ) ?: longArrayOf()

                    val recordUseCase =
                        CameraMetadata
                            .SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD
                            .toLong()

                    if (cases.contains(recordUseCase)) {
                        output.setStreamUseCase(recordUseCase)
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

    private fun startTsReader() {
        val readFd = pipeRead ?: return

        readerThread = Thread {
            val input = BufferedInputStream(
                ParcelFileDescriptor.AutoCloseInputStream(readFd),
                512 * 1024
            )
            val packet = ByteArray(188)
            var videoPid = -1
            var pesData = ByteArrayOutputStream(512 * 1024)
            var pesPtsUs = 0L
            var havePes = false

            fun flushPes() {
                if (!havePes || pesData.size() == 0) return
                val bytes = pesData.toByteArray()
                val keyFrame = containsHevcIrap(bytes)
                listener.onAccessUnit(
                    bytes,
                    if (pesPtsUs > 0L) {
                        pesPtsUs
                    } else {
                        System.nanoTime() / 1000L
                    },
                    keyFrame,
                    false
                )
                pesData = ByteArrayOutputStream(512 * 1024)
            }

            try {
                while (starting.get() || running.get()) {
                    if (!readTsPacket(input, packet)) break
                    if ((packet[0].toInt() and 0xff) != 0x47) {
                        continue
                    }

                    val payloadStart =
                        (packet[1].toInt() and 0x40) != 0
                    val pid =
                        ((packet[1].toInt() and 0x1f) shl 8) or
                            (packet[2].toInt() and 0xff)
                    val afc =
                        (packet[3].toInt() ushr 4) and 0x03
                    if (afc == 0 || afc == 2) continue

                    var offset = 4
                    if (afc == 3) {
                        if (offset >= packet.size) continue
                        val adaptationLength =
                            packet[offset].toInt() and 0xff
                        offset += 1 + adaptationLength
                    }
                    if (offset >= packet.size) continue

                    if (payloadStart) {
                        val pes = parsePesHeader(packet, offset)
                        if (pes != null && pes.video) {
                            if (videoPid < 0) {
                                videoPid = pid
                            }
                            if (pid == videoPid) {
                                flushPes()
                                havePes = true
                                pesPtsUs = pes.ptsUs
                                if (pes.payloadOffset < packet.size) {
                                    pesData.write(
                                        packet,
                                        pes.payloadOffset,
                                        packet.size -
                                            pes.payloadOffset
                                    )
                                }
                            }
                            continue
                        }
                    }

                    if (pid == videoPid && havePes) {
                        pesData.write(
                            packet,
                            offset,
                            packet.size - offset
                        )
                    }
                }
            } catch (_: Exception) {
            } finally {
                flushPes()
                runCatching { input.close() }
            }
        }.apply {
            name = "goat-mediarecorder-ts-reader"
            isDaemon = true
            start()
        }
    }

    private data class PesHeader(
        val video: Boolean,
        val payloadOffset: Int,
        val ptsUs: Long
    )

    private fun parsePesHeader(
        packet: ByteArray,
        offset: Int
    ): PesHeader? {
        if (offset + 9 > packet.size) return null
        if (
            packet[offset] != 0.toByte() ||
            packet[offset + 1] != 0.toByte() ||
            packet[offset + 2] != 1.toByte()
        ) {
            return null
        }

        val streamId = packet[offset + 3].toInt() and 0xff
        val video = streamId in 0xE0..0xEF
        if (!video) return PesHeader(false, offset, 0L)

        val flags2 = packet[offset + 7].toInt() and 0xff
        val headerLength = packet[offset + 8].toInt() and 0xff
        val payloadOffset = offset + 9 + headerLength
        if (payloadOffset > packet.size) return null

        var ptsUs = 0L
        if ((flags2 and 0x80) != 0 && offset + 14 <= packet.size) {
            val b0 = packet[offset + 9].toLong() and 0xffL
            val b1 = packet[offset + 10].toLong() and 0xffL
            val b2 = packet[offset + 11].toLong() and 0xffL
            val b3 = packet[offset + 12].toLong() and 0xffL
            val b4 = packet[offset + 13].toLong() and 0xffL

            val pts90k =
                (((b0 shr 1) and 0x07L) shl 30) or
                    (b1 shl 22) or
                    (((b2 shr 1) and 0x7fL) shl 15) or
                    (b3 shl 7) or
                    ((b4 shr 1) and 0x7fL)

            ptsUs = pts90k * 1_000_000L / 90_000L
        }

        return PesHeader(
            video = true,
            payloadOffset = payloadOffset,
            ptsUs = ptsUs
        )
    }

    private fun containsHevcIrap(data: ByteArray): Boolean {
        var i = 0
        while (i + 4 < data.size) {
            var startLength = 0
            if (
                data[i] == 0.toByte() &&
                data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte()
            ) {
                startLength = 3
            } else if (
                i + 4 < data.size &&
                data[i] == 0.toByte() &&
                data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() &&
                data[i + 3] == 1.toByte()
            ) {
                startLength = 4
            }

            if (startLength > 0) {
                val nalIndex = i + startLength
                if (nalIndex < data.size) {
                    val nalType =
                        (data[nalIndex].toInt() ushr 1) and 0x3f
                    if (nalType in 16..23) return true
                }
                i += startLength
            } else {
                i++
            }
        }
        return false
    }

    private fun readTsPacket(
        input: BufferedInputStream,
        packet: ByteArray
    ): Boolean {
        var total = 0
        while (total < packet.size) {
            val read = input.read(
                packet,
                total,
                packet.size - total
            )
            if (read < 0) return false
            total += read
        }
        return true
    }

    private fun fail(message: String) {
        synchronized(lock) {
            val wasActive = running.get() || starting.get()
            stopLocked()
            if (wasActive) {
                listener.onError(message)
            }
        }
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

        val localRecorder = recorder
        recorder = null

        if (recorderStarted) {
            runCatching { localRecorder?.stop() }
        }
        recorderStarted = false
        runCatching { localRecorder?.reset() }
        runCatching { localRecorder?.release() }

        runCatching { pipeWrite?.close() }
        pipeWrite = null
        runCatching { pipeRead?.close() }
        pipeRead = null

        runCatching { cameraThread?.quitSafely() }
        cameraThread = null
        cameraHandler = null
        readerThread = null

        configuredWidth = 0
        configuredHeight = 0
        configuredFps = 0
        configuredBitrate = 0
    }
}
