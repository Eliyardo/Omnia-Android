package com.eliyardo.omnia.security

import com.eliyardo.omnia.settings.AppSettings
import com.eliyardo.omnia.tunnel.OpenVpnResolvedConfig
import org.junit.Assert.*
import org.junit.Test

class SecurityLevelPolicyTest {

    @Test
    fun testPreferredSecurityLevelAppliesStrictSettings() {
        val config = OpenVpnResolvedConfig.resolve(
            securityLevel = AppSettings.SECURITY_LEVEL_PREFERRED,
            enforceTls13 = false
        )

        assertFalse("Preferred mode must disable legacy algorithms (no BF-CBC, DES, RC4)", config.enableLegacyAlgorithms)
        assertFalse("Preferred mode must disable non-preferred data ciphers", config.enableNonPreferredDCAlgorithms)
        assertEquals("Preferred mode must enforce modern TLS certificate profile", "preferred", config.tlsCertProfileOverride)
        assertEquals("TLS version min should be empty when not enforced", "", config.tlsVersionMinOverride)
    }

    @Test
    fun testLegacySecurityLevelAppliesCompatibleSettings() {
        val config = OpenVpnResolvedConfig.resolve(
            securityLevel = AppSettings.SECURITY_LEVEL_LEGACY,
            enforceTls13 = false
        )

        assertTrue("Legacy mode must permit legacy algorithms for broad server compatibility", config.enableLegacyAlgorithms)
        assertTrue("Legacy mode must permit non-preferred data ciphers", config.enableNonPreferredDCAlgorithms)
        assertEquals("Legacy mode must use legacy TLS cert profile", "legacy", config.tlsCertProfileOverride)
    }

    @Test
    fun testInsecureSecurityLevelAppliesWeakSettings() {
        val config = OpenVpnResolvedConfig.resolve(
            securityLevel = AppSettings.SECURITY_LEVEL_INSECURE,
            enforceTls13 = false
        )

        assertTrue("Insecure mode must permit legacy algorithms", config.enableLegacyAlgorithms)
        assertTrue("Insecure mode must permit non-preferred data ciphers", config.enableNonPreferredDCAlgorithms)
        assertEquals("Insecure mode must map to insecure cert profile", "insecure", config.tlsCertProfileOverride)
    }

    @Test
    fun testEnforceTls13Policy() {
        val withTls13 = OpenVpnResolvedConfig.resolve(
            securityLevel = AppSettings.SECURITY_LEVEL_PREFERRED,
            enforceTls13 = true
        )
        assertEquals("Enforcing TLS 1.3 must override tlsVersionMinOverride to tls_1_3", "tls_1_3", withTls13.tlsVersionMinOverride)

        val withoutTls13 = OpenVpnResolvedConfig.resolve(
            securityLevel = AppSettings.SECURITY_LEVEL_PREFERRED,
            enforceTls13 = false
        )
        assertEquals("Default TLS must not restrict tlsVersionMinOverride", "", withoutTls13.tlsVersionMinOverride)
    }

    @Test
    fun testProtocolOverridePolicy() {
        val adaptive = OpenVpnResolvedConfig.resolve(
            securityLevel = AppSettings.SECURITY_LEVEL_PREFERRED,
            enforceTls13 = false,
            protoOverride = AppSettings.PROTO_ADAPTIVE
        )
        assertEquals("adaptive", adaptive.protoOverride)

        val udp = OpenVpnResolvedConfig.resolve(
            securityLevel = AppSettings.SECURITY_LEVEL_PREFERRED,
            enforceTls13 = false,
            protoOverride = AppSettings.PROTO_UDP
        )
        assertEquals("udp", udp.protoOverride)

        val tcp = OpenVpnResolvedConfig.resolve(
            securityLevel = AppSettings.SECURITY_LEVEL_PREFERRED,
            enforceTls13 = false,
            protoOverride = AppSettings.PROTO_TCP
        )
        assertEquals("tcp", tcp.protoOverride)

        val custom = OpenVpnResolvedConfig.resolve(
            securityLevel = AppSettings.SECURITY_LEVEL_PREFERRED,
            enforceTls13 = false,
            protoOverride = "unknown"
        )
        assertEquals("", custom.protoOverride)
    }

    @Test
    fun testIpv6Policy() {
        val ipv6Enabled = OpenVpnResolvedConfig.resolve(
            securityLevel = AppSettings.SECURITY_LEVEL_PREFERRED,
            enforceTls13 = false,
            ipv6Enabled = true
        )
        assertEquals("yes", ipv6Enabled.allowUnusedAddrFamilies)

        val ipv6Disabled = OpenVpnResolvedConfig.resolve(
            securityLevel = AppSettings.SECURITY_LEVEL_PREFERRED,
            enforceTls13 = false,
            ipv6Enabled = false
        )
        assertEquals("no", ipv6Disabled.allowUnusedAddrFamilies)
    }

    @Test
    fun testLanBypassPolicy() {
        val lanBypass = OpenVpnResolvedConfig.resolve(
            securityLevel = AppSettings.SECURITY_LEVEL_PREFERRED,
            enforceTls13 = false,
            bypassLanTun = true
        )
        assertTrue(lanBypass.allowLocalLanAccess)

        val noLanBypass = OpenVpnResolvedConfig.resolve(
            securityLevel = AppSettings.SECURITY_LEVEL_PREFERRED,
            enforceTls13 = false,
            bypassLanTun = false
        )
        assertFalse(noLanBypass.allowLocalLanAccess)
    }

    @Test
    fun testConnectionTimeoutOptions() {
        for (timeout in listOf(AppSettings.TIMEOUT_CONTINUOUS, AppSettings.TIMEOUT_10S, AppSettings.TIMEOUT_30S, AppSettings.TIMEOUT_1M, AppSettings.TIMEOUT_2M)) {
            val resolved = OpenVpnResolvedConfig.resolve(
                securityLevel = AppSettings.SECURITY_LEVEL_PREFERRED,
                enforceTls13 = false,
                connTimeout = timeout
            )
            assertEquals(timeout, resolved.connTimeout)
        }
    }
}
