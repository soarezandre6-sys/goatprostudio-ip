package com.goatpro.ip

import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Small low-latency AVC encoder fed from the same NV21 frames used by MJPEG.
 *
 * The encoder uses the phone's MediaCodec implementation (normally hardware backed).
 * It intentionally drops a frame instead of blocking CameraX when the codec has no
 * input buffer ready.
 */
class H264Encoder(
    private val listener: Listener
) {
    interface Listener {
        fun onAccessUnit(data: ByteArray, presentationTimeUs: Long, keyFrame: Boolean, codecConfig: Boolean)
    }

    private val lock = Any()
    private val running = AtomicBoolean(false)

    private var codec: MediaCodec? = null
    private var drainThread: Thread? = null
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var configuredFps = 0
    private var configuredBitrate = 0
    private var configuredColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

    fun isRunning(): Boolean = running.get()

    fun matches(width: Int, height: Int, fps: Int, bitrate: Int): Boolean =
        running.get() &&
            configuredWidth == width &&
            configuredHeight == height &&
            configuredFps == fps &&
            configuredBitrate == bitrate

    fun ensureStarted(width: Int, height: Int, fps: Int, bitrate: Int): Boolean {
        val safeWidth = width and -2
        val safeHeight = height and -2
        val safeFps = fps.coerceIn(5, 120)
        val safeBitrate = bitrate.coerceIn(500_000, 40_000_000)

        synchronized(lock) {
            if (matches(safeWidth, safeHeight, safeFps, safeBitrate)) return true
            stopLocked()

            return try {
                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val caps = encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val colorFormat = chooseColorFormat(caps.colorFormats)

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

                val baselineSupported = caps.profileLevels.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                }
                val fullHdOrLower =
                    safeWidth.toLong() * safeHeight.toLong() <= 1920L * 1080L

                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    safeWidth,
                    safeHeight
                ).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                    setInteger(MediaFormat.KEY_BIT_RATE, safeBitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, safeFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)

                    // Favor realtime encoding over compression efficiency. Avoid frame
                    // reordering (B-frames), which adds latency even when FPS is high.
                    runCatching { setInteger(MediaFormat.KEY_PRIORITY, 0) }
                    runCatching {
                        setFloat(
                            MediaFormat.KEY_OPERATING_RATE,
                            safeFps.toFloat()
                        )
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        runCatching {
                            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                        }
                        // Repeat SPS/PPS on IDR so an RTSP client can start decoding
                        // immediately without waiting for extra codec configuration.
                        runCatching {
                            setInteger("prepend-sps-pps-to-idr-frames", 1)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 30 &&
                        caps.isFeatureSupported(
                            MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency
                        )
                    ) {
                        runCatching { setInteger("low-latency", 1) }
                    }
                    if (baselineSupported && fullHdOrLower) {
                        runCatching {
                            setInteger(
                                MediaFormat.KEY_PROFILE,
                                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                            )
                        }
                    }
                }

                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()

                codec = encoder
                configuredWidth = safeWidth
                configuredHeight = safeHeight
                configuredFps = safeFps
                configuredBitrate = safeBitrate
                configuredColorFormat = colorFormat
                running.set(true)
                startDrainThread(encoder)
                true
            } catch (_: Exception) {
                stopLocked()
                false
            }
        }
    }

    fun offerNv21(frame: ImageUtils.Nv21Frame, presentationTimeNs: Long): Boolean {
        val encoder = codec ?: return false
        if (!running.get()) return false
        if (frame.width != configuredWidth || frame.height != configuredHeight) return false

        return try {
            val index = encoder.dequeueInputBuffer(0)
            if (index < 0) return false

            val input = encoder.getInputBuffer(index) ?: run {
                encoder.queueInputBuffer(index, 0, 0, presentationTimeNs / 1_000L, 0)
                return false
            }

            val expected = frame.width * frame.height * 3 / 2
            if (input.capacity() < expected || frame.bytes.size < expected) {
                encoder.queueInputBuffer(index, 0, 0, presentationTimeNs / 1_000L, 0)
                return false
            }

            input.clear()
            writeEncoderYuv(frame.bytes, frame.width, frame.height, input, configuredColorFormat)
            encoder.queueInputBuffer(
                index,
                0,
                expected,
                presentationTimeNs / 1_000L,
                0
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Build 46 thermal fast path: feed CameraX YUV directly into MediaCodec.
     * This avoids allocating a full 4K NV21 ByteArray and then converting/copying
     * the same frame a second time before H.264 encode.
     */
    fun offerImageProxy(
        image: ImageProxy,
        presentationTimeNs: Long,
        watermarkEnabled: Boolean
    ): Boolean {
        val encoder = codec ?: return false
        if (!running.get()) return false
        if (image.format != ImageFormat.YUV_420_888) return false
        if (image.width != configuredWidth || image.height != configuredHeight) return false
        if (image.planes.size < 3) return false

        return try {
            val index = encoder.dequeueInputBuffer(0)
            if (index < 0) return false

            val input = encoder.getInputBuffer(index) ?: run {
                encoder.queueInputBuffer(index, 0, 0, presentationTimeNs / 1_000L, 0)
                return false
            }

            val expected = image.width * image.height * 3 / 2
            if (input.capacity() < expected) {
                encoder.queueInputBuffer(index, 0, 0, presentationTimeNs / 1_000L, 0)
                return false
            }

            input.clear()
            writeImageProxyYuv(image, input, configuredColorFormat)
            WatermarkOverlay.applyLuma(
                input,
                image.width,
                image.height,
                watermarkEnabled
            )
            input.position(expected)
            encoder.queueInputBuffer(
                index,
                0,
                expected,
                presentationTimeNs / 1_000L,
                0
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    fun requestKeyFrame() {
        val encoder = codec ?: return
        if (!running.get()) return
        runCatching {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            encoder.setParameters(params)
        }
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        running.set(false)
        val localCodec = codec
        codec = null
        runCatching { localCodec?.signalEndOfInputStream() }
        runCatching { localCodec?.stop() }
        runCatching { localCodec?.release() }
        drainThread = null
        configuredWidth = 0
        configuredHeight = 0
        configuredFps = 0
        configuredBitrate = 0
    }

    private fun startDrainThread(encoder: MediaCodec) {
        drainThread = Thread {
            val info = MediaCodec.BufferInfo()
            while (running.get() && codec === encoder) {
                try {
                    when (val index = encoder.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        else -> if (index >= 0) {
                            val out = encoder.getOutputBuffer(index)
                            if (out != null && info.size > 0) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                out.get(bytes)

                                val codecConfig =
                                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                val keyFrame =
                                    (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                                listener.onAccessUnit(
                                    bytes,
                                    info.presentationTimeUs,
                                    keyFrame,
                                    codecConfig
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
            name = "goat-h264-drain"
            isDaemon = true
            start()
        }
    }

    private fun writeImageProxyYuv(
        image: ImageProxy,
        output: ByteBuffer,
        colorFormat: Int
    ) {
        val width = image.width
        val height = image.height
        val planes = image.planes

        writePlaneSequential(planes[0], width, height, output)

        val planar =
            colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
                colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar

        val chromaWidth = width / 2
        val chromaHeight = height / 2
        if (planar) {
            writePlaneSequential(planes[1], chromaWidth, chromaHeight, output)
            writePlaneSequential(planes[2], chromaWidth, chromaHeight, output)
            return
        }

        // Existing semi-planar encoder path expects NV12 (UV). CameraX exposes
        // U and V separately, often as pixelStride=2 views over NV21 memory.
        // Interleave directly into the codec input buffer without an NV21 staging array.
        val u = planes[1]
        val v = planes[2]
        val ub = u.buffer.duplicate()
        val vb = v.buffer.duplicate()
        for (row in 0 until chromaHeight) {
            val uRow = row * u.rowStride
            val vRow = row * v.rowStride
            for (col in 0 until chromaWidth) {
                output.put(ub.get(uRow + col * u.pixelStride))
                output.put(vb.get(vRow + col * v.pixelStride))
            }
        }
    }

    private fun writePlaneSequential(
        plane: ImageProxy.PlaneProxy,
        width: Int,
        height: Int,
        output: ByteBuffer
    ) {
        val source = plane.buffer.duplicate()
        if (plane.pixelStride == 1) {
            for (row in 0 until height) {
                val start = row * plane.rowStride
                val rowBuffer = source.duplicate()
                rowBuffer.position(start)
                rowBuffer.limit(start + width)
                output.put(rowBuffer)
            }
            return
        }

        for (row in 0 until height) {
            val rowStart = row * plane.rowStride
            for (col in 0 until width) {
                output.put(source.get(rowStart + col * plane.pixelStride))
            }
        }
    }

    private fun chooseColorFormat(colorFormats: IntArray): Int {
        val preferred = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar
        )
        for (candidate in preferred) {
            if (colorFormats.contains(candidate)) return candidate
        }
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    }

    private fun writeEncoderYuv(
        nv21: ByteArray,
        width: Int,
        height: Int,
        output: ByteBuffer,
        colorFormat: Int
    ) {
        val ySize = width * height
        output.put(nv21, 0, ySize)

        val planar =
            colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
                colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar

        if (planar) {
            val chromaSamples = ySize / 4
            val uStart = ySize
            val vStart = ySize + chromaSamples

            // NV21 is VU. Planar encoders expect I420: Y + U + V.
            var src = ySize
            var sample = 0
            while (sample < chromaSamples) {
                output.put(uStart + sample, nv21[src + 1])
                output.put(vStart + sample, nv21[src])
                src += 2
                sample++
            }
            output.position(ySize + chromaSamples * 2)
        } else {
            // Most Android AVC encoders expect NV12 for semi-planar/flexible input.
            // Convert only the chroma order (VU -> UV); the Y plane is copied as-is.
            var src = ySize
            val end = nv21.size
            while (src + 1 < end) {
                output.put(nv21[src + 1])
                output.put(nv21[src])
                src += 2
            }
        }
    }

    companion object {
        fun recommendedBitrate(width: Int, height: Int, fps: Int, quality: Int): Int {
            val pixels = width.toLong() * height.toLong()
            val pixelsPerSecond = pixels * max(5, fps)
            val qualityFactor = 0.11 + (quality.coerceIn(1, 100) / 100.0) * 0.16
            val calculated = (pixelsPerSecond * qualityFactor).toLong()

            val ceiling = when {
                pixels >= 3840L * 2160L -> 18_000_000L
                pixels >= 2560L * 1440L -> 20_000_000L
                fps >= 100 -> 32_000_000L
                fps >= 60 -> 22_000_000L
                else -> 12_000_000L
            }

            return calculated
                .coerceIn(1_000_000L, ceiling)
                .toInt()
        }
    }
}
