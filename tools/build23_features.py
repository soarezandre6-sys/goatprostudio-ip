from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/goatpro/ip/MainActivity.kt"
SERVER = ROOT / "app/src/main/java/com/goatpro/ip/MjpegServer.kt"
STATUS = ROOT / "BUILD_STATUS.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: esperado 1 trecho, encontrado {count}")
    return text.replace(old, new, 1)


main = MAIN.read_text(encoding="utf-8")

main = replace_once(
    main,
    """    private var fallbackResolutionAfterFront4kFailure: ResolutionOption? = null
    private var autoRestartStreamAfterCameraBind = false

    private var metricsWindowStartedNs = 0L
""",
    """    private var fallbackResolutionAfterFront4kFailure: ResolutionOption? = null
    private var autoRestartStreamAfterCameraBind = false
    private var streamBitrateBps = 0
    private var watermarkEnabled = true
    private var preferredCameraKey: String? = null
    private var preferredResolutionKey: String? = null

    private var metricsWindowStartedNs = 0L
""",
    "state fields",
)

main = replace_once(
    main,
    """        cameraExecutor = Executors.newSingleThreadExecutor()
        setupOrientationTracking()

        applyPreviewAspectRatio()
""",
    """        cameraExecutor = Executors.newSingleThreadExecutor()
        setupOrientationTracking()
        restoreSmartLinkState()

        applyPreviewAspectRatio()
""",
    "restore smart link",
)

