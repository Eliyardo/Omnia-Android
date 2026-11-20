package com.eliyardo.omnia.service

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer

object VpnNetworkUtils {
    private const val TAG = "VpnNetworkUtils"

    fun getActiveLocalSubnets(context: Context): List<String> {
        val result = mutableListOf<String>()
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return result
            val activeNetwork = cm.activeNetwork ?: return result
            val linkProps = cm.getLinkProperties(activeNetwork) ?: return result
            for (la in linkProps.linkAddresses) {
                val addr = la.address
                val prefix = la.prefixLength
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    if (prefix <= 0 || prefix > 32) continue
                    val ipInt = ByteBuffer.wrap(addr.address).int
                    val mask = -1 shl (32 - prefix)
                    val netInt = ipInt and mask
                    val netAddr = InetAddress.getByAddress(
                        ByteBuffer.allocate(4).putInt(netInt).array()
                    ).hostAddress
                    if (netAddr != null) {
                        result.add("$netAddr/$prefix")
                    }
                } else if (addr is Inet6Address && addr.isLinkLocalAddress) {
                    result.add("fe80::/10")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed inspecting local subnets for LAN bypass", e)
        }
        return result
    }
}
