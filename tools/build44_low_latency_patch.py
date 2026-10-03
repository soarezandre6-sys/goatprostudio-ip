from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RTSP = ROOT / "app/src/main/java/com/goatpro/ip/RtspH264Server.kt"
MAIN = ROOT / "app/src/main/java/com/goatpro/ip/MainActivity.kt"
FRONT = ROOT / "app/src/main/java/com/goatpro/ip/Front4kDirectStreamer.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Build44 patch: trecho nao encontrado: {label}")
    return text.replace(old, new, 1)

# RTSP: minimize application and kernel buffering without touching codec quality.
text = RTSP.read_text(encoding="utf-8")
text = replace_once(
    text,
    "        val sendQueue = ArrayBlockingQueue<PendingAccessUnit>(24)\n",
    "        // Build 44: Live View must prefer current frames over backlog.\n        // Four access units at 30 FPS cap app-side queueing near ~130 ms.\n        val sendQueue = ArrayBlockingQueue<PendingAccessUnit>(4)\n",
    "RTSP queue 24 -> 4",
)
text = replace_once(
    text,
    "                        runCatching { socket.sendBufferSize = 2 * 1024 * 1024 }\n",
    "                        // Build 44: 2 MB could hide almost a second of UHD H.264\n                        // in the TCP kernel buffer. Keep it small for live monitoring.\n                        runCatching { socket.sendBufferSize = 256 * 1024 }\n",
    "TCP send buffer 2MB -> 256KB",
)
text = replace_once(
    text,
    "        session.udpRtpSocket = DatagramSocket()\n        session.udpRtcpSocket = DatagramSocket()\n",
    "        session.udpRtpSocket = DatagramSocket().apply {\n            runCatching { sendBufferSize = 256 * 1024 }\n        }\n        session.udpRtcpSocket = DatagramSocket().apply {\n            runCatching { sendBufferSize = 64 * 1024 }\n        }\n",
    "UDP low-latency buffers",
)
RTSP.write_text(text, encoding="utf-8")

# If Studio has an RTSP client, do not spend CPU/ISP on a simultaneous 4K JPEG path.
text = MAIN.read_text(encoding="utf-8")
text = replace_once(
    text,
    """                    front4kDirectStreamer.setMjpegOutput(\n                        enabled = count > 0,\n                        quality = streamJpegQuality,\n                        fps = (if (streamTargetFps > 0) streamTargetFps else 15)\n                            .coerceIn(5, 15)\n                    )\n""",
    """                    front4kDirectStreamer.setMjpegOutput(\n                        enabled = count > 0 && rtspServer.activeClientCount() == 0,\n                        quality = streamJpegQuality,\n                        fps = (if (streamTargetFps > 0) streamTargetFps else 15)\n                            .coerceIn(5, 15)\n                    )\n""",
    "disable concurrent MJPEG when RTSP active",
)
text = replace_once(
    text,
    """            override fun onActiveClientCountChanged(count: Int) {\n                if (count > 0) {\n                    when {\n""",
    """            override fun onActiveClientCountChanged(count: Int) {\n                if (selectedResolution.directFront4k) {\n                    front4kDirectStreamer.setMjpegOutput(\n                        enabled = count == 0 && server.videoClientCount() > 0,\n                        quality = streamJpegQuality,\n                        fps = (if (streamTargetFps > 0) streamTargetFps else 15)\n                            .coerceIn(5, 15)\n                    )\n                }\n                if (count > 0) {\n                    when {\n""",
    "RTSP wins over 4K MJPEG",
)
MAIN.write_text(text, encoding="utf-8")

# Smaller read-ahead on the MediaRecorder MPEG-TS pipe. This does not change bitrate/resolution.
text = FRONT.read_text(encoding="utf-8")
text = replace_once(
    text,
    "                512 * 1024\n",
    "                64 * 1024\n",
    "TS read buffer 512KB -> 64KB",
)
FRONT.write_text(text, encoding="utf-8")

print("Build 44 low-latency patch applied")
