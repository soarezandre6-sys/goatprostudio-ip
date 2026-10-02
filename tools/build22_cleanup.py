from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/goatpro/ip/MainActivity.kt"
FRONT = ROOT / "app/src/main/java/com/goatpro/ip/Front4kDirectStreamer.kt"
LAYOUT = ROOT / "app/src/main/res/layout/activity_main.xml"

main = MAIN.read_text(encoding="utf-8")

bad_rtsp = '''        val rtspCount = rtspServer.activeClientCount() else {
                rtspServer.activeClientCount()
            }
'''
if bad_rtsp not in main:
    raise RuntimeError("Marcador rtspCount quebrado não encontrado")
main = main.replace(
    bad_rtsp,
    "        val rtspCount = rtspServer.activeClientCount()\n",
    1,
)

# The previous maintenance pass inserted a real newline inside two normal Kotlin
# strings. Convert both to the escaped newline sequence expected by Kotlin.
main = main.replace(
    '":8080/video\nH.264 RTSP: rtsp://" +',
    '":8080/video\\nH.264 RTSP: rtsp://" +',
)

# Front direct mode should inherit the profile FPS; 30 FPS is the safe fallback.
main = main.replace(
    "                streamTargetFps = option.directFps ?: 24\n",
    "                streamTargetFps = option.directFps ?: 30\n",
)

# Avoid a race when changing away from direct front 4K while a stream is active.
# selectResolutionOption already restarts the camera after the new option is set.
main = main.replace(
    '''        if (
            selectedResolution.directFront4k &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }
''',
    '''        if (
            selectedResolution.directFront4k &&
            !autoRestartStreamAfterCameraBind &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }
''',
    1,
)

if "activeClientCount() else" in main:
    raise RuntimeError("rtspCount ainda inválido")
if '":8080/video\nH.264 RTSP: rtsp://" +' in main:
    raise RuntimeError("Ainda existe newline literal dentro de string Kotlin")
if "8K" in main or "7680" in main or "4320" in main:
    raise RuntimeError("MainActivity voltou a conter 8K")
if main.count("{") != main.count("}"):
    raise RuntimeError("MainActivity com chaves desbalanceadas")
MAIN.write_text(main, encoding="utf-8")

front = FRONT.read_text(encoding="utf-8")
old_af = '''                    val afModes = chars.get(
                        CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
                    ).orEmpty()
'''
new_af = '''                    val afModes = chars.get(
                        CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
                    ) ?: intArrayOf()
'''
if old_af not in front:
    raise RuntimeError("Marcador AF modes não encontrado")
front = front.replace(old_af, new_af, 1)

# The S21 fallback should target the primary public front camera observed on the
# device. Other front IDs remain eligible only when Android exposes an official
# 2160p profile for them.
front = front.replace(
    "            return if (samsungS21 && enoughPixels) {\n",
    "            return if (samsungS21 && enoughPixels && cameraId == \"1\") {\n",
    1,
)

if ").orEmpty()" in front and "CONTROL_AF_AVAILABLE_MODES" in front:
    # Other orEmpty usages are List/Array based and valid; make sure the IntArray
    # occurrence specifically is gone.
    fragment = "CONTROL_AF_AVAILABLE_MODES\n                    ).orEmpty()"
    if fragment in front:
        raise RuntimeError("IntArray.orEmpty ainda presente")
if front.count("{") != front.count("}"):
    raise RuntimeError("Front4kDirectStreamer com chaves desbalanceadas")
FRONT.write_text(front, encoding="utf-8")

layout = LAYOUT.read_text(encoding="utf-8")
for forbidden in ("8K", "vendorDiagnostic", "local8k"):
    if forbidden in layout:
        raise RuntimeError(f"Layout ainda contém {forbidden}")

for removed in (
    "CameraX8kRecorderProbe.kt",
    "HevcDirectStreamer.kt",
    "Local8kProfileRecorder.kt",
    "MediaRecorderHevcStreamer.kt",
    "SamsungVendorDiagnostics.kt",
    "RtspH265Server.kt",
):
    if (ROOT / "app/src/main/java/com/goatpro/ip" / removed).exists():
        raise RuntimeError(f"Arquivo 8K ainda presente: {removed}")

print("Build 22 fix validation passed")
print("Main braces:", main.count("{"), main.count("}"))
print("Front braces:", front.count("{"), front.count("}"))
