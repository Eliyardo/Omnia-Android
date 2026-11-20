package com.eliyardo.omnia.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Temporary staging area for active connection credentials and profile payloads.
 *
 * This prevents:
 * 1. Intent logging by Android system server and dumpsys.
 * 2. android.os.TransactionTooLargeException from Binder buffer exhaustion on large .ovpn profiles.
 * 3. Residual credential retention in the Android system transaction record.
 */
object ConnectionSessionHolder {
    private const val SESSION_TTL_MS = 30_000L
    private val cleanupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    data class Session(
        val profileId: String,
        val profileContent: String? = null,
        val username: String? = null,
        val password: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val sessionRef = AtomicReference<Session?>(null)

    fun stageSession(
        profileId: String,
        profileContent: String? = null,
        username: String? = null,
        password: String? = null
    ) {
        val session = Session(profileId, profileContent, username, password)
        sessionRef.set(session)
        // Automatic cleanup after TTL in case session is never consumed (e.g. process death or user abort)
        cleanupScope.launch {
            delay(SESSION_TTL_MS + 1000L)
            sessionRef.compareAndSet(session, null)
        }
    }

    fun consumeSession(expectedProfileId: String?): Session? {
        val current = sessionRef.get() ?: return null
        if (System.currentTimeMillis() - current.timestamp > SESSION_TTL_MS) {
            sessionRef.compareAndSet(current, null)
            return null
        }
        if (expectedProfileId == null || current.profileId == expectedProfileId) {
            if (sessionRef.compareAndSet(current, null)) {
                return current
            }
        }
        return null
    }

    fun clear() {
        sessionRef.set(null)
    }
}
