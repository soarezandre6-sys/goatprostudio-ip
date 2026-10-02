from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/goatpro/ip/MainActivity.kt"
STATUS = ROOT / "BUILD_26_STATUS.md"


def rep(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: esperado 1, encontrado {count}")
    return text.replace(old, new, 1)

main = MAIN.read_text(encoding="utf-8")

main = rep(
    main,
    "    private var backgroundHeadless = false\n",
    "    private var backgroundHeadless = false\n    private var backgroundDirectActive = false\n",
    "background direct flag",
)

# RTSP keyframe routing: background direct -> front 4K -> normal NV21 encoder.
old_rtsp = '''                if (count > 0) {
                    if (
                        front4kDirectStreamer.isRunning() ||
                        front4kDirectStreamer.isStarting()
                    ) {
                        front4kDirectStreamer.requestKeyFrame()
                    } else {
                        h264Encoder.requestKeyFrame()
                    }
                } else if (!front4kDirectStreamer.isRunning()) {
                    h264Encoder.stop()
                }
'''
new_rtsp = '''                if (count > 0) {
                    when {
                        backgroundH264Streamer.isRunning() ||
                            backgroundH264Streamer.isStarting() ->
                            backgroundH264Streamer.requestKeyFrame()
                        front4kDirectStreamer.isRunning() ||
                            front4kDirectStreamer.isStarting() ->
                            front4kDirectStreamer.requestKeyFrame()
                        else -> h264Encoder.requestKeyFrame()
                    }
                } else if (
                    !front4kDirectStreamer.isRunning() &&
                    !backgroundH264Streamer.isRunning()
                ) {
                    h264Encoder.stop()
                }
'''
main = rep(main, old_rtsp, new_rtsp, "rtsp keyframe routing")

# Add background direct streamer before front 4K streamer.
anchor = '''    private val front4kDirectStreamer:
        Front4kDirectStreamer by lazy {
'''
background_block = '''    private val backgroundH264Streamer:
        BackgroundH264Streamer by lazy {
        BackgroundH264Streamer(
            this,
            object : BackgroundH264Streamer.Listener {
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
                    backgroundDirectActive = true
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        backgroundDirectActive = false
                        if (
                            backgroundHeadless &&
                            isStreamingActive() &&
                            !selectedResolution.directFront4k
                        ) {
                            // Safe fallback: keep streaming with ImageAnalysis only.
                            startCamera()
                        }
                    }
                }

                override fun onStopped() {
                    backgroundDirectActive = false
                }
            }
        )
    }

'''
main = rep(main, anchor, background_block + anchor, "background streamer block")

# Make front 4K status identify the new legacy route.
main = main.replace(
    '"4K frontal direto • H.264 hardware • " +',
    '"4K frontal Camera1/MediaRecorder • H.264 • " +',
)

# Include background direct route in active state.
old_active = '''    private fun isStreamingActive(): Boolean =
        server.isRunning() ||
            rtspServer.isRunning() ||
            front4kDirectStreamer.isRunning() ||
            front4kDirectStreamer.isStarting()
'''
new_active = '''    private fun isStreamingActive(): Boolean =
        server.isRunning() ||
            rtspServer.isRunning() ||
            front4kDirectStreamer.isRunning() ||
            front4kDirectStreamer.isStarting() ||
            backgroundH264Streamer.isRunning() ||
            backgroundH264Streamer.isStarting()
'''
main = rep(main, old_active, new_active, "isStreamingActive")

# Replace Build25 headless-only logic with low-overhead direct RTSP when possible.
old_enter = '''    private fun enterBackgroundCaptureMode() {
        if (!backgroundStreamingEnabled || !isStreamingActive()) return

        StreamingCameraLifecycle.setActive(true)
        startStreamingForegroundService()

        // The dedicated 4K front recorder owns its Camera2/MediaRecorder surface
        // and does not depend on PreviewView, so do not disturb that session.
        if (selectedResolution.directFront4k) return

        // Normal CameraX streaming used to keep PreviewView bound. When Android
        // destroys the visible Surface after Home/screen-off, Samsung can stall
        // the whole camera session. Rebind only ImageAnalysis while hidden.
        if (!backgroundHeadless) {
            backgroundHeadless = true
            startCamera()
        }
    }

    private fun exitBackgroundCaptureMode() {
        if (!backgroundHeadless) return
        backgroundHeadless = false

        // Restore Preview + ImageAnalysis without touching MJPEG/RTSP servers.
        if (isStreamingActive() && !selectedResolution.directFront4k) {
            startCamera()
        }
    }
'''
new_enter = '''    private fun enterBackgroundCaptureMode() {
        if (!backgroundStreamingEnabled || !isStreamingActive()) return

        StreamingCameraLifecycle.setActive(true)
        startStreamingForegroundService()

        // The dedicated front 4K recorder is already UI-independent.
        if (selectedResolution.directFront4k) return
        if (backgroundHeadless) return

        backgroundHeadless = true

        val option = selectedCameraOption
        val useDirectRtsp =
            option != null &&
            rtspServer.activeClientCount() > 0 &&
            server.videoClientCount() == 0 &&
            selectedResolution.size.width <= 1920 &&
            selectedResolution.size.height <= 1080

        if (!useDirectRtsp) {
            // MJPEG or higher-resolution fallback still needs ImageAnalysis.
            startCamera()
            return
        }

        val zoom = currentCamera?.cameraInfo?.zoomState?.value?.zoomRatio
            ?: option.zoomRatio
            ?: 1f
        val targetFps =
            (if (streamTargetFps > 0) streamTargetFps else 20).coerceIn(10, 30)
        val targetBitrate = if (streamBitrateBps > 0) {
            streamBitrateBps
        } else {
            H264Encoder.recommendedBitrate(
                selectedResolution.size.width,
                selectedResolution.size.height,
                targetFps,
                streamJpegQuality
            )
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (!backgroundHeadless || !isStreamingActive()) return@addListener
            try {
                val provider = providerFuture.get()
                provider.unbindAll()
                previewUseCase = null
                analysisUseCase = null
                currentCamera = null
                h264Encoder.stop()

                val started = backgroundH264Streamer.start(
                    logicalCameraId = option.logicalCameraId,
                    physicalCameraId = option.physicalCameraId,
                    targetWidth = selectedResolution.size.width,
                    targetHeight = selectedResolution.size.height,
                    targetFps = targetFps,
                    targetBitrate = targetBitrate,
                    targetZoomRatio = zoom
                )
                backgroundDirectActive = started
                if (!started) {
                    startCamera()
                }
            } catch (_: Exception) {
                backgroundDirectActive = false
                startCamera()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun exitBackgroundCaptureMode() {
        val wasHeadless = backgroundHeadless
        backgroundHeadless = false

        if (
            backgroundDirectActive ||
            backgroundH264Streamer.isRunning() ||
            backgroundH264Streamer.isStarting()
        ) {
            backgroundH264Streamer.stop()
            backgroundDirectActive = false
        }

        if (
            wasHeadless &&
            isStreamingActive() &&
            !selectedResolution.directFront4k
        ) {
            startCamera()
        }
    }
'''
main = rep(main, old_enter, new_enter, "background mode")

# startCamera must not take camera ownership while direct background session owns it.
start_anchor = '''    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
'''
start_new = '''    private fun startCamera() {
        if (
            backgroundH264Streamer.isRunning() ||
            backgroundH264Streamer.isStarting()
        ) return

        val providerFuture = ProcessCameraProvider.getInstance(this)
'''
main = rep(main, start_anchor, start_new, "startCamera direct guard")

# stopStreaming also stops low-overhead background path.
main = rep(
    main,
    '''        h264Encoder.stop()
        front4kDirectStreamer.stop()
        rtspServer.stop()
''',
    '''        h264Encoder.stop()
        backgroundH264Streamer.stop()
        backgroundDirectActive = false
        front4kDirectStreamer.stop()
        rtspServer.stop()
''',
    "stop streaming direct background",
)

# onDestroy also releases it (different anchor includes same sequence again after prior replace,
# so add explicit line near audio cleanup if still absent there).
destroy_anchor = '''        server.setAudioEnabled(false)
        h264Encoder.stop()
        front4kDirectStreamer.stop()
'''
if destroy_anchor in main:
    main = rep(
        main,
        destroy_anchor,
        '''        server.setAudioEnabled(false)
        h264Encoder.stop()
        backgroundH264Streamer.stop()
        backgroundDirectActive = false
        front4kDirectStreamer.stop()
''',
        "destroy background streamer",
    )

# Native stream button recognizes background direct state as active.
main = main.replace(
    '''                front4kDirectStreamer.isRunning() ||
                front4kDirectStreamer.isStarting()
''',
    '''                front4kDirectStreamer.isRunning() ||
                front4kDirectStreamer.isStarting() ||
                backgroundH264Streamer.isRunning() ||
                backgroundH264Streamer.isStarting()
''',
    1,
)

MAIN.write_text(main, encoding="utf-8")

status = '''# GOAT Cam Build 26\n\nStatus: código preparado; APK ainda não gerado.\n\n## 4K frontal\n- Build 24 Camera2 + MediaCodec: recusado no aparelho.\n- Build 25 Camera2 + MediaRecorder: recusado/travou no aparelho.\n- Build 26: Camera1 legado + MediaRecorder H.264.\n- 4K só é oferecido se a câmera frontal Camera1 publicar `CamcorderProfile.QUALITY_2160P` exato 3840x2160.\n- removido fallback que inferia 4K apenas pelo tamanho do sensor.\n\n## Segundo plano / tela apagada\n- Home e bloqueio continuam protegidos por Foreground Service + WakeLock + Wi-Fi Lock.\n- para RTSP em HD/FHD sem cliente MJPEG, CameraX/ImageAnalysis é desligado ao sair da tela.\n- Camera2 envia direto para uma Surface do encoder H.264 de hardware.\n- objetivo: reduzir cópia NV21, CPU, aquecimento e travadas.\n- ao voltar ao app, o encoder direto é encerrado e Preview + ImageAnalysis são restaurados.\n- se o caminho direto não abrir, há fallback automático para o modo headless da Build 25.\n\n## Observação\nA Build 26 ainda precisa de compilação e teste no aparelho.\n'''
STATUS.write_text(status, encoding="utf-8")

# Static sanity checks only; this does not compile Android.
for p in [MAIN, ROOT / "app/src/main/java/com/goatpro/ip/BackgroundH264Streamer.kt", ROOT / "app/src/main/java/com/goatpro/ip/Front4kDirectStreamer.kt"]:
    txt = p.read_text(encoding="utf-8")
    if txt.count("{") != txt.count("}"):
        raise RuntimeError(f"Chaves desbalanceadas em {p}")

required = [
    "BackgroundH264Streamer by lazy",
    "backgroundDirectActive",
    "backgroundH264Streamer.start(",
    "backgroundH264Streamer.stop()",
    "Camera1/MediaRecorder",
]
for marker in required:
    if marker not in main:
        raise RuntimeError(f"MainActivity sem marcador: {marker}")

print("Build 26 integration patched and statically validated")
