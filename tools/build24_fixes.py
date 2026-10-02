from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/goatpro/ip/MainActivity.kt"
FRONT = ROOT / "app/src/main/java/com/goatpro/ip/Front4kDirectStreamer.kt"
SERVER = ROOT / "app/src/main/java/com/goatpro/ip/MjpegServer.kt"
SERVICE = ROOT / "app/src/main/java/com/goatpro/ip/StreamingForegroundService.kt"
PLAN = ROOT / "GOAT_CAM_PRO_PLAN.md"
STATUS = ROOT / "BUILD_STATUS.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: esperado 1 trecho, encontrado {count}")
    return text.replace(old, new, 1)


# ---------------- MainActivity ----------------
main = MAIN.read_text(encoding="utf-8")

main = replace_once(
    main,
    """        cameraExecutor = Executors.newSingleThreadExecutor()
        setupOrientationTracking()
        restoreSmartLinkState()

        applyPreviewAspectRatio()
""",
    """        cameraExecutor = Executors.newSingleThreadExecutor()
        setupOrientationTracking()
        restoreSmartLinkState()
        StreamingCameraLifecycle.setActive(true)

        applyPreviewAspectRatio()
""",
    "activate independent lifecycle",
)

# Bind CameraX to an independent lifecycle so analyzer keeps running when Activity is STOPPED.
main = main.replace(
    "provider.bindToLifecycle(this, selector, preview)",
    "provider.bindToLifecycle(StreamingCameraLifecycle, selector, preview)"
)
main = main.replace(
    "provider.bindToLifecycle(this, selector, preview, analysis)",
    "provider.bindToLifecycle(StreamingCameraLifecycle, selector, preview, analysis)"
)
if "bindToLifecycle(this, selector" in main:
    raise RuntimeError("Ainda existe bind CameraX preso à Activity")

# Foreground service helpers before startStreaming.
helpers = r'''    private fun isStreamingActive(): Boolean =
        server.isRunning() ||
            rtspServer.isRunning() ||
            front4kDirectStreamer.isRunning() ||
            front4kDirectStreamer.isStarting()

    private fun startStreamingForegroundService() {
        StreamingCameraLifecycle.setActive(true)
        val intent = Intent(this, StreamingForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopStreamingForegroundService() {
        stopService(Intent(this, StreamingForegroundService::class.java))
    }

'''
main = replace_once(
    main,
    "    private fun startStreaming() {\n",
    helpers + "    private fun startStreaming() {\n",
    "foreground helpers",
)

main = replace_once(
    main,
    """    private fun startStreaming() {
        val ip = NetworkUtils.localIpv4()
""",
    """    private fun startStreaming() {
        val ip = NetworkUtils.localIpv4()
""",
    "start streaming anchor",
)
# Insert service only after Wi-Fi validation succeeds.
main = replace_once(
    main,
    """        front4kDirectStreamer.stop()
        refreshAddress()
""",
    """        startStreamingForegroundService()
        front4kDirectStreamer.stop()
        refreshAddress()
""",
    "start foreground service",
)

main = replace_once(
    main,
    """        server.stop()
        nextEncodeDueNs = 0L
""",
    """        server.stop()
        stopStreamingForegroundService()
        StreamingCameraLifecycle.setActive(true)
        nextEncodeDueNs = 0L
""",
    "stop foreground service",
)

# Better lifecycle handling for Home/screen-off. Do not stop stream on onStop.
old_lifecycle = r'''    override fun onResume() {
        super.onResume()
        if (::orientationListener.isInitialized && orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
        if (::addressText.isInitialized) refreshAddress()
    }

    override fun onPause() {
        if (::orientationListener.isInitialized) orientationListener.disable()
        super.onPause()
    }

    override fun onDestroy() {
'''
new_lifecycle = r'''    override fun onStart() {
        super.onStart()
        StreamingCameraLifecycle.setActive(true)
    }

    override fun onResume() {
        super.onResume()
        StreamingCameraLifecycle.setActive(true)
        if (::orientationListener.isInitialized && orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
        if (::addressText.isInitialized) refreshAddress()
    }

    override fun onPause() {
        if (::orientationListener.isInitialized) orientationListener.disable()
        super.onPause()
    }

    override fun onStop() {
        // If a stream is active, the foreground service keeps CameraX/Camera2 alive.
        // Otherwise release the independent camera lifecycle while the app is hidden.
        if (!isStreamingActive()) {
            StreamingCameraLifecycle.setActive(false)
        }
        super.onStop()
    }

    override fun onDestroy() {
'''
main = replace_once(main, old_lifecycle, new_lifecycle, "activity lifecycle")

