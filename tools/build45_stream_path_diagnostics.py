from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
rtsp_path = ROOT / "app/src/main/java/com/goatpro/ip/RtspH264Server.kt"
main_path = ROOT / "app/src/main/java/com/goatpro/ip/MainActivity.kt"
mjpeg_path = ROOT / "app/src/main/java/com/goatpro/ip/MjpegServer.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"anchor not found: {label}")
    if text.count(old) != 1:
        raise SystemExit(f"anchor not unique ({text.count(old)}): {label}")
    return text.replace(old, new, 1)


rtsp = rtsp_path.read_text(encoding="utf-8")
rtsp = replace_once(
    rtsp,
    """        @Volatile\n        var queueOverflows = 0\n\n        @Volatile\n        var playing = false\n""",
    """        @Volatile\n        var queueOverflows = 0\n\n        @Volatile\n        var totalQueueOverflows = 0L\n\n        @Volatile\n        var maxQueueDepth = 0\n\n        @Volatile\n        var lastSendDurationNs = 0L\n\n        @Volatile\n        var accessUnitsSent = 0L\n\n        @Volatile\n        var playing = false\n""",
    "rtsp client diagnostics fields",
)

rtsp = replace_once(
    rtsp,
    """    fun activeClientCount(): Int = activeCount.get()\n\n    fun url(ip: String): String = \"rtsp://${NetworkUtils.urlHost(ip)}:$port/h264\"\n""",
    """    fun activeClientCount(): Int = activeCount.get()\n\n    fun diagnosticsSummary(): String {\n        val client = clients.firstOrNull {\n            it.playing && it.transportMode != TransportMode.NONE\n        } ?: return \"RTSP sem cliente\"\n\n        val transport = when (client.transportMode) {\n            TransportMode.TCP -> \"TCP\"\n            TransportMode.UDP -> \"UDP\"\n            TransportMode.NONE -> \"-\"\n        }\n        val sendMs = client.lastSendDurationNs / 1_000_000.0\n        return String.format(\n            Locale.US,\n            \"RTSP %s • fila %d/4 (pico %d) • envio %.1f ms • estouros %d\",\n            transport,\n            client.sendQueue.size,\n            client.maxQueueDepth,\n            sendMs,\n            client.totalQueueOverflows\n        )\n    }\n\n    fun url(ip: String): String = \"rtsp://${NetworkUtils.urlHost(ip)}:$port/h264\"\n""",
    "rtsp diagnostics summary",
)

rtsp = replace_once(
    rtsp,
    """        if (!client.sendQueue.offer(unit)) {\n            client.sendQueue.clear()\n            client.queueOverflows++\n            client.waitingForKeyFrame = true\n""",
    """        if (!client.sendQueue.offer(unit)) {\n            client.sendQueue.clear()\n            client.queueOverflows++\n            client.totalQueueOverflows++\n            client.waitingForKeyFrame = true\n""",
    "rtsp overflow total",
)

rtsp = replace_once(
    rtsp,
    """        } else if (client.queueOverflows > 0) {\n            client.queueOverflows--\n        }\n\n        startClientSender(client)\n""",
    """        } else if (client.queueOverflows > 0) {\n            client.queueOverflows--\n        }\n\n        client.maxQueueDepth = maxOf(client.maxQueueDepth, client.sendQueue.size)\n        startClientSender(client)\n""",
    "rtsp max queue depth",
)

