from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/goatpro/ip/MainActivity.kt"
LAYOUT = ROOT / "app/src/main/res/layout/activity_main.xml"
GRADLE = ROOT / "app/build.gradle.kts"
STATUS = ROOT / "BUILD_STATUS.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: esperado 1 marcador, encontrado {count}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    out, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"{label}: esperado 1 bloco, encontrado {count}")
    return out


main = MAIN.read_text(encoding="utf-8")

# Imports left by the abandoned 8K experiments.
for line in (
    "import android.hardware.camera2.params.RecommendedStreamConfigurationMap\n",
    "import android.media.CamcorderProfile\n",
    "import android.media.MediaRecorder\n",
):
    main = main.replace(line, "")

# Remove temporary 8K UI fields.
for line in (
    "    private lateinit var vendorDiagnosticButton: Button\n",
    "    private lateinit var vendorDiagnosticText: TextView\n",
    "    private lateinit var local8kTestButton: Button\n",
    "    private lateinit var local8kTestText: TextView\n",
):
    main = main.replace(line, "")

main = replace_once(
    main,
    "        highResolution = false,\n        directHevc = false\n",
    "        highResolution = false,\n        directFront4k = false\n",
    "resolução inicial",
)

main = main.replace(
    "    private var fallbackResolutionAfterHevcFailure: ResolutionOption? = null\n",
    "    private var fallbackResolutionAfterFront4kFailure: ResolutionOption? = null\n",
)
main = main.replace("    private var pendingLocal8kProfileTest = false\n", "")

# RTSP keyframe control must work with either the normal NV21 encoder or the
# dedicated surface encoder used by front 4K.
main = replace_once(
    main,
    """            override fun onActiveClientCountChanged(count: Int) {
                if (count > 0) {
                    h264Encoder.requestKeyFrame()
                } else {
                    h264Encoder.stop()
                }
                runOnUiThread { updateConnectionStatus(server.videoClientCount()) }
            }
""",
    """            override fun onActiveClientCountChanged(count: Int) {
                if (count > 0) {
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
                runOnUiThread { updateConnectionStatus(server.videoClientCount()) }
            }
""",
    "callback RTSP H264",
)

front_streamer = r'''    private val front4kDirectStreamer:
        Front4kDirectStreamer by lazy {
        Front4kDirectStreamer(
            this,
            object : Front4kDirectStreamer.Listener {
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
                        statusText.text =
                            "TRANSMITINDO 4K FRONTAL PARA O GOAT PRO STUDIO"
                        statusText.setTextColor(
                            ContextCompat.getColor(
                                this@MainActivity,
                                R.color.green
                            )
                        )
                        performanceText.text =
                            "4K frontal direto • H.264 hardware • " +
                                fps + " FPS • " +
                                String.format(
                                    java.util.Locale.US,
                                    "%.1f Mbps",
                                    bitrate / 1_000_000.0
                                )
                        updateStreamInfo()
                        refreshAddress()
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        front4kDirectStreamer.stop()
                        rtspServer.stop()
                        server.stop()
                        audioCapture.stop()
                        server.setAudioEnabled(false)
                        updateConnectionStatus(0)

                        val fallback =
                            fallbackResolutionAfterFront4kFailure
                                ?: availableResolutionOptions.firstOrNull {
                                    !it.directFront4k &&
                                        it.key == "1920x1080"
                                }
                                ?: availableResolutionOptions.firstOrNull {
                                    !it.directFront4k
                                }

                        if (fallback != null) {
                            fallbackResolutionAfterFront4kFailure = null
                            selectedResolution = fallback
                            applyHighResolutionDefaults(fallback)

                            if (::resolutionSpinner.isInitialized) {
                                val index =
                                    availableResolutionOptions.indexOfFirst {
                                        it.key == fallback.key
                                    }
                                if (index >= 0) {
                                    resolutionSpinner.setSelection(index)
                                }
                            }

                            actualStreamWidth = 0
                            actualStreamHeight = 0
                            nextEncodeDueNs = 0L
                            resetPerformanceStats()
                            updateStreamInfo()
                            refreshAddress()

                            setError(
                                "4K frontal recusado • " + message +
                                    " • voltando para " + fallback.label
                            )
                            Toast.makeText(
                                this@MainActivity,
                                "4K frontal recusado. Voltando para " +
                                    fallback.label + ".",
                                Toast.LENGTH_LONG
                            ).show()

                            autoRestartStreamAfterCameraBind = true
                            startCamera()
                            return@runOnUiThread
                        }

                        setError("4K frontal: " + message)
                        streamButton.text = "Iniciar transmissão"
                        streamButton.setBackgroundResource(
                            R.drawable.bg_button_primary
                        )
                        streamButton.backgroundTintList = null
                        streamButton.setTextColor(
                            ContextCompat.getColor(
                                this@MainActivity,
                                R.color.black
                            )
                        )
                        startCamera()
                    }
                }

                override fun onStopped() = Unit
            }
        )
    }

'''

