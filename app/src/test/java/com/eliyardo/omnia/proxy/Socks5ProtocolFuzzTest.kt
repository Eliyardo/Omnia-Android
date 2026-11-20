package com.eliyardo.omnia.proxy

import com.eliyardo.omnia.tunnel.TunnelEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random

/**
 * Fuzzing and robustness tests for SOCKS5 protocol state machine and parser.
 * Tests truncated payloads, invalid versions, malformed addresses, and random byte streams.
 */
class Socks5ProtocolFuzzTest {

    private val engine = TunnelEngine()

    @Test
    fun testHandleAuthWithRandomFuzzStreams() {
        val random = Random(12345)
        val server = Socks5Server(
            tunnelEngine = engine,
            authRequired = true,
            authUsername = "user",
            authPassword = "password"
        )

        for (i in 0 until 500) {
            val length = random.nextInt(100)
            val randomBytes = ByteArray(length)
            random.nextBytes(randomBytes)

            val input = ByteArrayInputStream(randomBytes)
            val output = ByteArrayOutputStream()

            try {
                server.handleAuth(input, output)
            } catch (_: IOException) {
                // Expected when stream ends prematurely during readFully
            } catch (e: Exception) {
                org.junit.Assert.fail("handleAuth threw an unhandled unexpected exception on fuzz input iteration $i: ${e.message}")
            }
        }
    }

    @Test
    fun testTruncatedAuthNegotiation() {
        val server = Socks5Server(tunnelEngine = engine, authRequired = false)
        val truncatedCases = listOf(
            byteArrayOf(), // 0 bytes
            byteArrayOf(0x05), // Only version
            byteArrayOf(0x05, 0x05), // Declares 5 methods but provides 0
            byteArrayOf(0x05, 0x02, 0x01), // Declares 2 methods but provides 1
            byteArrayOf(0x04, 0x01, 0x00) // SOCKS4 version
        )

        for (tc in truncatedCases) {
            val input = ByteArrayInputStream(tc)
            val output = ByteArrayOutputStream()
            val accepted = try {
                server.handleAuth(input, output)
            } catch (_: IOException) {
                false
            }
            assertFalse("Truncated or invalid auth must be rejected", accepted)
        }
    }

    @Test
    fun testMalformedUsernamePasswordSubnegotiation() {
        val server = Socks5Server(
            tunnelEngine = engine,
            authRequired = true,
            authUsername = "admin",
            authPassword = "secret"
        )

        val malformedCases = listOf(
            byteArrayOf(), // Empty
            byteArrayOf(0x02), // Wrong subneg version (not 0x01)
            byteArrayOf(0x01), // Missing ULEN
            byteArrayOf(0x01, 0x05), // ULEN=5 but EOF
            byteArrayOf(0x01, 0x05, 0x61, 0x64, 0x6D, 0x69, 0x6E), // Has username, missing PLEN
            byteArrayOf(0x01, 0x05, 0x61, 0x64, 0x6D, 0x69, 0x6E, 0x06), // PLEN=6 but EOF
            byteArrayOf(0x01, 0xFF.toByte(), 0x00), // ULEN=255, truncated
            byteArrayOf(0x01, 0x00, 0x00) // 0-length username and password
        )

        for (tc in malformedCases) {
            val input = ByteArrayInputStream(tc)
            val output = ByteArrayOutputStream()
            val result = try {
                server.handleUsernamePasswordAuth(input, output)
            } catch (_: IOException) {
                false
            }
            assertFalse("Malformed user/pass auth must fail safely", result)
        }
    }

    @Test
    fun testReadRequestWithRandomFuzzStreams() {
        val random = Random(67890)
        val server = Socks5Server(tunnelEngine = engine)

        for (i in 0 until 500) {
            val length = random.nextInt(150)
            val randomBytes = ByteArray(length)
            random.nextBytes(randomBytes)

            val input = ByteArrayInputStream(randomBytes)
            try {
                val req = server.readRequest(input)
                if (req != null) {
                    org.junit.Assert.assertTrue(req.destPort in 0..65535)
                }
            } catch (_: IOException) {
                // Expected when stream truncates on socket read
            } catch (e: Exception) {
                org.junit.Assert.fail("readRequest threw an unexpected exception on fuzz input iteration $i: ${e.message}")
            }
        }
    }

    @Test
    fun testReadRequestTruncationsAndInvalidAddressTypes() {
        val server = Socks5Server(tunnelEngine = engine)
        val cases = listOf(
            byteArrayOf(), // Empty
            byteArrayOf(0x05),
            byteArrayOf(0x05, 0x01),
            byteArrayOf(0x05, 0x01, 0x00),
            byteArrayOf(0x05, 0x01, 0x00, 0x02), // Unsupported ATYP 0x02
            byteArrayOf(0x05, 0x01, 0x00, 0x05), // Unsupported ATYP 0x05
            byteArrayOf(0x05, 0x01, 0x00, 0x01, 127, 0, 0), // Truncated IPv4 (3 bytes instead of 4)
            byteArrayOf(0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1), // Valid IPv4, missing port
            byteArrayOf(0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, 0x04), // Valid IPv4, only 1 port byte
            byteArrayOf(0x05, 0x01, 0x00, 0x03), // Domain ATYP without length byte
            byteArrayOf(0x05, 0x01, 0x00, 0x03, 0x05, 0x61, 0x62), // Domain declares 5 bytes, gives 2
            byteArrayOf(0x05, 0x01, 0x00, 0x04, 0x20, 0x01) // Truncated IPv6
        )

        for (tc in cases) {
            val input = ByteArrayInputStream(tc)
            val req = try {
                server.readRequest(input)
            } catch (_: IOException) {
                null
            }
            assertNull("Malformed or truncated request must return null", req)
        }
    }
}
