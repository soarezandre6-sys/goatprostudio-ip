from pathlib import Path
import re

ROOT = Path('.')
JAVA = ROOT / 'app/src/main/java/com/goatpro/ip'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'Build37 patch: trecho nao encontrado: {label}')
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f'Build37 patch: regex {label} alterou {count} trechos')
    return updated

# 1) NetworkUtils: IPv4 + IPv6 + formatacao correta de URL.
network = JAVA / 'NetworkUtils.kt'
network.write_text('''package com.goatpro.ip

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {
    private fun activeAddresses(): List<InetAddress> {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { iface ->
                    val name = (iface.name ?: "") + " " + (iface.displayName ?: "")
                    if (name.contains("wlan", ignoreCase = true) ||
                        name.contains("wifi", ignoreCase = true)) 0 else 1
                }

            interfaces.flatMap { iface ->
                iface.inetAddresses.toList().filter { address ->
                    !address.isLoopbackAddress &&
                        !address.isAnyLocalAddress &&
                        !address.isMulticastAddress
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun localInetAddresses(): List<InetAddress> {
        val all = activeAddresses()
        val ipv4 = all.filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?: all.filterIsInstance<Inet4Address>().firstOrNull()

        val ipv6Candidates = all.filterIsInstance<Inet6Address>()
        val ipv6 = ipv6Candidates.firstOrNull { !it.isLinkLocalAddress }
            ?: ipv6Candidates.firstOrNull()

        return listOfNotNull(ipv4, ipv6)
    }

    fun localIpv4(): String? =
        localInetAddresses().filterIsInstance<Inet4Address>()
            .firstOrNull()?.hostAddress

    fun localIpv6(): String? =
        localInetAddresses().filterIsInstance<Inet6Address>()
            .firstOrNull()?.hostAddress

    fun urlHost(address: String): String {
        if (!address.contains(':')) return address
        val escapedScope = address.replace("%", "%25")
        return "[$escapedScope]"
    }

    fun sdpAddress(address: String): String = address.substringBefore('%')
}
''', encoding='utf-8')

# 2) Discovery: manter compatibilidade IPv4 e anunciar IPv6 tambem.
discovery = JAVA / 'DiscoveryResponder.kt'
discovery.write_text('''package com.goatpro.ip

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

class DiscoveryResponder(
    private val httpPort: Int = 8080,
    private val isStreaming: () -> Boolean,
    private val isAudioEnabled: () -> Boolean,
    private val rtspCodec: () -> String = { "H264" }
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
                    sock.bind(InetSocketAddress(DISCOVERY_PORT))
                    val buffer = ByteArray(1024)
                    while (running.get()) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        sock.receive(packet)
                        val request = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                        if (request != DISCOVERY_REQUEST) continue

                        val ipv4 = NetworkUtils.localIpv4()
                        val ipv6 = NetworkUtils.localIpv6()
                        val primary = ipv4 ?: ipv6 ?: continue
                        val response = buildString {
                            append("GOAT_PRO_IP_V1")
                            append("|ip=").append(primary)
                            append("|ipv4=").append(ipv4.orEmpty())
                            append("|ipv6=").append(ipv6.orEmpty())
                            append("|port=").append(httpPort)
                            append("|video=/video")
                            append("|rtspPort=8554")
                            append("|h264=/h264")
                            append("|h265=/h265")
                            append("|rtspCodec=").append(rtspCodec())
                            append("|audio=/audio.pcm")
                            append("|streaming=").append(if (isStreaming()) "1" else "0")
                            append("|audioEnabled=").append(if (isAudioEnabled()) "1" else "0")
                            append("|version=1.1.0")
                        }.toByteArray(Charsets.UTF_8)

                        val reply = DatagramPacket(
                            response,
                            response.size,
                            packet.address,
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
''', encoding='utf-8')

