from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/goatpro/ip/MainActivity.kt"
MJPEG = ROOT / "app/src/main/java/com/goatpro/ip/MjpegServer.kt"
FRONT = ROOT / "app/src/main/java/com/goatpro/ip/Front4kDirectStreamer.kt"
H264 = ROOT / "app/src/main/java/com/goatpro/ip/H264Encoder.kt"
BG = ROOT / "app/src/main/java/com/goatpro/ip/BackgroundH264Streamer.kt"
STATUS = ROOT / "BUILD_27_STATUS.md"


def once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: esperado 1, encontrado {count}")
    return text.replace(old, new, 1)

main = MAIN.read_text(encoding="utf-8")

# Preserve up to 120 FPS in Smart Link/presets and normal H.264 path.
main = main.replace(".coerceIn(5, 60)", ".coerceIn(5, 120)")
main = main.replace(".coerceIn(10, 30)", ".coerceIn(10, 60)")

# Route RTSP keyframes through high-speed encoder when active.
main = once(
    main,
    '''                    when {
                        backgroundH264Streamer.isRunning() ||
                            backgroundH264Streamer.isStarting() ->
                            backgroundH264Streamer.requestKeyFrame()
''',
    '''                    when {
                        highSpeedH264Streamer.isRunning() ||
                            highSpeedH264Streamer.isStarting() ->
                            highSpeedH264Streamer.requestKeyFrame()
                        backgroundH264Streamer.isRunning() ||
                            backgroundH264Streamer.isStarting() ->
                            backgroundH264Streamer.requestKeyFrame()
''',
    "rtsp highspeed keyframe",
)
main = once(
    main,
    '''                    !front4kDirectStreamer.isRunning() &&
                    !backgroundH264Streamer.isRunning()
''',
    '''                    !front4kDirectStreamer.isRunning() &&
                    !backgroundH264Streamer.isRunning() &&
                    !highSpeedH264Streamer.isRunning()
''',
    "rtsp highspeed stop guard",
)

# High-speed streamer instance.
anchor = '''    private val backgroundH264Streamer:
        BackgroundH264Streamer by lazy {
'''
hs_block = '''    private val highSpeedH264Streamer:
        HighSpeedH264Streamer by lazy {
        HighSpeedH264Streamer(
            this,
            object : HighSpeedH264Streamer.Listener {
                override fun onAccessUnit(
                    data: ByteArray,
                    presentationTimeUs: Long,
                    keyFrame: Boolean,
                    codecConfig: Boolean
                ) {
                    rtspServer.onAccessUnit(
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
                    bitrate: Int
                ) {
                    runOnUiThread {
                        actualStreamWidth = width
                        actualStreamHeight = height
                        statusText.text = "TRANSMITINDO HIGH-SPEED • $fps FPS"
                        performanceText.text =
                            "H.264 high-speed • ${width}×${height} • $fps FPS • " +
                                String.format(
                                    java.util.Locale.US,
                                    "%.1f Mbps",
                                    bitrate / 1_000_000.0
                                )
                        updateStreamInfo()
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        highSpeedH264Streamer.stop()
                        if (streamTargetFps > 60) {
                            streamTargetFps = maxRegularFpsForSelectedCamera()
                                .coerceAtMost(60)
                                .coerceAtLeast(30)
                            selectedQualityProfile = QualityProfile.CUSTOM
                        }
                        setError("High-speed recusado • $message • voltando para $streamTargetFps FPS")
                        startCamera()
                        updateStreamInfo()
                        saveSmartLinkState()
                    }
                }

                override fun onStopped() = Unit
            }
        )
    }

'''
main = once(main, anchor, hs_block + anchor, "highspeed block")

# Stream button recognizes high-speed route.
main = once(
    main,
    '''                backgroundH264Streamer.isRunning() ||
                backgroundH264Streamer.isStarting()
''',
    '''                backgroundH264Streamer.isRunning() ||
                backgroundH264Streamer.isStarting() ||
                highSpeedH264Streamer.isRunning() ||
                highSpeedH264Streamer.isStarting()
''',
    "stream button highspeed",
)