main = regex_once(
    main,
    r"    private val hevcRtspServer: RtspH265Server by lazy \{.*?\n    private val audioCapture by lazy \{",
    front_streamer + "    private val audioCapture by lazy {",
    "substituir pilha 8K por 4K frontal",
)

main = regex_once(
    main,
    r'''            rtspCodec = \{\n                if \(selectedResolution\.directHevc\) "H265" else "H264"\n            \}''',
    '            rtspCodec = { "H264" }',
    "discovery H264",
)

# Restore normal microphone permission flow now that the local 8K recorder is gone.
audio_permission = r'''    private val audioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        audioEnabled = granted
        server.setAudioEnabled(granted)
        if (granted && server.isRunning()) {
            if (!audioCapture.start()) {
                audioEnabled = false
                server.setAudioEnabled(false)
                Toast.makeText(
                    this,
                    "Não foi possível iniciar o microfone.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        if (!granted) {
            Toast.makeText(
                this,
                "Permissão de microfone não concedida.",
                Toast.LENGTH_SHORT
            ).show()
        }
        updateAudioButton()
    }

'''
main = regex_once(
    main,
    r"    private val audioPermission = registerForActivityResult\(.*?\n    override fun onCreate",
    audio_permission + "    override fun onCreate",
    "callback permissão áudio",
)

for block in (
    """        vendorDiagnosticButton =
            findViewById(R.id.vendorDiagnosticButton)
        vendorDiagnosticText =
            findViewById(R.id.vendorDiagnosticText)
""",
    """        local8kTestButton =
            findViewById(R.id.local8kTestButton)
        local8kTestText =
            findViewById(R.id.local8kTestText)
""",
    """        vendorDiagnosticButton.setOnClickListener {
            runVendor8kDiagnostic()
        }
""",
    """        local8kTestButton.setOnClickListener {
            requestLocal8kProfileTest()
        }
""",
):
    main = main.replace(block, "")

main = regex_once(
    main,
    r'''        streamButton\.setOnClickListener \{\n            if \(\n                server\.isRunning\(\) \|\|\n                cameraX8kRecorderProbe\.isRunning\(\) \|\|\n                cameraX8kRecorderProbe\.isStarting\(\) \|\|\n                local8kProfileRecorder\.isRunning\(\) \|\|\n                local8kProfileRecorder\.isStarting\(\)\n            \) \{''',
    '''        streamButton.setOnClickListener {
            if (
                server.isRunning() ||
                front4kDirectStreamer.isRunning() ||
                front4kDirectStreamer.isStarting()
            ) {''',
    "botão transmissão",
)

# Remove diagnostic/local test methods in one contiguous block.
main = regex_once(
    main,
    r"    private fun requestLocal8kProfileTest\(\) \{.*?\n    private fun openGoatProStudioWebsite\(\) \{",
    "    private fun openGoatProStudioWebsite() {",
    "métodos 8K temporários",
)

# Resolution model: keep only normal CameraX/YUV and a dedicated front-4K surface route.
main = regex_once(
    main,
    r"    private data class ResolutionOption\(.*?\n    private fun resolutionKey\(size: Size\): String =",
    r'''    private data class ResolutionOption(
        val key: String,
        val label: String,
        val size: Size,
        val highResolution: Boolean,
        val directFront4k: Boolean,
        val directCameraId: String? = null,
        val directFps: Int? = null,
        val directBitrate: Int? = null
    )

    private fun resolutionKey(size: Size): String =''',
    "modelo ResolutionOption",
)

