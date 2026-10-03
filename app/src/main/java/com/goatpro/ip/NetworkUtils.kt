package com.goatpro.ip

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
