from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/goatpro/ip/MainActivity.kt"
LAYOUT = ROOT / "app/src/main/res/layout/activity_main.xml"
STATUS = ROOT / "BUILD_25_STATUS.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: esperado 1 trecho, encontrado {count}")
    return text.replace(old, new, 1)

main = MAIN.read_text(encoding="utf-8")
layout = LAYOUT.read_text(encoding="utf-8")

# Native switch + state flags.
main = replace_once(
    main,
    "    private lateinit var autoDiscoverySwitch: SwitchCompat\n",
    "    private lateinit var autoDiscoverySwitch: SwitchCompat\n    private lateinit var backgroundStreamingSwitch: SwitchCompat\n",
    "background switch field",
)

main = replace_once(
    main,
    "    private var autoDiscoveryEnabled = true\n",
    "    private var autoDiscoveryEnabled = true\n    private var backgroundStreamingEnabled = true\n    private var backgroundHeadless = false\n",
    "background flags",
)

main = replace_once(
    main,
    "        autoDiscoverySwitch = findViewById(R.id.autoDiscoverySwitch)\n",
    "        autoDiscoverySwitch = findViewById(R.id.autoDiscoverySwitch)\n        backgroundStreamingSwitch = findViewById(R.id.backgroundStreamingSwitch)\n",
    "find background switch",
)

main = replace_once(
    main,
    "        setupAutomaticDiscovery()\n        refreshAddress()\n",
    "        setupAutomaticDiscovery()\n        setupBackgroundStreaming()\n        refreshAddress()\n",
    "setup background switch",
)

# Add background preference/setup immediately before updateAutomaticDiscoveryText.
background_methods = r'''    private fun setupBackgroundStreaming() {
        val prefs = getSharedPreferences("goat_pro_ip", Context.MODE_PRIVATE)
        backgroundStreamingEnabled = prefs.getBoolean(
            "background_streaming_enabled",
            true
        )
        backgroundStreamingSwitch.isChecked = backgroundStreamingEnabled
        updateBackgroundStreamingText()

        backgroundStreamingSwitch.setOnCheckedChangeListener { _, checked ->
            backgroundStreamingEnabled = checked
            prefs.edit()
                .putBoolean("background_streaming_enabled", checked)
                .apply()
            updateBackgroundStreamingText()

            if (checked && isStreamingActive()) {
                startStreamingForegroundService()
            } else if (!checked) {
                stopStreamingForegroundService()
            }

            Toast.makeText(
                this,
                if (checked) {
                    "Segundo plano ativado: Home e tela apagada manterão a transmissão."
                } else {
                    "Segundo plano desativado."
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateBackgroundStreamingText() {
        if (!::backgroundStreamingSwitch.isInitialized) return
        backgroundStreamingSwitch.text = if (backgroundStreamingEnabled) {
            "Continuar transmitindo em segundo plano / tela apagada: LIGADO"
        } else {
            "Continuar transmitindo em segundo plano / tela apagada: DESLIGADO"
        }
    }

    private fun enterBackgroundCaptureMode() {
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
main = replace_once(
    main,
    "    private fun updateAutomaticDiscoveryText() {\n",
    background_methods + "    private fun updateAutomaticDiscoveryText() {\n",
    "background methods",
)

# FGS should be tied to the explicit switch.
main = replace_once(
    main,
    """    private fun startStreamingForegroundService() {
        StreamingCameraLifecycle.setActive(true)
        val intent = Intent(this, StreamingForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
""",
    """    private fun startStreamingForegroundService() {
        if (!backgroundStreamingEnabled) return
        StreamingCameraLifecycle.setActive(true)
        val intent = Intent(this, StreamingForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
""",
    "foreground service switch gate",
)

# Headless binding: while hidden, ImageAnalysis must not depend on PreviewView.
main = replace_once(
    main,
    """                provider.unbindAll()
                previewUseCase = preview
                analysisUseCase = analysis
                currentCamera = provider.bindToLifecycle(StreamingCameraLifecycle, selector, preview, analysis)
""",
    """                provider.unbindAll()
                analysisUseCase = analysis
                if (backgroundHeadless && server.isRunning()) {
                    previewUseCase = null
                    currentCamera = provider.bindToLifecycle(
                        StreamingCameraLifecycle,
                        selector,
                        analysis
                    )
                } else {
                    previewUseCase = preview
                    currentCamera = provider.bindToLifecycle(
                        StreamingCameraLifecycle,
                        selector,
                        preview,
                        analysis
                    )
                }
""",
    "headless CameraX binding",
)

# Lifecycle: switch into a PreviewView-free camera session on Home/screen lock,
# and restore preview when returning.
main = replace_once(
    main,
    """    override fun onStart() {
        super.onStart()
        StreamingCameraLifecycle.setActive(true)
    }
""",
    """    override fun onStart() {
        super.onStart()
        StreamingCameraLifecycle.setActive(true)
        exitBackgroundCaptureMode()
    }
""",
    "restore foreground preview",
)

main = replace_once(
    main,
    """    override fun onStop() {
        // If a stream is active, the foreground service keeps CameraX/Camera2 alive.
        // Otherwise release the independent camera lifecycle while the app is hidden.
        if (!isStreamingActive()) {
            StreamingCameraLifecycle.setActive(false)
        }
        super.onStop()
    }
""",
    """    override fun onStop() {
        if (isStreamingActive() && backgroundStreamingEnabled) {
            enterBackgroundCaptureMode()
        } else {
            backgroundHeadless = false
            StreamingCameraLifecycle.setActive(false)
        }
        super.onStop()
    }
""",
    "background onStop",
)

# Ensure stop resets hidden mode.
main = replace_once(
    main,
    """        stopStreamingForegroundService()
        StreamingCameraLifecycle.setActive(true)
        nextEncodeDueNs = 0L
""",
    """        stopStreamingForegroundService()
        backgroundHeadless = false
        StreamingCameraLifecycle.setActive(true)
        nextEncodeDueNs = 0L
""",
    "stop reset headless",
)

# Expose state to the Studio web control JSON.
main = replace_once(
    main,
    "                ",
    "                ",
    "noop anchor",
) if False else main

# Native UI: add a clear switch under auto-discovery, before the IP address.
address_anchor = '''            <TextView
                android:id="@+id/addressText"'''
background_ui = '''            <androidx.appcompat.widget.SwitchCompat
                android:id="@+id/backgroundStreamingSwitch"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="14dp"
                android:checked="true"
                android:text="Continuar transmitindo em segundo plano / tela apagada: LIGADO"
                android:textColor="@color/text"
                android:textSize="13sp"
                app:thumbTint="@color/gold"
                app:trackTint="@color/line" />

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="5dp"
                android:text="Ligado: Home e bloqueio da tela mantêm câmera, CPU, Wi-Fi e transmissão ativos."
                android:textColor="@color/muted"
                android:textSize="11sp" />

