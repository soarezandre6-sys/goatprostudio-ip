from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'missing block: {label}')
    return text.replace(old, new, 1)

# 1) Camera1 GPU path: keep real UHD geometry, monotonic PTS, larger encoder->RTSP buffer.
p = Path('app/src/main/java/com/goatpro/ip/Camera1GpuH264Streamer.kt')
s = p.read_text()

s = replace_once(
    s,
    '    private val deliveryQueue = ArrayBlockingQueue<EncodedUnit>(8)\n',
    '    private val deliveryQueue = ArrayBlockingQueue<EncodedUnit>(24)\n',
    'camera1 delivery queue'
)

s = replace_once(
    s,
    '    @Volatile\n    private var droppedDeliveryFrames = 0L\n',
    '    @Volatile\n    private var droppedDeliveryFrames = 0L\n\n    @Volatile\n    private var lastPresentationNs = 0L\n',
    'camera1 pts field'
)

s = replace_once(
    s,
    '        droppedDeliveryFrames = 0L\n        deliveryQueue.clear()\n',
    '        droppedDeliveryFrames = 0L\n        lastPresentationNs = 0L\n        deliveryQueue.clear()\n',
    'camera1 pts reset'
)

s = replace_once(
    s,
    '        actualBitrate = config.targetBitrate.coerceIn(8_000_000, 28_000_000)\n',
    '        actualBitrate = config.targetBitrate.coerceIn(8_000_000, 12_000_000)\n',
    'camera1 bitrate cap'
)

s = replace_once(
    s,
    '''        val totalRotation = normalize(relativeRotation + config.extraRotationDegrees)\n        val portrait = totalRotation == 90 || totalRotation == 270\n        outputWidth = if (portrait) config.targetHeight else config.targetWidth\n        outputHeight = if (portrait) config.targetWidth else config.targetHeight\n''',
    '''        val totalRotation = normalize(relativeRotation + config.extraRotationDegrees)\n\n        // Keep the encoded stream in the selected UHD geometry. Rotating the\n        // encoder itself to 2160x3840 caused decoder compatibility problems in\n        // desktop clients even though the phone preview remained fluid.\n        outputWidth = config.targetWidth\n        outputHeight = config.targetHeight\n''',
    'camera1 fixed UHD geometry'
)

s = replace_once(
    s,
    '''            EGLExt.eglPresentationTimeANDROID(\n                eglDisplay,\n                eglSurface,\n                texture.timestamp\n            )\n''',
    '''            val rawTimestamp = texture.timestamp\n            val frameStepNs = 1_000_000_000L / actualFps.coerceAtLeast(1)\n            val presentationNs = when {\n                rawTimestamp <= 0L -> lastPresentationNs + frameStepNs\n                rawTimestamp <= lastPresentationNs -> lastPresentationNs + frameStepNs\n                else -> rawTimestamp\n            }\n            lastPresentationNs = presentationNs\n            EGLExt.eglPresentationTimeANDROID(\n                eglDisplay,\n                eglSurface,\n                presentationNs\n            )\n''',
    'camera1 monotonic pts'
)

p.write_text(s)

# 2) Camera2/GPU fallback: same geometry + PTS guarantees for the second frontal route.
p = Path('app/src/main/java/com/goatpro/ip/GpuCameraH264Streamer.kt')
s = p.read_text()

s = replace_once(
    s,
    '    private val deliveryQueue = ArrayBlockingQueue<EncodedUnit>(10)\n',
    '    private val deliveryQueue = ArrayBlockingQueue<EncodedUnit>(24)\n',
    'gpu delivery queue'
)

s = replace_once(
    s,
    '    @Volatile\n    private var droppedDeliveryFrames = 0L\n',
    '    @Volatile\n    private var droppedDeliveryFrames = 0L\n\n    @Volatile\n    private var lastPresentationNs = 0L\n',
    'gpu pts field'
)

s = replace_once(
    s,
    '        droppedDeliveryFrames = 0L\n        deliveryQueue.clear()\n',
    '        droppedDeliveryFrames = 0L\n        lastPresentationNs = 0L\n        deliveryQueue.clear()\n',
    'gpu pts reset'
)