# onDestroy still means true Activity destruction (Back/process teardown); clean normally.
main = replace_once(
    main,
    """        rtspServer.stop()
        server.stop()
        cameraExecutor.shutdown()
""",
    """        rtspServer.stop()
        server.stop()
        stopStreamingForegroundService()
        StreamingCameraLifecycle.setActive(false)
        cameraExecutor.shutdown()
""",
    "destroy service cleanup",
)

# Expand preset save to camera controls.
old_save = r'''    private fun saveProPreset(slot: Int) {
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
'''
new_save = r'''    private fun saveProPreset(slot: Int) {
        val safeSlot = slot.coerceIn(1, 3)
        val prefix = "preset_${safeSlot}_"
        val camera = currentCamera
        val zoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        val ev = camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0
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
            .putFloat(prefix + "zoom", zoom)
            .putInt(prefix + "ev", ev)
            .putBoolean(prefix + "manual_exposure", manualExposureEnabled)
            .putInt(prefix + "iso", manualIso)
            .putLong(prefix + "shutter_ns", manualExposureTimeNs)
            .putLong(prefix + "frame_duration_ns", manualFrameDurationNs)
            .putBoolean(prefix + "manual_focus", manualFocusEnabled)
            .putFloat(prefix + "focus_diopters", manualFocusDiopters)
            .putInt(prefix + "white_balance", selectedWhiteBalanceMode)
            .putInt(prefix + "antibanding", selectedAntibandingMode)
            .putInt(prefix + "scene_mode", selectedSceneMode)
            .apply()
        Toast.makeText(
            this,
            "Preset $safeSlot salvo com câmera e controles.",
            Toast.LENGTH_SHORT
        ).show()
    }
'''
main = replace_once(main, old_save, new_save, "expanded preset save")

# Inject additional preset restore values before preferredCameraKey.
main = replace_once(
    main,
    """        watermarkEnabled = prefs.getBoolean(
            prefix + "watermark_enabled",
            true
        )
        selectedRotationMode = runCatching {
""",
    """        watermarkEnabled = prefs.getBoolean(
            prefix + "watermark_enabled",
            true
        )
        val presetZoom = prefs.getFloat(prefix + "zoom", 1f)
        val presetEv = prefs.getInt(prefix + "ev", 0)
        manualExposureEnabled = prefs.getBoolean(prefix + "manual_exposure", false)
        manualIso = prefs.getInt(prefix + "iso", manualIso)
        manualExposureTimeNs = prefs.getLong(prefix + "shutter_ns", manualExposureTimeNs)
        manualFrameDurationNs = prefs.getLong(prefix + "frame_duration_ns", 0L)
        manualFocusEnabled = prefs.getBoolean(prefix + "manual_focus", false)
        manualFocusDiopters = prefs.getFloat(prefix + "focus_diopters", 0f)
        selectedWhiteBalanceMode = prefs.getInt(prefix + "white_balance", selectedWhiteBalanceMode)
        selectedAntibandingMode = prefs.getInt(prefix + "antibanding", selectedAntibandingMode)
        selectedSceneMode = prefs.getInt(prefix + "scene_mode", selectedSceneMode)
        selectedRotationMode = runCatching {
""",
    "preset extra restore values",
)

# Apply restored camera controls after camera/resolution has had time to rebind.
main = replace_once(
    main,
    """            h264Encoder.stop()
            updateStreamInfo()
            saveSmartLinkState()
            Toast.makeText(
                this,
                "Preset Pro $safeSlot carregado.",
                Toast.LENGTH_SHORT
            ).show()
        }, 350L)
""",
    """            h264Encoder.stop()
            updateStreamInfo()
            saveSmartLinkState()

            previewView.postDelayed({
                val camera = currentCamera
                if (camera != null) {
                    camera.cameraInfo.zoomState.value?.let { state ->
                        runCatching {
                            camera.cameraControl.setZoomRatio(
                                presetZoom.coerceIn(state.minZoomRatio, state.maxZoomRatio)
                            )
                        }
                    }
                    if (!manualExposureEnabled) {
                        val state = camera.cameraInfo.exposureState
                        val range = state.exposureCompensationRange
                        runCatching {
                            camera.cameraControl.setExposureCompensationIndex(
                                presetEv.coerceIn(range.lower, range.upper)
                            )
                        }
                    }
                    applyAutomaticSensorControls()
                    applyLensControls()
                    if (manualFocusEnabled) applyManualFocus()
                    if (manualExposureEnabled) applyManualExposure()
                }
            }, 450L)

            Toast.makeText(
                this,
                "Preset $safeSlot carregado e aplicado.",
                Toast.LENGTH_SHORT
            ).show()
        }, 450L)
""",
    "preset apply controls",
)

