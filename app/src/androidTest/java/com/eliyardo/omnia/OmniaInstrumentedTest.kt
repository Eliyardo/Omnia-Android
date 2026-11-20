package com.eliyardo.omnia

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eliyardo.omnia.security.KeystoreHelper
import com.eliyardo.omnia.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test executing on an Android device or emulator.
 * Tests package identity, Android Keystore AES-256-GCM encryption roundtrip,
 * and AppSettings DataStore initialization.
 */
@RunWith(AndroidJUnit4::class)
class OmniaInstrumentedTest {

    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.eliyardo.omnia", appContext.packageName)
    }

    @Test
    fun testKeystoreEncryptionDecryptionRoundtrip() {
        val plain = "SecretPassword123!@#"
        val encrypted = KeystoreHelper.encrypt(plain)
        assertTrue("Ciphertext must not be empty", encrypted.isNotEmpty())
        assertTrue("Ciphertext must not equal plaintext", encrypted != plain)

        val decrypted = KeystoreHelper.decrypt(encrypted)
        assertEquals("Decrypted text must match original plaintext", plain, decrypted)
    }

    @Test
    fun testAppSettingsInitialization() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AppSettings(appContext)
        assertNotNull("AppSettings must initialize cleanly", settings)
    }
}
