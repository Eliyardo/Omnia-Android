package com.eliyardo.omnia.proxy

import com.eliyardo.omnia.tunnel.TunnelEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.Random

/**
 * Fuzzing and resilience tests for HTTP proxy request headers and authentication parser.
 */
class HttpProxyProtocolFuzzTest {

    private val engine = TunnelEngine()

    @Test
    fun testValidateAuthFuzzing() {
        val server = HttpProxyServer(
            tunnelEngine = engine,
            authRequired = true,
            authUsername = "proxyUser",
            authPassword = "proxyPassword"
        )

        val random = Random(54321)
        for (i in 0 until 500) {
            val length = random.nextInt(100)
            val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/= :;\r\n\t"
            val sb = StringBuilder()
            for (j in 0 until length) {
                sb.append(chars[random.nextInt(chars.length)])
            }
            val header = "Proxy-Authorization: " + sb.toString()
            try {
                val ok = server.validateAuth(header)
                // Random junk must almost certainly be rejected
                assertFalse("Random fuzz header should not validate", ok)
            } catch (e: Exception) {
                org.junit.Assert.fail("validateAuth threw an unhandled exception on fuzz input iteration $i: ${e.message}")
            }
        }
    }

    @Test
    fun testValidateAuthBoundaryCases() {
        val server = HttpProxyServer(
            tunnelEngine = engine,
            authRequired = true,
            authUsername = "admin",
            authPassword = "password123"
        )

        // Valid base64 encoding of "admin:password123"
        val validToken = Base64.getEncoder().encodeToString("admin:password123".toByteArray())
        assertTrue("Valid Basic auth must pass", server.validateAuth("Proxy-Authorization: Basic $validToken"))
        assertTrue("Case-insensitive header name must pass", server.validateAuth("proxy-authorization: Basic $validToken"))
        assertTrue("Case-insensitive Basic scheme must pass", server.validateAuth("Proxy-Authorization: basic $validToken"))

        // Invalid cases
        assertFalse("Null header", server.validateAuth(null))
        assertFalse("Empty header", server.validateAuth(""))
        assertFalse("Missing value", server.validateAuth("Proxy-Authorization:"))
        assertFalse("Bearer scheme", server.validateAuth("Proxy-Authorization: Bearer $validToken"))
        assertFalse("Digest scheme", server.validateAuth("Proxy-Authorization: Digest $validToken"))
        assertFalse("No credentials after scheme", server.validateAuth("Proxy-Authorization: Basic "))

        // Malformed credentials
        val noColon = Base64.getEncoder().encodeToString("adminpassword".toByteArray())
        assertFalse("No colon in credentials", server.validateAuth("Proxy-Authorization: Basic $noColon"))

        val emptyUser = Base64.getEncoder().encodeToString(":password123".toByteArray())
        assertFalse("Empty username", server.validateAuth("Proxy-Authorization: Basic $emptyUser"))

        val emptyPass = Base64.getEncoder().encodeToString("admin:".toByteArray())
        assertFalse("Empty password", server.validateAuth("Proxy-Authorization: Basic $emptyPass"))

        val wrongPass = Base64.getEncoder().encodeToString("admin:wrong".toByteArray())
        assertFalse("Wrong password", server.validateAuth("Proxy-Authorization: Basic $wrongPass"))
    }

    @Test
    fun testReadHttpHeadersTruncatedAndOversized() {
        val server = HttpProxyServer(tunnelEngine = engine)

        // Truncated headers (no \r\n\r\n)
        val truncatedCases = listOf(
            byteArrayOf(),
            "CONNECT example.com:443 HTTP/1.1\r\n".toByteArray(),
            "GET / HTTP/1.1\r\nHost: example.com\r\n".toByteArray()
        )

        for (tc in truncatedCases) {
            val input = ByteArrayInputStream(tc)
            val res = server.readHttpHeaders(input)
            assertNull("Truncated headers without delimiter must return null", res)
        }

        // Oversized header (> 8192 bytes without \r\n\r\n)
        val oversized = ByteArray(10_000) { 'A'.code.toByte() }
        val oversizedInput = ByteArrayInputStream(oversized)
        val res = server.readHttpHeaders(oversizedInput)
        assertNull("Oversized headers exceeding buffer must return null", res)

        // Valid minimal header
        val valid = "CONNECT 1.1.1.1:443 HTTP/1.1\r\nHost: 1.1.1.1:443\r\n\r\n".toByteArray()
        val validRes = server.readHttpHeaders(ByteArrayInputStream(valid))
        assertNotNull("Valid header must be read", validRes)
        org.junit.Assert.assertArrayEquals(valid, validRes)
    }
}
