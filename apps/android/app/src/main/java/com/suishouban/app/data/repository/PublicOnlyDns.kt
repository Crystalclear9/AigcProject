package com.suishouban.app.data.repository

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException

/** Rejects DNS answers that could route a configured provider into a local network. */
object PublicOnlyDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !isPublic(it) }) {
            throw UnknownHostException("Endpoint does not resolve exclusively to public addresses")
        }
        return addresses
    }

    internal fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return false
        val bytes = address.address
        if (address is Inet4Address) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            return !(first == 0 || first == 127 ||
                (first == 100 && second in 64..127) ||
                (first == 169 && second == 254) ||
                (first == 192 && second == 0) ||
                (first == 192 && second == 88) ||
                (first == 198 && second in 18..19) || first >= 224)
        }
        // Java commonly exposes IPv4-mapped IPv6 as Inet4Address. For native
        // IPv6, reject unspecified/documentation and unique-local ranges too.
        val first = bytes.firstOrNull()?.toInt()?.and(0xff) ?: return false
        return first !in listOf(0x00, 0xfc, 0xfd) &&
            !(first == 0x20 && (bytes.getOrNull(1)?.toInt()?.and(0xff) == 0x01) &&
                (bytes.getOrNull(2)?.toInt()?.and(0xff) == 0x0d) &&
                (bytes.getOrNull(3)?.toInt()?.and(0xff) == 0xb8))
    }
}