'''
layout = replace_once(
    layout,
    address_anchor,
    background_ui + address_anchor,
    "background switch UI",
)

# Persist the feature status with the branch notes.
status = STATUS.read_text(encoding="utf-8") if STATUS.exists() else "# GOAT Cam Build 25\n"
if "## Correção segundo plano / tela apagada" not in status:
    status += r'''

## Correção segundo plano / tela apagada
- adicionada chave nativa `Continuar transmitindo em segundo plano / tela apagada`;
- padrão: LIGADO;
- foreground service só é mantido quando a chave está ligada;
- WakeLock e Wi-Fi lock da Build 24 continuam ativos;
- ao pressionar Home ou bloquear a tela no stream normal, CameraX troca para captura headless (ImageAnalysis sem PreviewView);
- ao voltar ao app, o Preview é restaurado sem parar MJPEG/RTSP;
- no 4K frontal MediaRecorder, a sessão Camera2 própria é preservada e não depende do PreviewView;
- objetivo: impedir a perda do stream causada pela destruição da Surface visual.
'''

MAIN.write_text(main, encoding="utf-8")
LAYOUT.write_text(layout, encoding="utf-8")
STATUS.write_text(status, encoding="utf-8")

# Lightweight source validation (no APK/build generated here).
for path in (MAIN,):
    text = path.read_text(encoding="utf-8")
    if text.count("{") != text.count("}"):
        raise RuntimeError(f"Chaves desbalanceadas em {path}")
    if text.count("(") != text.count(")"):
        raise RuntimeError(f"Parênteses desbalanceados em {path}")

final_main = MAIN.read_text(encoding="utf-8")
final_layout = LAYOUT.read_text(encoding="utf-8")
required = [
    "backgroundStreamingSwitch",
    "backgroundStreamingEnabled",
    "backgroundHeadless",
    "enterBackgroundCaptureMode",
    "exitBackgroundCaptureMode",
    "provider.bindToLifecycle(\n                        StreamingCameraLifecycle,\n                        selector,\n                        analysis",
]
for marker in required:
    if marker not in final_main:
        raise RuntimeError(f"Marcador ausente: {marker}")
if "@+id/backgroundStreamingSwitch" not in final_layout:
    raise RuntimeError("Switch de segundo plano ausente do layout")

print("Build 25 background fix applied and statically validated")
