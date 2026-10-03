package com.goatpro.ip

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal RTSP/RTP H.264 server for local-network use.
 *
 * Supports both RTP-over-UDP and RTP interleaved over the RTSP TCP socket.
 * Audio stays on the existing /audio.pcm endpoint for this first H.264 test.
 */
class RtspH264Server(
    private val port: Int = 8554,
    private val listener: Listener? = null
) {
    interface Listener {
        fun onActiveClientCountChanged(count: Int)
    }

    private enum class TransportMode { NONE, UDP, TCP }

    private data class PendingAccessUnit(
        val nals: List<ByteArray>,
        val timestamp: Long,
        val keyFrame: Boolean
    )

    private class ClientSession(
        val socket: Socket,
        val output: OutputStream,
        val sessionId: String
    ) {
        val writeLock = Any()
        // Build 44: Live View must prefer current frames over backlog.
        // Four access units at 30 FPS cap app-side queueing near ~130 ms.
        val sendQueue = ArrayBlockingQueue<PendingAccessUnit>(4)
        val senderRunning = AtomicBoolean(false)

        @Volatile
        var senderThread: Thread? = null

        @Volatile
        var waitingForKeyFrame = true

        @Volatile
        var queueOverflows = 0

        @Volatile
        var totalQueueOverflows = 0L

        @Volatile
        var maxQueueDepth = 0

        @Volatile
        var lastSendDurationNs = 0L

        @Volatile
        var accessUnitsSent = 0L

        @Volatile
        var playing = false

        @Volatile
        var transportMode = TransportMode.NONE

        var tcpRtpChannel = 0
        var tcpRtcpChannel = 1
        var udpRtpSocket: DatagramSocket? = null
        var udpRtcpSocket: DatagramSocket? = null
        var clientRtpPort = 0
        var clientRtcpPort = 0
        var sequence = ((System.nanoTime() ushr 8) and 0xffff).toInt()
        val ssrc = (0x47500000L or (sessionId.hashCode().toLong() and 0xffffL)).toInt()

        fun close() {
            playing = false
            senderRunning.set(false)
            runCatching { senderThread?.interrupt() }
            senderThread = null
            sendQueue.clear()
            runCatching { udpRtpSocket?.close() }
            runCatching { udpRtcpSocket?.close() }
            runCatching { socket.close() }
        }
    }

    private val running = AtomicBoolean(false)
    private val activeCount = AtomicInteger(0)
    private val clients = CopyOnWriteArrayList<ClientSession>()

    private val serverSockets = CopyOnWriteArrayList<ServerSocket>()
    private val acceptThreads = CopyOnWriteArrayList<Thread>()

    @Volatile
    private var latestSps: ByteArray? = null

    @Volatile
    private var latestPps: ByteArray? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return

        val boundServers = NetworkUtils.localInetAddresses().mapNotNull { address ->
            runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(address, port))
                }
            }.getOrNull()
        }

        if (boundServers.isEmpty()) {
            running.set(false)
            return
        }

        serverSockets.addAll(boundServers)
        boundServers.forEachIndexed { index, server ->
            Thread {
                try {
                    while (running.get() && !server.isClosed) {
                        val socket = try {
                            server.accept()
                        } catch (_: SocketException) {
                            break
                        }
                        socket.tcpNoDelay = true
                        // Build 44: 2 MB could hide almost a second of UHD H.264
                        // in the TCP kernel buffer. Keep it small for live monitoring.
                        runCatching { socket.sendBufferSize = 256 * 1024 }
                        runCatching { socket.trafficClass = 0x10 }
                        Thread {
                            handleClient(socket)
                        }.apply {
                            name = "goat-rtsp-client"
                            isDaemon = true
                            start()
                        }
                    }
                } catch (_: Exception) {
                    // One address family may disappear while the other remains valid.
                } finally {
                    runCatching { server.close() }
                    serverSockets.remove(server)
                }
            }.apply {
                name = "goat-rtsp-accept-$index"
                isDaemon = true
                acceptThreads += this
                start()
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        serverSockets.forEach { server -> runCatching { server.close() } }
        serverSockets.clear()
        acceptThreads.forEach { thread -> runCatching { thread.interrupt() } }
        acceptThreads.clear()
        clients.forEach { it.close() }
        clients.clear()
        activeCount.set(0)
        listener?.onActiveClientCountChanged(0)
    }

    fun isRunning(): Boolean = running.get()

    fun activeClientCount(): Int = activeCount.get()

    fun diagnosticsSummary(): String {
        val client = clients.firstOrNull {
            it.playing && it.transportMode != TransportMode.NONE
        } ?: return "RTSP sem cliente"

        val transport = when (client.transportMode) {
            TransportMode.TCP -> "TCP"
            TransportMode.UDP -> "UDP"
            TransportMode.NONE -> "-"
        }
        val sendMs = client.lastSendDurationNs / 1_000_000.0
        return String.format(
            Locale.US,
            "RTSP %s • fila %d/4 (pico %d) • envio %.1f ms • estouros %d",
            transport,
            client.sendQueue.size,
            client.maxQueueDepth,
            sendMs,
            client.totalQueueOverflows
        )
    }

    fun url(ip: String): String = "rtsp://${NetworkUtils.urlHost(ip)}:$port/h264"

    fun onAccessUnit(
        data: ByteArray,
        presentationTimeUs: Long,
        keyFrame: Boolean,
        codecConfig: Boolean
    ) {
        val nals = splitNals(data)
        if (nals.isEmpty()) return

        nals.forEach { nal ->
            when (nalType(nal)) {
                7 -> latestSps = nal.copyOf()
                8 -> latestPps = nal.copyOf()
            }
        }

        if (codecConfig) return

        val sendNals = ArrayList<ByteArray>()
        val hasSps = nals.any { nalType(it) == 7 }
        val hasPps = nals.any { nalType(it) == 8 }

        if (keyFrame) {
            if (!hasSps) latestSps?.let { sendNals.add(it) }
            if (!hasPps) latestPps?.let { sendNals.add(it) }
        }
        sendNals.addAll(nals)

        val timestamp = ((presentationTimeUs * 90L) / 1000L) and 0xffffffffL
        val targets = clients.filter { it.playing && it.transportMode != TransportMode.NONE }
        if (targets.isEmpty()) return

        val pending = PendingAccessUnit(
            nals = sendNals.map { it.copyOf() },
            timestamp = timestamp,
            keyFrame = keyFrame
        )
        targets.forEach { client ->
            enqueueClientAccessUnit(client, pending)
        }
    }

    private fun enqueueClientAccessUnit(client: ClientSession, unit: PendingAccessUnit) {
        if (!client.playing || client.transportMode == TransportMode.NONE) return

        if (client.waitingForKeyFrame && !unit.keyFrame) return
        if (unit.keyFrame) client.waitingForKeyFrame = false

        if (!client.sendQueue.offer(unit)) {
            client.sendQueue.clear()
            client.queueOverflows++
            client.totalQueueOverflows++
            client.waitingForKeyFrame = true

            if (unit.keyFrame) {
                client.waitingForKeyFrame = false
                client.sendQueue.offer(unit)
            }

            // Do not tear down the Studio connection on a short 4K burst.
            // Keep the session alive and resume from the next IDR/keyframe.
            if (client.queueOverflows > 12) {
                client.queueOverflows = 12
            }
        } else if (client.queueOverflows > 0) {
            client.queueOverflows--
        }

        client.maxQueueDepth = maxOf(client.maxQueueDepth, client.sendQueue.size)
        startClientSender(client)
    }

    private fun startClientSender(client: ClientSession) {
        if (!client.senderRunning.compareAndSet(false, true)) return
        client.senderThread = Thread {
            try {
                while (
                    running.get() &&
                    client.senderRunning.get() &&
                    client.playing &&
                    !client.socket.isClosed
                ) {
                    val unit = try {
                        client.sendQueue.poll(250, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        null
                    } ?: continue

                    val sendStartedNs = System.nanoTime()
                    unit.nals.forEachIndexed { index, nal ->
                        sendNal(
                            client,
                            nal,
                            unit.timestamp,
                            index == unit.nals.lastIndex
                        )
                    }
                    if (client.transportMode == TransportMode.TCP) {
                        synchronized(client.writeLock) {
                            client.output.flush()
                        }
                    }
                    client.lastSendDurationNs =
                        (System.nanoTime() - sendStartedNs).coerceAtLeast(0L)
                    client.accessUnitsSent++
                }
            } catch (_: Exception) {
                removeClient(client)
            } finally {
                client.senderRunning.set(false)
            }
        }.apply {
            name = "goat-rtsp-sender"
            isDaemon = true
            start()
        }
    }

    private fun handleClient(socket: Socket) {
        val session = ClientSession(
            socket,
            socket.getOutputStream(),
            ((System.nanoTime() and 0x7fffffffL).toString(16))
        )
        clients.add(session)

        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            while (running.get() && !socket.isClosed) {
                val requestLine = reader.readLine() ?: break
                if (requestLine.isBlank()) continue

                val parts = requestLine.split(' ')
                if (parts.size < 2) break
                val method = parts[0].uppercase(Locale.US)
                val requestUri = parts[1]

                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                            line.substring(colon + 1).trim()
                    }
                }

                val cseq = headers["cseq"] ?: "1"

                when (method) {
                    "OPTIONS" -> {
                        respond(
                            session,
                            cseq,
                            extraHeaders = listOf(
                                "Public: OPTIONS, DESCRIBE, SETUP, PLAY, GET_PARAMETER, TEARDOWN"
                            )
                        )
                    }

                    "DESCRIBE" -> {
                        val localIp = socket.localAddress.hostAddress ?: "0.0.0.0"
                        val base = requestUri.trimEnd('/') + "/"
                        val sdp = buildSdp(localIp)
                        respond(
                            session,
                            cseq,
                            extraHeaders = listOf(
                                "Content-Base: $base",
                                "Content-Type: application/sdp"
                            ),
                            body = sdp
                        )
                    }

                    "SETUP" -> {
                        val transport = headers["transport"].orEmpty()
                        configureTransport(session, transport)

                        val transportResponse = when (session.transportMode) {
                            TransportMode.TCP ->
                                "RTP/AVP/TCP;unicast;interleaved=${session.tcpRtpChannel}-${session.tcpRtcpChannel};ssrc=${ssrcHex(session.ssrc)}"
                            TransportMode.UDP -> {
                                val rtp = session.udpRtpSocket?.localPort ?: 0
                                val rtcp = session.udpRtcpSocket?.localPort ?: (rtp + 1)
                                "RTP/AVP/UDP;unicast;client_port=${session.clientRtpPort}-${session.clientRtcpPort};server_port=$rtp-$rtcp;ssrc=${ssrcHex(session.ssrc)}"
                            }
                            else -> ""
                        }

                        if (session.transportMode == TransportMode.NONE) {
                            respond(session, cseq, status = "461 Unsupported Transport")
                        } else {
                            respond(
                                session,
                                cseq,
                                extraHeaders = listOf(
                                    "Session: ${session.sessionId};timeout=60",
                                    "Transport: $transportResponse"
                                )
                            )
                        }
                    }

                    "PLAY" -> {
                        if (!session.playing) {
                            session.playing = true
                            session.waitingForKeyFrame = true
                            session.queueOverflows = 0
                            session.totalQueueOverflows = 0L
                            session.maxQueueDepth = 0
                            session.lastSendDurationNs = 0L
                            session.accessUnitsSent = 0L
                            session.sendQueue.clear()
                            val count = activeCount.incrementAndGet()
                            listener?.onActiveClientCountChanged(count)
                        }
                        respond(
                            session,
                            cseq,
                            extraHeaders = listOf(
                                "Session: ${session.sessionId};timeout=60",
                                "Range: npt=0.000-"
                            )
                        )
                    }

                    "GET_PARAMETER" -> {
                        respond(
                            session,
                            cseq,
                            extraHeaders = listOf("Session: ${session.sessionId};timeout=60")
                        )
                    }

                    "TEARDOWN" -> {
                        respond(
                            session,
                            cseq,
                            extraHeaders = listOf("Session: ${session.sessionId}")
                        )
                        break
                    }

                    else -> respond(session, cseq, status = "405 Method Not Allowed")
                }
            }
        } catch (_: Exception) {
            // Client disconnected.
        } finally {
            removeClient(session)
        }
    }

    private fun configureTransport(session: ClientSession, transport: String) {
        runCatching { session.udpRtpSocket?.close() }
        runCatching { session.udpRtcpSocket?.close() }
        session.udpRtpSocket = null
        session.udpRtcpSocket = null
        session.transportMode = TransportMode.NONE

        if (transport.contains("RTP/AVP/TCP", ignoreCase = true)) {
            val match = Regex("interleaved=(\\d+)-(\\d+)", RegexOption.IGNORE_CASE)
                .find(transport)
            session.tcpRtpChannel = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            session.tcpRtcpChannel = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1
            session.transportMode = TransportMode.TCP
            return
        }

        val ports = Regex("client_port=(\\d+)(?:-(\\d+))?", RegexOption.IGNORE_CASE)
            .find(transport)
        val rtpPort = ports?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return
        val rtcpPort = ports.groupValues.getOrNull(2)?.toIntOrNull() ?: (rtpPort + 1)

        session.clientRtpPort = rtpPort
        session.clientRtcpPort = rtcpPort
        session.udpRtpSocket = DatagramSocket().apply {
            runCatching { sendBufferSize = 256 * 1024 }
        }
        session.udpRtcpSocket = DatagramSocket().apply {
            runCatching { sendBufferSize = 64 * 1024 }
        }
        session.transportMode = TransportMode.UDP
    }

    private fun buildSdp(localIp: String): String {
    val address = NetworkUtils.sdpAddress(localIp)
    val family = if (address.contains(':')) "IP6" else "IP4"
    return "v=0\r\n" +
        "o=- 0 0 IN $family $address\r\n" +
        "s=GOAT PRO IP H264\r\n" +
        "c=IN $family $address\r\n" +
        "t=0 0\r\n" +
        "a=control:*\r\n" +
        "m=video 0 RTP/AVP 96\r\n" +
        "a=rtpmap:96 H264/90000\r\n" +
        "a=fmtp:96 packetization-mode=1\r\n" +
        "a=control:trackID=0\r\n"
}