# FPS capability helpers inserted before quality selector.
quality_anchor = '''    private fun setupQualitySelector() {
'''
fps_helpers = '''    private fun maxRegularFpsForSelectedCamera(): Int {
        if (selectedResolution.directFront4k) {
            return (selectedResolution.directFps ?: 60).coerceIn(30, 60)
        }
        val option = selectedCameraOption ?: return 30
        val cameraId = option.physicalCameraId ?: option.logicalCameraId
        return try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ranges = manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                .orEmpty()
            (ranges.maxOfOrNull { it.upper } ?: 30).coerceIn(30, 60)
        } catch (_: Exception) {
            30
        }
    }

    private fun maxSelectableFps(): Int {
        if (selectedResolution.directFront4k) {
            return (selectedResolution.directFps ?: 60).coerceIn(30, 60)
        }
        val option = selectedCameraOption ?: return 30
        val regular = maxRegularFpsForSelectedCamera()
        val highSpeed = HighSpeedH264Streamer.maxSupportedFps(
            this,
            option.logicalCameraId,
            selectedResolution.size
        )
        return maxOf(regular, highSpeed).coerceIn(30, 120)
    }

    private fun preferredRegularFpsRange(): android.util.Range<Int>? {
        val camera = currentCamera ?: return null
        if (manualExposureEnabled || selectedResolution.directFront4k) return null
        val requested =
            (if (streamTargetFps > 0) streamTargetFps else 30)
                .coerceAtMost(60)
        return try {
            val ranges = Camera2CameraInfo.from(camera.cameraInfo)
                .getCameraCharacteristic(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                ).orEmpty()
            ranges
                .filter { it.lower <= requested && it.upper >= requested }
                .minWithOrNull(
                    compareBy<android.util.Range<Int>> { it.upper - it.lower }
                        .thenBy { kotlin.math.abs(it.upper - requested) }
                )
        } catch (_: Exception) {
            null
        }
    }

'''
main = once(main, quality_anchor, fps_helpers + quality_anchor, "fps helpers")

# Resolution label reports front 4K FPS and option remains visible when legacy/S21 path is available.
main = once(
    main,
    '''                        label = "4K UHD · 3840×2160 · frontal",
''',
    '''                        label = "4K UHD · 3840×2160 · frontal · até ${profile.fps} FPS",
''',
    "front4k label",
)

# Remote FPS control obeys actual selected-camera limit and restarts the route if already streaming.
old_fps = '''            "fpsLimit" -> {
                val requested = value?.toIntOrNull() ?: return
                streamTargetFps = if (requested <= 0) 0 else requested.coerceIn(5, 120)
                selectedQualityProfile = QualityProfile.CUSTOM
                qualitySpinner.setSelection(QualityProfile.entries.indexOf(QualityProfile.CUSTOM))
                nextEncodeDueNs = 0L
                resetPerformanceStats()
                updateStreamInfo()
                if (manualExposureEnabled) applyManualExposure()
            }
'''
new_fps = '''            "fpsLimit" -> {
                val requested = value?.toIntOrNull() ?: return
                val maxFps = maxSelectableFps()
                streamTargetFps =
                    if (requested <= 0) 0
                    else requested.coerceIn(5, maxFps)
                selectedQualityProfile = QualityProfile.CUSTOM
                qualitySpinner.setSelection(
                    QualityProfile.entries.indexOf(QualityProfile.CUSTOM)
                )
                nextEncodeDueNs = 0L
                resetPerformanceStats()
                updateStreamInfo()
                if (manualExposureEnabled) {
                    applyManualExposure()
                } else {
                    applyAutomaticSensorControls()
                }

                if (isStreamingActive()) {
                    stopStreaming()
                    previewView.postDelayed({
                        startCamera()
                        startStreaming()
                    }, 250L)
                }
            }
'''
main = once(main, old_fps, new_fps, "fps remote block")

# Add FPS range to automatic Camera2 controls.
old_auto = '''            if (selectedSceneMode == CaptureRequest.CONTROL_SCENE_MODE_DISABLED) {
'''
new_auto = '''            preferredRegularFpsRange()?.let { fpsRange ->
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    fpsRange
                )
            }

            if (selectedSceneMode == CaptureRequest.CONTROL_SCENE_MODE_DISABLED) {
'''
main = once(main, old_auto, new_auto, "automatic fps range")

