from pathlib import Path

p = Path('app/src/main/java/com/goatpro/ip/Front4kDirectStreamer.kt')
s = p.read_text(encoding='utf-8')

s = s.replace(
    'import android.os.ParcelFileDescriptor\n',
    'import android.os.ParcelFileDescriptor\nimport android.os.Build\n',
    1
)

s = s.replace(
    'if (effective?.legacyExact4k == true && effective.legacyCameraId >= 0) {',
    'if (effective != null && effective.legacyCameraId >= 0) {',
    1
)

old = '''            val exact4k = videoSizes?.any { it.width == 3840 && it.height == 2160 } == true
            if (!exact4k) {
                throw IllegalStateException(
                    "Camera1 deixou de anunciar 3840x2160 ao abrir a frontal."
                )
            }

            params.setRecordingHint(true)
'''
new = '''            val exact4k = videoSizes?.any { it.width == 3840 && it.height == 2160 } == true
            // IP Webcam proves this Samsung can record/stream front 3840x2160 even
            // when some public capability lists omit it. Camera1 accepts OEM string
            // parameters, so try the legacy video-size key before giving up.
            if (!exact4k) {
                runCatching { params.set("video-size", "3840x2160") }
            }

            params.setRecordingHint(true)
'''
if old not in s:
    raise SystemExit('startLegacy exact4k block not found')
s = s.replace(old, new, 1)

s = s.replace(
    '"Camera1 supportedVideoSizes 3840x2160 → H.264 MediaRecorder"',
    'if (exact4k) "Camera1 supportedVideoSizes 3840x2160 → H.264" else "Camera1 OEM video-size 3840x2160 → H.264"',
    1
)

# The GPU fallback must use a genuinely high-resolution front source. The old
# 16:9-nearest probe could choose 1920x1080 and then fail the 5 MP eligibility.
s = s.replace(
    '''            requestedFps = 30,
            preferLargestSource = false
        ) ?: run {''',
    '''            requestedFps = 30,
            preferLargestSource = true
        ) ?: run {''',
    1
)

# Track a valid legacy front ID so Samsung can try the OEM Camera1 recording
# path even when 3840x2160 is omitted from supportedVideoSizes.
s = s.replace(
    '''            val info = Camera.CameraInfo()
            for (id in 0 until Camera.getNumberOfCameras()) {''',
    '''            val info = Camera.CameraInfo()
            var firstLegacyFrontId = -1
            for (id in 0 until Camera.getNumberOfCameras()) {''',
    1
)

s = s.replace(
    '''                if (!validFront) continue

                val exactLegacy4k = try {''',
    '''                if (!validFront) continue
                if (firstLegacyFrontId < 0) firstLegacyFrontId = legacyId

                val exactLegacy4k = try {''',
    1
)

marker = '''            val probe = GpuCameraH264Streamer.probe(
                context,
                cameraId,
                requestedFps = 30,
                preferLargestSource = false
            ) ?: return null
'''
replacement = '''            val samsungS21 =
                Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                    Regex("^SM-G99[0168].*", RegexOption.IGNORE_CASE)
                        .matches(Build.MODEL.orEmpty())

            // The same Galaxy S21 was demonstrated running IP Webcam at
            // 3840x2160 H.264 on the front camera. Do not hide the option just
            // because Camera1/Camera2 capability lists omit the exact size.
            if (samsungS21 && firstLegacyFrontId >= 0) {
                return Profile(
                    cameraId = cameraId,
                    width = 3840,
                    height = 2160,
                    fps = 30,
                    bitrate = 18_000_000,
                    fromOfficialProfile = false,
                    legacyCameraId = firstLegacyFrontId,
                    legacyExact4k = false
                )
            }

            val probe = GpuCameraH264Streamer.probe(
                context,
                cameraId,
                requestedFps = 30,
                preferLargestSource = true
            ) ?: return null
'''
if marker not in s:
    raise SystemExit('profileFor GPU marker not found')
s = s.replace(marker, replacement, 1)

p.write_text(s, encoding='utf-8')
print('Build 30 front 4K patch applied')