main = main.replace(
    '            size.width == 7680 && size.height == 4320 -> "8K UHD · HEVC experimental · "\n',
    '',
)
main = main.replace(
    "        if (landscape.width > 7680 || landscape.height > 4320) return false",
    "        if (landscape.width > 3840 || landscape.height > 2160) return false",
)

main = regex_once(
    main,
    r"    private fun find8kCameraSource\(.*?\n    private fun supportedResolutionOptions\(\): List<ResolutionOption> \{",
    "    private fun supportedResolutionOptions(): List<ResolutionOption> {",
    "remover find8kCameraSource",
)

# Normal resolution constructors keep the same fifth positional meaning, now directFront4k.
main = main.replace("directHevc = false", "directFront4k = false")

front_option = r'''            // Samsung may expose front UHD only through its recording
            // profile/private encoder path, not through CameraX ImageAnalysis YUV.
            // Add one exact 3840x2160 option only for the selected front camera.
            if (
                option?.facing == CameraSelector.LENS_FACING_FRONT &&
                !combined.containsKey("3840x2160")
            ) {
                val profile = Front4kDirectStreamer.profileFor(
                    this,
                    option.logicalCameraId
                )
                if (profile != null) {
                    combined["3840x2160"] = ResolutionOption(
                        key = "3840x2160",
                        label = "4K UHD · 3840×2160 · frontal",
                        size = Size(3840, 2160),
                        highResolution = true,
                        directFront4k = true,
                        directCameraId = profile.cameraId,
                        directFps = profile.fps,
                        directBitrate = profile.bitrate
                    )
                }
            }

'''
main = regex_once(
    main,
    r"            // 8K is intentionally separate.*?\n            val options = combined\.values\.sortedWith\(",
    front_option + "            val options = combined.values.sortedWith(",
    "opção 4K frontal",
)

# High-resolution defaults and resolution switching.
main = main.replace("option.directHevc", "option.directFront4k")
main = main.replace("selectedResolution.directHevc", "selectedResolution.directFront4k")
main = main.replace(
    "fallbackResolutionAfterHevcFailure",
    "fallbackResolutionAfterFront4kFailure",
)
main = main.replace(
    "                cameraX8kRecorderProbe.isRunning() ||\n                cameraX8kRecorderProbe.isStarting()",
    "                front4kDirectStreamer.isRunning() ||\n                front4kDirectStreamer.isStarting()",
)
main = main.replace('            "8K", "4320P" -> "7680x4320"\n', '')

# Replace the preview-only branch inside startCamera. It exists so CameraX does
# not try to bind a 4K ImageAnalysis use case that Samsung does not advertise.
start_camera_start = main.index("    private fun startCamera() {")
start_camera_end = main.index("    private fun resetPerformanceStats()", start_camera_start)
start_camera = main[start_camera_start:start_camera_end]
start_camera = regex_once(
    start_camera,
    r"            if \(selectedResolution\.directFront4k\) \{.*?\n            val analysisFallbackRule =",
    r'''            if (selectedResolution.directFront4k) {
                if (
                    front4kDirectStreamer.isRunning() ||
                    front4kDirectStreamer.isStarting()
                ) {
                    return@addListener
                }

                val previewResolutionSelector =
                    ResolutionSelector.Builder()
                        .setAllowedResolutionMode(
                            ResolutionSelector
                                .PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION
                        )
                        .setAspectRatioStrategy(
                            AspectRatioStrategy
                                .RATIO_16_9_FALLBACK_AUTO_STRATEGY
                        )
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy
                                    .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(previewResolutionSelector)
                    .setTargetRotation(targetRotation)
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                try {
                    provider.unbindAll()
                    analysisUseCase = null
                    previewUseCase = preview
                    currentCamera =
                        provider.bindToLifecycle(this, selector, preview)
                    torchEnabled = false
                    updateTorchButton()
                    if (!server.isRunning()) setReadyState()

                    if (autoRestartStreamAfterCameraBind) {
                        autoRestartStreamAfterCameraBind = false
                        startStreaming()
                    }
                } catch (_: Exception) {
                    previewUseCase = null
                    analysisUseCase = null
                    currentCamera = null
                    updateTorchButton()
                    setError("ERRO AO ABRIR PREVIEW 4K FRONTAL")
                }
                return@addListener
            }

            val analysisFallbackRule =''',
    "preview 4K frontal",
)
main = main[:start_camera_start] + start_camera + main[start_camera_end:]