helpers = r'''    private fun smartLinkPrefs() =
        getSharedPreferences("goat_cam_smart_link", Context.MODE_PRIVATE)

    private fun proPresetPrefs() =
        getSharedPreferences("goat_cam_pro_presets", Context.MODE_PRIVATE)

    private fun restoreSmartLinkState() {
        val prefs = smartLinkPrefs()
        preferredCameraKey = prefs.getString("camera_key", null)
        preferredResolutionKey = prefs.getString("resolution_key", null)
        selectedQualityProfile = runCatching {
            QualityProfile.valueOf(
                prefs.getString(
                    "quality",
                    selectedQualityProfile.name
                ).orEmpty()
            )
        }.getOrDefault(QualityProfile.BALANCED)
        streamJpegQuality = prefs.getInt(
            "jpeg_quality",
            selectedQualityProfile.jpegQuality
        ).coerceIn(1, 100)
        streamTargetFps = prefs.getInt(
            "target_fps",
            selectedQualityProfile.targetFps
        ).let { if (it <= 0) 0 else it.coerceIn(5, 60) }
        streamBitrateBps = prefs.getInt("bitrate_bps", 0)
            .coerceIn(0, 60_000_000)
        watermarkEnabled = prefs.getBoolean("watermark_enabled", true)
        selectedRotationMode = runCatching {
            RotationMode.valueOf(
                prefs.getString(
                    "rotation",
                    selectedRotationMode.name
                ).orEmpty()
            )
        }.getOrDefault(RotationMode.AUTO)
    }

    private fun saveSmartLinkState() {
        smartLinkPrefs().edit()
            .putString(
                "camera_key",
                selectedCameraOption?.key ?: preferredCameraKey
            )
            .putString(
                "resolution_key",
                selectedResolution.key
            )
            .putString("quality", selectedQualityProfile.name)
            .putInt("jpeg_quality", streamJpegQuality)
            .putInt("target_fps", streamTargetFps)
            .putInt("bitrate_bps", streamBitrateBps)
            .putBoolean("watermark_enabled", watermarkEnabled)
            .putString("rotation", selectedRotationMode.name)
            .apply()
    }

    private fun saveProPreset(slot: Int) {
        val safeSlot = slot.coerceIn(1, 3)
        val prefix = "preset_${safeSlot}_"
        proPresetPrefs().edit()
            .putBoolean(prefix + "saved", true)
            .putString(prefix + "camera_key", selectedCameraOption?.key)
            .putString(prefix + "resolution_key", selectedResolution.key)
            .putString(prefix + "quality", selectedQualityProfile.name)
            .putInt(prefix + "jpeg_quality", streamJpegQuality)
            .putInt(prefix + "target_fps", streamTargetFps)
            .putInt(prefix + "bitrate_bps", streamBitrateBps)
            .putBoolean(prefix + "watermark_enabled", watermarkEnabled)
            .putString(prefix + "rotation", selectedRotationMode.name)
            .apply()
        Toast.makeText(
            this,
            "Preset Pro $safeSlot salvo.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun loadProPreset(slot: Int) {
        val safeSlot = slot.coerceIn(1, 3)
        val prefix = "preset_${safeSlot}_"
        val prefs = proPresetPrefs()
        if (!prefs.getBoolean(prefix + "saved", false)) {
            Toast.makeText(
                this,
                "Preset Pro $safeSlot ainda não foi salvo.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        selectedQualityProfile = runCatching {
            QualityProfile.valueOf(
                prefs.getString(
                    prefix + "quality",
                    QualityProfile.BALANCED.name
                ).orEmpty()
            )
        }.getOrDefault(QualityProfile.BALANCED)
        streamJpegQuality = prefs.getInt(
            prefix + "jpeg_quality",
            selectedQualityProfile.jpegQuality
        ).coerceIn(1, 100)
        streamTargetFps = prefs.getInt(
            prefix + "target_fps",
            selectedQualityProfile.targetFps
        ).let { if (it <= 0) 0 else it.coerceIn(5, 60) }
        streamBitrateBps = prefs.getInt(
            prefix + "bitrate_bps",
            0
        ).coerceIn(0, 60_000_000)
        watermarkEnabled = prefs.getBoolean(
            prefix + "watermark_enabled",
            true
        )
        selectedRotationMode = runCatching {
            RotationMode.valueOf(
                prefs.getString(
                    prefix + "rotation",
                    RotationMode.AUTO.name
                ).orEmpty()
            )
        }.getOrDefault(RotationMode.AUTO)
        preferredCameraKey = prefs.getString(prefix + "camera_key", null)
        preferredResolutionKey = prefs.getString(
            prefix + "resolution_key",
            null
        )

        preferredCameraKey?.let { key ->
            cameraLensOptions.firstOrNull { it.key == key }
                ?.takeIf { it.key != selectedCameraOption?.key }
                ?.let { selectCameraLens(it) }
        }

        previewView.postDelayed({
            refreshResolutionOptions()
            preferredResolutionKey?.let { key ->
                availableResolutionOptions.firstOrNull { it.key == key }
                    ?.takeIf { it.key != selectedResolution.key }
                    ?.let { selectResolutionOption(it) }
            }
            if (::qualitySpinner.isInitialized) {
                qualitySpinner.setSelection(
                    QualityProfile.entries.indexOf(selectedQualityProfile)
                )
            }
            if (::rotationSpinner.isInitialized) {
                rotationSpinner.setSelection(
                    RotationMode.entries.indexOf(selectedRotationMode)
                )
            }
            h264Encoder.stop()
            updateStreamInfo()
            saveSmartLinkState()
            Toast.makeText(
                this,
                "Preset Pro $safeSlot carregado.",
                Toast.LENGTH_SHORT
            ).show()
        }, 350L)
    }

'''

main = replace_once(
    main,
    "    private fun openGoatProStudioWebsite() {\n",
    helpers + "    private fun openGoatProStudioWebsite() {\n",
    "insert pro helpers",
)

main = replace_once(
    main,
    """        selectedCameraOption =
            cameraLensOptions.firstOrNull {
                it.facing == CameraSelector.LENS_FACING_BACK &&
                    it.label.contains("principal", ignoreCase = true)
            }
                ?: cameraLensOptions.firstOrNull {
                    it.facing == CameraSelector.LENS_FACING_BACK
                }
                ?: cameraLensOptions.first()
""",
    """        selectedCameraOption =
            preferredCameraKey?.let { key ->
                cameraLensOptions.firstOrNull { it.key == key }
            }
                ?: cameraLensOptions.firstOrNull {
                    it.facing == CameraSelector.LENS_FACING_BACK &&
                        it.label.contains("principal", ignoreCase = true)
                }
                ?: cameraLensOptions.firstOrNull {
                    it.facing == CameraSelector.LENS_FACING_BACK
                }
                ?: cameraLensOptions.first()
""",
    "preferred camera",
)

