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
import android.media.CamcorderProfile
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated front-camera 4K path.
 *
 * CameraX ImageAnalysis often exposes only up to 1080p/near-4K YUV on Samsung
 * front cameras even when the stock camera records UHD. This path asks the
 * public recording stack for the 2160p profile and feeds the camera directly
 * into a hardware AVC surface, avoiding the CPU YUV/JPEG path used by the
 * normal GOAT Cam stream.
 */
class Front4kDirectStreamer(
    private val context: Context,
    private val listener: Listener
) {
    data class Profile(
        val cameraId: String,
        val width: Int = 3840,
        val height: Int = 2160,
        val fps: Int = 30,
        val bitrate: Int = 32_000_000,
        val fromOfficialProfile: Boolean = false
    )

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

    private var configuredWidth = 3840
    private var configuredHeight = 2160
    private var configuredFps = 30
    private var configuredBitrate = 32_000_000

    fun isRunning(): Boolean = running.get()
    fun isStarting(): Boolean = starting.get()

    fun start(profile: Profile): Boolean {
        synchronized(lock) {
            if (running.get() || starting.get()) return true

            val safeFps = profile.fps.coerceIn(5, 60)
            val safeBitrate = profile.bitrate.coerceIn(8_000_000, 60_000_000)

            return try {
                val encoder = MediaCodec.createEncoderByType(
                    MediaFormat.MIMETYPE_VIDEO_AVC
                )
                val caps = encoder.codecInfo.getCapabilitiesForType(
                    MediaFormat.MIMETYPE_VIDEO_AVC
                )
                val encoderCaps = caps.encoderCapabilities
                val bitrateMode = if (
                    encoderCaps.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                ) {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                } else {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                }

                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    profile.width,
                    profile.height
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
                        setFloat(MediaFormat.KEY_OPERATING_RATE, safeFps.toFloat())
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        runCatching { setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) }
                        runCatching {
                            setInteger("prepend-sps-pps-to-idr-frames", 1)
                        }
                    }
                    if (
                        Build.VERSION.SDK_INT >= 30 &&
                        caps.isFeatureSupported(
                            MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency
                        )
                    ) {
                        runCatching { setInteger("low-latency", 1) }
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

                val thread = HandlerThread("goat-front-4k-camera").apply {
                    start()
                }
                val handler = Handler(thread.looper)

                codec = encoder
                inputSurface = surface
                cameraThread = thread
                cameraHandler = handler
                configuredWidth = profile.width
                configuredHeight = profile.height
                configuredFps = safeFps
                configuredBitrate = safeBitrate
                starting.set(true)

                startDrainThread(encoder)
                openCamera(profile.cameraId, handler)
                true
            } catch (ex: Exception) {
                stopLocked()
                listener.onError(
                    "Falha ao preparar H.264 4K frontal: " +
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
            val wasActive = running.get() || starting.get()
            stopLocked()
            if (wasActive) listener.onStopped()
        }
    }

    private fun openCamera(cameraId: String, handler: Handler) {
        val manager =
            context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

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
                    createCaptureSession(manager, camera, cameraId, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    fail("Câmera frontal desconectada durante o 4K.")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    fail("Erro Camera2 no 4K frontal: " + error)
                }
            },
            handler
        )
    }

    private fun createCaptureSession(
        manager: CameraManager,
        camera: CameraDevice,
        cameraId: String,
        handler: Handler
    ) {
        val surface = inputSurface ?: run {
            fail("Surface H.264 4K frontal indisponível.")
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
                    val chars = manager.getCameraCharacteristics(cameraId)
                    val afModes = chars.get(
                        CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
                    ).orEmpty()

                    val request = camera.createCaptureRequest(
                        CameraDevice.TEMPLATE_RECORD
                    ).apply {
                        addTarget(surface)
                        set(
                            CaptureRequest.CONTROL_MODE,
                            CaptureRequest.CONTROL_MODE_AUTO
                        )
                        if (
                            afModes.contains(
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                            )
                        ) {
                            set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                            )
                        }
                        set(
                            CaptureRequest.CONTROL_CAPTURE_INTENT,
                            CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
                        )

                        val ranges = chars.get(
                            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                        ).orEmpty()
                        val range = ranges
                            .filter {
                                it.lower <= configuredFps &&
                                    it.upper >= configuredFps
                            }
                            .minWithOrNull(
                                compareBy<android.util.Range<Int>> {
                                    it.upper - it.lower
                                }.thenBy {
                                    kotlin.math.abs(it.upper - configuredFps)
                                }
                            )
                        if (range != null) {
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                        }
                    }

                    session.setRepeatingRequest(request.build(), null, handler)
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
                        "Falha ao iniciar captura 4K frontal: " +
                            (ex.message ?: ex.javaClass.simpleName)
                    )
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                fail(
                    "A câmera recusou a sessão frontal " +
                        configuredWidth + "x" + configuredHeight + " H.264."
                )
            }
        }

        if (Build.VERSION.SDK_INT >= 28) {
            val output = OutputConfiguration(surface)
            if (Build.VERSION.SDK_INT >= 33) {
                runCatching {
                    val useCases = manager
                        .getCameraCharacteristics(cameraId)
                        .get(
                            CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES
                        ) ?: longArrayOf()
                    val recordUseCase =
                        CameraMetadata
                            .SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD
                            .toLong()
                    if (useCases.contains(recordUseCase)) {
                        output.setStreamUseCase(recordUseCase)
                    }
                }
            }

            val executor = Executor { command -> handler.post(command) }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(output),
                executor,
                callback
            )
            camera.createCaptureSession(config)
        } else {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(surface), callback, handler)
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
                    when (val index = encoder.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = encoder.outputFormat
                            listOf("csd-0", "csd-1").forEach { key ->
                                format.getByteBuffer(key)
                                    ?.let(::byteBufferToBytes)
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
                            val output = encoder.getOutputBuffer(index)
                            if (output != null && info.size > 0) {
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                output.get(bytes)
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
            name = "goat-front-4k-h264-drain"
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
    }

    companion object {
        @Suppress("DEPRECATION")
        fun profileFor(
            context: Context,
            cameraId: String
        ): Profile? {
            val manager =
                context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = runCatching {
                manager.getCameraCharacteristics(cameraId)
            }.getOrNull() ?: return null

            if (
                chars.get(CameraCharacteristics.LENS_FACING) !=
                    CameraCharacteristics.LENS_FACING_FRONT
            ) {
                return null
            }

            if (Build.VERSION.SDK_INT >= 31) {
                val profiles = runCatching {
                    CamcorderProfile.getAll(
                        cameraId,
                        CamcorderProfile.QUALITY_2160P
                    )
                }.getOrNull()

                val exact = profiles?.videoProfiles
                    ?.filter { it.width == 3840 && it.height == 2160 }
                    .orEmpty()
                val preferred = exact.firstOrNull {
                    it.codec == MediaRecorder.VideoEncoder.H264
                } ?: exact.firstOrNull()

                if (preferred != null) {
                    return Profile(
                        cameraId = cameraId,
                        fps = preferred.frameRate.coerceIn(5, 60),
                        bitrate = preferred.bitrate.coerceIn(
                            8_000_000,
                            60_000_000
                        ),
                        fromOfficialProfile = true
                    )
                }
            }

            val numericId = cameraId.toIntOrNull()
            if (
                numericId != null &&
                CamcorderProfile.hasProfile(
                    numericId,
                    CamcorderProfile.QUALITY_2160P
                )
            ) {
                val profile = runCatching {
                    CamcorderProfile.get(
                        numericId,
                        CamcorderProfile.QUALITY_2160P
                    )
                }.getOrNull()
                if (
                    profile != null &&
                    profile.videoFrameWidth == 3840 &&
                    profile.videoFrameHeight == 2160
                ) {
                    return Profile(
                        cameraId = cameraId,
                        fps = profile.videoFrameRate.coerceIn(5, 60),
                        bitrate = profile.videoBitRate.coerceIn(
                            8_000_000,
                            60_000_000
                        ),
                        fromOfficialProfile = true
                    )
                }
            }

            // Samsung S21-family fallback: the stock camera can expose UHD
            // through its recording path even when Camera2 omits 3840x2160
            // from the public YUV list. Keep this fallback scoped to the S21
            // family and only to a front camera with a large enough sensor.
            val samsungS21 =
                Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                    Regex("^SM-G99[0168].*", RegexOption.IGNORE_CASE)
                        .matches(Build.MODEL.orEmpty())
            val pixel = chars.get(
                CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE
            )
            val active = chars.get(
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
            )
            val sensorWidth = maxOf(pixel?.width ?: 0, active?.width() ?: 0)
            val sensorHeight = maxOf(pixel?.height ?: 0, active?.height() ?: 0)
            val enoughPixels =
                maxOf(sensorWidth, sensorHeight) >= 3200 &&
                    minOf(sensorWidth, sensorHeight) >= 2000

            return if (samsungS21 && enoughPixels) {
                Profile(
                    cameraId = cameraId,
                    fps = 30,
                    bitrate = 32_000_000,
                    fromOfficialProfile = false
                )
            } else {
                null
            }
        }
    }
}