# Expose preset saved state to web panel.
main = replace_once(
    main,
    """            val resolutionOptionsCsv =
                availableResolutionOptions.joinToString(";") {
                    it.key + "|" + it.label.replace(";", " ").replace("|", " ")
                }

            "{\\\"available\\\":true" +
""",
    """            val resolutionOptionsCsv =
                availableResolutionOptions.joinToString(";") {
                    it.key + "|" + it.label.replace(";", " ").replace("|", " ")
                }
            val presetPrefs = proPresetPrefs()
            val preset1Saved = presetPrefs.getBoolean("preset_1_saved", false)
            val preset2Saved = presetPrefs.getBoolean("preset_2_saved", false)
            val preset3Saved = presetPrefs.getBoolean("preset_3_saved", false)

            "{\\\"available\\\":true" +
""",
    "preset json vars",
)
main = replace_once(
    main,
    """                ",\\\"smartLinkEnabled\\\":true" +
                ",\\\"rotation\\\":\\\"${selectedRotationMode.name}\\\"" +
""",
    """                ",\\\"smartLinkEnabled\\\":true" +
                ",\\\"preset1Saved\\\":$preset1Saved" +
                ",\\\"preset2Saved\\\":$preset2Saved" +
                ",\\\"preset3Saved\\\":$preset3Saved" +
                ",\\\"backgroundStreaming\\\":${isStreamingActive()}" +
                ",\\\"rotation\\\":\\\"${selectedRotationMode.name}\\\"" +
""",
    "preset json fields",
)

MAIN.write_text(main, encoding="utf-8")

# ---------------- Front4kDirectStreamer ----------------
front = FRONT.read_text(encoding="utf-8")

front = replace_once(
    front,
    """    private var configuredFps = 30
    private var configuredBitrate = 32_000_000
""",
    """    private var configuredFps = 30
    private var configuredBitrate = 32_000_000
    @Volatile private var sessionConfigured = false
    @Volatile private var firstFrameDelivered = false
""",
    "front watchdog fields",
)

old_surface = r'''                encoder.configure(
                    format,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
                )
                val surface = encoder.createInputSurface()
                encoder.start()
'''
new_surface = r'''                // Samsung recording pipelines are more reliable with a persistent
                // encoder input Surface, which behaves closer to the recorder path used
                // by the stock camera than a transient createInputSurface().
                val surface = MediaCodec.createPersistentInputSurface()
                encoder.configure(
                    format,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
                )
                encoder.setInputSurface(surface)
                encoder.start()
'''
front = replace_once(front, old_surface, new_surface, "persistent surface")

front = replace_once(
    front,
    """                configuredFps = safeFps
                configuredBitrate = safeBitrate
                starting.set(true)
""",
    """                configuredFps = safeFps
                configuredBitrate = safeBitrate
                sessionConfigured = false
                firstFrameDelivered = false
                starting.set(true)
""",
    "reset watchdog state",
)

old_started = r'''                    session.setRepeatingRequest(request.build(), null, handler)
                    starting.set(false)
                    running.set(true)
                    listener.onStarted(
                        configuredWidth,
                        configuredHeight,
                        configuredFps,
                        configuredBitrate
                    )
'''
new_started = r'''                    session.setRepeatingRequest(request.build(), null, handler)
                    sessionConfigured = true
                    starting.set(false)
                    running.set(true)

                    // A session can be accepted by Samsung yet deliver zero encoder
                    // frames. Treat that as failure instead of leaving GOAT Cam frozen.
                    handler.postDelayed({
                        if (running.get() && sessionConfigured && !firstFrameDelivered) {
                            fail(
                                "Sessão 4K frontal abriu, mas não entregou frames em 4 segundos."
                            )
                        }
                    }, 4_000L)
'''
front = replace_once(front, old_started, new_started, "front startup watchdog")

# Notify started only after the first actual encoded access unit.
old_output = r'''                                listener.onAccessUnit(
                                    bytes,
                                    info.presentationTimeUs,
                                    (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                )
'''
new_output = r'''                                if (!firstFrameDelivered && sessionConfigured) {
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
                                    info.presentationTimeUs,
                                    (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                )
'''
front = replace_once(front, old_output, new_output, "first encoded frame")

