package com.eliyardo.omnia.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object KeystoreHelper {
    private const val TAG = "KeystoreHelper"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS_SETTINGS = "omnia_settings_key"
    const val KEY_ALIAS_PROFILES = "omnia_profiles_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    sealed class DecryptionResult {
        data class Success(val decryptedText: String) : DecryptionResult()
        data class Empty(val reason: String = "Ciphertext was empty") : DecryptionResult()
        data class TagMismatch(val message: String, val cause: Throwable? = null) : DecryptionResult()
        data class KeyInvalidated(val message: String, val cause: Throwable) : DecryptionResult()
        data class Failure(val message: String, val cause: Throwable? = null) : DecryptionResult()
    }

    sealed class DecryptionBytesResult {
        data class Success(val decryptedBytes: ByteArray) : DecryptionBytesResult() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as Success
                return decryptedBytes.contentEquals(other.decryptedBytes)
            }
            override fun hashCode(): Int = decryptedBytes.contentHashCode()
        }
        data class Empty(val reason: String = "Ciphertext was empty") : DecryptionBytesResult()
        data class TagMismatch(val message: String, val cause: Throwable? = null) : DecryptionBytesResult()
        data class KeyInvalidated(val message: String, val cause: Throwable) : DecryptionBytesResult()
        data class Failure(val message: String, val cause: Throwable? = null) : DecryptionBytesResult()
    }

    @Synchronized
    private fun getOrCreateKey(alias: String = KEY_ALIAS_SETTINGS): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun decodeBase64(str: String): ByteArray {
        val res = try {
            Base64.decode(str, Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
        return res ?: java.util.Base64.getDecoder().decode(str)
    }

    private fun encodeBase64(bytes: ByteArray): String {
        val res = try {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
        return res ?: java.util.Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * Encrypts a string using AES-256-GCM via Android KeyStore.
     * Returns Base64-encoded [IV (12 bytes) + Ciphertext + Tag (16 bytes)].
     */
    fun encrypt(plainText: String, alias: String = KEY_ALIAS_SETTINGS): String {
        if (plainText.isEmpty()) return ""
        return try {
            val plainBytes = plainText.toByteArray(Charsets.UTF_8)
            val combined = encryptBytes(plainBytes, null, alias)
            encodeBase64(combined)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt text via Keystore (alias=$alias)", e)
            throw RuntimeException("Keystore encryption failure", e)
        }
    }

    /**
     * Decrypts a Base64-encoded ciphertext with detailed result classification.
     */
    fun decryptWithResult(encryptedText: String, alias: String = KEY_ALIAS_SETTINGS): DecryptionResult {
        if (encryptedText.isEmpty()) return DecryptionResult.Empty()
        val combined = try {
            decodeBase64(encryptedText)
        } catch (e: Throwable) {
            return DecryptionResult.Failure("Invalid Base64 format", e)
        }
        return when (val bytesRes = decryptBytesWithResult(combined, null, alias)) {
            is DecryptionBytesResult.Success -> DecryptionResult.Success(String(bytesRes.decryptedBytes, Charsets.UTF_8))
            is DecryptionBytesResult.Empty -> DecryptionResult.Empty(bytesRes.reason)
            is DecryptionBytesResult.TagMismatch -> DecryptionResult.TagMismatch(bytesRes.message, bytesRes.cause)
            is DecryptionBytesResult.KeyInvalidated -> DecryptionResult.KeyInvalidated(bytesRes.message, bytesRes.cause)
            is DecryptionBytesResult.Failure -> DecryptionResult.Failure(bytesRes.message, bytesRes.cause)
        }
    }

    /**
     * Legacy/convenience decrypt method returning String directly.
     * Logs descriptive warnings on non-success.
     */
    fun decrypt(encryptedText: String, alias: String = KEY_ALIAS_SETTINGS): String {
        return when (val result = decryptWithResult(encryptedText, alias)) {
            is DecryptionResult.Success -> result.decryptedText
            is DecryptionResult.Empty -> ""
            is DecryptionResult.TagMismatch -> {
                Log.w(TAG, "Decryption failed: AEAD auth tag mismatch (data corrupted or key changed)")
                ""
            }
            is DecryptionResult.KeyInvalidated -> {
                Log.e(TAG, "Decryption failed: Keystore key permanently invalidated", result.cause)
                ""
            }
            is DecryptionResult.Failure -> {
                Log.w(TAG, "Decryption failed: ${result.message}", result.cause)
                ""
            }
        }
    }

    /**
     * Encrypts raw bytes using AES-256-GCM with optional AAD binding.
     * Returns byte array formatted as: [IV (12 bytes)] + [Ciphertext + Tag (16 bytes)].
     */
    fun encryptBytes(plainBytes: ByteArray, aad: ByteArray? = null, alias: String = KEY_ALIAS_SETTINGS): ByteArray {
        if (plainBytes.isEmpty()) return ByteArray(0)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = getOrCreateKey(alias)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv ?: ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
        aad?.let { cipher.updateAAD(it) }
        val cipherText = cipher.doFinal(plainBytes)
        val result = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(cipherText, 0, result, iv.size, cipherText.size)
        return result
    }

    /**
     * Decrypts raw bytes using AES-256-GCM with optional AAD verification.
     */
    fun decryptBytesWithResult(combined: ByteArray, aad: ByteArray? = null, alias: String = KEY_ALIAS_SETTINGS): DecryptionBytesResult {
        if (combined.isEmpty()) return DecryptionBytesResult.Empty()
        if (combined.size < GCM_IV_LENGTH + 16) {
            return DecryptionBytesResult.Failure("Ciphertext is too short to contain valid IV and GCM authentication tag")
        }
        return try {
            val iv = ByteArray(GCM_IV_LENGTH)
            val cipherText = ByteArray(combined.size - GCM_IV_LENGTH)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            val secretKey = getOrCreateKey(alias)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            aad?.let { cipher.updateAAD(it) }
            val decrypted = cipher.doFinal(cipherText)
            DecryptionBytesResult.Success(decrypted)
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.e(TAG, "Keystore key permanently invalidated for alias $alias", e)
            DecryptionBytesResult.KeyInvalidated("Keystore key permanently invalidated", e)
        } catch (e: AEADBadTagException) {
            Log.w(TAG, "Authentication tag mismatch during decryption with alias $alias", e)
            DecryptionBytesResult.TagMismatch("Authentication tag mismatch: data may be corrupted or altered", e)
        } catch (e: Exception) {
            Log.w(TAG, "Decryption error for alias $alias: ${e.message}", e)
            DecryptionBytesResult.Failure(e.message ?: "Decryption error", e)
        }
    }

    /**
     * Direct bytes decryption returning null on any failure.
     */
    fun decryptBytes(combined: ByteArray, aad: ByteArray? = null, alias: String = KEY_ALIAS_SETTINGS): ByteArray? {
        return when (val res = decryptBytesWithResult(combined, aad, alias)) {
            is DecryptionBytesResult.Success -> res.decryptedBytes
            else -> null
        }
    }
}