main = replace_once(
    main,
    """        selectedCameraOption = option
        lensFacing = option.facing

        if (updateSpinner) {
""",
    """        selectedCameraOption = option
        lensFacing = option.facing
        preferredCameraKey = option.key
        saveSmartLinkState()

        if (updateSpinner) {
""",
    "save camera selection",
)

main = replace_once(
    main,
    """        selectedResolution = stillAvailable
            ?: availableResolutionOptions.firstOrNull { it.key == "1920x1080" }
""",
    """        selectedResolution = preferredResolutionKey?.let { key ->
            availableResolutionOptions.firstOrNull { it.key == key }
        }
            ?: stillAvailable
            ?: availableResolutionOptions.firstOrNull { it.key == "1920x1080" }
""",
    "preferred resolution",
)

main = replace_once(
    main,
    """        selectedResolution = option
        applyHighResolutionDefaults(option)
""",
    """        selectedResolution = option
        preferredResolutionKey = option.key
        applyHighResolutionDefaults(option)
        saveSmartLinkState()
""",
    "save resolution",
)

main = replace_once(
    main,
    """                    nextEncodeDueNs = 0L
                    resetPerformanceStats()
                    updateStreamInfo()
                    if (server.isRunning()) {
""",
    """                    nextEncodeDueNs = 0L
                    resetPerformanceStats()
                    updateStreamInfo()
                    saveSmartLinkState()
                    if (server.isRunning()) {
""",
    "save quality",
)

main = replace_once(
    main,
    """                    updateStreamInfo()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupAutomaticDiscovery() {
""",
    """                    updateStreamInfo()
                    saveSmartLinkState()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupAutomaticDiscovery() {
""",
    "save rotation",
)

main = replace_once(
    main,
    """            updateAutomaticDiscoveryText()
            updateConnectionStatus(server.videoClientCount())
            Toast.makeText(
""",
    """            updateAutomaticDiscoveryText()
            updateConnectionStatus(server.videoClientCount())
            saveSmartLinkState()
            Toast.makeText(
""",
    "save auto discovery",
)

main = replace_once(
    main,
    """                            val prepared = ImageUtils.imageProxyToNv21(
                                image = image,
                                rotationDegrees = manualRotation
                            )
""",
    """                            val prepared = ImageUtils.imageProxyToNv21(
                                image = image,
                                rotationDegrees = manualRotation
                            )?.let { frame ->
                                WatermarkOverlay.apply(
                                    frame,
                                    watermarkEnabled
                                )
                            }
""",
    "watermark pipeline",
)

main = replace_once(
    main,
    """                                    val bitrate = H264Encoder.recommendedBitrate(
                                        prepared.width,
                                        prepared.height,
                                        h264Fps,
                                        streamJpegQuality
                                    )
""",
    """                                    val bitrate =
                                        if (streamBitrateBps > 0) {
                                            streamBitrateBps
                                        } else {
                                            H264Encoder.recommendedBitrate(
                                                prepared.width,
                                                prepared.height,
                                                h264Fps,
                                                streamJpegQuality
                                            )
                                        }
""",
    "manual bitrate h264",
)

