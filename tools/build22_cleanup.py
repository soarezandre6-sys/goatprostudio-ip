from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/goatpro/ip/MainActivity.kt"
FRONT = ROOT / "app/src/main/java/com/goatpro/ip/Front4kDirectStreamer.kt"
LAYOUT = ROOT / "app/src/main/res/layout/activity_main.xml"

main = MAIN.read_text(encoding="utf-8")

broken = (
    '":8080/video' + "\\" + "\n" +
    'H.264 RTSP: rtsp://" +'
)
fixed = (
    '":8080/video' + "\\n" +
    'H.264 RTSP: rtsp://" +'
)

if broken in main:
    raise RuntimeError("Existe string Kotlin com barra antes da quebra de linha")
if main.count(fixed) != 2:
    raise RuntimeError(
        f"Esperadas 2 strings de endereço normalizadas, encontradas {main.count(fixed)}"
    )
if "activeClientCount() else" in main:
    raise RuntimeError("rtspCount inválido")
if "8K" in main or "7680" in main or "4320" in main:
    raise RuntimeError("MainActivity ainda contém código 8K")
if main.count("{") != main.count("}"):
    raise RuntimeError("MainActivity com chaves desbalanceadas")

front = FRONT.read_text(encoding="utf-8")
if "CONTROL_AF_AVAILABLE_MODES\n                    ).orEmpty()" in front:
    raise RuntimeError("IntArray.orEmpty ainda presente no 4K frontal")
if 'cameraId == "1"' not in front:
    raise RuntimeError("Fallback S21 não está restrito à câmera frontal principal")
if "QUALITY_2160P" not in front:
    raise RuntimeError("Perfil 2160p frontal ausente")
if "3840" not in front or "2160" not in front:
    raise RuntimeError("Resolução frontal 4K ausente")
if front.count("{") != front.count("}"):
    raise RuntimeError("Front4kDirectStreamer com chaves desbalanceadas")

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

print("Build 22 final source validation passed")
print("Address strings:", main.count(fixed))
print("Main braces:", main.count("{"), main.count("}"))
print("Front braces:", front.count("{"), front.count("}"))
