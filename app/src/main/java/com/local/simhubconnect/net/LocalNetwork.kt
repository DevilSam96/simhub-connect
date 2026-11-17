package com.local.simhubconnect.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * IPv4 network of the tablet plus the candidate hosts to scan.
 * Scans are capped to a /24 around our address so discovery stays fast even
 * when the router hands out a larger subnet.
 */
data class LocalNetworkInfo(
    val ipAddress: String,
    val scanPrefixLength: Int,
    val networkAddress: String
) {
    val hostCount: Int get() = (1 shl (32 - scanPrefixLength)) - 2

    fun hosts(): List<String> {
        val network = ipToLong(networkAddress)
        val last = network + hostCount
        val result = ArrayList<String>(hostCount)
        var value = network + 1
        while (value <= last) {
            val ip = longToIp(value)
            if (ip != ipAddress) result.add(ip)
            value++
        }
        return result
    }

    companion object {
        fun ipToLong(ip: String): Long {
            val parts = ip.split('.')
            if (parts.size != 4) return 0L
            var result = 0L
            for (part in parts) {
                val octet = part.toIntOrNull()?.coerceIn(0, 255) ?: return 0L
                result = (result shl 8) or octet.toLong()
            }
            return result and 0xFFFFFFFFL
        }

        fun longToIp(value: Long): String {
            val v = value and 0xFFFFFFFFL
            return "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"
        }
    }
}

object LocalNetwork {

    fun current(context: Context): LocalNetworkInfo? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager != null) {
            // Wi-Fi first, then Ethernet. A sim PC network often has no internet
            // access, so the default network may still be mobile data: look at
            // every network instead of only the active one.
            val networks = manager.allNetworks.sortedByDescending { network ->
                val capabilities = manager.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) 1 else 0
            }
            for (network in networks) {
                val capabilities = manager.getNetworkCapabilities(network) ?: continue
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) {
                    continue
                }
                val link = manager.getLinkProperties(network) ?: continue
                val address = link.linkAddresses.firstOrNull { linkAddress ->
                    linkAddress.address is Inet4Address &&
                        !linkAddress.address.isLoopbackAddress &&
                        !linkAddress.address.isLinkLocalAddress
                } ?: continue
                val ip = address.address.hostAddress ?: continue
                return fromAddress(ip, address.prefixLength)
            }
        }
        return fromInterfaces()
    }

    private fun fromInterfaces(): LocalNetworkInfo? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList()
        } catch (_: Exception) {
            null
        } ?: return null

        for (iface in interfaces) {
            val ip = try {
                if (!iface.isUp || iface.isLoopback) {
                    null
                } else {
                    val address = iface.inetAddresses.toList().firstOrNull {
                        it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress
                    } as? Inet4Address
                    address?.hostAddress
                }
            } catch (_: Exception) {
                null
            } ?: continue
            return fromAddress(ip, 24)
        }
        return null
    }

    private fun fromAddress(ip: String, prefix: Int): LocalNetworkInfo {
        val scanPrefix = prefix.coerceIn(8, 30).coerceAtLeast(24)
        val mask = (0xFFFFFFFFL shl (32 - scanPrefix)) and 0xFFFFFFFFL
        val network = LocalNetworkInfo.ipToLong(ip) and mask
        return LocalNetworkInfo(
            ipAddress = ip,
            scanPrefixLength = scanPrefix,
            networkAddress = LocalNetworkInfo.longToIp(network)
        )
    }
}
