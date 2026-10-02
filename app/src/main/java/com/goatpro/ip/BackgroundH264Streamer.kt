package com.goatpro.ip

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
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
 * Low-overhead background RTSP path.
 *
 * Camera2 writes directly into a hardware H.264 encoder Surface, avoiding the
 * CameraX ImageAnalysis -> NV21 -> CPU copy path while the UI is hidden.
 * This path is intentionally limited to HD/FHD background streaming, where
 * Samsung devices expose reliable public Camera2 combinations.
 */
class BackgroundH264Streamer(
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

    private var width = 1920
    private var height = 1080
    private var fps = 20
    private var bitrate = 8_000_000
    private var zoomRatio = 1f

    fun isRunning(): Boolean = running.get()
    fun isStarting(): Boolean = starting.get()

    fun start(
        logicalCameraId: String,
        physicalCameraId: String?,
        targetWidth: Int,
        targetHeight: Int,
        targetFps: Int,
        targetBitrate: Int,
        targetZoomRatio: Float = 1f
    ): Boolean {
        synchronized(lock) {
            if (running.get() || starting.get()) return true

            val safeWidth = targetWidth.coerceAtMost(1920).coerceAtLeast(640) and -2
            val safeHeight = targetHeight.coerceAtMost(1080).coerceAtLeast(360) and -2
            val safeFps = targetFps.coerceIn(10, 30)
            val safeBitrate = targetBitrate.coerceIn(2_000_000, 16_000_000)

            return try {
                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val caps = encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val videoCaps = caps.videoCapabilities
                if (!videoCaps.isSizeSupported(safeWidth, safeHeight)) {
                    encoder.release()
                    listener.onError("Encoder H.264 de fundo não aceita ${safeWidth}x${safeHeight}.")
                    return false
                }

                val mode = if (
                    caps.encoderCapabilities.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                ) {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                } else {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                }

                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    safeWidth,
                    safeHeight
                ).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, safeBitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, safeFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
                    runCatching { setInteger(MediaFormat.KEY_PRIORITY, 0) }
                    runCatching { setFloat(MediaFormat.KEY_OPERATING_RATE, safeFps.toFloat()) }
                    if (Build.VERSION.SDK_INT >= 29) {
                        runCatching { setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) }
                        runCatching { setInteger("prepend-sps-pps-to-idr-frames", 1) }
                    }
                    if (
                        Build.VERSION.SDK_INT >= 30 &&
                        caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                    ) {
                        runCatching { setInteger("low-latency", 1) }
                    }
                }

                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = encoder.createInputSurface()
                encoder.start()

                val thread = HandlerThread("goat-background-h264").apply { start() }
                val handler = Handler(thread.looper)

                codec = encoder
                inputSurface = surface
                cameraThread = thread
                width = safeWidth
                height = safeHeight
                fps = safeFps
                bitrate = safeBitrate
                zoomRatio = targetZoomRatio.coerceAtLeast(1f)
                starting.set(true)

                startDrainThread(encoder)
                openCamera(logicalCameraId, physicalCameraId, handler)
                true
            } catch (ex: Exception) {
                stopLocked()
                listener.onError(
                    "Falha no modo leve de segundo plano: " +
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
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            encoder.setParameters(params)
        }
    }

    fun stop() {
        synchronized(lock) {
            val active = running.get() || starting.get()
            stopLocked()
            if (active) listener.onStopped()
        }
    }

    private fun openCamera(
        logicalId: String,
        physicalId: String?,
        handler: Handler
    ) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manager.openCamera(
            logicalId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    synchronized(lock) {
                        if (!starting.get()) {
                            runCatching { camera.close() }
                            return
                        }
                        cameraDevice = camera
                    }
                    createSession(manager, camera, logicalId, physicalId, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    fail("Câmera desconectada no segundo plano.")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    fail("Erro Camera2 no segundo plano: $error")
                }
            },
            handler
        )
    }

    private fun createSession(
        manager: CameraManager,
        camera: CameraDevice,
        logicalId: String,
        physicalId: String?,
        handler: Handler
    ) {
        val surface = inputSurface ?: run {
            fail("Surface H.264 de segundo plano indisponível.")
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
                    val chars = manager.getCameraCharacteristics(physicalId ?: logicalId)
                    val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                        ?: intArrayOf()
                    val zoomRange = if (Build.VERSION.SDK_INT >= 30) {
                        chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    } else null
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(
                            CaptureRequest.CONTROL_CAPTURE_INTENT,
                            CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
                        )
                        if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                            set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                            )
                        }
                        if (Build.VERSION.SDK_INT >= 30 && zoomRange != null) {
                            set(
                                CaptureRequest.CONTROL_ZOOM_RATIO,
                                zoomRatio.coerceIn(zoomRange.lower, zoomRange.upper)
                            )
                        }
                        val ranges = chars.get(
                            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                        ).orEmpty()
                        val target = ranges
                            .filter { it.lower <= fps && it.upper >= fps }
                            .minWithOrNull(
                                compareBy<android.util.Range<Int>> { it.upper - it.lower }
                                    .thenBy { kotlin.math.abs(it.upper - fps) }
                            )
                        if (target != null) {
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, target)
                        }
                    }
                    session.setRepeatingRequest(request.build(), null, handler)
                    starting.set(false)
                    running.set(true)
                    listener.onStarted(width, height, fps, bitrate)
                } catch (ex: Exception) {
                    fail(
                        "Falha ao iniciar captura leve: " +
                            (ex.message ?: ex.javaClass.simpleName)
                    )
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                fail("A câmera recusou o modo H.264 leve de segundo plano.")
            }
        }

        if (Build.VERSION.SDK_INT >= 28) {
            val output = OutputConfiguration(surface)
            if (physicalId != null) output.setPhysicalCameraId(physicalId)
            val executor = Executor { command -> handler.post(command) }
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(output),
                    executor,
                    callback
                )
            )
        } else {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(surface), callback, handler)
        }
    }

    private fun startDrainThread(encoder: MediaCodec) {
        drainThread = Thread {
            val info = MediaCodec.BufferInfo()
            while ((starting.get() || running.get()) && codec === encoder) {
                try {
                    when (val index = encoder.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = encoder.outputFormat
                            listOf("csd-0", "csd-1").forEach { key ->
                                format.getByteBuffer(key)?.let(::bufferBytes)
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { listener.onAccessUnit(it, 0L, false, true) }
                            }
                        }
                        else -> if (index >= 0) {
                            val out = encoder.getOutputBuffer(index)
                            if (out != null && info.size > 0) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                out.get(bytes)
                                listener.onAccessUnit(
                                    bytes,
                                    info.presentationTimeUs,
                                    (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
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
            name = "goat-background-h264-drain"
            isDaemon = true
            start()
        }
    }

    private fun bufferBytes(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        return ByteArray(copy.remaining()).also(copy::get)
    }

    private fun fail(message: String) {
        synchronized(lock) { stopLocked() }
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
        drainThread = null
    }
}