main = replace_once(
    main,
    """            "shutterUs" -> {
                val requestedUs = value?.toLongOrNull() ?: return
                manualExposureTimeNs = requestedUs.coerceAtLeast(1L) * 1_000L
                if (manualExposureEnabled) applyManualExposure()
            }
        }
    }
""",
    """            "shutterUs" -> {
                val requestedUs = value?.toLongOrNull() ?: return
                manualExposureTimeNs = requestedUs.coerceAtLeast(1L) * 1_000L
                if (manualExposureEnabled) applyManualExposure()
            }

            "bitrateKbps" -> {
                val requested = value?.toIntOrNull() ?: return
                streamBitrateBps = if (requested <= 0) {
                    0
                } else {
                    requested.coerceIn(500, 60_000) * 1_000
                }
                h264Encoder.stop()
                if (front4kDirectStreamer.isRunning()) {
                    autoRestartStreamAfterCameraBind = true
                    stopStreaming()
                    startCamera()
                }
                updateStreamInfo()
            }

            "watermark" -> {
                watermarkEnabled =
                    value == "1" || value.equals("true", true)
                updateStreamInfo()
            }

            "savePreset" -> {
                val slot = value?.toIntOrNull() ?: return
                saveProPreset(slot)
            }

            "loadPreset" -> {
                val slot = value?.toIntOrNull() ?: return
                loadProPreset(slot)
            }
        }
        saveSmartLinkState()
    }
""",
    "pro remote controls",
)

main = replace_once(
    main,
    """                ",\\\"jpegQuality\\\":$streamJpegQuality" +
                ",\\\"targetFps\\\":$streamTargetFps" +
                ",\\\"rotation\\\":\\\"${selectedRotationMode.name}\\\"" +
""",
    """                ",\\\"jpegQuality\\\":$streamJpegQuality" +
                ",\\\"targetFps\\\":$streamTargetFps" +
                ",\\\"bitrateKbps\\\":${streamBitrateBps / 1000}" +
                ",\\\"watermarkEnabled\\\":$watermarkEnabled" +
                ",\\\"smartLinkEnabled\\\":true" +
                ",\\\"rotation\\\":\\\"${selectedRotationMode.name}\\\"" +
""",
    "state pro fields",
)

main = replace_once(
    main,
    """                        bitrate =
                            selectedResolution.directBitrate
                                ?: 32_000_000,
""",
    """                        bitrate =
                            if (streamBitrateBps > 0) {
                                streamBitrateBps
                            } else {
                                selectedResolution.directBitrate
                                    ?: 32_000_000
                            },
""",
    "front 4k bitrate",
)

MAIN.write_text(main, encoding="utf-8")

server = SERVER.read_text(encoding="utf-8")

server = replace_once(
    server,
    """                    <div class=\"rangeLimits\">0 = sem limite · útil para medir o máximo real do aparelho</div>

                    <label>Rotação da imagem</label>
""",
    """                    <div class=\"rangeLimits\">0 = sem limite · útil para medir o máximo real do aparelho</div>

                    <label class=\"rangeHead\">
                      <span>Bitrate H.264</span>
                      <span class=\"rangeValue\" id=\"bitrateText\">Automático</span>
                    </label>
                    <input id=\"bitrateKbps\" type=\"range\" min=\"0\" max=\"60000\" step=\"500\" value=\"0\"
                           oninput=\"bitrateText.textContent=(this.value==='0'?'Automático':(Number(this.value)/1000).toFixed(1)+' Mbps')\"
                           onchange=\"cmd('bitrateKbps',this.value)\">
                    <div class=\"rangeLimits\">0 = automático · no 4K frontal é aplicado ao reiniciar o stream</div>

                    <div class=\"muted\" style=\"color:#F2B620;font-weight:bold;margin-top:16px;margin-bottom:6px\">RECURSOS PRO EM TESTE</div>
                    <label><input id=\"watermarkEnabled\" type=\"checkbox\" onchange=\"cmd('watermark',this.checked?'1':'0')\"> Marca d'água GOAT CAM FREE</label>
                    <div class=\"rangeLimits\">No produto final, a versão Free mantém a marca; o Pro remove.</div>
                    <div class=\"row\" style=\"margin-top:8px\">
                      <button onclick=\"cmd('savePreset','1')\">Salvar P1</button>
                      <button onclick=\"cmd('savePreset','2')\">Salvar P2</button>
                      <button onclick=\"cmd('savePreset','3')\">Salvar P3</button>
                    </div>
                    <div class=\"row\">
                      <button class=\"secondary\" onclick=\"cmd('loadPreset','1')\">Carregar P1</button>
                      <button class=\"secondary\" onclick=\"cmd('loadPreset','2')\">Carregar P2</button>
                      <button class=\"secondary\" onclick=\"cmd('loadPreset','3')\">Carregar P3</button>
                    </div>
                    <div class=\"rangeLimits\">Smart Link lembra automaticamente câmera, resolução, qualidade, FPS, bitrate, rotação e marca d'água.</div>

                    <label>Rotação da imagem</label>
""",
    "pro panel controls",
)