private fun respond(
        client: ClientSession,
        cseq: String,
        status: String = "200 OK",
        extraHeaders: List<String> = emptyList(),
        body: String? = null
    ) {
        val bodyBytes = body?.toByteArray(Charsets.US_ASCII)
        val text = buildString {
            append("RTSP/1.0 ").append(status).append("\r\n")
            append("CSeq: ").append(cseq).append("\r\n")
            append("Server: GOAT-PRO-IP/0.8\r\n")
            extraHeaders.forEach { append(it).append("\r\n") }
            if (bodyBytes != null) {
                append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            }
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)

        synchronized(client.writeLock) {
            client.output.write(text)
            if (bodyBytes != null) client.output.write(bodyBytes)
            client.output.flush()
        }
    }

    private fun removeClient(client: ClientSession) {
        val removed = clients.remove(client)
        if (!removed) return

        if (client.playing) {
            client.playing = false
            val count = activeCount.updateAndGet { value -> if (value > 0) value - 1 else 0 }
            listener?.onActiveClientCountChanged(count)
        }
        client.close()
    }

    private fun sendNal(
        client: ClientSession,
        nal: ByteArray,
        timestamp: Long,
        markerForNal: Boolean
    ) {
        if (nal.isEmpty()) return

        val maxPayload = 1400
        if (nal.size <= maxPayload) {
            val packet = makeRtpPacket(
                client,
                nal,
                timestamp,
                markerForNal
            )
            sendRtpPacket(client, packet)
            return
        }

        val nalHeader = nal[0].toInt() and 0xff
        val fuIndicator = (nalHeader and 0xe0) or 28
        val nalType = nalHeader and 0x1f

        var offset = 1
        var first = true
        while (offset < nal.size) {
            val chunk = minOf(maxPayload - 2, nal.size - offset)
            val end = offset + chunk >= nal.size
            val fuHeader =
                (if (first) 0x80 else 0) or
                    (if (end) 0x40 else 0) or
                    nalType

            val payload = ByteArray(chunk + 2)
            payload[0] = fuIndicator.toByte()
            payload[1] = fuHeader.toByte()
            System.arraycopy(nal, offset, payload, 2, chunk)

            val packet = makeRtpPacket(
                client,
                payload,
                timestamp,
                markerForNal && end
            )
            sendRtpPacket(client, packet)

            first = false
            offset += chunk
        }
    }

    private fun makeRtpPacket(
        client: ClientSession,
        payload: ByteArray,
        timestamp: Long,
        marker: Boolean
    ): ByteArray {
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0x80.toByte()
        packet[1] = ((if (marker) 0x80 else 0) or 96).toByte()

        val seq = client.sequence and 0xffff
        client.sequence = (client.sequence + 1) and 0xffff
        packet[2] = (seq ushr 8).toByte()
        packet[3] = seq.toByte()

        val ts = timestamp and 0xffffffffL
        packet[4] = (ts ushr 24).toByte()
        packet[5] = (ts ushr 16).toByte()
        packet[6] = (ts ushr 8).toByte()
        packet[7] = ts.toByte()

        val ssrc = client.ssrc
        packet[8] = (ssrc ushr 24).toByte()
        packet[9] = (ssrc ushr 16).toByte()
        packet[10] = (ssrc ushr 8).toByte()
        packet[11] = ssrc.toByte()

        System.arraycopy(payload, 0, packet, 12, payload.size)
        return packet
    }

    private fun sendRtpPacket(client: ClientSession, packet: ByteArray) {
        when (client.transportMode) {
            TransportMode.UDP -> {
                val socket = client.udpRtpSocket ?: return
                val target = DatagramPacket(
                    packet,
                    packet.size,
                    client.socket.inetAddress,
                    client.clientRtpPort
                )
                socket.send(target)
            }

            TransportMode.TCP -> {
                synchronized(client.writeLock) {
                    client.output.write(0x24)
                    client.output.write(client.tcpRtpChannel and 0xff)
                    client.output.write((packet.size ushr 8) and 0xff)
                    client.output.write(packet.size and 0xff)
                    client.output.write(packet)
                }
            }

            TransportMode.NONE -> Unit
        }
    }

    private fun splitNals(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    starts.add(i to 3)
                    i += 3
                    continue
                }
                if (i + 3 < data.size &&
                    data[i + 2] == 0.toByte() &&
                    data[i + 3] == 1.toByte()
                ) {
                    starts.add(i to 4)
                    i += 4
                    continue
                }
            }
            i++
        }

        if (starts.isNotEmpty()) {
            val result = ArrayList<ByteArray>()
            starts.forEachIndexed { index, pair ->
                val start = pair.first + pair.second
                val end = if (index + 1 < starts.size) starts[index + 1].first else data.size
                if (end > start) result.add(data.copyOfRange(start, end))
            }
            return result
        }

        // Some MediaCodec implementations return AVCC length-prefixed NAL units.
        val avcc = ArrayList<ByteArray>()
        var pos = 0
        while (pos + 4 <= data.size) {
            val length =
                ((data[pos].toInt() and 0xff) shl 24) or
                    ((data[pos + 1].toInt() and 0xff) shl 16) or
                    ((data[pos + 2].toInt() and 0xff) shl 8) or
                    (data[pos + 3].toInt() and 0xff)
            pos += 4
            if (length <= 0 || pos + length > data.size) {
                avcc.clear()
                break
            }
            avcc.add(data.copyOfRange(pos, pos + length))
            pos += length
        }
        if (avcc.isNotEmpty() && pos == data.size) return avcc

        return listOf(data)
    }

    private fun nalType(nal: ByteArray): Int =
        if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1f

    private fun ssrcHex(ssrc: Int): String =
        String.format(Locale.US, "%08X", ssrc)
}