s = replace_once(
    s,
    '''        val totalRotation = normalizeDegrees(relativeRotation + request.extraRotationDegrees)\n        val portraitOutput = totalRotation == 90 || totalRotation == 270\n        outputWidth = if (portraitOutput) request.targetHeight else request.targetWidth\n        outputHeight = if (portraitOutput) request.targetWidth else request.targetHeight\n\n        setupEncoder(outputWidth, outputHeight, actualFps, request.targetBitrate)\n''',
    '''        val totalRotation = normalizeDegrees(relativeRotation + request.extraRotationDegrees)\n\n        // Keep the wire format exactly at the selected resolution. Rotation is\n        // done in texture coordinates, not by swapping the encoded dimensions.\n        outputWidth = request.targetWidth\n        outputHeight = request.targetHeight\n\n        val stableBitrate = request.targetBitrate.coerceAtMost(12_000_000)\n        setupEncoder(outputWidth, outputHeight, actualFps, stableBitrate)\n''',
    'gpu fixed UHD geometry'
)

s = replace_once(
    s,
    '''            EGLExt.eglPresentationTimeANDROID(\n                eglDisplay,\n                eglSurface,\n                texture.timestamp\n            )\n''',
    '''            val rawTimestamp = texture.timestamp\n            val frameStepNs = 1_000_000_000L / actualFps.coerceAtLeast(1)\n            val presentationNs = when {\n                rawTimestamp <= 0L -> lastPresentationNs + frameStepNs\n                rawTimestamp <= lastPresentationNs -> lastPresentationNs + frameStepNs\n                else -> rawTimestamp\n            }\n            lastPresentationNs = presentationNs\n            EGLExt.eglPresentationTimeANDROID(\n                eglDisplay,\n                eglSurface,\n                presentationNs\n            )\n''',
    'gpu monotonic pts'
)

p.write_text(s)

# 3) RTP/RTSP: larger short-term jitter queue and fewer packets per 4K frame.
p = Path('app/src/main/java/com/goatpro/ip/RtspH264Server.kt')
s = p.read_text()

s = replace_once(
    s,
    '        val sendQueue = ArrayBlockingQueue<PendingAccessUnit>(4)\n',
    '        val sendQueue = ArrayBlockingQueue<PendingAccessUnit>(24)\n',
    'rtsp per-client queue'
)

s = replace_once(
    s,
    '                        runCatching { socket.sendBufferSize = 512 * 1024 }\n',
    '                        runCatching { socket.sendBufferSize = 2 * 1024 * 1024 }\n',
    'rtsp tcp buffer'
)

s = replace_once(
    s,
    '''            // A socket that blocks repeatedly is worse than a reconnect. Closing\n            // it forces the Studio to reconnect instead of leaving a frozen 4K frame.\n            if (client.queueOverflows >= 3) {\n                removeClient(client)\n                return\n            }\n''',
    '''            // Do not tear down the Studio connection on a short 4K burst.\n            // Keep the session alive and resume from the next IDR/keyframe.\n            if (client.queueOverflows > 12) {\n                client.queueOverflows = 12\n            }\n''',
    'rtsp overflow policy'
)

s = replace_once(
    s,
    '        val maxPayload = 1200\n',
    '        val maxPayload = 1400\n',
    'rtp payload size'
)

p.write_text(s)

# 4) Front 4K profile: network-stable UHD bitrate. Resolution/FPS stay unchanged.
p = Path('app/src/main/java/com/goatpro/ip/Front4kDirectStreamer.kt')
s = p.read_text()
s = s.replace('val bitrate: Int = 18_000_000,', 'val bitrate: Int = 12_000_000,', 1)
s = s.replace('private var configuredBitrate = 18_000_000', 'private var configuredBitrate = 12_000_000', 1)
s = s.replace(
    'bitrate = profile.bitrate.coerceIn(8_000_000, 28_000_000),',
    'bitrate = profile.bitrate.coerceIn(8_000_000, 12_000_000),',
    1
)
s = s.replace(
    'targetBitrate = profile.bitrate.coerceIn(8_000_000, 28_000_000),',
    'targetBitrate = profile.bitrate.coerceIn(8_000_000, 12_000_000),',
    1
)
s = s.replace(
    'val safeBitrate = profile.bitrate.coerceIn(8_000_000, 28_000_000)',
    'val safeBitrate = profile.bitrate.coerceIn(8_000_000, 12_000_000)',
    1
)
s = s.replace(
    'profile.bitrate.coerceIn(8_000_000, 28_000_000)',
    'profile.bitrate.coerceIn(8_000_000, 12_000_000)',
    1
)
p.write_text(s)

print('Build 33 front 4K stability patch applied')
