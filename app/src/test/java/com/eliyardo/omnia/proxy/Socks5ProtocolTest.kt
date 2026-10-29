package com.eliyardo.omnia.proxy

import com.eliyardo.omnia.tunnel.TunnelEngine
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress

class Socks5ProtocolTest {

    private val engine = TunnelEngine()

    @Test
    fun testNoAuthHandshakeNegotiation() {
        // Client sends: VER=5, NMETHODS=1, METHOD=[0x00 (NO AUTH)]
        val clientGreeting = byteArrayOf(0x05, 0x01, 0x00)
        val input = ByteArrayInputStream(clientGreeting)
        val output = ByteArrayOutputStream()

        val server = Socks5Server(tunnelEngine = engine, authRequired = false)
        val accepted = server.handleAuth(input, output)

        assertTrue("No-auth handshake should be accepted", accepted)
        assertArrayEquals(byteArrayOf(0x05, 0x00), output.toByteArray())
    }

    @Test
    fun testUserPassHandshakeNegotiation() {
        // Client sends: VER=5, NMETHODS=2, METHODS=[0x00 (NO AUTH), 0x02 (USER/PASS)]
        // Followed by valid RFC 1929 subnegotiation: VER=1, ULEN=5, UNAME="admin", PLEN=6, PASSWD="secret"
        val payload = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
            write(0x01)
            write(5)
            write("admin".toByteArray(Charsets.UTF_8))
            write(6)
            write("secret".toByteArray(Charsets.UTF_8))
        }.toByteArray()

        val input = ByteArrayInputStream(payload)
        val output = ByteArrayOutputStream()

        val server = Socks5Server(
            tunnelEngine = engine,
            authRequired = true,
            authUsername = "admin",
            authPassword = "secret"
        )
        val accepted = server.handleAuth(input, output)

        assertTrue("User/Pass auth should succeed", accepted)
        assertArrayEquals(byteArrayOf(0x05, 0x02, 0x01, 0x00), output.toByteArray())
    }

    @Test
    fun testUserPassAuthVerificationSuccess() {
        // RFC 1929 subnegotiation: VER=1, ULEN=5, UNAME="admin", PLEN=6, PASSWD="secret"
        val authPayload = ByteArrayOutputStream().apply {
            write(0x01) // subnegotiation version
            write(5)    // ulen
            write("admin".toByteArray(Charsets.UTF_8))
            write(6)    // plen
            write("secret".toByteArray(Charsets.UTF_8))
        }.toByteArray()

        val input = ByteArrayInputStream(authPayload)
        val output = ByteArrayOutputStream()

        val server = Socks5Server(
            tunnelEngine = engine,
            authRequired = true,
            authUsername = "admin",
            authPassword = "secret"
        )
        val success = server.handleUsernamePasswordAuth(input, output)

        assertTrue(success)
        assertArrayEquals(byteArrayOf(0x01, 0x00), output.toByteArray())
    }

    @Test
    fun testUserPassAuthVerificationFailure() {
        val authPayload = ByteArrayOutputStream().apply {
            write(0x01)
            write(4)
            write("user".toByteArray(Charsets.UTF_8))
            write(8)
            write("wrongpwd".toByteArray(Charsets.UTF_8))
        }.toByteArray()

        val input = ByteArrayInputStream(authPayload)
        val output = ByteArrayOutputStream()

        val server = Socks5Server(
            tunnelEngine = engine,
            authRequired = true,
            authUsername = "admin",
            authPassword = "secret"
        )
        val success = server.handleUsernamePasswordAuth(input, output)

        assertFalse(success)
        assertArrayEquals(byteArrayOf(0x01, 0x01), output.toByteArray())
    }

    @Test
    fun testIpv4ConnectRequestParsing() {
        // VER=5, CMD=1 (CONNECT), RSV=0, ATYP=1 (IPv4), ADDR=1.2.3.4, PORT=80 (0x0050)
        val requestBytes = byteArrayOf(
            0x05, 0x01, 0x00, 0x01,
            1, 2, 3, 4,
            0x00, 0x50
        )

        val server = Socks5Server(tunnelEngine = engine)
        val req = server.readRequest(ByteArrayInputStream(requestBytes))

        assertNotNull(req)
        assertEquals(0x01.toByte(), req!!.command)
        assertEquals(0x01.toByte(), req.addressType)
        assertEquals("1.2.3.4", req.destAddress)
        assertEquals(80, req.destPort)
    }

    @Test
    fun testDomainConnectRequestParsing() {
        // VER=5, CMD=1, RSV=0, ATYP=3 (Domain), LEN=11, ADDR="example.com", PORT=443 (0x01BB)
        val domain = "example.com".toByteArray(Charsets.US_ASCII)
        val stream = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x05, 0x01, 0x00, 0x03))
            write(domain.size)
            write(domain)
            write(byteArrayOf(0x01, 0xBB.toByte()))
        }

        val server = Socks5Server(tunnelEngine = engine)
        val req = server.readRequest(ByteArrayInputStream(stream.toByteArray()))

        assertNotNull(req)
        assertEquals(0x01.toByte(), req!!.command)
        assertEquals(0x03.toByte(), req.addressType)
        assertEquals("example.com", req.destAddress)
        assertEquals(443, req.destPort)
    }

    @Test
    fun testIpv6ConnectRequestParsing() {
        // VER=5, CMD=1, RSV=0, ATYP=4 (IPv6: 2001:db8::1), PORT=8080 (0x1F90)
        val ipv6Bytes = InetAddress.getByName("2001:db8::1").address
        val stream = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x05, 0x01, 0x00, 0x04))
            write(ipv6Bytes)
            write(byteArrayOf(0x1F, 0x90.toByte()))
        }

        val server = Socks5Server(tunnelEngine = engine)
        val req = server.readRequest(ByteArrayInputStream(stream.toByteArray()))

        assertNotNull(req)
        assertEquals(0x01.toByte(), req!!.command)
        assertEquals(0x04.toByte(), req.addressType)
        assertEquals(InetAddress.getByName("2001:db8::1").hostAddress, req.destAddress)
        assertEquals(8080, req.destPort)
    }
}