# 3) HTTP MJPEG: listeners separados IPv4/IPv6 na mesma porta.
mjpeg_path = JAVA / 'MjpegServer.kt'
mjpeg = mjpeg_path.read_text(encoding='utf-8')
mjpeg = replace_once(
    mjpeg,
    'import java.net.ServerSocket\n',
    'import java.net.InetSocketAddress\nimport java.net.ServerSocket\n',
    'Mjpeg import InetSocketAddress'
)
mjpeg = replace_once(
    mjpeg,
    '    private var serverThread: Thread? = null\n    private var serverSocket: ServerSocket? = null\n',
    '    private val serverThreads = CopyOnWriteArrayList<Thread>()\n    private val serverSockets = CopyOnWriteArrayList<ServerSocket>()\n',
    'Mjpeg server fields'
)
mjpeg = regex_once(
    mjpeg,
    r'    fun start\(\) \{.*?\n    \}\n\n    private fun serve\(socket: Socket\) \{',
    '''    fun start() {
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
                        val socket = server.accept()
                        clients += socket
                        Thread { serve(socket) }.apply {
                            name = "goat-ip-client"
                            isDaemon = true
                            start()
                        }
                    }
                } catch (_: Exception) {
                    // stop() closes accept(); individual address failures do not stop the other stack.
                } finally {
                    runCatching { server.close() }
                    serverSockets.remove(server)
                }
            }.apply {
                name = "goat-ip-server-$index"
                isDaemon = true
                serverThreads += this
                start()
            }
        }
    }

    private fun serve(socket: Socket) {''',
    'Mjpeg start dual stack'
)
mjpeg = regex_once(
    mjpeg,
    r'    fun stop\(\) \{.*?\n    \}\n\n    fun videoClientCount\(\): Int',
    '''    fun stop() {
        if (!running.compareAndSet(true, false)) return
        serverSockets.forEach { server -> runCatching { server.close() } }
        serverSockets.clear()
        serverThreads.forEach { thread -> runCatching { thread.interrupt() } }
        serverThreads.clear()
        closeAllClients()
        latestFrame.set(null)
        latestAudio.set(null)
    }

    fun videoClientCount(): Int''',
    'Mjpeg stop dual stack'
)
mjpeg_path.write_text(mjpeg, encoding='utf-8')

# 4) RTSP: listeners IPv4/IPv6 + URL com colchetes + SDP IP6.
rtsp_path = JAVA / 'RtspH264Server.kt'
rtsp = rtsp_path.read_text(encoding='utf-8')
rtsp = replace_once(
    rtsp,
    'import java.net.DatagramSocket\nimport java.net.ServerSocket\n',
    'import java.net.DatagramSocket\nimport java.net.InetSocketAddress\nimport java.net.ServerSocket\n',
    'RTSP import InetSocketAddress'
)
rtsp = replace_once(
    rtsp,
    '    private var serverSocket: ServerSocket? = null\n    private var acceptThread: Thread? = null\n',
    '    private val serverSockets = CopyOnWriteArrayList<ServerSocket>()\n    private val acceptThreads = CopyOnWriteArrayList<Thread>()\n',
    'RTSP server fields'
)
rtsp = regex_once(
    rtsp,
    r'    fun start\(\) \{.*?\n    \}\n\n    fun stop\(\) \{',
    '''    fun start() {
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
                        runCatching { socket.sendBufferSize = 2 * 1024 * 1024 }
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

    fun stop() {''',
    'RTSP start dual stack'
)
rtsp = regex_once(
    rtsp,
    r'    fun stop\(\) \{.*?\n    \}\n\n    fun isRunning\(\): Boolean',
    '''    fun stop() {
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

    fun isRunning(): Boolean''',
    'RTSP stop dual stack'
)
rtsp = replace_once(
    rtsp,
    '    fun url(ip: String): String = "rtsp://$ip:$port/h264"\n',
    '    fun url(ip: String): String = "rtsp://${NetworkUtils.urlHost(ip)}:$port/h264"\n',
    'RTSP IPv6 URL'
)
rtsp = regex_once(
    rtsp,
    r'    private fun buildSdp\(localIp: String\): String =\n        "v=0\\r\\n" \+\n            "o=- 0 0 IN IP4 \$localIp\\r\\n" \+\n            "s=GOAT PRO IP H264\\r\\n" \+\n            "c=IN IP4 \$localIp\\r\\n" \+',
    '''    private fun buildSdp(localIp: String): String {
        val address = NetworkUtils.sdpAddress(localIp)
        val family = if (address.contains(':')) "IP6" else "IP4"
        return "v=0\\r\\n" +
            "o=- 0 0 IN $family $address\\r\\n" +
            "s=GOAT PRO IP H264\\r\\n" +
            "c=IN $family $address\\r\\n" +''',
    'RTSP SDP IP family'
)
# Fecha bloco buildSdp convertido de expression body para function body.
rtsp = replace_once(
    rtsp,
    '            "a=control:trackID=0\\r\\n"\n\n    private fun respond(',
    '            "a=control:trackID=0\\r\\n"\n    }\n\n    private fun respond(',
    'RTSP buildSdp closing brace'
)
rtsp_path.write_text(rtsp, encoding='utf-8')

