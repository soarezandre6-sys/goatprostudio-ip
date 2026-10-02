package com.goatpro.ip

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct high-speed Camera2 -> hardware AVC/H.264 route.
 * Used only when the selected Camera2 camera explicitly advertises the exact
 * resolution/FPS combination through constrained high-speed video configs.
 */
class HighSpeedH264Streamer(
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
    private var session: CameraConstrainedHighSpeedCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var drainThread: Thread? = null

    private var width = 1920
    private var height = 1080
    private var fps = 120
    private var bitrate = 24_000_000
    private var zoomRatio = 1f
    private var cameraId = "0"

    fun isRunning(): Boolean = running.get()
    fun isStarting(): Boolean = starting.get()

    fun start(
        targetCameraId: String,
        targetWidth: Int,
        targetHeight: Int,
        targetFps: Int,
        targetBitrate: Int,
        targetZoomRatio: Float = 1f
    ): Boolean {
        synchronized(lock) {
            if (running.get() || starting.get()) return true

            val supportedRange = findRange(
                context,
                targetCameraId,
                Size(targetWidth, targetHeight),
                targetFps
            ) ?: run {
                listener.onError(
                    "Modo high-speed ${targetWidth}x${targetHeight} @ ${targetFps} FPS não foi anunciado pela câmera."
                )
                return false
            }

            return try {
                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val caps = encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val videoCaps = caps.videoCapabilities
                if (!videoCaps.areSizeAndRateSupported(
                        targetWidth,
                        targetHeight,
                        targetFps.toDouble()
                    )
                ) {
                    encoder.release()
                    listener.onError(
                        "Encoder H.264 não aceita ${targetWidth}x${targetHeight} @ ${targetFps} FPS."
                    )
                    return false
                }

                val bitrateMode = if (
                    caps.encoderCapabilities.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                ) {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                } else {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                }

                val safeBitrate = targetBitrate.coerceIn(8_000_000, 60_000_000)
                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    targetWidth,
                    targetHeight
                ).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, safeBitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
                    runCatching { setInteger(MediaFormat.KEY_PRIORITY, 0) }
                    runCatching {
                        setFloat(MediaFormat.KEY_OPERATING_RATE, targetFps.toFloat())
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        runCatching { setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) }
                        runCatching { setInteger("prepend-sps-pps-to-idr-frames", 1) }
                    }
                }

                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = encoder.createInputSurface()
                encoder.start()

                codec = encoder
                inputSurface = surface
                width = targetWidth
                height = targetHeight
                fps = targetFps
                bitrate = safeBitrate
                zoomRatio = targetZoomRatio.coerceAtLeast(1f)
                cameraId = targetCameraId
                starting.set(true)

                val thread = HandlerThread("goat-highspeed-camera").apply { start() }
                cameraThread = thread
                startDrainThread(encoder)
                openCamera(thread.looper.let(::Handler), supportedRange)
                true
            } catch (ex: Exception) {
                stopLocked()
                listener.onError(
                    "Falha ao preparar modo high-speed: " +
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
            val bundle = android.os.Bundle()
            bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            encoder.setParameters(bundle)
        }
    }

    fun stop() {
        synchronized(lock) {
            val active = running.get() || starting.get()
            stopLocked()
            if (active) listener.onStopped()
        }
    }

    private fun openCamera(handler: Handler, fpsRange: Range<Int>) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
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
                    createSession(manager, camera, handler, fpsRange)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    fail("Câmera desconectada durante high-speed.")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    fail("Erro Camera2 high-speed: $error")
                }
            },
            handler
        )
    }

    private fun createSession(
        manager: CameraManager,
        camera: CameraDevice,
        handler: Handler,
        fpsRange: Range<Int>
    ) {
        val surface = inputSurface ?: run {
            fail("Surface high-speed indisponível.")
            return
        }

        @Suppress("DEPRECATION")
        camera.createConstrainedHighSpeedCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(baseSession: CameraCaptureSession) {
                    val high = baseSession as? CameraConstrainedHighSpeedCaptureSession
                        ?: run {
                            fail("Sessão high-speed inválida.")
                            return
                        }
                    session = high
                    try {
                        val chars = manager.getCameraCharacteristics(cameraId)
                        val zoomRange = if (Build.VERSION.SDK_INT >= 30) {
                            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                        } else null

                        val request = camera.createCaptureRequest(
                            CameraDevice.TEMPLATE_RECORD
                        ).apply {
                            addTarget(surface)
                            set(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                fpsRange
                            )
                            set(
                                CaptureRequest.CONTROL_CAPTURE_INTENT,
                                CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
                            )
                            if (Build.VERSION.SDK_INT >= 30 && zoomRange != null) {
                                set(
                                    CaptureRequest.CONTROL_ZOOM_RATIO,
                                    zoomRatio.coerceIn(zoomRange.lower, zoomRange.upper)
                                )
                            }
                        }

                        val burst = high.createHighSpeedRequestList(request.build())
                        high.setRepeatingBurst(burst, null, handler)
                        starting.set(false)
                        running.set(true)
                        listener.onStarted(width, height, fps, bitrate)
                    } catch (ex: Exception) {
                        fail(
                            "Falha ao iniciar high-speed: " +
                                (ex.message ?: ex.javaClass.simpleName)
                        )
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    fail("A câmera recusou a sessão high-speed.")
                }
            },
            handler
        )
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
                                format.getByteBuffer(key)?.let(::toBytes)
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
            name = "goat-highspeed-h264-drain"
            isDaemon = true
            start()
        }
    }

    private fun toBytes(buffer: ByteBuffer): ByteArray {
        val duplicate = buffer.duplicate()
        return ByteArray(duplicate.remaining()).also(duplicate::get)
    }

    private fun fail(message: String) {
        synchronized(lock) { stopLocked() }
        listener.onError(message)
    }

    private fun stopLocked() {
        running.set(false)
        starting.set(false)
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }
        runCatching { session?.close() }
        session = null
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

    companion object {
        fun maxSupportedFps(
            context: Context,
            cameraId: String,
            size: Size
        ): Int {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val map = runCatching {
                manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            }.getOrNull() ?: return 0

            val exact = map.highSpeedVideoSizes.orEmpty().any {
                (it.width == size.width && it.height == size.height) ||
                    (it.width == size.height && it.height == size.width)
            }
            if (!exact) return 0

            return runCatching {
                map.getHighSpeedVideoFpsRangesFor(size)
                    .maxOfOrNull { it.upper } ?: 0
            }.getOrDefault(0).coerceAtMost(120)
        }

        fun supports(
            context: Context,
            cameraId: String,
            size: Size,
            fps: Int
        ): Boolean = findRange(context, cameraId, size, fps) != null

        private fun findRange(
            context: Context,
            cameraId: String,
            size: Size,
            fps: Int
        ): Range<Int>? {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val map = runCatching {
                manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            }.getOrNull() ?: return null

            val actualSize = map.highSpeedVideoSizes.orEmpty().firstOrNull {
                (it.width == size.width && it.height == size.height) ||
                    (it.width == size.height && it.height == size.width)
            } ?: return null

            val ranges = runCatching {
                map.getHighSpeedVideoFpsRangesFor(actualSize).toList()
            }.getOrDefault(emptyList())

            return ranges
                .filter { it.lower <= fps && it.upper >= fps }
                .minWithOrNull(
                    compareBy<Range<Int>> { it.upper - it.lower }
                        .thenBy { kotlin.math.abs(it.upper - fps) }
                )
        }
    }
}
