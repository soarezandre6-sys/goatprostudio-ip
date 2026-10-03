from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GPU = ROOT / "app/src/main/java/com/goatpro/ip/GpuCameraH264Streamer.kt"
FRONT = ROOT / "app/src/main/java/com/goatpro/ip/Front4kDirectStreamer.kt"
MAIN = ROOT / "app/src/main/java/com/goatpro/ip/MainActivity.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Build41 patch: trecho nao encontrado: {label}")
    return text.replace(old, new, 1)

text = GPU.read_text(encoding="utf-8")
text = replace_once(
    text,
    "        mjpegOrientationDegrees = totalRotation\n        setupMjpegReader(characteristics, outputWidth, outputHeight)\n\n        val stableBitrate = request.targetBitrate.coerceAtMost(12_000_000)\n",
    "        mjpegOrientationDegrees = totalRotation\n\n        // Build 41: direct UHD is RTSP/H.264 only. A concurrent JPEG ImageReader on\n        // the Samsung front camera was adding ISP/JPEG load and hurting cadence/detail.\n        val directUhdH264Only = outputWidth >= 3840 && outputHeight >= 2160\n        if (!directUhdH264Only) {\n            setupMjpegReader(characteristics, outputWidth, outputHeight)\n        }\n\n        val stableBitrate = request.targetBitrate.coerceAtMost(28_000_000)\n",
    "desativar JPEG concorrente no 4K e liberar bitrate",
)
needle = '''            if (Build.VERSION.SDK_INT >= 30) {\n                characteristics.get(\n                    CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE\n                )?.let { range ->\n                    set(\n                        CaptureRequest.CONTROL_ZOOM_RATIO,\n                        request.zoomRatio.coerceIn(range.lower, range.upper)\n                    )\n                }\n            }\n'''
insert = '''            if (Build.VERSION.SDK_INT >= 30) {\n                characteristics.get(\n                    CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE\n                )?.let { range ->\n                    set(\n                        CaptureRequest.CONTROL_ZOOM_RATIO,\n                        request.zoomRatio.coerceIn(range.lower, range.upper)\n                    )\n                }\n            }\n\n            // Build 41: ask Camera2 for the least smoothed image the public API exposes.\n            val nrModes = characteristics.get(\n                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES\n            ).orEmpty()\n            if (nrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF)) {\n                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)\n            }\n\n            val edgeModes = characteristics.get(\n                CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES\n            ).orEmpty()\n            when {\n                edgeModes.contains(CaptureRequest.EDGE_MODE_HIGH_QUALITY) ->\n                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)\n                edgeModes.contains(CaptureRequest.EDGE_MODE_FAST) ->\n                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)\n            }\n\n            val effectModes = characteristics.get(\n                CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS\n            ).orEmpty()\n            if (effectModes.contains(CaptureRequest.CONTROL_EFFECT_MODE_OFF)) {\n                set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)\n            }\n'''
text = replace_once(text, needle, insert, "controles de detalhe Camera2")
GPU.write_text(text, encoding="utf-8")

text = FRONT.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''        val safeBitrate = if (profile.bitrate > 0) {\n            profile.bitrate.coerceIn(8_000_000, 12_000_000)\n        } else {\n            H264Encoder.recommendedBitrate(3840, 2160, 30, 80)\n        }\n''',
    '''        val safeBitrate = if (profile.bitrate > 0) {\n            profile.bitrate.coerceIn(18_000_000, 28_000_000)\n        } else {\n            24_000_000\n        }\n''',
    "bitrate Camera2 fallback",
)
FRONT.write_text(text, encoding="utf-8")

text = MAIN.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''            override fun onVideoClientCountChanged(count: Int) {\n                if (selectedResolution.directFront4k) {\n                    front4kDirectStreamer.setMjpegOutput(\n                        enabled = count > 0,\n                        quality = streamJpegQuality,\n                        fps = (if (streamTargetFps > 0) streamTargetFps else 15)\n                            .coerceIn(5, 15)\n                    )\n                }\n                runOnUiThread { updateConnectionStatus(count) }\n            }\n''',
    '''            override fun onVideoClientCountChanged(count: Int) {\n                if (selectedResolution.directFront4k) {\n                    // Build 41: direct UHD is RTSP/H.264 only. Keep HTTP alive for\n                    // state/control, but never start the concurrent 4K JPEG path.\n                    front4kDirectStreamer.setMjpegOutput(\n                        enabled = false,\n                        quality = streamJpegQuality,\n                        fps = 1\n                    )\n                }\n                runOnUiThread { updateConnectionStatus(count) }\n            }\n''',
    "desativar MJPEG 4K no listener HTTP",
)
text = replace_once(
    text,
    '''                        statusText.text =\n                            "TRANSMITINDO 4K FRONTAL PARA O GOAT PRO STUDIO"\n''',
    '''                        statusText.text =\n                            "TRANSMITINDO 4K FRONTAL · RTSP/H.264 DIRETO"\n''',
    "status 4K RTSP",
)
MAIN.write_text(text, encoding="utf-8")

print("Build 41 RTSP/quality patch applied")
