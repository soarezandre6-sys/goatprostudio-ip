package com.goatpro.ip

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.CamcorderProfile
import android.os.Build

/**
 * Front UHD compatibility path for Samsung devices whose stock camera records
 * 4K but whose public camera API does not publish a 3840x2160 output stream.
 *
 * Instead of asking the HAL for a fake/unsupported 3840x2160 camera Surface,
 * the camera writes its largest REAL public SurfaceTexture size. The GPU then
 * crops/scales that source into a 3840x2160 H.264 encoder Surface.
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
        val bitrate: Int = 36_000_000,
        val fromOfficialProfile: Boolean = false,
        val deviceRotationDegrees: Int = 0,
        val rotationOffsetDegrees: Int = 0
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

    @Volatile
    private var activeProfile: Profile? = null

    @Volatile
    private var delegate: GpuCameraH264Streamer? = null

    @Volatile
    var lastSourceDescription: String = ""
        private set

    fun isRunning(): Boolean = delegate?.isRunning() == true
    fun isStarting(): Boolean = delegate?.isStarting() == true
    fun requestKeyFrame() = delegate?.requestKeyFrame() ?: Unit

    fun start(profile: Profile): Boolean {
        if (isRunning() || isStarting()) return true
        activeProfile = profile
        return startDelegate(profile)
    }

    fun updateRotation(deviceRotationDegrees: Int, rotationOffsetDegrees: Int = 0) {
        val current = activeProfile ?: return
        val normalizedDevice = normalize(deviceRotationDegrees)
        val normalizedOffset = normalize(rotationOffsetDegrees)
        if (
            current.deviceRotationDegrees == normalizedDevice &&
            current.rotationOffsetDegrees == normalizedOffset
        ) return

        val updated = current.copy(
            deviceRotationDegrees = normalizedDevice,
            rotationOffsetDegrees = normalizedOffset
        )
        activeProfile = updated
        if (!isRunning() && !isStarting()) return

        val old = delegate
        delegate = null
        old?.stop()
        startDelegate(updated)
    }

    fun stop() {
        activeProfile = null
        val old = delegate
        delegate = null
        old?.stop()
    }

    private fun startDelegate(profile: Profile): Boolean {
        val probe = GpuCameraH264Streamer.probe(
            context,
            profile.cameraId,
            requestedFps = 30,
            preferLargestSource = true
        )
        if (probe == null) {
            listener.onError("Frontal não expôs uma SurfaceTexture pública utilizável.")
            return false
        }

        val sourcePixels = probe.sourceSize.width.toLong() * probe.sourceSize.height.toLong()
        if (sourcePixels < MIN_FRONT_UHD_SOURCE_PIXELS) {
            listener.onError(
                "Fonte frontal pública pequena demais para 4K: " +
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
                    sourceHeight: Int
                ) {
                    lastSourceDescription =
                        "fonte pública ${sourceWidth}x${sourceHeight} → ${width}x${height} GPU"
                    listener.onStarted(width, height, fps, bitrate)
                }

                override fun onError(message: String) {
                    listener.onError("4K frontal GPU: $message")
                }

                override fun onStopped() {
                    listener.onStopped()
                }
            }
        )
        delegate = local

        val safeBitrate = if (profile.bitrate > 0) {
            profile.bitrate.coerceIn(12_000_000, 60_000_000)
        } else if (profile.fps > 30) {
            48_000_000
        } else {
            36_000_000
        }

        return local.start(
            GpuCameraH264Streamer.Config(
                logicalCameraId = profile.cameraId,
                targetWidth = profile.width,
                targetHeight = profile.height,
                targetFps = profile.fps.coerceIn(5, 60),
                targetBitrate = safeBitrate,
                zoomRatio = 1f,
                deviceRotationDegrees = normalize(profile.deviceRotationDegrees),
                extraRotationDegrees = normalize(profile.rotationOffsetDegrees),
                preferLargestSource = true,
                // profileFor() already requires a high-resolution public source.
                // At 60 FPS the GPU may deliberately choose a smaller 60-FPS source.
                minimumSourcePixels = 0L
            )
        )
    }

    companion object {
        private const val MIN_FRONT_UHD_SOURCE_PIXELS = 5_000_000L

        fun profileFor(context: Context, cameraId: String): Profile? {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = runCatching {
                manager.getCameraCharacteristics(cameraId)
            }.getOrNull() ?: return null
            if (
                chars.get(CameraCharacteristics.LENS_FACING) !=
                CameraCharacteristics.LENS_FACING_FRONT
            ) return null

            val probe = GpuCameraH264Streamer.probe(
                context,
                cameraId,
                requestedFps = 30,
                preferLargestSource = true
            ) ?: return null
            val sourcePixels =
                probe.sourceSize.width.toLong() * probe.sourceSize.height.toLong()
            if (sourcePixels < MIN_FRONT_UHD_SOURCE_PIXELS) return null

            val numericId = cameraId.toIntOrNull()
            val official = numericId != null && runCatching {
                CamcorderProfile.hasProfile(numericId, CamcorderProfile.QUALITY_2160P) &&
                    CamcorderProfile.get(numericId, CamcorderProfile.QUALITY_2160P).let {
                        it.videoFrameWidth == 3840 && it.videoFrameHeight == 2160
                    }
            }.getOrDefault(false)

            val samsungS21 =
                Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                    Regex("^SM-G99[0168].*", RegexOption.IGNORE_CASE)
                        .matches(Build.MODEL.orEmpty())
            val maxFps = when {
                probe.maxAeFps >= 60 -> 60
                samsungS21 -> 60
                else -> probe.qualityFps.coerceIn(30, 60)
            }

            return Profile(
                cameraId = cameraId,
                width = 3840,
                height = 2160,
                fps = maxFps,
                bitrate = if (maxFps > 30) 48_000_000 else 36_000_000,
                fromOfficialProfile = official
            )
        }

        private fun normalize(value: Int): Int =
            ((value % 360) + 360) % 360
    }
}