main = main.replace(
    '\",\\\"h264Running\\\":${h264Encoder.isRunning()}\" +',
    '\",\\\"h264Running\\\":${h264Encoder.isRunning() || front4kDirectStreamer.isRunning()}\" +',
)

# Remove the abandoned CameraX 8K handling immediately before startStreaming.
main = regex_once(
    main,
    r"    private fun handleCameraX8kFailure\(.*?\n    private fun startStreaming\(\) \{",
    "    private fun startStreaming() {",
    "handlers CameraX 8K",
)

start_streaming = r'''    private fun startStreaming() {
        val ip = NetworkUtils.localIpv4()
        if (ip == null) {
            setError("CONECTE O CELULAR A UMA REDE WI-FI")
            Toast.makeText(
                this,
                "Nenhum endereço IPv4 local encontrado.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        front4kDirectStreamer.stop()
        refreshAddress()
        nextEncodeDueNs = 0L
        resetPerformanceStats()
        server.start()
        server.setAudioEnabled(audioEnabled)

        if (audioEnabled && !audioCapture.start()) {
            audioEnabled = false
            server.setAudioEnabled(false)
            updateAudioButton()
            Toast.makeText(
                this,
                "Vídeo iniciado sem áudio.",
                Toast.LENGTH_SHORT
            ).show()
        }

        if (selectedResolution.directFront4k) {
            h264Encoder.stop()
            rtspServer.start()
            statusText.text = "INICIANDO 4K FRONTAL H.264…"
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
                    currentCamera = null
                    updateTorchButton()

                    val option = selectedCameraOption
                        ?: throw IllegalStateException(
                            "Câmera frontal selecionada indisponível."
                        )
                    val cameraId =
                        selectedResolution.directCameraId
                            ?: option.logicalCameraId
                    val profile = Front4kDirectStreamer.Profile(
                        cameraId = cameraId,
                        width = selectedResolution.size.width,
                        height = selectedResolution.size.height,
                        fps = selectedResolution.directFps ?: 30,
                        bitrate =
                            selectedResolution.directBitrate
                                ?: 32_000_000,
                        fromOfficialProfile =
                            Front4kDirectStreamer.profileFor(
                                this,
                                cameraId
                            )?.fromOfficialProfile == true
                    )

                    fallbackResolutionAfterFront4kFailure =
                        availableResolutionOptions.firstOrNull {
                            !it.directFront4k &&
                                it.key == "1920x1080"
                        }
                            ?: availableResolutionOptions.firstOrNull {
                                !it.directFront4k
                            }

                    val started = front4kDirectStreamer.start(profile)
                    if (!started) {
                        rtspServer.stop()
                        server.stop()
                    }
                } catch (ex: Exception) {
                    rtspServer.stop()
                    server.stop()
                    setError(
                        "4K frontal: " +
                            (ex.message ?: "falha ao abrir a câmera")
                    )
                    startCamera()
                }
            }, ContextCompat.getMainExecutor(this))
        } else {
            front4kDirectStreamer.stop()
            rtspServer.start()
            statusText.text = "TRANSMITINDO PARA O GOAT PRO STUDIO"
            statusText.setTextColor(
                ContextCompat.getColor(this, R.color.green)
            )
        }

        updateConnectionStatus(0)
        streamButton.text = "Parar transmissão"
        streamButton.setBackgroundResource(R.drawable.bg_button_danger)
        streamButton.backgroundTintList = null
        streamButton.setTextColor(
            ContextCompat.getColor(this, android.R.color.white)
        )
    }

'''
main = regex_once(
    main,
    r"    private fun startStreaming\(\) \{.*?\n    private fun stopStreaming\(\) \{",
    start_streaming + "    private fun stopStreaming() {",
    "startStreaming 4K frontal",
)

