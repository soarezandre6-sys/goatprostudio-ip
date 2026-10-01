package com.goatpro.ip

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class DiscoveryResponder(
    private val httpPort: Int = 8080,
    private val isStreaming: () -> Boolean,
    private val isAudioEnabled: () -> Boolean
) {
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread {
            try {
                DatagramSocket(null).also { sock ->
                    socket = sock
                    sock.reuseAddress = true
                    sock.broadcast = true
                    sock.bind(java.net.InetSocketAddress(DISCOVERY_PORT))
                    val buffer = ByteArray(1024)
                    while (running.get()) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        sock.receive(packet)
                        val request = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                        if (request != DISCOVERY_REQUEST) continue

                        val ip = NetworkUtils.localIpv4() ?: continue
                        val response = buildString {
                            append("GOAT_PRO_IP_V1")
                            append("|ip=").append(ip)
                            append("|port=").append(httpPort)
                            append("|video=/video")
                            append("|audio=/audio.pcm")
                            append("|streaming=").append(if (isStreaming()) "1" else "0")
                            append("|audioEnabled=").append(if (isAudioEnabled()) "1" else "0")
                            append("|version=0.5.0-alpha")
                        }.toByteArray(Charsets.UTF_8)

                        val reply = DatagramPacket(
                            response,
                            response.size,
                            packet.address ?: InetAddress.getByName(ip),
                            packet.port
                        )
                        sock.send(reply)
                    }
                }
            } catch (_: Exception) {
                // Socket is closed during normal shutdown.
            } finally {
                running.set(false)
                runCatching { socket?.close() }
                socket = null
            }
        }.apply {
            name = "goat-ip-discovery"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { socket?.close() }
        socket = null
        worker = null
    }

    companion object {
        const val DISCOVERY_PORT = 37991
        const val DISCOVERY_REQUEST = "GOAT_PRO_IP_DISCOVER_V1"
    }
}
