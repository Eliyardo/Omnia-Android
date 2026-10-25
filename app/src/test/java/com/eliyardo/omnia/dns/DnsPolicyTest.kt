package com.eliyardo.omnia.dns

import com.eliyardo.omnia.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test

class DnsPolicyTest {

    @Test
    fun testServerPushedDnsIsDefault() {
        val defaultResolution = AppSettings.resolveDnsIps(AppSettings.DNS_PROVIDER_SERVER, "", "")
        assertNull("Default DNS provider should be server-pushed (null)", defaultResolution)
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
}