stop_streaming = r'''    private fun stopStreaming() {
        audioCapture.stop()
        server.setAudioEnabled(false)
        h264Encoder.stop()
        front4kDirectStreamer.stop()
        rtspServer.stop()
        server.stop()
        nextEncodeDueNs = 0L
        resetPerformanceStats()
        actualStreamWidth = 0
        actualStreamHeight = 0
        setReadyState()
        updateConnectionStatus(0)
        streamButton.text = "Iniciar transmissão"
        streamButton.setBackgroundResource(R.drawable.bg_button_primary)
        streamButton.backgroundTintList = null
        streamButton.setTextColor(
            ContextCompat.getColor(this, R.color.black)
        )

        if (
            selectedResolution.directFront4k &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }
    }

'''
main = regex_once(
    main,
    r"    private fun stopStreaming\(\) \{.*?\n    private fun updateConnectionStatus\(count: Int\) \{",
    stop_streaming + "    private fun updateConnectionStatus(count: Int) {",
    "stopStreaming 4K frontal",
)

main = regex_once(
    main,
    r'''        val rtspCount =\n            if \(selectedResolution\.directFront4k\) \{.*?\n            \}''',
    '        val rtspCount = rtspServer.activeClientCount()',
    "contagem RTSP",
)

refresh_address = r'''    private fun refreshAddress() {
        val ip = NetworkUtils.localIpv4()
        addressText.text = if (ip != null) {
            if (selectedResolution.directFront4k) {
                "4K frontal H.264 RTSP: rtsp://" +
                    ip + ":8554/h264"
            } else {
                "MJPEG: http://" + ip +
                    ":8080/video\nH.264 RTSP: rtsp://" +
                    ip + ":8554/h264"
            }
        } else {
            "Sem endereço Wi-Fi disponível"
        }
    }

'''
main = regex_once(
    main,
    r"    private fun refreshAddress\(\) \{.*?\n    private fun updateStreamInfo\(\) \{",
    refresh_address + "    private fun updateStreamInfo() {",
    "refreshAddress",
)

main = replace_once(
    main,
    '''        if (selectedResolution.directFront4k) {
            streamInfoText.text =
                dimensions + " • " + fpsLabel +
                    " • HEVC/H.265 hardware • 8K experimental"
            return
        }
''',
    '''        if (selectedResolution.directFront4k) {
            streamInfoText.text =
                dimensions + " • " + fpsLabel +
                    " • H.264 hardware • 4K frontal"
            return
        }
''',
    "streamInfo frontal",
)

copy_address = r'''    private fun copyAddressToClipboard() {
        val ip = NetworkUtils.localIpv4()
        if (ip == null) {
            Toast.makeText(
                this,
                "Conecte o celular ao Wi-Fi primeiro.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val urls =
            if (selectedResolution.directFront4k) {
                "4K frontal H.264 RTSP: rtsp://" +
                    ip + ":8554/h264"
            } else {
                "MJPEG: http://" + ip +
                    ":8080/video\nH.264 RTSP: rtsp://" +
                    ip + ":8554/h264"
            }

        val clipboard =
            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("GOAT Cam", urls)
        )
        Toast.makeText(
            this,
            if (selectedResolution.directFront4k) {
                "Endereço 4K frontal H.264 copiado"
            } else {
                "Endereços MJPEG e H.264 copiados"
            },
            Toast.LENGTH_SHORT
        ).show()
    }

'''
main = regex_once(
    main,
    r"    private fun copyAddressToClipboard\(\) \{.*?\n    override fun onResume\(\) \{",
    copy_address + "    override fun onResume() {",
    "copyAddress",
)

# Cleanup lifecycle references.
for old in (
    "        local8kProfileRecorder.stop()\n",
    "        cameraX8kRecorderProbe.stop()\n",
    "        mediaRecorderHevcStreamer.stop()\n",
    "        hevcDirectStreamer.stop()\n",
    "        hevcRtspServer.stop()\n",
):
    main = main.replace(old, "")
main = replace_once(
    main,
    "        h264Encoder.stop()\n        rtspServer.stop()\n",
    "        h264Encoder.stop()\n        front4kDirectStreamer.stop()\n        rtspServer.stop()\n",
    "onDestroy front4k",
)