front = replace_once(
    front,
    """        running.set(false)
        starting.set(false)

        runCatching { captureSession?.stopRepeating() }
""",
    """        running.set(false)
        starting.set(false)
        sessionConfigured = false
        firstFrameDelivered = false

        runCatching { captureSession?.stopRepeating() }
""",
    "reset direct state",
)

# Replace camera-1-only S21 fallback with per-front-camera capability probing.
old_fallback = r'''            // Samsung S21-family fallback: the stock camera can expose UHD
            // through its recording path even when Camera2 omits 3840x2160
            // from the public YUV list. Keep this fallback scoped to the S21
            // family and only to a front camera with a large enough sensor.
            val samsungS21 =
                Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                    Regex("^SM-G99[0168].*", RegexOption.IGNORE_CASE)
                        .matches(Build.MODEL.orEmpty())
            val pixel = chars.get(
                CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE
            )
            val active = chars.get(
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
            )
            val sensorWidth = maxOf(pixel?.width ?: 0, active?.width() ?: 0)
            val sensorHeight = maxOf(pixel?.height ?: 0, active?.height() ?: 0)
            val enoughPixels =
                maxOf(sensorWidth, sensorHeight) >= 3200 &&
                    minOf(sensorWidth, sensorHeight) >= 2000

            return if (samsungS21 && enoughPixels && cameraId == "1") {
                Profile(
                    cameraId = cameraId,
                    fps = 30,
                    bitrate = 32_000_000,
                    fromOfficialProfile = false
                )
            } else {
                null
            }
'''
new_fallback = r'''            // Probe the public recording/private Surface maps per front camera.
            // This avoids the old hardcode that exposed 4K only on camera ID 1.
            val map = chars.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            fun hasExact4k(sizes: Array<android.util.Size>?): Boolean =
                sizes.orEmpty().any {
                    (it.width == 3840 && it.height == 2160) ||
                        (it.width == 2160 && it.height == 3840)
                }

            val recorder4k = runCatching {
                hasExact4k(map?.getOutputSizes(MediaRecorder::class.java))
            }.getOrDefault(false)
            val codec4k = runCatching {
                hasExact4k(map?.getOutputSizes(MediaCodec::class.java))
            }.getOrDefault(false)
            if (recorder4k || codec4k) {
                return Profile(
                    cameraId = cameraId,
                    fps = 30,
                    bitrate = 32_000_000,
                    fromOfficialProfile = false
                )
            }

            // Some Galaxy S21 firmware hides UHD front recording from the public
            // size maps although the stock app offers it. Permit an experimental
            // attempt on every real front camera with a sufficiently large sensor;
            // the 4-second watchdog prevents an unsupported path from hanging.
            val samsungS21 =
                Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                    Regex("^SM-G99[0168].*", RegexOption.IGNORE_CASE)
                        .matches(Build.MODEL.orEmpty())
            val pixel = chars.get(
                CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE
            )
            val active = chars.get(
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
            )
            val sensorWidth = maxOf(pixel?.width ?: 0, active?.width() ?: 0)
            val sensorHeight = maxOf(pixel?.height ?: 0, active?.height() ?: 0)
            val enoughPixels =
                maxOf(sensorWidth, sensorHeight) >= 3000 &&
                    minOf(sensorWidth, sensorHeight) >= 2000

            return if (samsungS21 && enoughPixels) {
                Profile(
                    cameraId = cameraId,
                    fps = 30,
                    bitrate = 32_000_000,
                    fromOfficialProfile = false
                )
            } else {
                null
            }
'''
front = replace_once(front, old_fallback, new_fallback, "front per-camera fallback")

FRONT.write_text(front, encoding="utf-8")

