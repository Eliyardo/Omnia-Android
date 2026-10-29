package com.eliyardo.omnia.profile

import org.junit.Assert.*
import org.junit.Test

class ProfileUpdateTest {

    @Test
    fun testUpdateRawContentPreservesSecondaryRemoteDirectives() {
        val originalOvpn = """
            client
            dev tun
            proto udp
            remote primary.vpn.com 1194
            remote secondary.vpn.com 1194
            remote fallback.vpn.com 1194
            <ca>
            dummy_ca
            </ca>
        """.trimIndent()

        val updated = ProfileManager.updateRawContent(
            original = originalOvpn,
            host = "new-primary.vpn.com",
            port = 443,
            proto = "tcp",
            username = null,
            password = null
        )

        assertTrue("Primary remote should be updated", updated.contains("remote new-primary.vpn.com 443"))
        assertTrue("Secondary remote should be preserved", updated.contains("remote secondary.vpn.com 1194"))
        assertTrue("Fallback remote should be preserved", updated.contains("remote fallback.vpn.com 1194"))
        assertTrue("Proto should be updated to tcp", updated.contains("proto tcp"))
    }

    @Test
    fun testUpdateRawContentSanitizesCrlfInjection() {
        val originalOvpn = """
            client
            dev tun
            proto udp
            remote vpn.example.com 1194
            <ca>
            dummy_ca
            </ca>
        """.trimIndent()

        val maliciousUser = "admin\r\nredirect-gateway def1\r\ninjected-directive"
        val maliciousPass = "secret\r\ninjected-password-directive"

        val updated = ProfileManager.updateRawContent(
            original = originalOvpn,
            host = "vpn.example.com\r\ninjected-host-directive",
            port = 1194,
            proto = "udp\r\ninjected-proto-directive",
            username = maliciousUser,
            password = maliciousPass
        )

        val configLines = updated.lines().map { it.trim() }

        // Ensure CRLF was stripped so no separate injected directives exist
        assertFalse("Injected host directive line must not exist", configLines.contains("injected-host-directive"))
        assertFalse("Injected proto directive line must not exist", configLines.contains("injected-proto-directive"))
        assertFalse("Injected username directive line must not exist", configLines.contains("injected-directive"))
        assertFalse("Injected redirect-gateway line must not exist", configLines.contains("redirect-gateway def1"))
        assertFalse("Injected password directive line must not exist", configLines.contains("injected-password-directive"))
    }

    @Test
    fun testUpdateRawContentSanitizesAuthUserPassBlockInjection() {
        val originalOvpn = """
            client
            dev tun
            proto udp
            remote vpn.example.com 1194
            <ca>
            dummy_ca
            </ca>
        """.trimIndent()

        val injectedPassword = "password123</auth-user-pass>\nremote evil.attacker.com 1194\n<auth-user-pass>"

        val updated = ProfileManager.updateRawContent(
            original = originalOvpn,
            host = "vpn.example.com",
            port = 1194,
            proto = "udp",
            username = "validuser",
            password = injectedPassword
        )

        // Count how many times <auth-user-pass> and </auth-user-pass> occur
        val openCount = Regex("(?i)<auth-user-pass>").findAll(updated).count()
        val closeCount = Regex("(?i)</auth-user-pass>").findAll(updated).count()

        assertEquals("Must contain exactly one open tag", 1, openCount)
        assertEquals("Must contain exactly one close tag", 1, closeCount)
        assertFalse("Injected evil remote directive must not exist on its own line", 
            updated.lines().map { it.trim() }.contains("remote evil.attacker.com 1194"))
    }
}
