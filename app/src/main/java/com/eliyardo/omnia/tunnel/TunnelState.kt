package com.eliyardo.omnia.tunnel

import com.eliyardo.omnia.settings.AppSettings

sealed class TunnelState {
    data object Disconnected : TunnelState()
    data class Connecting(val serverAddress: String = "") : TunnelState()
    data class Connected(
        val serverAddress: String = "",
        val assignedIp: String = "",
        val connectedSince: Long = System.currentTimeMillis(),
        val bytesIn: Long = 0,
        val bytesOut: Long = 0
    ) : TunnelState()
    data class Reconnecting(val attempt: Int = 1, val reason: String = "") : TunnelState()
    data class Error(val message: String, val isRecoverable: Boolean = false) : TunnelState()
}

data class ProxyState(
    val socks5Running: Boolean = false,
    val socks5Port: Int = AppSettings.DEFAULT_SOCKS5_PORT,
    val httpRunning: Boolean = false,
    val httpPort: Int = AppSettings.DEFAULT_HTTP_PORT,
    val activeConnections: Int = 0
)