# ---------------- MjpegServer web UI ----------------
server = SERVER.read_text(encoding="utf-8")
old_preset_ui = r'''                    <div class="row" style="margin-top:8px">
                      <button onclick="cmd('savePreset','1')">Salvar P1</button>
                      <button onclick="cmd('savePreset','2')">Salvar P2</button>
                      <button onclick="cmd('savePreset','3')">Salvar P3</button>
                    </div>
                    <div class="row">
                      <button class="secondary" onclick="cmd('loadPreset','1')">Carregar P1</button>
                      <button class="secondary" onclick="cmd('loadPreset','2')">Carregar P2</button>
                      <button class="secondary" onclick="cmd('loadPreset','3')">Carregar P3</button>
                    </div>
                    <div class="rangeLimits">Smart Link lembra automaticamente câmera, resolução, qualidade, FPS, bitrate, rotação e marca d'água.</div>
'''
new_preset_ui = r'''                    <div class="rangeLimits" style="margin-top:8px">Presets guardam câmera, resolução e controles manuais para reutilizar depois.</div>
                    <div class="row" style="margin-top:8px">
                      <button onclick="presetCmd('savePreset','1','Preset 1 salvo')">Salvar Preset 1</button>
                      <button class="secondary" onclick="presetCmd('loadPreset','1','Preset 1 aplicado')">Aplicar Preset 1</button>
                    </div>
                    <div class="row">
                      <button onclick="presetCmd('savePreset','2','Preset 2 salvo')">Salvar Preset 2</button>
                      <button class="secondary" onclick="presetCmd('loadPreset','2','Preset 2 aplicado')">Aplicar Preset 2</button>
                    </div>
                    <div class="row">
                      <button onclick="presetCmd('savePreset','3','Preset 3 salvo')">Salvar Preset 3</button>
                      <button class="secondary" onclick="presetCmd('loadPreset','3','Preset 3 aplicado')">Aplicar Preset 3</button>
                    </div>
                    <div class="rangeLimits" id="presetState">Nenhum preset confirmado ainda.</div>
                    <div class="rangeLimits">Smart Link lembra automaticamente a última configuração mesmo sem usar preset.</div>
'''
server = replace_once(server, old_preset_ui, new_preset_ui, "clear preset UI")

server = replace_once(
    server,
    """                async function cmd(action,value){
""",
    """                async function presetCmd(action,slot,message){
                  document.getElementById('status').textContent=message+'…';
                  await cmd(action,slot);
                  setTimeout(loadState,650);
                }

                async function cmd(action,value){
""",
    "preset command js",
)

server = replace_once(
    server,
    """                    document.getElementById('watermarkEnabled').checked=!!s.watermarkEnabled;
                    document.getElementById('rotation').value=s.rotation||'AUTO';
""",
    """                    document.getElementById('watermarkEnabled').checked=!!s.watermarkEnabled;
                    const saved=[];
                    if(s.preset1Saved)saved.push('Preset 1');
                    if(s.preset2Saved)saved.push('Preset 2');
                    if(s.preset3Saved)saved.push('Preset 3');
                    document.getElementById('presetState').textContent=
                      saved.length ? ('Salvos: '+saved.join(' · ')) : 'Nenhum preset salvo ainda.';
                    document.getElementById('rotation').value=s.rotation||'AUTO';
""",
    "preset state js",
)

SERVER.write_text(server, encoding="utf-8")

# Foreground service lifecycle must not deactivate CameraX before Activity decides.
service = SERVICE.read_text(encoding="utf-8")
service = service.replace(
    """        releaseLocks()
        StreamingCameraLifecycle.setActive(false)
        super.onDestroy()
""",
    """        releaseLocks()
        super.onDestroy()
"""
)
SERVICE.write_text(service, encoding="utf-8")

# ---------------- planning/status docs ----------------
plan = PLAN.read_text(encoding="utf-8")
if "## Build 24 em implementação" not in plan:
    plan += r'''

## Build 24 em implementação
- 4K frontal: caminho revisto por câmera; removido hardcode da frontal ID 1; Surface persistente + watchdog de 4 s; tentativa também na segunda frontal quando elegível.
- Presets Pro: nomes claros Preset 1/2/3; salvam câmera, resolução, qualidade, FPS, bitrate, rotação, zoom, EV, ISO/shutter, foco e modos Camera2 quando disponíveis.
- Segundo plano/tela apagada: foreground service de câmera/microfone, wake lock, Wi-Fi lock e lifecycle CameraX independente da Activity.
- 8K continua removido.
'''
PLAN.write_text(plan, encoding="utf-8")

status = STATUS.read_text(encoding="utf-8")
if "## Build 24" not in status:
    status += r'''

## Build 24
Branch: `build-24-goat-cam`

Objetivos:
- corrigir travamento do 4K frontal e expor tentativa por câmera frontal;
- refazer Preset 1/2/3 com salvamento real de controles;
- implementar transmissão em segundo plano/tela apagada com foreground service;
- preservar traseira 4K, tele, Smart Link, watermark e controles manuais;
- sem 8K.
'''
STATUS.write_text(status, encoding="utf-8")

print("Build 24 patch aplicado")
