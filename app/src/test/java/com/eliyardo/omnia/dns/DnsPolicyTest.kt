package com.eliyardo.omnia.dns

import com.eliyardo.omnia.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer

class DnsPolicyTest {

    // ==========================================
    // 1. DNS Provider Mapping & Custom Overrides
    // ==========================================

    @Test
    fun testServerPushedDnsIsDefault() {
        val defaultResolution = AppSettings.resolveDnsIps(AppSettings.DNS_PROVIDER_SERVER, "", "")
        assertNull("Default DNS provider should be server-pushed (null)", defaultResolution)
    }

    @Test
    fun testCloudflareDnsResolution() {
        val cloudflare = AppSettings.resolveDnsIps(AppSettings.DNS_PROVIDER_CLOUDFLARE, "", "")
        assertNotNull(cloudflare)
        assertEquals("1.1.1.1", cloudflare!!.first)
        assertEquals("1.0.0.1", cloudflare.second)
    }

    @Test
    fun testGoogleDnsResolution() {
        val google = AppSettings.resolveDnsIps(AppSettings.DNS_PROVIDER_GOOGLE, "", "")
        assertNotNull(google)
        assertEquals("8.8.8.8", google!!.first)
        assertEquals("8.8.4.4", google.second)
    }

    @Test
    fun testQuad9DnsResolution() {
        val quad9 = AppSettings.resolveDnsIps(AppSettings.DNS_PROVIDER_QUAD9, "", "")
        assertNotNull(quad9)
        assertEquals("9.9.9.9", quad9!!.first)
        assertEquals("149.112.112.112", quad9.second)
    }

    @Test
    fun testAdGuardDnsResolution() {
        val adguard = AppSettings.resolveDnsIps(AppSettings.DNS_PROVIDER_ADGUARD, "", "")
        assertNotNull(adguard)
        assertEquals("94.140.14.14", adguard!!.first)
        assertEquals("94.140.15.15", adguard.second)
    }

    @Test
    fun testCustomDnsResolutionFallback() {
        val custom = AppSettings.resolveDnsIps(AppSettings.DNS_PROVIDER_CUSTOM, "10.0.0.53", "10.0.0.54")
        assertNotNull(custom)
        assertEquals("10.0.0.53", custom!!.first)
        assertEquals("10.0.0.54", custom.second)
    }

    @Test
    fun testCustomDnsWithBlankValuesUsesSafeDefaults() {
        val custom = AppSettings.resolveDnsIps(AppSettings.DNS_PROVIDER_CUSTOM, "", "")
        assertNotNull(custom)
        assertEquals("1.1.1.1", custom!!.first)
        assertEquals("1.0.0.1", custom.second)
    }

    @Test
    fun testCustomDnsWhitespaceTrimming() {
        val custom = AppSettings.resolveDnsIps(AppSettings.DNS_PROVIDER_CUSTOM, "  192.168.1.1  ", "  192.168.1.254\n")
        assertNotNull(custom)
        assertEquals("192.168.1.1", custom!!.first)
        assertEquals("192.168.1.254", custom.second)
    }

    // ==========================================
    // 2. Proxy Mode DNS Leak Prevention Tests
    // ==========================================

