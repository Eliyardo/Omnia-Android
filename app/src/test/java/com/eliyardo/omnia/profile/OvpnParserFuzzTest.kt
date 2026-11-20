package com.eliyardo.omnia.profile

import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.Random

/**
 * Fuzz and property tests for [OvpnParser].
 * Validates that parser is memory-safe, crash-resistant, and bounds-enforced
 * against arbitrary, truncated, oversized, or malicious configurations.
 */
class OvpnParserFuzzTest {

    private lateinit var parser: OvpnParser

    @Before
    fun setUp() {
        parser = OvpnParser()
    }

    @Test
    fun testRandomFuzzInputsNeverCrash() {
        val random = Random(42) // Fixed seed for reproducible tests
        val validDirectives = listOf("remote", "proto", "dev", "port", "cipher", "auth", "client", "nobind", "verb", "ca", "cert", "key")

        for (i in 0 until 500) {
            val sb = StringBuilder()
            val numLines = random.nextInt(30) + 1
            for (lineIdx in 0 until numLines) {
                when (random.nextInt(6)) {
                    0 -> sb.append(validDirectives[random.nextInt(validDirectives.size)])
                        .append(" ")
                        .append(generateRandomAscii(random, random.nextInt(40)))
                        .append("\n")
                    1 -> sb.append("<").append(validDirectives[random.nextInt(validDirectives.size)]).append(">\n")
                    2 -> sb.append("</").append(validDirectives[random.nextInt(validDirectives.size)]).append(">\n")
                    3 -> sb.append(generateRandomAscii(random, random.nextInt(100))).append("\n")
                    4 -> sb.append(generateRandomUnicode(random, random.nextInt(50))).append("\n")
                    5 -> sb.append("\r\n\t  ; # \u0000").append("\n")
                }
            }

            val input = sb.toString()
            val res = parser.parse(input, "fuzz_$i.ovpn")
            assertNotNull("Result must never be null on fuzz input", res)
        }
    }

    @Test
    fun testOversizedLineDoesNotCrash() {
        // Line exceeding 100 KB
        val hugeLine = "remote " + "A".repeat(100_000) + " 1194 udp"
        val res = parser.parse(hugeLine, "huge.ovpn")
        assertNotNull(res)
    }

    @Test
    fun testMalformedPortValues() {
        val testCases = listOf(
            "remote 1.2.3.4 -1 udp",
            "remote 1.2.3.4 0 udp",
            "remote 1.2.3.4 9999999999999999999999999 udp",
            "remote 1.2.3.4 not_a_port udp",
            "port 9999999999999999999999999",
            "port -80",
            "port abc"
        )
        for (tc in testCases) {
            val res = parser.parse(tc, "port_test.ovpn")
            assertNotNull(res)
        }
    }

    @Test
    fun testDeeplyNestedAndUnclosedTags() {
        val testCases = listOf(
            "<ca>\n<ca>\n<cert>\n</key>\n",
            "<ca>\n".repeat(50) + "-----BEGIN CERTIFICATE-----\n",
            "</ca>\n".repeat(50),
            "<auth-user-pass>\nuser\n", // Unclosed auth tag
            "<inline>\n" + "X".repeat(10_000) + "\n</inline>",
            "<<<>>>\n<>\n</>\n"
        )
        for (tc in testCases) {
            val res = parser.parse(tc, "tags.ovpn")
            assertNotNull(res)
        }
    }

    @Test
    fun testNullBytesAndControlCharacters() {
        val sb = StringBuilder()
        sb.append("client\n")
        sb.append("remote\u00001.2.3.4\u00001194\n")
        sb.append("dev tun\u0007\u0008\u001B\n")
        sb.append("<ca>\n\u0000\u0001\u0002\u0003\n</ca>\n")

        val res = parser.parse(sb.toString(), "ctrl.ovpn")
        assertNotNull(res)
    }

    @Test
    fun testUnsupportedAndScriptDirectivesAreAlwaysFlaggedOrStripped() {
        val scriptPayloads = listOf(
            "up \"/system/bin/sh -c id\"",
            "down /data/local/tmp/exploit.sh",
            "script-security 3",
            "plugin /lib/openvpn-plugin-auth-pam.so",
            "tls-verify /etc/openvpn/verify-cn"
        )

        for (directive in scriptPayloads) {
            val config = "client\ndev tun\nremote 1.1.1.1 1194\n$directive\n<ca>\n-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n</ca>\n"
            val res = parser.parse(config, "script.ovpn")
            assertNotNull(res)
            // Unsupported directives should be recorded in unsupported list or warnings
            val firstWord = directive.split(" ")[0]
            val recorded = res.unsupportedDirectives.contains(firstWord) || res.warnings.any { it.contains(firstWord) }
            org.junit.Assert.assertTrue("Directive '$firstWord' must be flagged as unsupported or warned", recorded)
        }
    }

    private fun generateRandomAscii(random: Random, length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~:/?#[]@!$&'()*+,;= \t\r"
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            sb.append(chars[random.nextInt(chars.length)])
        }
        return sb.toString()
    }

    private fun generateRandomUnicode(random: Random, length: Int): String {
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            sb.append(random.nextInt(0xD7FF).toChar())
        }
        return sb.toString()
    }
}