# No abandoned 8K symbols are allowed to remain in active source.
for forbidden in (
    "directHevc",
    "CameraX8kRecorderProbe",
    "Local8kProfileRecorder",
    "SamsungVendorDiagnostics",
    "HevcDirectStreamer",
    "MediaRecorderHevcStreamer",
    "RtspH265Server",
    "find8kCameraSource",
    "7680",
    "4320",
    "8K",
):
    if forbidden in main:
        raise RuntimeError(f"MainActivity ainda contém marcador removido: {forbidden}")

if "Front4kDirectStreamer" not in main:
    raise RuntimeError("Front4kDirectStreamer não foi integrado")
if main.count("{") != main.count("}"):
    raise RuntimeError("MainActivity com chaves desbalanceadas")

MAIN.write_text(main, encoding="utf-8")

# Remove the two temporary test controls from the camera card.
layout = LAYOUT.read_text(encoding="utf-8")
for view_id in (
    "vendorDiagnosticButton",
    "vendorDiagnosticText",
    "local8kTestButton",
    "local8kTestText",
):
    pattern = rf'''\n\s*<(?:Button|TextView)\n\s*android:id="@\+id/{view_id}".*?/\>'''
    layout, count = re.subn(pattern, "", layout, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"layout: não removeu {view_id}")

if "8K" in layout or "vendorDiagnostic" in layout or "local8k" in layout:
    raise RuntimeError("layout ainda contém controles 8K")
LAYOUT.write_text(layout, encoding="utf-8")

# CameraX VideoCapture was added only for the discarded 8K experiment.
gradle = GRADLE.read_text(encoding="utf-8")
gradle = gradle.replace(
    '    implementation("androidx.camera:camera-video:1.5.1")\n',
    '',
)
GRADLE.write_text(gradle, encoding="utf-8")

# Delete abandoned 8K implementation files. Historical branches keep them.
for relative in (
    "app/src/main/java/com/goatpro/ip/CameraX8kRecorderProbe.kt",
    "app/src/main/java/com/goatpro/ip/HevcDirectStreamer.kt",
    "app/src/main/java/com/goatpro/ip/Local8kProfileRecorder.kt",
    "app/src/main/java/com/goatpro/ip/MediaRecorderHevcStreamer.kt",
    "app/src/main/java/com/goatpro/ip/SamsungVendorDiagnostics.kt",
    "app/src/main/java/com/goatpro/ip/RtspH265Server.kt",
):
    path = ROOT / relative
    if path.exists():
        path.unlink()

status = STATUS.read_text(encoding="utf-8")
note = r'''

## Build 22 — remover 8K e recuperar 4K frontal
Branch: `build-22-goat-cam`

- 8K abandonado por enquanto após os testes públicos serem recusados no Galaxy S21;
- removidos da interface o diagnóstico Samsung/vendor e o teste local 8K;
- removida a opção 7680×4320 do seletor de resolução;
- removidos do fluxo ativo CameraX 8K, HEVC 8K, MediaRecorder 8K e RTSP H.265 experimental;
- removida a dependência `camera-video`, usada somente pelo teste CameraX 8K;
- 4K traseiro e tele permanecem no caminho existente;
- novo caminho exclusivo para 4K frontal: Camera2 `TEMPLATE_RECORD` -> Surface do MediaCodec AVC/H.264 -> RTSP H.264;
- quando o perfil público `QUALITY_2160P` frontal existe, FPS e bitrate são derivados dele;
- fallback específico para Galaxy S21 permite tentar 3840×2160 mesmo quando o Camera2 omite 4K da lista YUV;
- se a sessão frontal 4K for recusada pelo firmware, o app volta automaticamente para 1080p;
- nenhuma APK foi gerada nesta etapa.
'''
if "## Build 22 — remover 8K e recuperar 4K frontal" not in status:
    status += note
STATUS.write_text(status, encoding="utf-8")

print("Build 22 cleanup/integration applied successfully")
print("MainActivity chars:", len(main))
print("MainActivity braces:", main.count("{"), main.count("}"))
