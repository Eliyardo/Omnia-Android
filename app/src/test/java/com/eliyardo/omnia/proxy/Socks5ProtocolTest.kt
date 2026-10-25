package com.eliyardo.omnia.proxy

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer

class Socks5ProtocolTest {

    companion object {
        const val SOCKS_VERSION: Byte = 0x05
        const val AUTH_NONE: Byte = 0x00
        const val AUTH_USER_PASS: Byte = 0x02
        const val AUTH_NO_ACCEPTABLE: Byte = 0xFF.toByte()

        const val CMD_CONNECT: Byte = 0x01
        const val CMD_BIND: Byte = 0x02
        const val CMD_UDP_ASSOCIATE: Byte = 0x03

        const val ATYP_IPV4: Byte = 0x01
        const val ATYP_DOMAIN: Byte = 0x03
        const val ATYP_IPV6: Byte = 0x04

        const val REP_SUCCESS: Byte = 0x00
        const val REP_COMMAND_NOT_SUPPORTED: Byte = 0x07
    }

    @Test
    fun testNoAuthHandshakeNegotiation() {
        // Client sends: VER=5, NMETHODS=1, METHOD=[0x00 (NO AUTH)]
        val clientGreeting = byteArrayOf(0x05, 0x01, 0x00)
        val input = ByteArrayInputStream(clientGreeting)
        val output = ByteArrayOutputStream()

        // Simulating server logic for auth negotiation
        val version = input.read().toByte()
        assertEquals(SOCKS_VERSION, version)

        val nmethods = input.read()
        val methods = ByteArray(nmethods)
        input.read(methods)
        assertTrue(methods.contains(AUTH_NONE))

        // Server chooses NO AUTH
        output.write(byteArrayOf(SOCKS_VERSION, AUTH_NONE))

        val serverResponse = output.toByteArray()
        assertArrayEquals(byteArrayOf(0x05, 0x00), serverResponse)
    }

    @Test
    fun testUserPassHandshakeNegotiation() {
        // Client sends: VER=5, NMETHODS=2, METHODS=[0x00 (NO AUTH), 0x02 (USER/PASS)]
        val clientGreeting = byteArrayOf(0x05, 0x02, 0x00, 0x02)
        val input = ByteArrayInputStream(clientGreeting)
        val output = ByteArrayOutputStream()

        val version = input.read().toByte()
        val nmethods = input.read()
        val methods = ByteArray(nmethods)
        input.read(methods)

        val serverRequiresAuth = true
        if (serverRequiresAuth && methods.contains(AUTH_USER_PASS)) {
            output.write(byteArrayOf(SOCKS_VERSION, AUTH_USER_PASS))
        } else {
            output.write(byteArrayOf(SOCKS_VERSION, AUTH_NO_ACCEPTABLE))
        }

        assertArrayEquals(byteArrayOf(0x05, 0x02), output.toByteArray())
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
        val subVer = input.read()
        assertEquals(1, subVer)

        val ulen = input.read()
        val userBytes = ByteArray(ulen)
        input.read(userBytes)
        val user = String(userBytes, Charsets.UTF_8)

        val plen = input.read()
        val passBytes = ByteArray(plen)
        input.read(passBytes)
        val pass = String(passBytes, Charsets.UTF_8)

        assertEquals("admin", user)
        assertEquals("secret", pass)

        val success = (user == "admin" && pass == "secret")
        val response = byteArrayOf(0x01, if (success) 0x00 else 0x01)
        assertArrayEquals(byteArrayOf(0x01, 0x00), response)
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
        input.read() // subVer
        val ulen = input.read()
        val userBytes = ByteArray(ulen).also { input.read(it) }
        val plen = input.read()
        val passBytes = ByteArray(plen).also { input.read(it) }

        val success = (String(userBytes) == "admin" && String(passBytes) == "secret")
        val response = byteArrayOf(0x01, if (success) 0x00 else 0x01)
        assertArrayEquals(byteArrayOf(0x01, 0x01), response)
    }

    @Test
    fun testIpv4ConnectRequestParsing() {
        // VER=5, CMD=1 (CONNECT), RSV=0, ATYP=1 (IPv4), ADDR=1.2.3.4, PORT=80 (0x0050)
        val requestBytes = byteArrayOf(
            0x05, 0x01, 0x00, 0x01,
            1, 2, 3, 4,
            0x00, 0x50
        )

        val buf = ByteBuffer.wrap(requestBytes)
        val ver = buf.get()
        val cmd = buf.get()
        val rsv = buf.get()
        val atyp = buf.get()

        assertEquals(0x05.toByte(), ver)
        assertEquals(CMD_CONNECT, cmd)
        assertEquals(0.toByte(), rsv)
        assertEquals(ATYP_IPV4, atyp)

        val ipBytes = ByteArray(4)
        buf.get(ipBytes)
        val host = InetAddress.getByAddress(ipBytes).hostAddress
        val port = buf.short.toInt() and 0xFFFF

        assertEquals("1.2.3.4", host)
        assertEquals(80, port)
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

        val buf = ByteBuffer.wrap(stream.toByteArray())
        buf.get() // ver
        buf.get() // cmd
        buf.get() // rsv
        val atyp = buf.get()
        assertEquals(ATYP_DOMAIN, atyp)

        val dlen = buf.get().toInt() and 0xFF
        val domainBytes = ByteArray(dlen)
        buf.get(domainBytes)
        val host = String(domainBytes, Charsets.US_ASCII)
        val port = buf.short.toInt() and 0xFFFF

        assertEquals("example.com", host)
        assertEquals(443, port)
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

        val buf = ByteBuffer.wrap(stream.toByteArray())
        buf.position(3)
        val atyp = buf.get()
        assertEquals(ATYP_IPV6, atyp)

        val addrBytes = ByteArray(16)
        buf.get(addrBytes)
        val host = InetAddress.getByAddress(addrBytes).hostAddress
        val port = buf.short.toInt() and 0xFFFF

        assertEquals(InetAddress.getByName("2001:db8::1").hostAddress, host)
        assertEquals(8080, port)
    }

    @Test
    fun testUnsupportedCommandReturnsProperError() {
        val cmd = CMD_UDP_ASSOCIATE
        val isSupported = (cmd == CMD_CONNECT)
        assertFalse(isSupported)

        // SOCKS5 reply format: VER=5, REP=CMD_NOT_SUPPORTED, RSV=0, ATYP=1, ADDR=0.0.0.0, PORT=0
        val reply = ByteBuffer.allocate(10).apply {
            put(SOCKS_VERSION)
            put(REP_COMMAND_NOT_SUPPORTED)
            put(0x00)
            put(ATYP_IPV4)
            put(byteArrayOf(0, 0, 0, 0))
            putShort(0)
        }.array()

        assertEquals(0x05.toByte(), reply[0])
        assertEquals(0x07.toByte(), reply[1])
    }
}
