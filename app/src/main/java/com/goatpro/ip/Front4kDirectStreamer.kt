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
 * Front-camera UHD recording path.
 *
 * Build 24 used a direct MediaCodec input Surface. Some Samsung firmware accepts
 * 4K in the stock camera while refusing that third-party Surface combination.
 * Build 25 switches the actual capture target to MediaRecorder's own Surface,
 * using the official 2160p profile values when available. MediaRecorder writes
 * H.264 inside MPEG-2 TS to an in-memory pipe; the elementary Annex-B H.264
 * payload is then forwarded to the existing RTSP server.
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

    private var recorder: MediaRecorder? = null
    private var recorderStarted = false
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var pipeRead: ParcelFileDescriptor? = null
    private var pipeWrite: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null

    private var configuredWidth = 3840
    private var configuredHeight = 2160
    private var configuredFps = 30
    private var configuredBitrate = 32_000_000

    @Volatile
    private var sessionConfigured = false

    @Volatile
    private var firstFrameDelivered = false

    fun isRunning(): Boolean = running.get()
    fun isStarting(): Boolean = starting.get()

    /** MediaRecorder has no public force-IDR API. */
    fun requestKeyFrame() = Unit

    fun start(profile: Profile): Boolean {
        synchronized(lock) {
            if (running.get() || starting.get()) return true

            val official = profileFor(context, profile.cameraId)
            val effective = official ?: profile
            val safeFps = effective.fps.coerceIn(5, 30)
            val safeBitrate = effective.bitrate.coerceIn(8_000_000, 60_000_000)

            return try {
                val pipe = ParcelFileDescriptor.createPipe()
                pipeRead = pipe[0]
                pipeWrite = pipe[1]

                @Suppress("DEPRECATION")
                val localRecorder = MediaRecorder().apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_2_TS)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoSize(effective.width, effective.height)
                    setVideoFrameRate(safeFps)
                    setVideoEncodingBitRate(safeBitrate)
                    setOutputFile(pipeWrite!!.fileDescriptor)
                    setOnErrorListener { _, what, extra ->
                        fail("MediaRecorder frontal erro $what/$extra")
                    }
                    prepare()
                }

                recorder = localRecorder
                configuredWidth = effective.width
                configuredHeight = effective.height
                configuredFps = safeFps
                configuredBitrate = safeBitrate
                sessionConfigured = false
                firstFrameDelivered = false
                starting.set(true)

                startTsReader()

                val thread = HandlerThread("goat-front-4k-mediarecorder").apply {
                    start()
                }
                val handler = Handler(thread.looper)
                cameraThread = thread
                cameraHandler = handler

                openCamera(effective.cameraId, handler)
                true
            } catch (ex: Exception) {
                stopLocked()
                listener.onError(
                    "Falha ao preparar MediaRecorder 4K frontal: " +
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
                    fail("Erro Camera2/MediaRecorder no 4K frontal: $error")
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
        val surface = recorder?.surface ?: run {
            fail("Surface MediaRecorder 4K frontal indisponível.")
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
                    ) ?: intArrayOf()

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
                        ) ?: emptyArray()
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
                    sessionConfigured = true

                    recorder?.start()
                    recorderStarted = true
                    starting.set(false)
                    running.set(true)

                    handler.postDelayed({
                        if (running.get() && sessionConfigured && !firstFrameDelivered) {
                            fail(
                                "MediaRecorder 4K frontal abriu, mas não entregou frames em 5 segundos."
                            )
                        }
                    }, 5_000L)
                } catch (ex: Exception) {
                    fail(
                        "Falha ao iniciar MediaRecorder 4K frontal: " +
                            (ex.message ?: ex.javaClass.simpleName)
                    )
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                fail(
                    "A câmera recusou a sessão MediaRecorder frontal " +
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
                if (bytes.isNotEmpty()) {
                    if (!firstFrameDelivered && sessionConfigured) {
                        firstFrameDelivered = true
                        listener.onStarted(
                            configuredWidth,
                            configuredHeight,
                            configuredFps,
                            configuredBitrate
                        )
                    }
                    listener.onAccessUnit(
                        bytes,
                        if (pesPtsUs > 0L) {
                            pesPtsUs
                        } else {
                            System.nanoTime() / 1000L
                        },
                        containsH264Idr(bytes),
                        false
                    )
                }
                pesData = ByteArrayOutputStream(512 * 1024)
            }

            try {
                while (starting.get() || running.get()) {
                    if (!readTsPacket(input, packet)) break
                    if ((packet[0].toInt() and 0xff) != 0x47) continue

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
                            if (videoPid < 0) videoPid = pid
                            if (pid == videoPid) {
                                flushPes()
                                havePes = true
                                pesPtsUs = pes.ptsUs
                                if (pes.payloadOffset < packet.size) {
                                    pesData.write(
                                        packet,
                                        pes.payloadOffset,
                                        packet.size - pes.payloadOffset
                                    )
                                }
                            }
                            continue
                        }
                    }

                    if (pid == videoPid && havePes) {
                        pesData.write(packet, offset, packet.size - offset)
                    }
                }
            } catch (_: Exception) {
            } finally {
                flushPes()
                runCatching { input.close() }
            }
        }.apply {
            name = "goat-front-4k-ts-reader"
            isDaemon = true
            start()
        }
    }

    private data class PesHeader(
        val video: Boolean,
        val payloadOffset: Int,
        val ptsUs: Long
    )

    private fun parsePesHeader(packet: ByteArray, offset: Int): PesHeader? {
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

        return PesHeader(true, payloadOffset, ptsUs)
    }

    private fun containsH264Idr(data: ByteArray): Boolean {
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
                    val nalType = data[nalIndex].toInt() and 0x1f
                    if (nalType == 5) return true
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
            val read = input.read(packet, total, packet.size - total)
            if (read < 0) return false
            total += read
        }
        return true
    }

    private fun fail(message: String) {
        synchronized(lock) {
            val wasActive = running.get() || starting.get()
            stopLocked()
            if (wasActive) listener.onError(message)
        }
    }

    private fun stopLocked() {
        running.set(false)
        starting.set(false)
        sessionConfigured = false
        firstFrameDelivered = false

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
    }

    companion object {
        @Suppress("DEPRECATION")
        fun profileFor(context: Context, cameraId: String): Profile? {
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
                        fps = preferred.frameRate.coerceIn(5, 30),
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
                        fps = profile.videoFrameRate.coerceIn(5, 30),
                        bitrate = profile.videoBitRate.coerceIn(
                            8_000_000,
                            60_000_000
                        ),
                        fromOfficialProfile = true
                    )
                }
            }

            val map = chars.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            fun hasExact4k(sizes: Array<android.util.Size>?): Boolean =
                sizes.orEmpty().any {
                    (it.width == 3840 && it.height == 2160) ||
                        (it.width == 2160 && it.height == 3840)
                }

            val recorder4k = runCatching {
                hasExact4k(map?.getOutputSizes(MediaRecorder::class.java))
            }.getOrDefault(false)
            val codec4k = runCatching {
                hasExact4k(map?.getOutputSizes(MediaCodec::class.java))
            }.getOrDefault(false)
            if (recorder4k || codec4k) {
                return Profile(
                    cameraId = cameraId,
                    fps = 30,
                    bitrate = 32_000_000,
                    fromOfficialProfile = false
                )
            }

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
                maxOf(sensorWidth, sensorHeight) >= 3000 &&
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