# JSON exposes dynamic maximum FPS to Studio/web panel.
main = once(
    main,
    '''                ",\\\"targetFps\\\":$streamTargetFps" +
''',
    '''                ",\\\"targetFps\\\":$streamTargetFps" +
                ",\\\"maxFps\\\":${maxSelectableFps()}" +
''',
    "maxFps json",
)
main = once(
    main,
    '''                ",\\\"h264Running\\\":${h264Encoder.isRunning() || front4kDirectStreamer.isRunning()}" +
''',
    '''                ",\\\"h264Running\\\":${h264Encoder.isRunning() || front4kDirectStreamer.isRunning() || highSpeedH264Streamer.isRunning()}" +
''',
    "h264 running json",
)

# High-speed session is already UI-independent in background.
main = once(
    main,
    '''        if (selectedResolution.directFront4k) return
        if (backgroundHeadless) return
''',
    '''        if (selectedResolution.directFront4k) return
        if (highSpeedH264Streamer.isRunning() || highSpeedH264Streamer.isStarting()) return
        if (backgroundHeadless) return
''',
    "background highspeed guard",
)
main = once(
    main,
    '''            backgroundH264Streamer.isRunning() ||
            backgroundH264Streamer.isStarting()
        ) return
''',
    '''            backgroundH264Streamer.isRunning() ||
            backgroundH264Streamer.isStarting() ||
            highSpeedH264Streamer.isRunning() ||
            highSpeedH264Streamer.isStarting()
        ) return
''',
    "startCamera highspeed guard",
)

# Start streaming: stop stale high-speed route and select constrained route above 60 FPS.
main = once(
    main,
    '''        startStreamingForegroundService()
        front4kDirectStreamer.stop()
''',
    '''        startStreamingForegroundService()
        highSpeedH264Streamer.stop()
        front4kDirectStreamer.stop()
''',
    "start streaming highspeed reset",
)
main = once(
    main,
    '''        if (selectedResolution.directFront4k) {
''',
    '''        val selectedCamera = selectedCameraOption
        val highSpeedRequested =
            !selectedResolution.directFront4k &&
            streamTargetFps > 60 &&
            selectedCamera != null &&
            HighSpeedH264Streamer.supports(
                this,
                selectedCamera.logicalCameraId,
                selectedResolution.size,
                streamTargetFps
            )

        if (selectedResolution.directFront4k) {
''',
    "highspeed request flag",
)
main = once(
    main,
    '''            }, ContextCompat.getMainExecutor(this))
        } else {
            front4kDirectStreamer.stop()
            rtspServer.start()
            statusText.text = "TRANSMITINDO PARA O GOAT PRO STUDIO"
''',
    '''            }, ContextCompat.getMainExecutor(this))
        } else if (highSpeedRequested) {
            front4kDirectStreamer.stop()
            h264Encoder.stop()
            backgroundH264Streamer.stop()
            rtspServer.start()
            statusText.text = "INICIANDO HIGH-SPEED ${streamTargetFps} FPS…"
            statusText.setTextColor(
                ContextCompat.getColor(this, R.color.green)
            )

            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    provider.unbindAll()
                    previewUseCase = null
                    analysisUseCase = null
                    val zoom = currentCamera?.cameraInfo?.zoomState?.value?.zoomRatio
                        ?: selectedCamera?.zoomRatio
                        ?: 1f
                    currentCamera = null
                    val bitrate = if (streamBitrateBps > 0) {
                        streamBitrateBps
                    } else {
                        H264Encoder.recommendedBitrate(
                            selectedResolution.size.width,
                            selectedResolution.size.height,
                            streamTargetFps,
                            streamJpegQuality
                        )
                    }
                    val started = highSpeedH264Streamer.start(
                        targetCameraId = selectedCamera!!.logicalCameraId,
                        targetWidth = selectedResolution.size.width,
                        targetHeight = selectedResolution.size.height,
                        targetFps = streamTargetFps,
                        targetBitrate = bitrate,
                        targetZoomRatio = zoom
                    )
                    if (!started) {
                        streamTargetFps = maxRegularFpsForSelectedCamera()
                            .coerceAtMost(60)
                            .coerceAtLeast(30)
                        startCamera()
                    }
                } catch (ex: Exception) {
                    streamTargetFps = maxRegularFpsForSelectedCamera()
                        .coerceAtMost(60)
                        .coerceAtLeast(30)
                    setError(
                        "High-speed: " +
                            (ex.message ?: "falha ao abrir a câmera")
                    )
                    startCamera()
                }
            }, ContextCompat.getMainExecutor(this))
        } else {
            front4kDirectStreamer.stop()
            highSpeedH264Streamer.stop()
            rtspServer.start()
            statusText.text = "TRANSMITINDO PARA O GOAT PRO STUDIO"
''',
    "highspeed start branch",
)

