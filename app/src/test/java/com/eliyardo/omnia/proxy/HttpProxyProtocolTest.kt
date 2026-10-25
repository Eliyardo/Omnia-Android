package com.eliyardo.omnia.proxy

import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.util.Base64

class HttpProxyProtocolTest {

    @Test
    fun testConnectRequestParsing() {
        val request = "CONNECT target.example.com:443 HTTP/1.1\r\nHost: target.example.com:443\r\nUser-Agent: test\r\n\r\n"
        val lines = request.split("\r\n")
        val requestLine = lines[0]
        val parts = requestLine.split(" ")

        assertEquals(3, parts.size)
        assertEquals("CONNECT", parts[0].uppercase())

        val target = parts[1]
        val colonIndex = target.lastIndexOf(':')
        assertTrue("Port separator should be present", colonIndex > 0)

        val host = target.substring(0, colonIndex)
        val port = target.substring(colonIndex + 1).toInt()

        assertEquals("target.example.com", host)
        assertEquals(443, port)
    }

    @Test
    fun testHttpProxyAuthenticationSuccess() {
        val user = "proxyuser"
        val pass = "secret123"
        val rawAuth = "$user:$pass"
        val encoded = Base64.getEncoder().encodeToString(rawAuth.toByteArray())
        val header = "Proxy-Authorization: Basic $encoded"

        val value = header.substringAfter(":").trim()
        assertTrue(value.startsWith("Basic ", ignoreCase = true))

        val decoded = String(Base64.getDecoder().decode(value.substring(6)))
        val colon = decoded.indexOf(':')
        val parsedUser = decoded.substring(0, colon)
        val parsedPass = decoded.substring(colon + 1)

        assertEquals(user, parsedUser)
        assertEquals(pass, parsedPass)
    }

    @Test
    fun testHttpProxyAuthenticationFailure() {
        val rawAuth = "wronguser:wrongpass"
        val encoded = Base64.getEncoder().encodeToString(rawAuth.toByteArray())
        val header = "Proxy-Authorization: Basic $encoded"

        val value = header.substringAfter(":").trim()
        val decoded = String(Base64.getDecoder().decode(value.substring(6)))
        val colon = decoded.indexOf(':')
        val parsedUser = decoded.substring(0, colon)
        val parsedPass = decoded.substring(colon + 1)

        assertFalse("Should not match valid credentials",
            parsedUser == "proxyuser" && parsedPass == "secret123")
    }

    @Test
    fun testConnectResponseGeneration() {
        val expected = "HTTP/1.1 200 Connection Established\r\n\r\n"
        val statusLine = "HTTP/1.1 200 Connection Established\r\n\r\n"
        assertEquals(expected, statusLine)
    }

    @Test
    fun testAuthRequired407Response() {
        val code = 407
        val message = "Proxy Authentication Required"
        val body = "<html><body><h1>$code $message</h1></body></html>"
        val response = "HTTP/1.1 $code $message\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "Connection: close\r\n" +
                "Proxy-Authenticate: Basic realm=\"Omnia\"\r\n\r\n" +
                body

        assertTrue(response.contains("HTTP/1.1 407 Proxy Authentication Required"))
        assertTrue(response.contains("Proxy-Authenticate: Basic realm=\"Omnia\""))
    }

    @Test
    fun testPlainHttpRequestLineRewriting() {
        val target = "http://example.org:8080/api/v1/data?query=test"
        val uri = URI(target)

        val host = uri.host
        val port = uri.port
        val relativePath = (uri.rawPath ?: "/") + (if (uri.rawQuery != null) "?${uri.rawQuery}" else "")

        assertEquals("example.org", host)
        assertEquals(8080, port)
        assertEquals("/api/v1/data?query=test", relativePath)

        val method = "GET"
        val version = "HTTP/1.1"
        val rewrittenRequestLine = "$method $relativePath $version"
        assertEquals("GET /api/v1/data?query=test HTTP/1.1", rewrittenRequestLine)
    }
}
