package com.eliyardo.omnia.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [KeystoreHelper] boundary conditions, input validation, and typed error handling.
 */
class KeystoreHelperTest {

    @Test
    fun testEmptyInputReturnsEmptyResult() {
        val res = KeystoreHelper.decryptWithResult("")
        assertTrue("Empty string should return DecryptionResult.Empty", res is KeystoreHelper.DecryptionResult.Empty)
        assertEquals("", KeystoreHelper.decrypt(""))
    }

    @Test
    fun testInvalidBase64ReturnsFailure() {
        // Characters not in Base64 alphabet
        val res = KeystoreHelper.decryptWithResult("%%%NOT_BASE64&&&")
        assertTrue("Invalid Base64 should return DecryptionResult.Failure", res is KeystoreHelper.DecryptionResult.Failure)
        assertEquals("", KeystoreHelper.decrypt("%%%NOT_BASE64&&&"))
    }

    @Test
    fun testEmptyBytesReturnsEmptyResult() {
        val res = KeystoreHelper.decryptBytesWithResult(ByteArray(0))
        assertTrue("Empty byte array should return DecryptionBytesResult.Empty", res is KeystoreHelper.DecryptionBytesResult.Empty)
        org.junit.Assert.assertNull(KeystoreHelper.decryptBytes(ByteArray(0)))
    }

    @Test
    fun testTooShortBytesReturnsFailure() {
        // Less than 12 bytes IV + 16 bytes tag (28 bytes)
        val shortBytes = ByteArray(10) { 0x01 }
        val res = KeystoreHelper.decryptBytesWithResult(shortBytes)
        assertTrue("Bytes too short should return DecryptionBytesResult.Failure", res is KeystoreHelper.DecryptionBytesResult.Failure)
    }
}
