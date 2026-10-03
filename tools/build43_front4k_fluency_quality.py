from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FRONT = ROOT / "app/src/main/java/com/goatpro/ip/Front4kDirectStreamer.kt"
MAIN = ROOT / "app/src/main/java/com/goatpro/ip/MainActivity.kt"
STATUS = ROOT / "BUILD_43_STATUS.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Build43 patch: trecho nao encontrado: {label}")
    return text.replace(old, new, 1)


front = FRONT.read_text(encoding="utf-8")

# Keep the Build 42 native Samsung route, but stop throwing away the useful
# bitrate already discovered by the 4K profile. 18 Mbps is intentionally below
# the failed 28 Mbps experiment from Build 41.
front = replace_once(
    front,
    "                    bitrate = profile.bitrate.coerceIn(8_000_000, 12_000_000),\n",
    "                    bitrate = profile.bitrate.coerceIn(8_000_000, 18_000_000),\n",
    "limite de bitrate da rota 4K nativa",
)
front = replace_once(
    front,
    "        val safeBitrate = profile.bitrate.coerceIn(8_000_000, 12_000_000)\n",
    "        val safeBitrate = profile.bitrate.coerceIn(8_000_000, 18_000_000)\n",
    "bitrate do MediaRecorder 4K",
)

# The recorder was asking for 30 FPS but the Camera1 parameter block was left on
# the OEM default range. On Samsung that range can fall back toward 15 FPS in
# automatic exposure. Prefer a fixed target range when the HAL publishes one;
# otherwise use the narrowest range that contains the requested cadence.
needle = '''            if (\n                params.supportedFocusModes?.contains(\n                    Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO\n                ) == true\n            ) {\n                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO\n            }\n\n            params.preferredPreviewSizeForVideo?.let { preferred ->\n'''
replacement = '''            if (\n                params.supportedFocusModes?.contains(\n                    Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO\n                ) == true\n            ) {\n                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO\n            }\n\n            val targetFps1000 = safeFps * 1000\n            val preferredFpsRange = params.supportedPreviewFpsRange.orEmpty()\n                .filter { range ->\n                    range.size >= 2 &&\n                        range[0] <= targetFps1000 &&\n                        range[1] >= targetFps1000\n                }\n                .minWithOrNull(\n                    compareBy<IntArray> { range -> range[1] - range[0] }\n                        .thenByDescending { range -> range[0] }\n                )\n            preferredFpsRange?.let { range ->\n                runCatching { params.setPreviewFpsRange(range[0], range[1]) }\n            }\n\n            if (params.supportedColorEffects?.contains(Camera.Parameters.EFFECT_NONE) == true) {\n                runCatching { params.colorEffect = Camera.Parameters.EFFECT_NONE }\n            }\n\n            params.preferredPreviewSizeForVideo?.let { preferred ->\n'''
front = replace_once(front, needle, replacement, "fixar faixa de FPS Camera1")

# Reuse the PES buffer instead of allocating a new 512 KiB buffer for every
# encoded frame. This removes avoidable GC pressure from the 4K hot path.
front = replace_once(
    front,
    "            var pesData = ByteArrayOutputStream(512 * 1024)\n",
    "            val pesData = ByteArrayOutputStream(256 * 1024)\n",
    "buffer PES reutilizavel",
)
front = replace_once(
    front,
    "                pesData = ByteArrayOutputStream(512 * 1024)\n",
    "                pesData.reset()\n",
    "reset do buffer PES",
)

# Make the active route explicit in the diagnostics without changing the wire
# protocol. This helps distinguish native 4K from GPU fallback during testing.
front = replace_once(
    front,
    '''            lastSourceDescription =\n                if (exact4k) "Camera1 supportedVideoSizes 3840x2160 → H.264" else "Camera1 OEM video-size 3840x2160 → H.264"\n''',
    '''            lastSourceDescription =\n                (if (exact4k) {\n                    "Camera1 supportedVideoSizes 3840x2160 → H.264"\n                } else {\n                    "Camera1 OEM video-size 3840x2160 → H.264"\n                }) + " • ${safeFps} FPS alvo • " +\n                    String.format(java.util.Locale.US, "%.1f Mbps", safeBitrate / 1_000_000.0)\n''',
    "diagnostico da rota nativa",
)

FRONT.write_text(front, encoding="utf-8")

