from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"pattern not found: {label}")
    return text.replace(old, new, 1)

cam = Path("app/src/main/java/com/goatpro/ip/Camera1GpuH264Streamer.kt")
text = cam.read_text(encoding="utf-8")
text = replace_once(
    text,
    '''        // Some Samsung camera1 stacks carry a separate recording-size key.\n        if (config.targetWidth == 3840 && config.targetHeight == 2160) {\n            runCatching { applyParams.set("video-size", "3840x2160") }\n        }\n\n''',
    '''        // Build 35: this is a PREVIEW/SurfaceTexture pipeline. Do not force\n        // Samsung's OEM video-size key here. Mixing a 4K recording size with a\n        // different preview stream can make the camera HAL deliver one frame and\n        // then stall. Native 4K recording is handled by Front4kDirectStreamer.\n\n''',
    "remove mixed video-size from preview path",
)
cam.write_text(text, encoding="utf-8")

front = Path("app/src/main/java/com/goatpro/ip/Front4kDirectStreamer.kt")
text = front.read_text(encoding="utf-8")
old_start = '''            val effective = profileFor(context, profile.cameraId)\n            if (effective != null && effective.legacyCameraId >= 0) {\n                val direct = effective.copy(\n                    fps = profile.fps.coerceIn(5, 60),\n                    bitrate = profile.bitrate.coerceIn(8_000_000, 12_000_000),\n                    deviceRotationDegrees = normalize(profile.deviceRotationDegrees),\n                    rotationOffsetDegrees = normalize(profile.rotationOffsetDegrees)\n                )\n                if (startCamera1Gpu(direct)) {\n                    return true\n                }\n            }\n\n            return startGpuFallback(profile)\n'''
new_start = '''            val effective = profileFor(context, profile.cameraId)\n            if (effective != null && effective.legacyCameraId >= 0) {\n                val direct = effective.copy(\n                    fps = profile.fps.coerceIn(5, 60),\n                    bitrate = profile.bitrate.coerceIn(8_000_000, 12_000_000),\n                    deviceRotationDegrees = normalize(profile.deviceRotationDegrees),\n                    rotationOffsetDegrees = normalize(profile.rotationOffsetDegrees)\n                )\n\n                // Build 35: choose the route that matches what the camera really\n                // publishes. If 3840x2160 exists as a PREVIEW size, use the\n                // SurfaceTexture path (which can also feed MJPEG /video). If 4K is\n                // only a recording/video size, use MediaRecorder instead of forcing\n                // recording parameters into the preview path.\n                val previewProbe = Camera1GpuH264Streamer.probe(\n                    direct.legacyCameraId,\n                    direct.width,\n                    direct.height\n                )\n                if (previewProbe?.exactPreview4k == true) {\n                    if (startCamera1Gpu(direct)) {\n                        return true\n                    }\n                }\n\n                val samsungS21 =\n                    Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&\n                        Regex("^SM-G99[0168].*", RegexOption.IGNORE_CASE)\n                            .matches(Build.MODEL.orEmpty())\n                if (direct.legacyExact4k || samsungS21) {\n                    if (startLegacy(direct)) {\n                        return true\n                    }\n                }\n\n                // Last Camera1 fallback: preview/GPU without the OEM video-size key.\n                if (startCamera1Gpu(direct)) {\n                    return true\n                }\n            }\n\n            return startGpuFallback(profile)\n'''
text = replace_once(text, old_start, new_start, "select correct front 4K route")

old_error = '''                        val fallbackStarted = startGpuFallback(current)\n                        if (!fallbackStarted) {\n                            listener.onError("Camera1 GPU 4K frontal: $message")\n                        }\n'''
new_error = '''                        // If the preview/GPU route stalls, try the camera's\n                        // native 4K recording pipeline before falling back to Camera2.\n                        val nativeStarted = if (current.legacyCameraId >= 0) {\n                            startLegacy(current)\n                        } else {\n                            false\n                        }\n                        val fallbackStarted = nativeStarted || startGpuFallback(current)\n                        if (!fallbackStarted) {\n                            listener.onError("Camera1 GPU 4K frontal: $message")\n                        }\n'''
text = replace_once(text, old_error, new_error, "native recorder fallback")
front.write_text(text, encoding="utf-8")

status = Path("BUILD_35_STATUS.md")
status.write_text(
    """# GOAT Cam Build 35\n\n"
    "Correção focada no congelamento 4K da câmera frontal no próprio GOAT CAM.\n\n"
    "- Remove `video-size=3840x2160` da rota Camera1 preview/SurfaceTexture.\n"
    "- Se a câmera publica preview 4K real, mantém preview 4K + GPU/MJPEG.\n"
    "- Se 4K existe apenas como modo de gravação, prioriza MediaRecorder nativo 4K.\n"
    "- Galaxy S21 recebe tentativa da rota nativa antes de composição GPU de fallback.\n"
    "- Se a rota preview travar em execução, tenta MediaRecorder nativo antes do fallback Camera2.\n"
    "- Build 34 preservada; alterações isoladas em `build-35-goat-cam`.\n"
    """,
    encoding="utf-8",
)