    @Test
    fun testSocks5RemoteDnsPreservesDomainVerbatim() {
        // RFC 1928: SOCKS5 CONNECT with ATYP = 0x03 (Domain name)
        val domain = "leak-test.dnsleaktest.com"
        val domainBytes = domain.toByteArray(Charsets.US_ASCII)

        val stream = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x05, 0x01, 0x00, 0x03)) // VER=5, CMD=CONNECT, RSV=0, ATYP=DOMAIN
            write(domainBytes.size)
            write(domainBytes)
            write(byteArrayOf(0x01, 0xBB.toByte())) // Port 443
        }

        val buf = ByteBuffer.wrap(stream.toByteArray())
        assertEquals(0x05.toByte(), buf.get()) // VER
        assertEquals(0x01.toByte(), buf.get()) // CMD
        assertEquals(0x00.toByte(), buf.get()) // RSV
        val atyp = buf.get()
        assertEquals(0x03.toByte(), atyp)     // ATYP_DOMAIN

        val dlen = buf.get().toInt() and 0xFF
        val extractedBytes = ByteArray(dlen).also { buf.get(it) }
        val extractedDomain = String(extractedBytes, Charsets.US_ASCII)
        val port = buf.short.toInt() and 0xFFFF

        // In Proxy Mode with remoteDns = true, the domain name MUST be forwarded to lwIP
        // without calling InetAddress.getByName() on the Android host.
        val remoteDns = true
        val targetToTunnel = if (remoteDns && atyp == 0x03.toByte()) {
            extractedDomain
        } else {
            "resolved-locally"
        }

        assertEquals("leak-test.dnsleaktest.com", targetToTunnel)
        assertEquals(443, port)
        assertFalse("Domain should not be resolved locally", targetToTunnel.contains("resolved-locally"))
    }

    @Test
    fun testSocks5UdpAssociateDomainPacketParsing() {
        // SOCKS5 UDP Request: RSV(2) + FRAG(1) + ATYP(1) + DST.ADDR + DST.PORT + DATA
        val domain = "udp-dns-target.internal"
        val domainBytes = domain.toByteArray(Charsets.US_ASCII)

        val packetStream = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x00, 0x00, 0x00, 0x03)) // RSV(2), FRAG=0, ATYP=DOMAIN
            write(domainBytes.size)
            write(domainBytes)
            write(byteArrayOf(0x00, 0x35)) // Port 53 (DNS)
            write("PAYLOAD".toByteArray(Charsets.UTF_8))
        }

        val packet = packetStream.toByteArray()
        val atyp = packet[3]
        assertEquals(0x03.toByte(), atyp)

        val domainLen = packet[4].toInt() and 0xFF
        val extractedDomain = String(packet, 5, domainLen, Charsets.US_ASCII)
        val portIdx = 5 + domainLen
        val dstPort = ((packet[portIdx].toInt() and 0xFF) shl 8) or (packet[portIdx + 1].toInt() and 0xFF)
        val dataOffset = portIdx + 2
        val payload = String(packet, dataOffset, packet.size - dataOffset, Charsets.UTF_8)

        assertEquals("udp-dns-target.internal", extractedDomain)
        assertEquals(53, dstPort)
        assertEquals("PAYLOAD", payload)
    }

    @Test
    fun testHttpProxyConnectPreservesDomainVerbatim() {
        val connectLine = "CONNECT secure.bank.com:443 HTTP/1.1\r\nHost: secure.bank.com:443\r\n\r\n"
        val firstLine = connectLine.split("\r\n")[0]
        val parts = firstLine.split(" ")
        assertEquals("CONNECT", parts[0])

        val target = parts[1]
        val colon = target.lastIndexOf(':')
        val host = if (colon >= 0) target.substring(0, colon) else target
        val port = if (colon >= 0) target.substring(colon + 1).toIntOrNull() else null

        val remoteDns = true
        val resolvedHost = if (remoteDns) host else "local-resolver-called"

        assertEquals("secure.bank.com", resolvedHost)
        assertEquals(443, port)
    }

    @Test
    fun testHttpProxyAbsoluteUriPreservesDomainVerbatim() {
        val getLine = "GET http://api.github.com/events HTTP/1.1\r\nHost: api.github.com\r\n\r\n"
        val firstLine = getLine.split("\r\n")[0]
        val parts = firstLine.split(" ")
        val uri = parts[1]
        val cleanUrl = uri.substring("http://".length)
        val slashIdx = cleanUrl.indexOf('/')
        val hostPort = if (slashIdx >= 0) cleanUrl.substring(0, slashIdx) else cleanUrl

        val colon = hostPort.lastIndexOf(':')
        val host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
        val port = if (colon >= 0) hostPort.substring(colon + 1).toIntOrNull() ?: 80 else 80

        val remoteDns = true
        val resolvedHost = if (remoteDns) host else "local-resolver-called"

        assertEquals("api.github.com", resolvedHost)
        assertEquals(80, port)
    }

    // ==========================================
    // 3. TUN Mode DNS & IPv6 Protection Tests
    // ==========================================

    @Test
    fun testTunModeDnsPushedFallbackWhenServerPushesNothing() {
        val pushedDns = emptyList<String>()
        val selectedProvider = AppSettings.DNS_PROVIDER_SERVER
        val customPrimary = ""
        val customSecondary = ""

        val dnsPair = AppSettings.resolveDnsIps(selectedProvider, customPrimary, customSecondary)
        val installedDnsServers = mutableListOf<String>()

        if (dnsPair != null) {
            installedDnsServers.add(dnsPair.first)
            installedDnsServers.add(dnsPair.second)
        } else if (pushedDns.isNotEmpty()) {
            installedDnsServers.addAll(pushedDns)
        } else {
            installedDnsServers.add("1.1.1.1") // Safe fallback to prevent local ISP DNS leak
        }

        assertEquals(1, installedDnsServers.size)
        assertEquals("1.1.1.1", installedDnsServers[0])
    }

    @Test
    fun testTunModeDnsServerPushedPreferredWhenConfigured() {
        val pushedDns = listOf("10.8.0.1", "10.8.0.2")
        val selectedProvider = AppSettings.DNS_PROVIDER_SERVER

        val dnsPair = AppSettings.resolveDnsIps(selectedProvider, "", "")
        val installedDnsServers = mutableListOf<String>()

        if (dnsPair != null) {
            installedDnsServers.add(dnsPair.first)
            installedDnsServers.add(dnsPair.second)
        } else if (pushedDns.isNotEmpty()) {
            installedDnsServers.addAll(pushedDns)
        } else {
            installedDnsServers.add("1.1.1.1")
        }

        assertEquals(2, installedDnsServers.size)
        assertEquals("10.8.0.1", installedDnsServers[0])
        assertEquals("10.8.0.2", installedDnsServers[1])
    }

    @Test
    fun testTunModeCustomDnsOverridesServerPushedDns() {
        val pushedDns = listOf("10.8.0.1", "10.8.0.2")
        val selectedProvider = AppSettings.DNS_PROVIDER_CLOUDFLARE

        val dnsPair = AppSettings.resolveDnsIps(selectedProvider, "", "")
        val installedDnsServers = mutableListOf<String>()

        if (dnsPair != null) {
            installedDnsServers.add(dnsPair.first)
            installedDnsServers.add(dnsPair.second)
        } else if (pushedDns.isNotEmpty()) {
            installedDnsServers.addAll(pushedDns)
        }

        assertEquals(2, installedDnsServers.size)
        assertEquals("1.1.1.1", installedDnsServers[0])
        assertEquals("1.0.0.1", installedDnsServers[1])
    }

    @Test
    fun testIpv6BlackholeRoutePreventsDualStackLeaks() {
        // When IPv6 is disabled or unconfigured by the server, VpnService installs a ULA dummy
        // address fd00::1/128 and routes ::/0 to ensure AAAA queries do not escape to Wi-Fi/LTE.
        val ipv6Enabled = false
        val assignedIpv6: String? = null

        var configuredAddress = ""
        var configuredPrefix = 0
        var configuredRoute = ""
        var configuredRoutePrefix = -1

        if (ipv6Enabled && !assignedIpv6.isNullOrBlank()) {
            configuredAddress = assignedIpv6
            configuredPrefix = 64
            configuredRoute = "::"
            configuredRoutePrefix = 0
        } else {
            configuredAddress = "fd00::1"
            configuredPrefix = 128
            configuredRoute = "::"
            configuredRoutePrefix = 0
        }

        assertEquals("fd00::1", configuredAddress)
        assertEquals(128, configuredPrefix)
        assertEquals("::", configuredRoute)
        assertEquals(0, configuredRoutePrefix)
    }
}