rtsp = replace_once(
    rtsp,
    """                    unit.nals.forEachIndexed { index, nal ->\n                        sendNal(\n                            client,\n                            nal,\n                            unit.timestamp,\n                            index == unit.nals.lastIndex\n                        )\n                    }\n                    if (client.transportMode == TransportMode.TCP) {\n                        synchronized(client.writeLock) {\n                            client.output.flush()\n                        }\n                    }\n""",
    """                    val sendStartedNs = System.nanoTime()\n                    unit.nals.forEachIndexed { index, nal ->\n                        sendNal(\n                            client,\n                            nal,\n                            unit.timestamp,\n                            index == unit.nals.lastIndex\n                        )\n                    }\n                    if (client.transportMode == TransportMode.TCP) {\n                        synchronized(client.writeLock) {\n                            client.output.flush()\n                        }\n                    }\n                    client.lastSendDurationNs =\n                        (System.nanoTime() - sendStartedNs).coerceAtLeast(0L)\n                    client.accessUnitsSent++\n""",
    "rtsp send timing",
)

rtsp = replace_once(
    rtsp,
    """                            session.waitingForKeyFrame = true\n                            session.queueOverflows = 0\n                            session.sendQueue.clear()\n                            val count = activeCount.incrementAndGet()\n""",
    """                            session.waitingForKeyFrame = true\n                            session.queueOverflows = 0\n                            session.totalQueueOverflows = 0L\n                            session.maxQueueDepth = 0\n                            session.lastSendDurationNs = 0L\n                            session.accessUnitsSent = 0L\n                            session.sendQueue.clear()\n                            val count = activeCount.incrementAndGet()\n""",
    "rtsp reset diagnostics",
)

rtsp_path.write_text(rtsp, encoding="utf-8")

main = main_path.read_text(encoding="utf-8")
main = replace_once(
    main,
    """                            runOnUiThread {\n                                performanceText.text =\n                                    \"4K frontal • %.1f FPS reais • $route\"\n                                        .format(java.util.Locale.US, realFps)\n                            }\n""",
    """                            val networkDiagnostics = rtspServer.diagnosticsSummary()\n                            runOnUiThread {\n                                performanceText.text =\n                                    (\"4K frontal • %.1f FPS reais • $route\\n\" +\n                                        networkDiagnostics)\n                                        .format(java.util.Locale.US, realFps)\n                            }\n""",
    "main 4k diagnostics display",
)
main_path.write_text(main, encoding="utf-8")

mjpeg = mjpeg_path.read_text(encoding="utf-8")
mjpeg = replace_once(
    mjpeg,
    """            socket.tcpNoDelay = true\n            socket.sendBufferSize = 1024 * 1024\n            socket.soTimeout = 10_000\n""",
    """            socket.tcpNoDelay = true\n            // Build 45: keep the HTTP/MJPEG fallback low-latency too. A 1 MB\n            // kernel send buffer could retain too much UHD JPEG data when the\n            // receiver slows down. The producer already favors the latest frame.\n            runCatching { socket.sendBufferSize = 256 * 1024 }\n            socket.soTimeout = 10_000\n""",
    "mjpeg low latency send buffer",
)
mjpeg_path.write_text(mjpeg, encoding="utf-8")

status = ROOT / "BUILD_45_STATUS.md"
status.write_text(
    """# GOAT CAM Build 45\n\n## Correcoes confirmadas\n- Controle de bitrate H.264 4K limitado a 8-18 Mbps.\n- Alterar bitrate durante 4K nao derruba nem reabre a camera; entra no proximo inicio do stream.\n- Diagnostico visivel do RTSP: transporte TCP/UDP, fila atual/pico, tempo de envio e estouros de fila.\n- Buffer TCP do fallback HTTP/MJPEG reduzido de 1 MB para 256 KB para priorizar baixa latencia.\n- Mantidos resolucao 4K, rota nativa/fallback, bitrate automatico e qualidade da Build 44.\n\n## Objetivo do teste\nSeparar gargalo de captura/encoder de gargalo de rede/envio. Se o FPS H.264 estiver alto e a fila/tempo de envio crescer, o atraso esta depois do encoder. Se o FPS estiver baixo com fila vazia e envio rapido, o gargalo esta antes do RTSP.\n""",
    encoding="utf-8",
)

print("Build 45 stream diagnostics patch applied")