# Stop/destroy/active state include high-speed route.
main = once(
    main,
    '''        h264Encoder.stop()
        backgroundH264Streamer.stop()
''',
    '''        h264Encoder.stop()
        highSpeedH264Streamer.stop()
        backgroundH264Streamer.stop()
''',
    "stop highspeed",
)
# onDestroy has the same pair later; replace the next occurrence.
main = once(
    main,
    '''        h264Encoder.stop()
        backgroundH264Streamer.stop()
''',
    '''        h264Encoder.stop()
        highSpeedH264Streamer.stop()
        backgroundH264Streamer.stop()
''',
    "destroy highspeed",
)
main = once(
    main,
    '''            backgroundH264Streamer.isRunning() ||
            backgroundH264Streamer.isStarting()
''',
    '''            backgroundH264Streamer.isRunning() ||
            backgroundH264Streamer.isStarting() ||
            highSpeedH264Streamer.isRunning() ||
            highSpeedH264Streamer.isStarting()
''',
    "active highspeed",
)

# Front 4K defaults to 60 FPS when its route advertises it.
main = main.replace(
    "fps = selectedResolution.directFps ?: 30,",
    "fps = selectedResolution.directFps ?: 60,"
)

MAIN.write_text(main, encoding="utf-8")

# Web/Studio panel: max 120, but dynamically clamp to camera/resolution capability.
mjpeg = MJPEG.read_text(encoding="utf-8")
mjpeg = once(
    mjpeg,
    'id="fpsLimit" type="range" min="0" max="30" step="1" value="20"',
    'id="fpsLimit" type="range" min="0" max="120" step="1" value="20"',
    "fps slider max",
)
mjpeg = once(
    mjpeg,
    '<div class="rangeLimits">0 = sem limite · útil para medir o máximo real do aparelho</div>',
    '<div class="rangeLimits" id="fpsLimits">0 = sem limite · máximo lido da câmera</div>',
    "fps limits id",
)
mjpeg = once(
    mjpeg,
    '''                    const fl=document.getElementById('fpsLimit');
                    fl.value=Number(s.targetFps||0);
''',
    '''                    const fl=document.getElementById('fpsLimit');
                    const maxFps=Math.max(30,Math.min(120,Number(s.maxFps||30)));
                    fl.max=maxFps;
                    fl.value=Math.min(maxFps,Number(s.targetFps||0));
                    document.getElementById('fpsLimits').textContent=
                      '0 = sem limite · máximo disponível nesta câmera/resolução: '+maxFps+' FPS';
''',
    "dynamic fps max",
)
MJPEG.write_text(mjpeg, encoding="utf-8")

# H264 normal encoder can be fed up to 120 FPS when the camera route can deliver it.
h264 = H264.read_text(encoding="utf-8")
h264 = once(h264, "val safeFps = fps.coerceIn(5, 60)", "val safeFps = fps.coerceIn(5, 120)", "h264 fps clamp")
h264 = once(
    h264,
    '''                pixels >= 2560L * 1440L -> 14_000_000L
                else -> 12_000_000L
''',
    '''                pixels >= 2560L * 1440L -> 20_000_000L
                fps >= 100 -> 32_000_000L
                fps >= 60 -> 22_000_000L
                else -> 12_000_000L
''',
    "h264 bitrate ceiling",
)
H264.write_text(h264, encoding="utf-8")