main = MAIN.read_text(encoding="utf-8")

# Add a real cadence window for the direct 4K stream. The previous counter only
# displayed total frames, which made a 15-vs-30 FPS diagnosis impossible.
main = replace_once(
    main,
    '''    @Volatile\n    private var front4kLastUiNs = 0L\n\n    @Volatile\n    private var manualExposureEnabled = false\n''',
    '''    @Volatile\n    private var front4kLastUiNs = 0L\n\n    @Volatile\n    private var front4kWindowStartedNs = 0L\n\n    @Volatile\n    private var front4kWindowFrames = 0L\n\n    @Volatile\n    private var manualExposureEnabled = false\n''',
    "contadores de FPS real 4K",
)

old_listener = '''                    if (!codecConfig && data.isNotEmpty()) {\n                        val now = System.nanoTime()\n                        front4kH264FrameCount += 1L\n                        front4kLastFrameNs = now\n                        if (now - front4kLastUiNs >= 1_000_000_000L) {\n                            front4kLastUiNs = now\n                            val count = front4kH264FrameCount\n                            val route = front4kDirectStreamer.lastSourceDescription\n                                .ifBlank { "rota 4K em inicialização" }\n                            runOnUiThread {\n                                performanceText.text =\n                                    "4K frontal • H.264 frames: $count • $route"\n                            }\n                        }\n                    }\n'''
new_listener = '''                    if (!codecConfig && data.isNotEmpty()) {\n                        val now = System.nanoTime()\n                        front4kH264FrameCount += 1L\n                        front4kLastFrameNs = now\n                        if (front4kWindowStartedNs == 0L) {\n                            front4kWindowStartedNs = now\n                            front4kWindowFrames = 0L\n                        }\n                        front4kWindowFrames += 1L\n                        if (now - front4kLastUiNs >= 1_000_000_000L) {\n                            front4kLastUiNs = now\n                            val elapsed = (now - front4kWindowStartedNs).coerceAtLeast(1L)\n                            val realFps = front4kWindowFrames * 1_000_000_000.0 / elapsed\n                            front4kWindowStartedNs = now\n                            front4kWindowFrames = 0L\n                            val route = front4kDirectStreamer.lastSourceDescription\n                                .ifBlank { "rota 4K em inicialização" }\n                            runOnUiThread {\n                                performanceText.text =\n                                    "4K frontal • %.1f FPS reais • $route"\n                                        .format(java.util.Locale.US, realFps)\n                            }\n                        }\n                    }\n'''
main = replace_once(main, old_listener, new_listener, "medidor de FPS real 4K")

main = replace_once(
    main,
    '''            front4kH264FrameCount = 0L\n            front4kLastFrameNs = 0L\n            front4kLastUiNs = 0L\n''',
    '''            front4kH264FrameCount = 0L\n            front4kLastFrameNs = 0L\n            front4kLastUiNs = 0L\n            front4kWindowStartedNs = 0L\n            front4kWindowFrames = 0L\n''',
    "reset dos contadores 4K",
)

MAIN.write_text(main, encoding="utf-8")

STATUS.write_text(
    """# GOAT CAM — BUILD 43\n\n"
    "Base: Build 42 funcional.\n\n"
    "Objetivo: melhorar fluidez do 4K frontal sem alterar o protocolo que voltou a funcionar.\n\n"
    "Mudancas isoladas:\n"
    "- preserva a rota Samsung S21 MediaRecorder/MPEG-TS/H.264 da Build 42;\n"
    "- tenta fixar a faixa Camera1 no FPS alvo (preferencia por 30-30 quando publicada);\n"
    "- reduz alocacoes no parser MPEG-TS reutilizando o buffer PES;\n"
    "- libera bitrate nativo ate 18 Mbps, sem repetir o experimento de 28 Mbps da Build 41;\n"
    "- pede efeito de cor NONE quando suportado;\n"
    "- mostra FPS H.264 real medido no app para diferenciar 15/20/30 FPS.\n\n"
    "Nao alterado:\n"
    "- RTSP server/protocolo;\n"
    "- fallback MJPEG;\n"
    "- formato 3840x2160;\n"
    "- ordem de fallback de camera;\n"
    "- GOAT PRO Studio.\n"
    """,
    encoding="utf-8",
)

print("Build 43 fluency/quality patch applied")
