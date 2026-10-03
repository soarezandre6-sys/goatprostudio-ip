from pathlib import Path

p = Path('app/src/main/java/com/goatpro/ip/Front4kDirectStreamer.kt')
s = p.read_text()


def rep(old: str, new: str) -> None:
    global s
    if old not in s:
        raise SystemExit('missing patch block:\n' + old[:240])
    s = s.replace(old, new, 1)

rep(
'''    @Volatile
    private var gpuDelegate: GpuCameraH264Streamer? = null
''',
'''    @Volatile
    private var gpuDelegate: GpuCameraH264Streamer? = null

    @Volatile
    private var camera1GpuDelegate: Camera1GpuH264Streamer? = null
'''
)

rep(
'''    fun isRunning(): Boolean = running.get() || gpuDelegate?.isRunning() == true
    fun isStarting(): Boolean = starting.get() || gpuDelegate?.isStarting() == true

    fun requestKeyFrame() {
        gpuDelegate?.requestKeyFrame()
        // MediaRecorder has no public force-IDR API.
    }
''',
'''    fun isRunning(): Boolean =
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
'''
)

rep(
'''            val effective = profileFor(context, profile.cameraId)
            if (effective != null && effective.legacyCameraId >= 0) {
                return startLegacy(
                    effective.copy(
                        fps = profile.fps.coerceIn(5, 60),
                        bitrate = profile.bitrate.coerceIn(8_000_000, 28_000_000),
                        deviceRotationDegrees = normalize(profile.deviceRotationDegrees),
                        rotationOffsetDegrees = normalize(profile.rotationOffsetDegrees)
                    )
                )
            }

            return startGpuFallback(profile)
''',
'''            val effective = profileFor(context, profile.cameraId)
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
'''
)

rep(
'''    fun updateRotation(deviceRotationDegrees: Int, rotationOffsetDegrees: Int = 0) {
        val current = activeProfile ?: return
        activeProfile = current.copy(
            deviceRotationDegrees = normalize(deviceRotationDegrees),
            rotationOffsetDegrees = normalize(rotationOffsetDegrees)
        )

        gpuDelegate?.let {
''',
'''    fun updateRotation(deviceRotationDegrees: Int, rotationOffsetDegrees: Int = 0) {
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
'''
)

rep(
'''            activeProfile = null
            gpuDelegate?.stop()
            gpuDelegate = null
            stopLegacyLocked()
            if (active) listener.onStopped()
''',
'''            activeProfile = null
            camera1GpuDelegate?.stop()
            camera1GpuDelegate = null
            gpuDelegate?.stop()
            gpuDelegate = null
            stopLegacyLocked()
            if (active) listener.onStopped()
'''
)

marker = '''    private fun startLegacy(profile: Profile): Boolean {
'''
helper = r'''    private fun startCamera1Gpu(profile: Profile): Boolean {
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

'''
if marker not in s:
    raise SystemExit('missing startLegacy marker')
s = s.replace(marker, helper + marker, 1)

p.write_text(s)
