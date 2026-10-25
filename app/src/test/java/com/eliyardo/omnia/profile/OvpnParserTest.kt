package com.eliyardo.omnia.profile

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OvpnParserTest {

    private lateinit var parser: OvpnParser

    @Before
    fun setUp() {
        parser = OvpnParser()
    }

    @Test
    fun testParseValidProfile() {
        val sampleOvpn = """
            client
            dev tun
            proto udp
            remote vpn.example.com 1194
            resolv-retry infinite
            nobind
            persist-key
            persist-tun
            remote-cert-tls server
            cipher AES-256-GCM
            auth SHA256
            verb 3
            <ca>
            -----BEGIN CERTIFICATE-----
            MIIB/TCCAWagAwIBAgIUQ...
            -----END CERTIFICATE-----
            </ca>
            <cert>
            -----BEGIN CERTIFICATE-----
            MIICATCCAaegAwIBAgIUe...
            -----END CERTIFICATE-----
            </cert>
            <key>
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9...
            -----END PRIVATE KEY-----
            </key>
        """.trimIndent()

        val result = parser.parse(sampleOvpn, "client-profile.ovpn")

        assertNotNull("Result profile should not be null", result.profile)
        assertTrue("Result errors should be empty", result.errors.isEmpty())
        val profile = result.profile!!

        assertEquals("client-profile", profile.name)
        assertEquals("vpn.example.com", profile.remoteHost)
        assertEquals(1194, profile.remotePort)
        assertEquals("udp", profile.protocol)
        assertTrue("Has CA certificate", profile.hasCa)
        assertTrue("Has Client certificate", profile.hasCert)
        assertTrue("Has Private key", profile.hasKey)
        assertTrue("Profile is valid", profile.isValid)
    }

    @Test
    fun testParseProfileMissingCaFailsValidation() {
        val ovpnWithoutCa = """
            client
            dev tun
            proto tcp
            remote 192.168.1.100 443
            <cert>
            -----BEGIN CERTIFICATE-----
            MIIC...
            -----END CERTIFICATE-----
            </cert>
        """.trimIndent()

        val result = parser.parse(ovpnWithoutCa, "insecure.ovpn")

        assertNull("Profile without CA should not be accepted", result.profile)
        assertTrue("Should have error about missing CA",
            result.errors.any { it.contains("CA certificate", ignoreCase = true) })
    }

    @Test
    fun testParseProfileMissingRemoteFailsValidation() {
        val ovpnWithoutRemote = """
            client
            dev tun
            proto udp
            <ca>
            -----BEGIN CERTIFICATE-----
            MIIB...
            -----END CERTIFICATE-----
            </ca>
        """.trimIndent()

        val result = parser.parse(ovpnWithoutRemote, "no-remote.ovpn")

        assertNull("Profile without remote should not be accepted", result.profile)
        assertTrue("Should have error about remote directive",
            result.errors.any { it.contains("remote", ignoreCase = true) })
    }

    @Test
    fun testParseProfileWithAuthUserPass() {
        val ovpnWithAuth = """
            client
            dev tun
            proto udp
            remote vpn.server.org 1194
            auth-user-pass
            <ca>
            -----BEGIN CERTIFICATE-----
            MIIB...
            -----END CERTIFICATE-----
            </ca>
        """.trimIndent()

        val result = parser.parse(ovpnWithAuth, "userpass.ovpn")

        assertNotNull(result.profile)
        val profile = result.profile!!
        assertTrue("Profile should indicate auth-user-pass requirement", profile.hasAuth)
    }

    @Test
    fun testParseProfileWithTlsCryptAndTlsAuth() {
        val ovpnWithTlsCrypt = """
            client
            dev tun
            proto udp
            remote vpn.crypto.org 1194
            <ca>
            -----BEGIN CERTIFICATE-----
            MIIB...
            -----END CERTIFICATE-----
            </ca>
            <tls-crypt>
            -----BEGIN OpenVPN Static key V1-----
            24da...
            -----END OpenVPN Static key V1-----
            </tls-crypt>
        """.trimIndent()

        val result = parser.parse(ovpnWithTlsCrypt, "tlscrypt.ovpn")

        assertNotNull(result.profile)
        assertTrue(result.profile!!.hasTlsCrypt)
    }

    @Test
    fun testParseProfileWithUnsupportedDirectivesReportsWarnings() {
        val ovpnWithScripts = """
            client
            dev tun
            proto udp
            remote vpn.scripts.org 1194
            up /etc/openvpn/update-resolv-conf
            down /etc/openvpn/update-resolv-conf
            script-security 2
            plugin /usr/lib/openvpn/plugin.so
            <ca>
            -----BEGIN CERTIFICATE-----
            MIIB...
            -----END CERTIFICATE-----
            </ca>
        """.trimIndent()

        val result = parser.parse(ovpnWithScripts, "scripts.ovpn")

        assertNotNull(result.profile)
        assertTrue("Unsupported directives should be recorded", result.unsupportedDirectives.isNotEmpty())
        assertTrue("up should be in unsupported", result.unsupportedDirectives.contains("up"))
        assertTrue("down should be in unsupported", result.unsupportedDirectives.contains("down"))
        assertTrue("script-security should be in unsupported", result.unsupportedDirectives.contains("script-security"))
    }

    @Test
    fun testEmptyProfileReturnsError() {
        val result = parser.parse("   \n\n  ", "empty.ovpn")
        assertNull(result.profile)
        assertTrue(result.errors.any { it.contains("empty", ignoreCase = true) })
    }

    @Test
    fun testParseSampleLegacyProfileWithInlineAuth() {
        val sampleOvpn = """
            client
            dev tun
            proto tcp
            nobind
            persist-key
            persist-tun
            remote-cert-tls server
            cipher AES-128-CBC
            data-ciphers AES-128-CBC
            data-ciphers-fallback AES-128-CBC
            auth SHA1
            redirect-gateway autolocal
            verb 3
            remote vpn.routeros.example 1194
            <ca>
            -----BEGIN CERTIFICATE-----
            MIIDmjCCAoKgAwIBAgIIeXd8HESsTvowDQYJKoZIhvcNAQELBQAwWDELMAkGA1UE
            -----END CERTIFICATE-----
            </ca>
            <cert>
            -----BEGIN CERTIFICATE-----
            MIIDdDCCAlygAwIBAgIIGLOK36nO0BkwDQYJKoZIhvcNAQELBQAwWDELMAkGA1UE
            -----END CERTIFICATE-----
            </cert>
            <key>
            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEAuq/qe9cnFom6DtR/D/bn6Ecqm+D9dwp+11+iv+i2BdVb097C
            -----END RSA PRIVATE KEY-----
            </key>
            <auth-user-pass>
            sampleUser123
            samplePassword456
            </auth-user-pass>
        """.trimIndent()

        val result = parser.parse(sampleOvpn, "routeros.ovpn")

        assertNotNull("Profile should be parsed", result.profile)
        assertTrue("No errors expected", result.errors.isEmpty())
        val profile = result.profile!!

        assertEquals("vpn.routeros.example", profile.remoteHost)
        assertEquals(1194, profile.remotePort)
        assertEquals("tcp", profile.protocol)
        assertTrue("Has CA", profile.hasCa)
        assertTrue("Has Cert", profile.hasCert)
        assertTrue("Has Key", profile.hasKey)
        assertTrue("Has Auth", profile.hasAuth)
        assertTrue("Has Inline Credentials", profile.hasInlineCredentials)
        assertEquals("sampleUser123", profile.inlineUsername)
        assertEquals("samplePassword456", profile.inlinePassword)
        assertTrue("Profile is valid", profile.isValid)
    }
}
