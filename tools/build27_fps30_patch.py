from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/goatpro/ip/MainActivity.kt"
FRONT = ROOT / "app/src/main/java/com/goatpro/ip/Front4kDirectStreamer.kt"
STATUS = ROOT / "BUILD_27_STATUS.md"


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: esperado 1, encontrado {count}")
    return text.replace(old, new, 1)

main = MAIN.read_text(encoding="utf-8")
front = FRONT.read_text(encoding="utf-8")

main = once(
    main,
    "    private var streamTargetFps = QualityProfile.BALANCED.targetFps\n",
    "    private var streamTargetFps = DEFAULT_START_FPS\n",
    "fps inicial",
)

main = once(
    main,
    '''        streamTargetFps = prefs.getInt(\n            "target_fps",\n            selectedQualityProfile.targetFps\n        ).let { if (it <= 0) 0 else it.coerceIn(5, 120) }\n''',
    '''        // Segurança térmica/performance: cada nova abertura do app volta a 30 FPS.\n        // O usuário pode subir manualmente depois para 60/120 quando suportado.\n        streamTargetFps = DEFAULT_START_FPS\n        if (selectedQualityProfile.targetFps != DEFAULT_START_FPS) {\n            selectedQualityProfile = QualityProfile.CUSTOM\n        }\n''',
    "smart link fps",
)

old_high = '''    private fun applyHighResolutionDefaults(option: ResolutionOption) {\n        val width = option.size.width\n        when {\n            option.directFront4k -> {\n                streamJpegQuality = 50\n                streamTargetFps = option.directFps ?: 30\n                selectedQualityProfile = QualityProfile.CUSTOM\n            }\n            width >= 3840 -> {\n                streamJpegQuality = 50\n                streamTargetFps = 10\n                selectedQualityProfile = QualityProfile.CUSTOM\n            }\n            width > 1920 -> {\n                streamJpegQuality = 58\n                streamTargetFps = 15\n                selectedQualityProfile = QualityProfile.CUSTOM\n            }\n            else -> return\n        }\n\n        if (::qualitySpinner.isInitialized) {\n            qualitySpinner.setSelection(\n                QualityProfile.entries.indexOf(QualityProfile.CUSTOM)\n            )\n        }\n    }\n'''
new_high = '''    private fun applyHighResolutionDefaults(option: ResolutionOption) {\n        // Toda troca de resolução volta ao ponto seguro de 30 FPS.\n        // 60/120 FPS só entram por escolha manual posterior do usuário.\n        streamTargetFps = DEFAULT_START_FPS\n        val width = option.size.width\n        when {\n            option.directFront4k -> {\n                streamJpegQuality = 50\n                selectedQualityProfile = QualityProfile.CUSTOM\n            }\n            width >= 3840 -> {\n                streamJpegQuality = 50\n                selectedQualityProfile = QualityProfile.CUSTOM\n            }\n            width > 1920 -> {\n                streamJpegQuality = 58\n                selectedQualityProfile = QualityProfile.CUSTOM\n            }\n            selectedQualityProfile.targetFps != DEFAULT_START_FPS -> {\n                selectedQualityProfile = QualityProfile.CUSTOM\n            }\n        }\n\n        nextEncodeDueNs = 0L\n        if (::qualitySpinner.isInitialized && selectedQualityProfile == QualityProfile.CUSTOM) {\n            qualitySpinner.setSelection(\n                QualityProfile.entries.indexOf(QualityProfile.CUSTOM)\n            )\n        }\n    }\n'''
main = once(main, old_high, new_high, "defaults resolução")

main = once(
    main,
    '''        selectedCameraOption = option\n        lensFacing = option.facing\n        preferredCameraKey = option.key\n        saveSmartLinkState()\n''',
    '''        selectedCameraOption = option\n        lensFacing = option.facing\n        preferredCameraKey = option.key\n        streamTargetFps = DEFAULT_START_FPS\n        nextEncodeDueNs = 0L\n        if (selectedQualityProfile.targetFps != DEFAULT_START_FPS) {\n            selectedQualityProfile = QualityProfile.CUSTOM\n            if (::qualitySpinner.isInitialized) {\n                qualitySpinner.setSelection(\n                    QualityProfile.entries.indexOf(QualityProfile.CUSTOM)\n                )\n            }\n        }\n        saveSmartLinkState()\n''',
    "troca câmera fps",
)

main = once(
    main,
    '''                        fps = selectedResolution.directFps ?: 60,\n''',
    '''                        fps = (\n                            if (streamTargetFps > 0) streamTargetFps\n                            else DEFAULT_START_FPS\n                        ).coerceIn(5, 60),\n''',
    "4k usa fps selecionado",
)

main = once(
    main,
    '''        BALANCED(\n            "Equilibrado • Q65 • 20 FPS",\n            "Equilibrado",\n            65,\n            20\n        ),\n''',
    '''        BALANCED(\n            "Equilibrado • Q65 • 30 FPS",\n            "Equilibrado",\n            65,\n            30\n        ),\n''',
    "balanced 30",
)

main = once(
    main,
    '''    companion object {\n        private const val PREVIEW_HEIGHT_RATIO = 9f / 16f\n    }\n''',
    '''    companion object {\n        private const val PREVIEW_HEIGHT_RATIO = 9f / 16f\n        private const val DEFAULT_START_FPS = 30\n    }\n''',
    "constante fps",
)

front = front.replace("val fps: Int = 60,", "val fps: Int = 30,", 1)
front = front.replace("private var configuredFps = 60", "private var configuredFps = 30", 1)

MAIN.write_text(main, encoding="utf-8")
FRONT.write_text(front, encoding="utf-8")

status = STATUS.read_text(encoding="utf-8")
marker = "## Segurança\n"
insert = '''## Padrão de FPS\n- toda nova abertura do app inicia em 30 FPS.\n- trocar câmera/lente ou resolução também volta para 30 FPS.\n- 60 e 120 FPS continuam disponíveis somente por escolha manual e quando suportados.\n- Smart Link não restaura automaticamente um FPS alto de uma sessão anterior.\n- o 4K frontal também parte de 30 FPS e só sobe se o usuário escolher depois.\n\n'''
if insert not in status:
    if marker not in status:
        raise RuntimeError("marcador BUILD_27_STATUS não encontrado")
    status = status.replace(marker, insert + marker, 1)
STATUS.write_text(status, encoding="utf-8")

for p in (MAIN, FRONT):
    txt = p.read_text(encoding="utf-8")
    if txt.count("{") != txt.count("}"):
        raise RuntimeError(f"chaves desbalanceadas em {p}")

required = [
    "private var streamTargetFps = DEFAULT_START_FPS",
    "streamTargetFps = DEFAULT_START_FPS",
    "private const val DEFAULT_START_FPS = 30",
    "Equilibrado • Q65 • 30 FPS",
]
for item in required:
    if item not in main:
        raise RuntimeError(f"marcador ausente: {item}")

print("Build 27 FPS padrão 30 aplicado")