server = server.replace(
    "rows.length+' resolução(ões) de vídeo disponíveis nesta câmera · 8K experimental tenta 7680×4320 exatos em aparelhos compatíveis'",
    "rows.length+' resolução(ões) de vídeo disponíveis nesta câmera'",
)

server = replace_once(
    server,
    """                    document.getElementById('fpsLimitText').textContent=
                      Number(s.targetFps||0)<=0?'Sem limite':Math.round(Number(s.targetFps))+' FPS';
                    document.getElementById('rotation').value=s.rotation||'AUTO';
""",
    """                    document.getElementById('fpsLimitText').textContent=
                      Number(s.targetFps||0)<=0?'Sem limite':Math.round(Number(s.targetFps))+' FPS';
                    const br=document.getElementById('bitrateKbps');
                    br.value=Number(s.bitrateKbps||0);
                    document.getElementById('bitrateText').textContent=
                      Number(s.bitrateKbps||0)<=0?'Automático':(Number(s.bitrateKbps)/1000).toFixed(1)+' Mbps';
                    document.getElementById('watermarkEnabled').checked=!!s.watermarkEnabled;
                    document.getElementById('rotation').value=s.rotation||'AUTO';
""",
    "load pro state",
)

SERVER.write_text(server, encoding="utf-8")

status = STATUS.read_text(encoding="utf-8")
marker = "## Build 23 — recursos Pro em teste"
if marker not in status:
    status += r'''

## Build 23 — recursos Pro em teste
Branch: `build-23-goat-cam`

- preserva a limpeza do 8K e o novo 4K frontal da Build 22;
- painel revisado: ISO, shutter, foco manual/auto, WB, EV, zoom, FPS, lentes, cena, abertura e filtro já existiam e foram mantidos;
- adicionada marca d'água leve `GOAT CAM FREE` no pipeline NV21 normal, compartilhada por MJPEG e H.264;
- controle de marca d'água exposto somente para teste; o bloqueio comercial Free/Pro ainda não foi ativado;
- bitrate H.264 manual de 0 (automático) até 60 Mbps;
- Smart Link persiste câmera, resolução, qualidade, JPEG, FPS, bitrate, rotação e estado da marca d'água;
- três presets Pro salvos (P1/P2/P3) para câmera, resolução e parâmetros de transmissão;
- removida do painel web a referência antiga ao 8K experimental;
- plano Free/Pro registrado em `GOAT_CAM_PRO_PLAN.md`.
'''
STATUS.write_text(status, encoding="utf-8")

# Source-level validation before commit.
if "8K experimental" in server:
    raise RuntimeError("Texto 8K antigo ainda presente no painel")
for required in (
    "streamBitrateBps",
    "watermarkEnabled",
    "saveProPreset",
    "loadProPreset",
    "WatermarkOverlay.apply",
    "bitrateKbps",
):
    if required not in main and required not in server:
        raise RuntimeError(f"Recurso ausente: {required}")
if main.count("{") != main.count("}"):
    raise RuntimeError("MainActivity com chaves desbalanceadas")
if server.count("{") != server.count("}"):
    raise RuntimeError("MjpegServer com chaves desbalanceadas")
print("Build 23 feature patch validated")
