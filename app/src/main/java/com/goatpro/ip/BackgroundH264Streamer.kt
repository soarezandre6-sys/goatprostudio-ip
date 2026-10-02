package com.goatpro.ip

import android.content.Context

/**
 * Low-overhead background RTSP path.
 *
 * Build 28 keeps the camera off the CPU hot path, but unlike the old direct
 * camera->encoder Surface route it inserts a tiny GPU transform stage. That
 * makes automatic rotation possible while Home / screen-off transmission stays
 * hardware accelerated.
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

    private data class Request(
        val logicalCameraId: String,
        val physicalCameraId: String?,
        val targetWidth: Int,
        val targetHeight: Int,
        val targetFps: Int,
        val targetBitrate: Int,
        val targetZoomRatio: Float,
        val deviceRotationDegrees: Int,
        val rotationOffsetDegrees: Int
    )

    @Volatile
    private var request: Request? = null

    @Volatile
    private var delegate: GpuCameraH264Streamer? = null

    @Volatile
    private var generation = 0

    fun isRunning(): Boolean = delegate?.isRunning() == true
    fun isStarting(): Boolean = delegate?.isStarting() == true

    fun start(
        logicalCameraId: String,
        physicalCameraId: String?,
        targetWidth: Int,
        targetHeight: Int,
        targetFps: Int,
        targetBitrate: Int,
        targetZoomRatio: Float = 1f,
        deviceRotationDegrees: Int = 0,
        rotationOffsetDegrees: Int = 0
    ): Boolean {
        if (isRunning() || isStarting()) return true

        val safe = Request(
            logicalCameraId = logicalCameraId,
            physicalCameraId = physicalCameraId,
            targetWidth = targetWidth.coerceAtMost(1920).coerceAtLeast(640) and -2,
            targetHeight = targetHeight.coerceAtMost(1080).coerceAtLeast(360) and -2,
            targetFps = targetFps.coerceIn(10, 60),
            targetBitrate = targetBitrate.coerceIn(2_000_000, 16_000_000),
            targetZoomRatio = targetZoomRatio.coerceAtLeast(1f),
            deviceRotationDegrees = normalize(deviceRotationDegrees),
            rotationOffsetDegrees = normalize(rotationOffsetDegrees)
        )
        request = safe
        return startDelegate(safe)
    }

    fun updateRotation(
        deviceRotationDegrees: Int,
        rotationOffsetDegrees: Int = 0
    ) {
        val current = request ?: return
        val updated = current.copy(
            deviceRotationDegrees = normalize(deviceRotationDegrees),
            rotationOffsetDegrees = normalize(rotationOffsetDegrees)
        )
        if (
            updated.deviceRotationDegrees == current.deviceRotationDegrees &&
            updated.rotationOffsetDegrees == current.rotationOffsetDegrees
        ) return
        request = updated

        if (!isRunning() && !isStarting()) return
        generation++
        val old = delegate
        delegate = null
        old?.stop()
        startDelegate(updated)
    }

    fun requestKeyFrame() {
        delegate?.requestKeyFrame()
    }

    fun stop() {
        generation++
        request = null
        val old = delegate
        delegate = null
        old?.stop()
    }

    private fun startDelegate(req: Request): Boolean {
        val localGeneration = ++generation
        val local = GpuCameraH264Streamer(
            context,
            object : GpuCameraH264Streamer.Listener {
                override fun onAccessUnit(
                    data: ByteArray,
                    presentationTimeUs: Long,
                    keyFrame: Boolean,
                    codecConfig: Boolean
                ) {
                    if (localGeneration != generation) return
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
                    if (localGeneration != generation) return
                    listener.onStarted(width, height, fps, bitrate)
                }

                override fun onError(message: String) {
                    if (localGeneration != generation) return
                    listener.onError("Modo leve GPU: $message")
                }

                override fun onStopped() {
                    if (localGeneration != generation) return
                    listener.onStopped()
                }
            }
        )
        delegate = local
        return local.start(
            GpuCameraH264Streamer.Config(
                logicalCameraId = req.logicalCameraId,
                physicalCameraId = req.physicalCameraId,
                targetWidth = req.targetWidth,
                targetHeight = req.targetHeight,
                targetFps = req.targetFps,
                targetBitrate = req.targetBitrate,
                zoomRatio = req.targetZoomRatio,
                deviceRotationDegrees = req.deviceRotationDegrees,
                extraRotationDegrees = req.rotationOffsetDegrees,
                preferLargestSource = false,
                minimumSourcePixels = 0L
            )
        )
    }

    private fun normalize(value: Int): Int =
        ((value % 360) + 360) % 360
}
