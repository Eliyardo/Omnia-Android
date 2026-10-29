package com.eliyardo.omnia.service

import com.eliyardo.omnia.settings.AppSettings
import com.eliyardo.omnia.tunnel.ProxyState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConnectionSessionHolderTest {

    @Before
    fun setUp() {
        ConnectionSessionHolder.clear()
    }

    @Test
    fun testStageAndConsumeSession() {
        ConnectionSessionHolder.stageSession(
            profileId = "prof-123",
            profileContent = "client\ndev tun\nremote 1.2.3.4 1194",
            username = "alice",
            password = "secret-password"
        )

        val session = ConnectionSessionHolder.consumeSession("prof-123")
        assertNotNull("Staged session should be retrieved", session)
        assertEquals("prof-123", session?.profileId)
        assertEquals("alice", session?.username)
        assertEquals("secret-password", session?.password)
        assertTrue(session?.profileContent?.contains("remote 1.2.3.4") == true)

        // Session must be consumed (single-use)
        val secondConsume = ConnectionSessionHolder.consumeSession("prof-123")
        assertNull("Consumed session must not be retrievable a second time", secondConsume)
    }

    @Test
    fun testConsumeWithMismatchedProfileIdDoesNotConsume() {
        ConnectionSessionHolder.stageSession(
            profileId = "prof-original",
            profileContent = "content",
            username = "bob",
            password = "pwd"
        )

        val mismatched = ConnectionSessionHolder.consumeSession("prof-different")
        assertNull("Mismatched profile ID must return null", mismatched)

        // The original session should still remain staged until consumed with matching ID
        val matching = ConnectionSessionHolder.consumeSession("prof-original")
        assertNotNull("Matching profile ID should consume the staged session", matching)
        assertEquals("bob", matching?.username)
    }

    @Test
    fun testClearWipesStagedSession() {
        ConnectionSessionHolder.stageSession(
            profileId = "prof-456",
            profileContent = "sensitive content",
            username = "admin",
            password = "super-secret"
        )

        ConnectionSessionHolder.clear()

        val session = ConnectionSessionHolder.consumeSession("prof-456")
        assertNull("Cleared session must return null", session)
    }

    @Test
    fun testDefaultProxyPortsMatchAppSettings() {
        val proxyState = ProxyState()
        assertEquals(
            "ProxyState default socks5Port must match AppSettings.DEFAULT_SOCKS5_PORT",
            AppSettings.DEFAULT_SOCKS5_PORT,
            proxyState.socks5Port
        )
        assertEquals(
            "ProxyState default httpPort must match AppSettings.DEFAULT_HTTP_PORT",
            AppSettings.DEFAULT_HTTP_PORT,
            proxyState.httpPort
        )
        assertEquals(10880, AppSettings.DEFAULT_SOCKS5_PORT)
        assertEquals(10890, AppSettings.DEFAULT_HTTP_PORT)
    }

    @Test
    fun testConcurrentConsumptionOnlyOneSucceeds() {
        ConnectionSessionHolder.stageSession(
            profileId = "prof-race",
            username = "race-user",
            password = "race-password"
        )

        val threadCount = 10
        val results = java.util.concurrent.CopyOnWriteArrayList<ConnectionSessionHolder.Session?>()
        val latch = java.util.concurrent.CountDownLatch(threadCount)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount)

        for (i in 0 until threadCount) {
            executor.submit {
                val session = ConnectionSessionHolder.consumeSession("prof-race")
                results.add(session)
                latch.countDown()
            }
        }

        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        executor.shutdown()

        val nonNullCount = results.filterNotNull().size
        assertEquals("Strictly only one thread may consume the session", 1, nonNullCount)
    }
}