# Low-overhead background route supports regular 60 FPS, not constrained 120 FPS.
bg = BG.read_text(encoding="utf-8")
bg = once(bg, "val safeFps = targetFps.coerceIn(10, 30)", "val safeFps = targetFps.coerceIn(10, 60)", "background 60 fps")
BG.write_text(bg, encoding="utf-8")

# Front 4K: re-expose on S21, inspect legacy video sizes, try 60 then 30.
front = FRONT.read_text(encoding="utf-8")
front = front.replace("import android.os.ParcelFileDescriptor", "import android.os.Build\nimport android.os.ParcelFileDescriptor")
front = front.replace("val fps: Int = 30,", "val fps: Int = 60,")
front = front.replace("val bitrate: Int = 32_000_000,", "val bitrate: Int = 48_000_000,")
front = front.replace("private var configuredFps = 30", "private var configuredFps = 60")
front = front.replace("private var configuredBitrate = 32_000_000", "private var configuredBitrate = 48_000_000")
front = once(
    front,
    '''            val effective = profileFor(context, profile.cameraId)
            if (effective == null || effective.legacyCameraId < 0) {
                listener.onError(
                    "Camera1 frontal não publicou perfil oficial 3840x2160."
                )
                return false
            }

            val safeFps = effective.fps.coerceIn(5, 30)
''',
    '''            val detected = profileFor(context, profile.cameraId)
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
''',
    "front effective profile",
)
front = once(front, "val opened = Camera.open(effective.legacyCameraId)", "val opened = Camera.open(legacyId)", "front legacy id")
front = once(
    front,
    '''                    params.setRecordingHint(true)
                    if (
''',
    '''                    params.setRecordingHint(true)
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
''',
    "front fps params",
)
front = once(
    front,
    '''            } catch (ex: Exception) {
                stopLocked()
                listener.onError(
                    "Falha Camera1/MediaRecorder 4K frontal: " +
                        (ex.message ?: ex.javaClass.simpleName)
                )
                false
            }
''',
    '''            } catch (ex: Exception) {
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
''',
    "front 60 fallback",
)

# Replace companion object with richer legacy detection + S21 fallback.
companion_start = front.index("    companion object {")
if companion_start < 0:
    raise RuntimeError("companion Front4k não encontrado")
front_prefix = front[:companion_start]
front_companion = '''    companion object {
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
'''
front = front_prefix + front_companion
FRONT.write_text(front, encoding="utf-8")

STATUS.write_text(
'''# GOAT Cam Build 27\n\nStatus: código integrado; APK ainda não gerado.\n\n## 4K frontal\n- 4K volta a aparecer no Galaxy S21 mesmo quando o CamcorderProfile legado não publica 2160p.\n- primeiro usa perfil oficial 2160p quando existir.\n- depois verifica `Camera.Parameters.supportedVideoSizes` da Camera1.\n- no Galaxy S21 há fallback experimental 3840x2160.\n- tenta 60 FPS; se a preparação do MediaRecorder recusar, tenta 30 FPS antes de desistir.\n\n## FPS\n- painel aceita até 120 FPS, mas o máximo é atualizado por câmera/resolução.\n- fluxo normal aplica `CONTROL_AE_TARGET_FPS_RANGE` até 60 FPS quando a câmera anuncia.\n- acima de 60 FPS entra a rota `CameraConstrainedHighSpeedCaptureSession` somente quando a combinação é oficialmente anunciada pelo Camera2.\n- H.264 foi liberado para até 120 FPS.\n- segundo plano leve foi liberado para até 60 FPS.\n\n## Segurança\n- Build 26 preservada.\n- checkpoint: `checkpoint-build26-before-fps4k-2026-10-02`.\n- nenhuma alteração na release.\n''',
encoding="utf-8"
)

# Static sanity.
for path in (MAIN, MJPEG, FRONT, H264, BG, ROOT / "app/src/main/java/com/goatpro/ip/HighSpeedH264Streamer.kt"):
    txt = path.read_text(encoding="utf-8")
    if txt.count("{") != txt.count("}"):
        raise RuntimeError(f"Chaves desbalanceadas: {path}")

print("Build 27 integrada e validada estaticamente")
