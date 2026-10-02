package com.goatpro.ip

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RtspH265Server(
    private val port: Int = 8554,
    private val listener: Listener? = null
) {
    interface Listener {
        fun onActiveClientCountChanged(count: Int)
    }

    private enum class TransportMode { NONE, UDP, TCP }

    private class ClientSession(
        val socket: Socket,
        val output: OutputStream,
        val sessionId: String
    ) {
        val writeLock = Any()
        @Volatile var playing = false
        @Volatile var transportMode = TransportMode.NONE
        var tcpRtpChannel = 0
        var tcpRtcpChannel = 1
        var udpRtpSocket: DatagramSocket? = null
        var udpRtcpSocket: DatagramSocket? = null
        var clientRtpPort = 0
        var clientRtcpPort = 0
        var sequence = ((System.nanoTime() ushr 8) and 0xffff).toInt()
        val ssrc = (0x47510000L or (sessionId.hashCode().toLong() and 0xffffL)).toInt()

        fun close() {
            playing = false
            runCatching { udpRtpSocket?.close() }
            runCatching { udpRtcpSocket?.close() }
            runCatching { socket.close() }
        }
    }

    private val running = AtomicBoolean(false)
    private val activeCount = AtomicInteger(0)
    private val clients = CopyOnWriteArrayList<ClientSession>()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile private var latestVps: ByteArray? = null
    @Volatile private var latestSps: ByteArray? = null
    @Volatile private var latestPps: ByteArray? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptThread = Thread {
            try {
                ServerSocket(port).also { server ->
                    server.reuseAddress = true
                    serverSocket = server
                    while (running.get()) {
                        val socket = try {
                            server.accept()
                        } catch (_: SocketException) {
                            break
                        }
                        socket.tcpNoDelay = true
                        runCatching { socket.sendBufferSize = 256 * 1024 }
                        runCatching { socket.trafficClass = 0x10 }
                        Thread {
                            handleClient(socket)
                        }.apply {
                            name = "goat-rtsp-h265-client"
                            isDaemon = true
                            start()
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                running.set(false)
                runCatching { serverSocket?.close() }
                serverSocket = null
            }
        }.apply {
            name = "goat-rtsp-h265-accept"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { it.close() }
        clients.clear()
        activeCount.set(0)
        latestVps = null
        latestSps = null
        latestPps = null
        listener?.onActiveClientCountChanged(0)
    }

    fun isRunning(): Boolean = running.get()
    fun activeClientCount(): Int = activeCount.get()
    fun url(ip: String): String = "rtsp://" + ip + ":" + port + "/h265"

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
                32 -> latestVps = nal.copyOf()
                33 -> latestSps = nal.copyOf()
                34 -> latestPps = nal.copyOf()
            }
        }

        if (codecConfig) return

        val sendNals = ArrayList<ByteArray>()
        val hasVps = nals.any { nalType(it) == 32 }
        val hasSps = nals.any { nalType(it) == 33 }
        val hasPps = nals.any { nalType(it) == 34 }

        if (keyFrame) {
            if (!hasVps) latestVps?.let { sendNals.add(it) }
            if (!hasSps) latestSps?.let { sendNals.add(it) }
            if (!hasPps) latestPps?.let { sendNals.add(it) }
        }
        sendNals.addAll(nals)

        val timestamp = ((presentationTimeUs * 90L) / 1000L) and 0xffffffffL
        val targets = clients.filter { it.playing && it.transportMode != TransportMode.NONE }
        if (targets.isEmpty()) return

        targets.forEach { client ->
            try {
                sendNals.forEachIndexed { index, nal ->
                    sendNal(client, nal, timestamp, index == sendNals.lastIndex)
                }
                if (client.transportMode == TransportMode.TCP) {
                    synchronized(client.writeLock) {
                        client.output.flush()
                    }
                }
            } catch (_: Exception) {
                removeClient(client)
            }
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
            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)
            )
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
                    "OPTIONS" -> respond(
                        session,
                        cseq,
                        extraHeaders = listOf(
                            "Public: OPTIONS, DESCRIBE, SETUP, PLAY, GET_PARAMETER, TEARDOWN"
                        )
                    )

                    "DESCRIBE" -> {
                        val localIp = socket.localAddress.hostAddress ?: "0.0.0.0"
                        respond(
                            session,
                            cseq,
                            extraHeaders = listOf(
                                "Content-Base: " + requestUri.trimEnd('/') + "/",
                                "Content-Type: application/sdp"
                            ),
                            body = buildSdp(localIp)
                        )
                    }

                    "SETUP" -> {
                        configureTransport(session, headers["transport"].orEmpty())
                        val transportResponse = when (session.transportMode) {
                            TransportMode.TCP ->
                                "RTP/AVP/TCP;unicast;interleaved=" +
                                    session.tcpRtpChannel + "-" + session.tcpRtcpChannel +
                                    ";ssrc=" + ssrcHex(session.ssrc)
                            TransportMode.UDP -> {
                                val rtp = session.udpRtpSocket?.localPort ?: 0
                                val rtcp = session.udpRtcpSocket?.localPort ?: (rtp + 1)
                                "RTP/AVP/UDP;unicast;client_port=" +
                                    session.clientRtpPort + "-" + session.clientRtcpPort +
                                    ";server_port=" + rtp + "-" + rtcp +
                                    ";ssrc=" + ssrcHex(session.ssrc)
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
                                    "Session: " + session.sessionId + ";timeout=60",
                                    "Transport: " + transportResponse
                                )
                            )
                        }
                    }

                    "PLAY" -> {
                        if (!session.playing) {
                            session.playing = true
                            listener?.onActiveClientCountChanged(
                                activeCount.incrementAndGet()
                            )
                        }
                        respond(
                            session,
                            cseq,
                            extraHeaders = listOf(
                                "Session: " + session.sessionId + ";timeout=60",
                                "Range: npt=0.000-"
                            )
                        )
                    }

                    "GET_PARAMETER" -> respond(
                        session,
                        cseq,
                        extraHeaders = listOf(
                            "Session: " + session.sessionId + ";timeout=60"
                        )
                    )

                    "TEARDOWN" -> {
                        respond(
                            session,
                            cseq,
                            extraHeaders = listOf(
                                "Session: " + session.sessionId
                            )
                        )
                        break
                    }

                    else -> respond(
                        session,
                        cseq,
                        status = "405 Method Not Allowed"
                    )
                }
            }
        } catch (_: Exception) {
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
            val match = Regex(
                "interleaved=(\\d+)-(\\d+)",
                RegexOption.IGNORE_CASE
            ).find(transport)
            session.tcpRtpChannel =
                match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            session.tcpRtcpChannel =
                match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1
            session.transportMode = TransportMode.TCP
            return
        }

        val ports = Regex(
            "client_port=(\\d+)(?:-(\\d+))?",
            RegexOption.IGNORE_CASE
        ).find(transport)
        val rtpPort =
            ports?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return
        val rtcpPort =
            ports.groupValues.getOrNull(2)?.toIntOrNull() ?: (rtpPort + 1)

        session.clientRtpPort = rtpPort
        session.clientRtcpPort = rtcpPort
        session.udpRtpSocket = DatagramSocket()
        session.udpRtcpSocket = DatagramSocket()
        session.transportMode = TransportMode.UDP
    }

    private fun buildSdp(localIp: String): String =
        "v=0\r\n" +
            "o=- 0 0 IN IP4 " + localIp + "\r\n" +
            "s=GOAT Cam HEVC 8K\r\n" +
            "c=IN IP4 " + localIp + "\r\n" +
            "t=0 0\r\n" +
            "a=control:*\r\n" +
            "m=video 0 RTP/AVP 96\r\n" +
            "a=rtpmap:96 H265/90000\r\n" +
            "a=control:trackID=0\r\n"

    private fun respond(
        client: ClientSession,
        cseq: String,
        status: String = "200 OK",
        extraHeaders: List<String> = emptyList(),
        body: String? = null
    ) {
        val bodyBytes = body?.toByteArray(Charsets.US_ASCII)
        val response = buildString {
            append("RTSP/1.0 ").append(status).append("\r\n")
            append("CSeq: ").append(cseq).append("\r\n")
            append("Server: GOAT-CAM-HEVC/0.13\r\n")
            extraHeaders.forEach { append(it).append("\r\n") }
            if (bodyBytes != null) {
                append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            }
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)

        synchronized(client.writeLock) {
            client.output.write(response)
            if (bodyBytes != null) client.output.write(bodyBytes)
            client.output.flush()
        }
    }

    private fun removeClient(client: ClientSession) {
        val removed = clients.remove(client)
        if (!removed) return
        if (client.playing) {
            client.playing = false
            val count = activeCount.updateAndGet { value ->
                if (value > 0) value - 1 else 0
            }
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
        if (nal.size < 2) return

        val maxPayload = 1200
        if (nal.size <= maxPayload) {
            sendRtpPacket(
                client,
                makeRtpPacket(client, nal, timestamp, markerForNal)
            )
            return
        }

        val originalType = nalType(nal)
        val payloadHeader0 =
            (nal[0].toInt() and 0x81) or (49 shl 1)
        val payloadHeader1 = nal[1].toInt() and 0xff

        var offset = 2
        var first = true
        while (offset < nal.size) {
            val chunk = minOf(maxPayload - 3, nal.size - offset)
            val end = offset + chunk >= nal.size
            val fuHeader =
                (if (first) 0x80 else 0) or
                    (if (end) 0x40 else 0) or
                    (originalType and 0x3f)

            val payload = ByteArray(chunk + 3)
            payload[0] = payloadHeader0.toByte()
            payload[1] = payloadHeader1.toByte()
            payload[2] = fuHeader.toByte()
            System.arraycopy(nal, offset, payload, 3, chunk)

            sendRtpPacket(
                client,
                makeRtpPacket(
                    client,
                    payload,
                    timestamp,
                    markerForNal && end
                )
            )

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
                socket.send(
                    DatagramPacket(
                        packet,
                        packet.size,
                        client.socket.inetAddress,
                        client.clientRtpPort
                    )
                )
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
                if (
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
                val end =
                    if (index + 1 < starts.size) {
                        starts[index + 1].first
                    } else {
                        data.size
                    }
                if (end > start) {
                    result.add(data.copyOfRange(start, end))
                }
            }
            return result
        }

        val prefixed = ArrayList<ByteArray>()
        var pos = 0
        while (pos + 4 <= data.size) {
            val length =
                ((data[pos].toInt() and 0xff) shl 24) or
                    ((data[pos + 1].toInt() and 0xff) shl 16) or
                    ((data[pos + 2].toInt() and 0xff) shl 8) or
                    (data[pos + 3].toInt() and 0xff)
            pos += 4
            if (length <= 0 || pos + length > data.size) {
                prefixed.clear()
                break
            }
            prefixed.add(data.copyOfRange(pos, pos + length))
            pos += length
        }
        if (prefixed.isNotEmpty() && pos == data.size) return prefixed
        return listOf(data)
    }

    private fun nalType(nal: ByteArray): Int =
        if (nal.size < 2) -1 else (nal[0].toInt() ushr 1) and 0x3f

    private fun ssrcHex(ssrc: Int): String =
        String.format(Locale.US, "%08X", ssrc)
}
