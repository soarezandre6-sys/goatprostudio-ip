from pathlib import Path

path = Path('app/src/main/java/com/goatpro/ip/MainActivity.kt')
s = path.read_text(encoding='utf-8')

def once(old, new, label):
    global s
    count = s.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: esperado 1, encontrado {count}')
    s = s.replace(old, new, 1)

# Report the new front GPU path instead of the removed Camera1/MediaRecorder route.
once(
'''                        performanceText.text =
                            "4K frontal Camera1/MediaRecorder • H.264 • " +
                                fps + " FPS • " +
                                String.format(
                                    java.util.Locale.US,
                                    "%.1f Mbps",
                                    bitrate / 1_000_000.0
                                )
''',
'''                        performanceText.text =
                            "4K frontal GPU • H.264 • " +
                                fps + " FPS • " +
                                String.format(
                                    java.util.Locale.US,
                                    "%.1f Mbps",
                                    bitrate / 1_000_000.0
                                ) +
                                " • " + front4kDirectStreamer.lastSourceDescription
''',
'front gpu status'
)

# Keep actual stream dimensions in sync when the GPU background encoder rotates.
once(
'''                override fun onStarted(
                    width: Int,
                    height: Int,
                    fps: Int,
                    bitrate: Int
                ) {
                    backgroundDirectActive = true
                }
''',
'''                override fun onStarted(
                    width: Int,
                    height: Int,
                    fps: Int,
                    bitrate: Int
                ) {
                    backgroundDirectActive = true
                    runOnUiThread {
                        actualStreamWidth = width
                        actualStreamHeight = height
                        updateStreamInfo()
                    }
                }
''',
'background dimensions'
)

# Pass current rotation into the GPU background stream.
once(
'''                val started = backgroundH264Streamer.start(
                    logicalCameraId = option.logicalCameraId,
                    physicalCameraId = option.physicalCameraId,
                    targetWidth = selectedResolution.size.width,
                    targetHeight = selectedResolution.size.height,
                    targetFps = targetFps,
                    targetBitrate = targetBitrate,
                    targetZoomRatio = zoom
                )
''',
'''                val started = backgroundH264Streamer.start(
                    logicalCameraId = option.logicalCameraId,
                    physicalCameraId = option.physicalCameraId,
                    targetWidth = selectedResolution.size.width,
                    targetHeight = selectedResolution.size.height,
                    targetFps = targetFps,
                    targetBitrate = targetBitrate,
                    targetZoomRatio = zoom,
                    deviceRotationDegrees = if (selectedRotationMode == RotationMode.AUTO) {
                        surfaceRotationDegrees(autoSurfaceRotation)
                    } else {
                        0
                    },
                    rotationOffsetDegrees = if (selectedRotationMode == RotationMode.AUTO) {
                        0
                    } else {
                        selectedRotationMode.offsetDegrees
                    }
                )
''',
'background rotation start'
)

# Pass current rotation into front 4K GPU composition.
once(
'''                        fromOfficialProfile =
                            Front4kDirectStreamer.profileFor(
                                this,
                                cameraId
                            )?.fromOfficialProfile == true
                    )
''',
'''                        fromOfficialProfile =
                            Front4kDirectStreamer.profileFor(
                                this,
                                cameraId
                            )?.fromOfficialProfile == true,
                        deviceRotationDegrees =
                            if (selectedRotationMode == RotationMode.AUTO) {
                                surfaceRotationDegrees(autoSurfaceRotation)
                            } else {
                                0
                            },
                        rotationOffsetDegrees =
                            if (selectedRotationMode == RotationMode.AUTO) {
                                0
                            } else {
                                selectedRotationMode.offsetDegrees
                            }
                    )
''',
'front rotation start'
)

# When orientation changes, update CameraX and the GPU routes.
once(
'''                if (selectedRotationMode == RotationMode.AUTO) {
                    applyCameraTargetRotation(rotation)
                    actualStreamWidth = 0
                    actualStreamHeight = 0
                    nextEncodeDueNs = 0L
                    runOnUiThread { updateStreamInfo() }
                }
''',
'''                if (selectedRotationMode == RotationMode.AUTO) {
                    applyCameraTargetRotation(rotation)
                    val degrees = surfaceRotationDegrees(rotation)
                    if (
                        backgroundH264Streamer.isRunning() ||
                        backgroundH264Streamer.isStarting()
                    ) {
                        backgroundH264Streamer.updateRotation(degrees, 0)
                    }
                    if (
                        front4kDirectStreamer.isRunning() ||
                        front4kDirectStreamer.isStarting()
                    ) {
                        front4kDirectStreamer.updateRotation(degrees, 0)
                    }
                    actualStreamWidth = 0
                    actualStreamHeight = 0
                    nextEncodeDueNs = 0L
                    runOnUiThread { updateStreamInfo() }
                }
''',
'orientation gpu propagation'
)

# Add a single conversion helper next to CameraX target rotation.
once(
'''    private fun applyCameraTargetRotation(rotation: Int) {
        previewUseCase?.targetRotation = rotation
        analysisUseCase?.targetRotation = rotation
    }
''',
'''    private fun applyCameraTargetRotation(rotation: Int) {
        previewUseCase?.targetRotation = rotation
        analysisUseCase?.targetRotation = rotation
    }

    private fun surfaceRotationDegrees(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
''',
'surface rotation helper'
)

# Do NOT switch the orientation sensor off when a background AUTO stream needs it.
once(
'''    override fun onPause() {
        if (::orientationListener.isInitialized) orientationListener.disable()
        super.onPause()
    }
''',
'''    override fun onPause() {
        val keepOrientationTracking =
            isStreamingActive() &&
                backgroundStreamingEnabled &&
                selectedRotationMode == RotationMode.AUTO
        if (
            ::orientationListener.isInitialized &&
            !keepOrientationTracking
        ) {
            orientationListener.disable()
        }
        super.onPause()
    }
''',
'keep orientation in background'
)

# Documentation comment in the background transition: it is now GPU transform, not direct Surface.
once(
'''        val useDirectRtsp =
''',
'''        // RTSP-only background mode now uses camera -> GPU -> encoder. The GPU
        // performs rotation/crop/scale without NV21/JPEG CPU conversion.
        val useDirectRtsp =
''',
'background gpu comment'
)

path.write_text(s, encoding='utf-8')
print('Build 28 MainActivity patch applied')