# 5) MainActivity: permitir iniciar com IPv6, exibir/copiar os dois enderecos.
main_path = JAVA / 'MainActivity.kt'
main = main_path.read_text(encoding='utf-8')
main = replace_once(
    main,
    '''    private fun startStreaming() {
        val ip = NetworkUtils.localIpv4()
        if (ip == null) {
            setError("CONECTE O CELULAR A UMA REDE WI-FI")
            Toast.makeText(
                this,
                "Nenhum endereço IPv4 local encontrado.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
''',
    '''    private fun startStreaming() {
        val ipv4 = NetworkUtils.localIpv4()
        val ipv6 = NetworkUtils.localIpv6()
        if (ipv4 == null && ipv6 == null) {
            setError("CONECTE O CELULAR A UMA REDE WI-FI")
            Toast.makeText(
                this,
                "Nenhum endereço IPv4/IPv6 local encontrado.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
''',
    'MainActivity startStreaming IPv6'
)
main = regex_once(
    main,
    r'    private fun refreshAddress\(\) \{.*?\n    \}\n\n    private fun ',
    '''    private fun refreshAddress() {
        val ipv4 = NetworkUtils.localIpv4()
        val ipv6 = NetworkUtils.localIpv6()
        val lines = mutableListOf<String>()

        ipv4?.let { address ->
            val host = NetworkUtils.urlHost(address)
            lines += "IPv4 MJPEG: http://$host:8080/video"
            lines += "IPv4 RTSP: rtsp://$host:8554/h264"
        }
        ipv6?.let { address ->
            val host = NetworkUtils.urlHost(address)
            lines += "IPv6 MJPEG: http://$host:8080/video"
            lines += "IPv6 RTSP: rtsp://$host:8554/h264"
        }

        addressText.text = if (lines.isNotEmpty()) {
            lines.joinToString("\\n")
        } else {
            "Sem endereço Wi-Fi disponível"
        }
    }

    private fun ''',
    'MainActivity refreshAddress'
)
main = regex_once(
    main,
    r'    private fun copyAddressToClipboard\(\) \{.*?\n    \}\n\n    private fun ',
    '''    private fun copyAddressToClipboard() {
        val ipv4 = NetworkUtils.localIpv4()
        val ipv6 = NetworkUtils.localIpv6()
        if (ipv4 == null && ipv6 == null) {
            Toast.makeText(
                this,
                "Conecte o celular ao Wi-Fi primeiro.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val lines = mutableListOf<String>()
        ipv4?.let { address ->
            val host = NetworkUtils.urlHost(address)
            lines += "IPv4 MJPEG: http://$host:8080/video"
            lines += "IPv4 RTSP: rtsp://$host:8554/h264"
        }
        ipv6?.let { address ->
            val host = NetworkUtils.urlHost(address)
            lines += "IPv6 MJPEG: http://$host:8080/video"
            lines += "IPv6 RTSP: rtsp://$host:8554/h264"
        }
        val urls = lines.joinToString("\\n")

        val clipboard =
            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("GOAT Cam endereços", urls)
        )
        Toast.makeText(
            this,
            "Endereços IPv4/IPv6 copiados.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun ''',
    'MainActivity copyAddress'
)
main_path.write_text(main, encoding='utf-8')

# 6) Status da build.
(ROOT / 'BUILD_37_STATUS.md').write_text('''# GOAT Cam Build 37\n\n- IPv4 preservado.\n- IPv6 adicionado ao GOAT CAM.\n- URLs IPv6 usam colchetes no formato correto: `http://[IPv6]:8080/video`.\n- HTTP/MJPEG abre listeners separados IPv4 e IPv6 na porta 8080.\n- RTSP/H.264 abre listeners separados IPv4 e IPv6 na porta 8554.\n- SDP RTSP anuncia `IP6` quando a conexão é IPv6.\n- Descoberta automática mantém `ip=` para compatibilidade e adiciona `ipv4=` e `ipv6=`.\n- Tela e botão copiar mostram os endereços IPv4 e IPv6 disponíveis.\n- A transmissão pode iniciar se houver IPv4 ou IPv6.\n- Base: Build 36.\n''', encoding='utf-8')

print('Build 37 IPv6 patch aplicado.')
