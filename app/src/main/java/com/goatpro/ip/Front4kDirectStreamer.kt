package com.goatpro.ip

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.Build
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Front 4K compatibility route.
 *
 * Samsung can expose 3840x2160 through the legacy Camera1 supportedVideoSizes
 * list even when Camera2/CamcorderProfile do not publish an equivalent public
 * 2160p path. Build 29 therefore prioritizes exact Camera1 3840x2160 +
 * MediaRecorder H.264 and keeps the GPU composition route only as fallback.
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
        val fps: Int = 30,
        val bitrate: Int = 18_000_000,
        val fromOfficialProfile: Boolean = false,
        val deviceRotationDegrees: Int = 0,
        val rotationOffsetDegrees: Int = 0,
        val legacyCameraId: Int = -1,
        val legacyExact4k: Boolean = false
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

    @Volatile
    private var activeProfile: Profile? = null

    @Volatile
    private var gpuDelegate: GpuCameraH264Streamer? = null

    @Volatile
    private var camera1GpuDelegate: Camera1GpuH264Streamer? = null

    @Volatile
    var lastSourceDescription: String = ""
        private set

    private var camera: Camera? = null
    private var previewTexture: SurfaceTexture? = null
    private var recorder: MediaRecorder? = null
    private var recorderStarted = false
    private var pipeRead: ParcelFileDescriptor? = null
    private var pipeWrite: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null

    private var configuredWidth = 3840
    private var configuredHeight = 2160
    private var configuredFps = 30
    private var configuredBitrate = 18_000_000

    @Volatile
    private var firstFrameDelivered = false

    fun isRunning(): Boolean =
        running.get() ||
            gpuDelegate?.isRunning() == true ||
            camera1GpuDelegate?.isRunning() == true

    fun isStarting(): Boolean =
        starting.get() ||
            gpuDelegate?.isStarting() == true ||
            camera1GpuDelegate?.isStarting() == true

    fun requestKeyFrame() {
        camera1GpuDelegate?.requestKeyFrame()
        gpuDelegate?.requestKeyFrame()
        // MediaRecorder fallback has no public force-IDR API.
    }

    fun start(profile: Profile): Boolean {
        synchronized(lock) {
            if (isRunning() || isStarting()) return true
            activeProfile = profile

            val effective = profileFor(context, profile.cameraId)
            if (effective != null && effective.legacyCameraId >= 0) {
                val direct = effective.copy(
                    fps = profile.fps.coerceIn(5, 60),
                    bitrate = profile.bitrate.coerceIn(8_000_000, 28_000_000),
                    deviceRotationDegrees = normalize(profile.deviceRotationDegrees),
                    rotationOffsetDegrees = normalize(profile.rotationOffsetDegrees)
                )
                if (startCamera1Gpu(direct)) {
                    return true
                }
            }

            return startGpuFallback(profile)
        }
    }

    fun updateRotation(deviceRotationDegrees: Int, rotationOffsetDegrees: Int = 0) {
        val current = activeProfile ?: return
        val device = normalize(deviceRotationDegrees)
        val offset = normalize(rotationOffsetDegrees)
        if (
            current.deviceRotationDegrees == device &&
            current.rotationOffsetDegrees == offset
        ) return

        activeProfile = current.copy(
            deviceRotationDegrees = device,
            rotationOffsetDegrees = offset
        )

        camera1GpuDelegate?.let {
            if (it.isRunning() || it.isStarting()) {
                val updated = activeProfile ?: return
                camera1GpuDelegate = null
                it.stop()
                startCamera1Gpu(updated)
                return
            }
        }

        gpuDelegate?.let {
            if (it.isRunning() || it.isStarting()) {
                val updated = activeProfile ?: return
                it.stop()
                gpuDelegate = null
                startGpuFallback(updated)
            }
        }
        // Camera1/MediaRecorder orientation is fixed at recorder start. Avoid
        // restarting an active 4K stream just because the phone moved.
    }

    fun stop() {
        synchronized(lock) {
            val active = isRunning() || isStarting()
            activeProfile = null
            camera1GpuDelegate?.stop()
            camera1GpuDelegate = null
            gpuDelegate?.stop()
            gpuDelegate = null
            stopLegacyLocked()
            if (active) listener.onStopped()
        }
    }

    private fun startCamera1Gpu(profile: Profile): Boolean {
        val legacyId = profile.legacyCameraId
        if (legacyId < 0) return false

        lateinit var local: Camera1GpuH264Streamer
        local = Camera1GpuH264Streamer(
            context,
            object : Camera1GpuH264Streamer.Listener {
                override fun onAccessUnit(
                    data: ByteArray,
                    presentationTimeUs: Long,
                    keyFrame: Boolean,
                    codecConfig: Boolean
                ) {
                    listener.onAccessUnit(
                        data,
                        presentationTimeUs,
                        keyFrame,
                        codecConfig
                    )
                }

                override fun onStarted(
                    width: Int,
                    height: Int,
                    fps: Int,
                    bitrate: Int,
                    sourceWidth: Int,
                    sourceHeight: Int,
                    exactPreview4k: Boolean,
                    exactVideo4k: Boolean
                ) {
                    lastSourceDescription = buildString {
                        append("Camera1 GPU ")
                        append(sourceWidth)
                        append('x')
                        append(sourceHeight)
                        append(" → ")
                        append(width)
                        append('x')
                        append(height)
                        when {
                            exactPreview4k -> append(" • preview 4K nativo")
                            exactVideo4k -> append(" • video-size 4K")
                            else -> append(" • composição GPU")
                        }
                    }
                    listener.onStarted(width, height, fps, bitrate)
                }

                override fun onError(message: String) {
                    synchronized(lock) {
                        if (camera1GpuDelegate !== local) return
                        camera1GpuDelegate = null
                        val current = activeProfile
                        if (current == null) return

                        val fallbackStarted = startGpuFallback(current)
                        if (!fallbackStarted) {
                            listener.onError("Camera1 GPU 4K frontal: $message")
                        }
                    }
                }

                override fun onStopped() = Unit
            }
        )
        camera1GpuDelegate = local

        val started = local.start(
            Camera1GpuH264Streamer.Config(
                legacyCameraId = legacyId,
                targetWidth = profile.width,
                targetHeight = profile.height,
                targetFps = profile.fps.coerceIn(5, 60),
                targetBitrate = profile.bitrate.coerceIn(8_000_000, 28_000_000),
                deviceRotationDegrees = normalize(profile.deviceRotationDegrees),
                extraRotationDegrees = normalize(profile.rotationOffsetDegrees)
            )
        )
        if (!started) {
            camera1GpuDelegate = null
        }
        return started
    }

    private fun startLegacy(profile: Profile): Boolean {
        val safeFps = supportedLegacyFps(profile.legacyCameraId, profile.fps)
        val safeBitrate = profile.bitrate.coerceIn(8_000_000, 28_000_000)

        return try {
            starting.set(true)
            running.set(false)
            firstFrameDelivered = false
            configuredWidth = 3840
            configuredHeight = 2160
            configuredFps = safeFps
            configuredBitrate = safeBitrate

            val opened = Camera.open(profile.legacyCameraId)
            camera = opened

            val params = opened.parameters
            val videoSizes = params.supportedVideoSizes ?: params.supportedPreviewSizes
            val exact4k = videoSizes?.any { it.width == 3840 && it.height == 2160 } == true
            // IP Webcam proves this Samsung can record/stream front 3840x2160 even
            // when some public capability lists omit it. Camera1 accepts OEM string
            // parameters, so try the legacy video-size key before giving up.
            if (!exact4k) {
                runCatching { params.set("video-size", "3840x2160") }
            }

            params.setRecordingHint(true)
            if (
                params.supportedFocusModes?.contains(
                    Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                ) == true
            ) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            }

            params.preferredPreviewSizeForVideo?.let { preferred ->
                if (params.supportedPreviewSizes?.any {
                        it.width == preferred.width && it.height == preferred.height
                    } == true
                ) {
                    params.setPreviewSize(preferred.width, preferred.height)
                }
            }
            opened.parameters = params

            val dummy = SurfaceTexture(0)
            val preview = opened.parameters.previewSize
            dummy.setDefaultBufferSize(preview.width, preview.height)
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
                setVideoSize(3840, 2160)
                setVideoFrameRate(safeFps)
                setVideoEncodingBitRate(safeBitrate)
                setOrientationHint(
                    recorderOrientationHint(
                        profile.legacyCameraId,
                        profile.deviceRotationDegrees,
                        profile.rotationOffsetDegrees
                    )
                )
                setOutputFile(pipeWrite!!.fileDescriptor)
                setOnErrorListener { _, what, extra ->
                    failLegacy("MediaRecorder Camera1 frontal erro $what/$extra")
                }
                prepare()
            }
            recorder = localRecorder

            startTsReader()
            localRecorder.start()
            recorderStarted = true
            starting.set(false)
            running.set(true)
            lastSourceDescription =
                if (exact4k) "Camera1 supportedVideoSizes 3840x2160 → H.264" else "Camera1 OEM video-size 3840x2160 → H.264"

            Thread {
                try {
                    Thread.sleep(5_000L)
                    if (running.get() && !firstFrameDelivered) {
                        failLegacy(
                            "Camera1 aceitou 3840x2160, mas não entregou H.264 em 5 segundos."
                        )
                    }
                } catch (_: InterruptedException) {
                }
            }.apply {
                name = "goat-front4k-camera1-watchdog"
                isDaemon = true
                start()
            }

            true
        } catch (ex: Exception) {
            stopLegacyLocked()
            val gpuStarted = startGpuFallback(profile)
            if (!gpuStarted) {
                listener.onError(
                    "Camera1 4K frontal falhou: " +
                        (ex.message ?: ex.javaClass.simpleName)
                )
            }
            gpuStarted
        }
    }

    private fun startGpuFallback(profile: Profile): Boolean {
        val probe = GpuCameraH264Streamer.probe(
            context,
            profile.cameraId,
            requestedFps = 30,
            preferLargestSource = true
        ) ?: run {
            listener.onError("Frontal não expôs fonte pública utilizável.")
            return false
        }

        val sourcePixels = probe.sourceSize.width.toLong() * probe.sourceSize.height.toLong()
        if (sourcePixels < MIN_FRONT_UHD_SOURCE_PIXELS) {
            listener.onError(
                "Fonte frontal pública pequena para composição 4K: " +
                    "${probe.sourceSize.width}x${probe.sourceSize.height}."
            )
            return false
        }

        val local = GpuCameraH264Streamer(
            context,
            object : GpuCameraH264Streamer.Listener {
                override fun onAccessUnit(
                    data: ByteArray,
                    presentationTimeUs: Long,
                    keyFrame: Boolean,
                    codecConfig: Boolean
                ) {
                    listener.onAccessUnit(data, presentationTimeUs, keyFrame, codecConfig)
                }

                override fun onStarted(
                    width: Int,
                    height: Int,
                    fps: Int,
                    bitrate: Int,
                    sourceWidth: Int,
                    sourceHeight: Int
                ) {
                    lastSourceDescription =
                        "fallback GPU ${sourceWidth}x${sourceHeight} → ${width}x${height}"
                    listener.onStarted(width, height, fps, bitrate)
                }

                override fun onError(message: String) {
                    listener.onError("4K frontal GPU fallback: $message")
                }

                override fun onStopped() = Unit
            }
        )
        gpuDelegate = local

        val safeBitrate = if (profile.bitrate > 0) {
            profile.bitrate.coerceIn(8_000_000, 28_000_000)
        } else {
            H264Encoder.recommendedBitrate(3840, 2160, 30, 80)
        }

        return local.start(
            GpuCameraH264Streamer.Config(
                logicalCameraId = profile.cameraId,
                targetWidth = 3840,
                targetHeight = 2160,
                targetFps = profile.fps.coerceIn(5, 60),
                targetBitrate = safeBitrate,
                zoomRatio = 1f,
                deviceRotationDegrees = normalize(profile.deviceRotationDegrees),
                extraRotationDegrees = normalize(profile.rotationOffsetDegrees),
                preferLargestSource = false,
                minimumSourcePixels = MIN_FRONT_UHD_SOURCE_PIXELS
            )
        )
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
            name = "goat-front4k-camera1-ts"
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

    private fun supportedLegacyFps(legacyId: Int, requested: Int): Int {
        val desired = requested.coerceIn(5, 60)
        return try {
            val opened = Camera.open(legacyId)
            try {
                val ranges = opened.parameters.supportedPreviewFpsRange.orEmpty()
                val maxFps = ranges.maxOfOrNull { range ->
                    if (range.size > 1) range[1] else 30_000
                } ?: 30_000
                minOf(desired, (maxFps / 1000).coerceAtLeast(5))
            } finally {
                opened.release()
            }
        } catch (_: Exception) {
            minOf(desired, 30)
        }
    }

    private fun recorderOrientationHint(
        legacyId: Int,
        deviceDegrees: Int,
        extraDegrees: Int
    ): Int {
        return try {
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(legacyId, info)
            val device = normalize(deviceDegrees)
            val base = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                normalize(info.orientation + device)
            } else {
                normalize(info.orientation - device)
            }
            normalize(base + extraDegrees)
        } catch (_: Exception) {
            normalize(extraDegrees)
        }
    }

    private fun failLegacy(message: String) {
        synchronized(lock) {
            val active = running.get() || starting.get()
            stopLegacyLocked()
            if (active) listener.onError(message)
        }
    }

    private fun stopLegacyLocked() {
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
        private const val MIN_FRONT_UHD_SOURCE_PIXELS = 5_000_000L

        fun profileFor(context: Context, cameraId: String): Profile? {
            val preferred = cameraId.toIntOrNull()
            val candidates = mutableListOf<Int>()
            if (preferred != null) candidates.add(preferred)

            val info = Camera.CameraInfo()
            var firstLegacyFrontId = -1
            for (id in 0 until Camera.getNumberOfCameras()) {
                val front = runCatching {
                    Camera.getCameraInfo(id, info)
                    info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                }.getOrDefault(false)
                if (front && id !in candidates) candidates.add(id)
            }

            for (legacyId in candidates) {
                val cameraInfo = Camera.CameraInfo()
                val validFront = runCatching {
                    Camera.getCameraInfo(legacyId, cameraInfo)
                    cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                }.getOrDefault(false)
                if (!validFront) continue
                if (firstLegacyFrontId < 0) firstLegacyFrontId = legacyId

                val exactLegacy4k = try {
                    val opened = Camera.open(legacyId)
                    try {
                        val params = opened.parameters
                        val sizes = params.supportedVideoSizes ?: params.supportedPreviewSizes
                        sizes?.any { it.width == 3840 && it.height == 2160 } == true
                    } finally {
                        opened.release()
                    }
                } catch (_: Exception) {
                    false
                }

                if (exactLegacy4k) {
                    val official = runCatching {
                        CamcorderProfile.hasProfile(
                            legacyId,
                            CamcorderProfile.QUALITY_2160P
                        ) && CamcorderProfile.get(
                            legacyId,
                            CamcorderProfile.QUALITY_2160P
                        ).let {
                            it.videoFrameWidth == 3840 && it.videoFrameHeight == 2160
                        }
                    }.getOrDefault(false)

                    val bitrate = if (official) {
                        runCatching {
                            CamcorderProfile.get(
                                legacyId,
                                CamcorderProfile.QUALITY_2160P
                            ).videoBitRate.coerceIn(8_000_000, 28_000_000)
                        }.getOrDefault(18_000_000)
                    } else {
                        18_000_000
                    }

                    return Profile(
                        cameraId = cameraId,
                        width = 3840,
                        height = 2160,
                        fps = 30,
                        bitrate = bitrate,
                        fromOfficialProfile = official,
                        legacyCameraId = legacyId,
                        legacyExact4k = true
                    )
                }
            }

            val samsungS21 =
                Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                    Regex("^SM-G99[0168].*", RegexOption.IGNORE_CASE)
                        .matches(Build.MODEL.orEmpty())

            // The same Galaxy S21 was demonstrated running IP Webcam at
            // 3840x2160 H.264 on the front camera. Do not hide the option just
            // because Camera1/Camera2 capability lists omit the exact size.
            if (samsungS21 && firstLegacyFrontId >= 0) {
                return Profile(
                    cameraId = cameraId,
                    width = 3840,
                    height = 2160,
                    fps = 30,
                    bitrate = 18_000_000,
                    fromOfficialProfile = false,
                    legacyCameraId = firstLegacyFrontId,
                    legacyExact4k = false
                )
            }

            val probe = GpuCameraH264Streamer.probe(
                context,
                cameraId,
                requestedFps = 30,
                preferLargestSource = true
            ) ?: return null
            val pixels = probe.sourceSize.width.toLong() * probe.sourceSize.height.toLong()
            if (pixels < MIN_FRONT_UHD_SOURCE_PIXELS) return null

            return Profile(
                cameraId = cameraId,
                width = 3840,
                height = 2160,
                fps = 30,
                bitrate = H264Encoder.recommendedBitrate(3840, 2160, 30, 80),
                fromOfficialProfile = false,
                legacyCameraId = -1,
                legacyExact4k = false
            )
        }

        private fun normalize(value: Int): Int =
            ((value % 360) + 360) % 360
    }
}
