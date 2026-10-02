package com.goatpro.ip

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Build 26 front-UHD route.
 *
 * The Camera2 + MediaCodec and Camera2 + MediaRecorder paths were both refused
 * by the Galaxy S21 front camera. This version deliberately tries the legacy
 * Camera1 + MediaRecorder recording route used by older Android camera stacks.
 * 4K is exposed only when the legacy front camera publishes an exact official
 * QUALITY_2160P profile; there is no sensor-size based fake 4K fallback.
 */
@Suppress("DEPRECATION")
class Front4kDirectStreamer(
    private val context: Context,
    private val listener: Listener
) {
    data class Profile(
        val cameraId: String,
        val width: Int = 3840,
        val height: Int = 2160,
        val fps: Int = 60,
        val bitrate: Int = 48_000_000,
        val fromOfficialProfile: Boolean = false,
        val legacyCameraId: Int = -1
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

    private var camera: Camera? = null
    private var previewTexture: SurfaceTexture? = null
    private var recorder: MediaRecorder? = null
    private var recorderStarted = false
    private var pipeRead: ParcelFileDescriptor? = null
    private var pipeWrite: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null

    private var configuredWidth = 3840
    private var configuredHeight = 2160
    private var configuredFps = 60
    private var configuredBitrate = 48_000_000

    @Volatile
    private var firstFrameDelivered = false

    fun isRunning(): Boolean = running.get()
    fun isStarting(): Boolean = starting.get()
    fun requestKeyFrame() = Unit

    fun start(profile: Profile): Boolean {
        synchronized(lock) {
            if (running.get() || starting.get()) return true

            val detected = profileFor(context, profile.cameraId)
            val effective = detected ?: profile
            val legacyId = if (effective.legacyCameraId >= 0) {
                effective.legacyCameraId
            } else {
                resolveLegacyFrontCameraId(profile.cameraId)
            }
            if (legacyId < 0) {
                listener.onError("Nenhuma câmera frontal Camera1 disponível.")
                return false
            }

            val safeFps = minOf(profile.fps, effective.fps).coerceIn(5, 60)
            val safeBitrate = effective.bitrate.coerceIn(8_000_000, 60_000_000)

            return try {
                starting.set(true)
                firstFrameDelivered = false
                configuredWidth = effective.width
                configuredHeight = effective.height
                configuredFps = safeFps
                configuredBitrate = safeBitrate

                val opened = Camera.open(legacyId)
                camera = opened

                runCatching {
                    val params = opened.parameters
                    params.setRecordingHint(true)
                    val fps1000 = safeFps * 1000
                    val fpsRange = params.supportedPreviewFpsRange
                        ?.firstOrNull { range ->
                            range.size >= 2 && range[0] <= fps1000 && range[1] >= fps1000
                        }
                    if (fpsRange != null) {
                        params.setPreviewFpsRange(fpsRange[0], fpsRange[1])
                    }
                    if (params.supportedPreviewFrameRates?.contains(safeFps) == true) {
                        params.previewFrameRate = safeFps
                    }
                    if (
                        params.supportedFocusModes?.contains(
                            Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                        ) == true
                    ) {
                        params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                    }
                    opened.parameters = params
                }

                // Camera1 expects a preview before MediaRecorder takes ownership.
                // A tiny off-screen SurfaceTexture avoids depending on the Activity UI.
                val dummy = SurfaceTexture(0).apply {
                    setDefaultBufferSize(640, 480)
                }
                previewTexture = dummy
                opened.setPreviewTexture(dummy)
                opened.startPreview()
                opened.unlock()

                val pipe = ParcelFileDescriptor.createPipe()
                pipeRead = pipe[0]
                pipeWrite = pipe[1]

                val localRecorder = MediaRecorder().apply {
                    setCamera(opened)
                    setVideoSource(MediaRecorder.VideoSource.CAMERA)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_2_TS)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoSize(effective.width, effective.height)
                    setVideoFrameRate(safeFps)
                    setVideoEncodingBitRate(safeBitrate)
                    setOutputFile(pipeWrite!!.fileDescriptor)
                    setOnErrorListener { _, what, extra ->
                        fail("MediaRecorder Camera1 frontal erro $what/$extra")
                    }
                    prepare()
                }
                recorder = localRecorder

                startTsReader()
                localRecorder.start()
                recorderStarted = true
                starting.set(false)
                running.set(true)

                Thread {
                    try {
                        Thread.sleep(5_000L)
                        if (running.get() && !firstFrameDelivered) {
                            fail(
                                "Camera1 4K frontal iniciou, mas não entregou frames em 5 segundos."
                            )
                        }
                    } catch (_: InterruptedException) {
                    }
                }.apply {
                    name = "goat-front-4k-watchdog"
                    isDaemon = true
                    start()
                }

                true
            } catch (ex: Exception) {
                stopLocked()
                if (safeFps > 30) {
                    return start(
                        profile.copy(
                            fps = 30,
                            bitrate = minOf(profile.bitrate, 36_000_000)
                        )
                    )
                }
                listener.onError(
                    "Falha Camera1/MediaRecorder 4K frontal: " +
                        (ex.message ?: ex.javaClass.simpleName)
                )
                false
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            val active = running.get() || starting.get()
            stopLocked()
            if (active) listener.onStopped()
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
                    if (!firstFrameDelivered) {
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
                        if (pesPtsUs > 0L) pesPtsUs else System.nanoTime() / 1000L,
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

                    val payloadStart = (packet[1].toInt() and 0x40) != 0
                    val pid =
                        ((packet[1].toInt() and 0x1f) shl 8) or
                            (packet[2].toInt() and 0xff)
                    val afc = (packet[3].toInt() ushr 4) and 0x03
                    if (afc == 0 || afc == 2) continue

                    var offset = 4
                    if (afc == 3) {
                        if (offset >= packet.size) continue
                        val adaptationLength = packet[offset].toInt() and 0xff
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
            name = "goat-front-4k-camera1-ts"
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
        ) return null

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
            val startLength = when {
                data[i] == 0.toByte() &&
                    data[i + 1] == 0.toByte() &&
                    data[i + 2] == 1.toByte() -> 3
                data[i] == 0.toByte() &&
                    data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() &&
                    data[i + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (startLength > 0) {
                val nalIndex = i + startLength
                if (nalIndex < data.size && (data[nalIndex].toInt() and 0x1f) == 5) {
                    return true
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
            val active = running.get() || starting.get()
            stopLocked()
            if (active) listener.onError(message)
        }
    }

    private fun stopLocked() {
        running.set(false)
        starting.set(false)
        firstFrameDelivered = false

        val localRecorder = recorder
        recorder = null
        if (recorderStarted) runCatching { localRecorder?.stop() }
        recorderStarted = false
        runCatching { localRecorder?.reset() }
        runCatching { localRecorder?.release() }

        runCatching { pipeWrite?.close() }
        pipeWrite = null
        runCatching { pipeRead?.close() }
        pipeRead = null

        val localCamera = camera
        camera = null
        runCatching { localCamera?.reconnect() }
        runCatching { localCamera?.lock() }
        runCatching { localCamera?.stopPreview() }
        runCatching { localCamera?.release() }

        runCatching { previewTexture?.release() }
        previewTexture = null
        readerThread = null
    }

    companion object {
        private fun resolveLegacyFrontCameraId(preferredCameraId: String): Int {
            val preferred = preferredCameraId.toIntOrNull()
            val info = Camera.CameraInfo()
            if (preferred != null && preferred in 0 until Camera.getNumberOfCameras()) {
                val isFront = runCatching {
                    Camera.getCameraInfo(preferred, info)
                    info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                }.getOrDefault(false)
                if (isFront) return preferred
            }
            for (id in 0 until Camera.getNumberOfCameras()) {
                val isFront = runCatching {
                    Camera.getCameraInfo(id, info)
                    info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                }.getOrDefault(false)
                if (isFront) return id
            }
            return -1
        }

        fun profileFor(context: Context, cameraId: String): Profile? {
            val preferred = cameraId.toIntOrNull()
            val candidates = mutableListOf<Int>()
            if (preferred != null) candidates.add(preferred)

            val info = Camera.CameraInfo()
            for (id in 0 until Camera.getNumberOfCameras()) {
                val isFront = runCatching {
                    Camera.getCameraInfo(id, info)
                    info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                }.getOrDefault(false)
                if (isFront && id !in candidates) candidates.add(id)
            }

            for (legacyId in candidates) {
                val cameraInfo = Camera.CameraInfo()
                val validFront = runCatching {
                    Camera.getCameraInfo(legacyId, cameraInfo)
                    cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                }.getOrDefault(false)
                if (!validFront) continue

                if (CamcorderProfile.hasProfile(legacyId, CamcorderProfile.QUALITY_2160P)) {
                    val profile = runCatching {
                        CamcorderProfile.get(legacyId, CamcorderProfile.QUALITY_2160P)
                    }.getOrNull()
                    if (
                        profile != null &&
                        profile.videoFrameWidth == 3840 &&
                        profile.videoFrameHeight == 2160
                    ) {
                        return Profile(
                            cameraId = cameraId,
                            width = 3840,
                            height = 2160,
                            fps = profile.videoFrameRate.coerceIn(5, 60),
                            bitrate = profile.videoBitRate.coerceIn(
                                8_000_000,
                                60_000_000
                            ),
                            fromOfficialProfile = true,
                            legacyCameraId = legacyId
                        )
                    }
                }

                val probed = runCatching {
                    val camera = Camera.open(legacyId)
                    try {
                        val params = camera.parameters
                        val has4k = params.supportedVideoSizes.orEmpty().any {
                            (it.width == 3840 && it.height == 2160) ||
                                (it.width == 2160 && it.height == 3840)
                        }
                        if (!has4k) return@runCatching null
                        val fpsByRange = params.supportedPreviewFpsRange.orEmpty()
                            .mapNotNull { range ->
                                if (range.size >= 2) range[1] / 1000 else null
                            }
                            .maxOrNull() ?: 30
                        val fpsByList = params.supportedPreviewFrameRates.orEmpty()
                            .maxOrNull() ?: 30
                        Profile(
                            cameraId = cameraId,
                            width = 3840,
                            height = 2160,
                            fps = maxOf(fpsByRange, fpsByList)
                                .coerceIn(30, 60),
                            bitrate = 48_000_000,
                            fromOfficialProfile = false,
                            legacyCameraId = legacyId
                        )
                    } finally {
                        camera.release()
                    }
                }.getOrNull()
                if (probed != null) return probed
            }

            val samsungS21 =
                Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                    Regex("^SM-G99[0168].*", RegexOption.IGNORE_CASE)
                        .matches(Build.MODEL.orEmpty())
            val legacyId = resolveLegacyFrontCameraId(cameraId)
            return if (samsungS21 && legacyId >= 0) {
                Profile(
                    cameraId = cameraId,
                    width = 3840,
                    height = 2160,
                    fps = 60,
                    bitrate = 48_000_000,
                    fromOfficialProfile = false,
                    legacyCameraId = legacyId
                )
            } else {
                null
            }
        }
    }
}
