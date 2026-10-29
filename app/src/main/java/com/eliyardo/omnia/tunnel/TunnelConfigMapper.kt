package com.eliyardo.omnia.tunnel

import com.eliyardo.omnia.settings.AppSettings

/**
 * Models and verifies the exact configuration parameters passed from Kotlin / AppSettings
 * to OpenVPN 3 Core via TunnelEngine and JNI.
 */
data class OpenVpnResolvedConfig(
    val enableLegacyAlgorithms: Boolean,
    val enableNonPreferredDCAlgorithms: Boolean,
    val tlsCertProfileOverride: String,
    val tlsVersionMinOverride: String,
    val protoOverride: String,
    val connTimeout: Int,
    val allowUnusedAddrFamilies: String,
    val allowLocalLanAccess: Boolean
) {
    companion object {
        fun resolve(
            securityLevel: String,
            enforceTls13: Boolean,
            protoOverride: String = AppSettings.PROTO_ADAPTIVE,
            connTimeout: Int = AppSettings.TIMEOUT_1M,
            ipv6Enabled: Boolean = true,
            bypassLanTun: Boolean = true
        ): OpenVpnResolvedConfig {
            val (legacyAlgs, nonPrefDC, certProfile) = when (securityLevel) {
                AppSettings.SECURITY_LEVEL_PREFERRED -> Triple(false, false, "preferred")
                AppSettings.SECURITY_LEVEL_INSECURE -> Triple(true, true, "insecure")
                else -> Triple(true, true, "legacy")
            }

            val tlsMin = if (enforceTls13) "tls_1_3" else ""

            val proto = when (protoOverride) {
                AppSettings.PROTO_ADAPTIVE -> "adaptive"
                AppSettings.PROTO_UDP -> "udp"
                AppSettings.PROTO_TCP -> "tcp"
                else -> ""
            }

            return OpenVpnResolvedConfig(
                enableLegacyAlgorithms = legacyAlgs,
                enableNonPreferredDCAlgorithms = nonPrefDC,
                tlsCertProfileOverride = certProfile,
                tlsVersionMinOverride = tlsMin,
                protoOverride = proto,
                connTimeout = connTimeout,
                allowUnusedAddrFamilies = if (ipv6Enabled) "yes" else "no",
                allowLocalLanAccess = bypassLanTun
            )
        }
    }
}
