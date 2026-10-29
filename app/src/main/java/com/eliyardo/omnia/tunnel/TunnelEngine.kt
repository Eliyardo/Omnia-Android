package com.eliyardo.omnia.tunnel

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class TunnelEngine {
    
    companion object {
        private const val TAG = "TunnelEngine"
        
        init {
            try {
                System.loadLibrary("omnia")
            } catch (e: UnsatisfiedLinkError) {
                // Ignore in host unit tests where native library is not present
            }
        }
    }
    
    @Volatile
    private var nativeHandle: Long = 0L
    private val handleLock = java.util.concurrent.locks.ReentrantReadWriteLock()
    private val _state = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    val state: StateFlow<TunnelState> = _state.asStateFlow()
    
    private val _stats = MutableStateFlow(TrafficStats())
    val stats: StateFlow<TrafficStats> = _stats.asStateFlow()
    
    private val connectionCallbacks = ConcurrentHashMap<Int, TunnelConnectionCallback>()
    private val udpConnectionCallbacks = ConcurrentHashMap<Int, TunnelUdpCallback>()
    private val callbackIdGenerator = AtomicLong(0)
    
    data class TrafficStats(
        val bytesIn: Long = 0,
        val bytesOut: Long = 0,
        val bytesInPerSec: Long = 0,
        val bytesOutPerSec: Long = 0
    )
    
    interface TunnelConnectionCallback {
        fun onConnected(connId: Int)
        fun onDataReceived(connId: Int, data: ByteArray, offset: Int, length: Int)
        fun onConnectionClosed(connId: Int, reason: String)
        fun onError(connId: Int, error: String)
    }

    interface TunnelUdpCallback {
        fun onUdpDataReceived(connId: Int, remoteHost: String, remotePort: Int, data: ByteArray, offset: Int, length: Int)
        fun onUdpClosed(connId: Int, reason: String)
    }
    
    @Synchronized
    fun initialize(): Boolean {
        handleLock.writeLock().lock()
        try {
            if (nativeHandle != 0L) return true
            nativeHandle = nativeInit()
            return nativeHandle != 0L
        } finally {
            handleLock.writeLock().unlock()
        }
    }
    
    @Synchronized
    fun connect(
        profileContent: String,
        username: String? = null,
        password: String? = null,
        isTunMode: Boolean = false,
        securityLevel: String = "preferred",
        enforceTls13: Boolean = false,
        protoOverride: String = "adaptive",
        connTimeout: Int = 60,
        ipv6Enabled: Boolean = true,
        bypassLanTun: Boolean = true,
        upstreamProxyHost: String = "",
        upstreamProxyPort: Int = 0,
        upstreamProxyUser: String = "",
        upstreamProxyPass: String = "",
        upstreamProxyAllowCleartext: Boolean = false
    ): Boolean {
        handleLock.writeLock().lock()
        try {
            if (nativeHandle == 0L) {
                Log.i(TAG, "Engine not initialized yet, initializing...")
                if (!initialize()) {
                    Log.e(TAG, "Failed to initialize native engine")
                    return false
                }
            }
            _state.value = TunnelState.Connecting()
            return try {
                nativeConnect(
                    nativeHandle,
                    profileContent,
                    username ?: "",
                    password ?: "",
                    isTunMode,
                    securityLevel,
                    enforceTls13,
                    protoOverride,
                    connTimeout,
                    ipv6Enabled,
                    bypassLanTun,
                    upstreamProxyHost,
                    upstreamProxyPort,
                    upstreamProxyUser,
                    upstreamProxyPass,
                    upstreamProxyAllowCleartext
                ) == 0
            } catch (t: Throwable) {
                Log.e(TAG, "nativeConnect threw exception", t)
                _state.value = TunnelState.Error("Native connect error: ${t.message}")
                false
            }
        } finally {
            handleLock.writeLock().unlock()
        }
    }
    
    @Synchronized
    fun disconnect() {
        handleLock.writeLock().lock()
        try {
            if (nativeHandle == 0L) return
            nativeDisconnect(nativeHandle)
            _state.value = TunnelState.Disconnected
            lastStatsTime = 0L
            lastBytesIn = 0L
            lastBytesOut = 0L
            _stats.value = TrafficStats()
            connectionCallbacks.clear()
            udpConnectionCallbacks.clear()
        } finally {
            handleLock.writeLock().unlock()
        }
    }
    
    @Synchronized
    fun destroy() {
        handleLock.writeLock().lock()
        try {
            if (nativeHandle == 0L) return
            val handle = nativeHandle
            nativeHandle = 0L
            nativeDestroy(handle)
            _state.value = TunnelState.Disconnected
            connectionCallbacks.clear()
            udpConnectionCallbacks.clear()
        } finally {
            handleLock.writeLock().unlock()
        }
    }
    
    fun openConnection(host: String, port: Int, callback: TunnelConnectionCallback): Int {
        if (!handleLock.readLock().tryLock()) return -1
        try {
            if (nativeHandle == 0L) return -1
            val callbackId = callbackIdGenerator.incrementAndGet()
            val connId = nativeOpenConnection(nativeHandle, host, port, callbackId)
            if (connId >= 0) {
                connectionCallbacks[connId] = callback
            }
            return connId
        } finally {
            handleLock.readLock().unlock()
        }
    }
    
    fun sendData(connId: Int, data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        if (!handleLock.readLock().tryLock()) return -1
        try {
            if (nativeHandle == 0L) return -1
            return nativeSendData(nativeHandle, connId, data, offset, length)
        } finally {
            handleLock.readLock().unlock()
        }
    }
    
    fun closeConnection(connId: Int) {
        if (!handleLock.readLock().tryLock()) {
            connectionCallbacks.remove(connId)
            return
        }
        try {
            if (nativeHandle != 0L) {
                nativeCloseConnection(nativeHandle, connId)
            }
        } finally {
            handleLock.readLock().unlock()
        }
        connectionCallbacks.remove(connId)
    }

    fun openUdpConnection(callback: TunnelUdpCallback): Int {
        if (!handleLock.readLock().tryLock()) return -1
        try {
            if (nativeHandle == 0L) return -1
            val callbackId = callbackIdGenerator.incrementAndGet()
            val connId = nativeOpenUdpConnection(nativeHandle, callbackId)
            if (connId >= 0) {
                udpConnectionCallbacks[connId] = callback
            }
            return connId
        } finally {
            handleLock.readLock().unlock()
        }
    }

    fun sendUdpData(connId: Int, host: String, port: Int, data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        if (!handleLock.readLock().tryLock()) return -1
        try {
            if (nativeHandle == 0L) return -1
            return nativeSendUdpData(nativeHandle, connId, host, port, data, offset, length)
        } finally {
            handleLock.readLock().unlock()
        }
    }

    fun closeUdpConnection(connId: Int) {
        if (!handleLock.readLock().tryLock()) {
            udpConnectionCallbacks.remove(connId)
            return
        }
        try {
            if (nativeHandle != 0L) {
                nativeCloseUdpConnection(nativeHandle, connId)
            }
        } finally {
            handleLock.readLock().unlock()
        }
        udpConnectionCallbacks.remove(connId)
    }
    
    fun resolveHostname(hostname: String): String? {
        if (!handleLock.readLock().tryLock()) return null
        try {
            if (nativeHandle == 0L) return null
            return nativeResolveHostname(nativeHandle, hostname)
        } finally {
            handleLock.readLock().unlock()
        }
    }
    
    // --- JNI callbacks (called from native code) ---
    
    @androidx.annotation.Keep
    fun onVpnStateChanged(state: Int, message: String) {
        Log.d(TAG, "VPN state changed: $state - $message")
        _state.value = when (state) {
            0 -> TunnelState.Disconnected
            1 -> TunnelState.Connecting(message)
            2 -> TunnelState.Connected(serverAddress = message)
            3 -> TunnelState.Reconnecting(reason = message)
            4 -> {
                val isRecoverable = !message.contains("AUTH_FAILED", ignoreCase = true) &&
                        !message.contains("Config error", ignoreCase = true) &&
                        !message.contains("FATAL", ignoreCase = true) &&
                        !message.contains("Certificate", ignoreCase = true)
                TunnelState.Error(message, isRecoverable = isRecoverable)
            }
            else -> TunnelState.Error("Unknown state: $state", isRecoverable = false)
        }
    }
    
    @androidx.annotation.Keep
    fun onTunnelConnected(connId: Int) {
        connectionCallbacks[connId]?.onConnected(connId)
    }
    
    @androidx.annotation.Keep
    fun onTunnelDataReceived(connId: Int, data: ByteArray, offset: Int, length: Int) {
        connectionCallbacks[connId]?.onDataReceived(connId, data, offset, length)
    }

    @androidx.annotation.Keep
    fun onTunnelUdpDataReceived(connId: Int, remoteHost: String, remotePort: Int, data: ByteArray, offset: Int, length: Int) {
        udpConnectionCallbacks[connId]?.onUdpDataReceived(connId, remoteHost, remotePort, data, offset, length)
    }
    
    @androidx.annotation.Keep
    fun onTunnelConnectionClosed(connId: Int, reason: String) {
        connectionCallbacks[connId]?.onConnectionClosed(connId, reason)
        connectionCallbacks.remove(connId)
    }
    
    @Volatile private var lastStatsTime: Long = 0L
    @Volatile private var lastBytesIn: Long = 0L
    @Volatile private var lastBytesOut: Long = 0L

    @androidx.annotation.Keep
    fun onStatsUpdated(bytesIn: Long, bytesOut: Long) {
        val now = System.currentTimeMillis()
        val dt = if (lastStatsTime > 0) (now - lastStatsTime) / 1000.0 else 1.0
        val inSpeed = if (dt > 0 && lastStatsTime > 0) ((bytesIn - lastBytesIn) / dt).toLong().coerceAtLeast(0) else 0L
        val outSpeed = if (dt > 0 && lastStatsTime > 0) ((bytesOut - lastBytesOut) / dt).toLong().coerceAtLeast(0) else 0L

        lastStatsTime = now
        lastBytesIn = bytesIn
        lastBytesOut = bytesOut

        _stats.value = TrafficStats(
            bytesIn = bytesIn,
            bytesOut = bytesOut,
            bytesInPerSec = inSpeed,
            bytesOutPerSec = outSpeed
        )
    }
    
    @androidx.annotation.Keep
    fun onLog(level: Int, message: String) {
        when {
            level <= 1 -> Log.e(TAG, message)
            level == 2 -> Log.w(TAG, message)
            level == 3 -> Log.i(TAG, message)
            else -> Log.d(TAG, message)
        }
    }

    fun acknowledgeData(connId: Int, length: Int) {
        if (length <= 0) return
        if (!handleLock.readLock().tryLock()) return
        try {
            if (nativeHandle != 0L) {
                nativeAcknowledgeData(nativeHandle, connId, length)
            }
        } finally {
            handleLock.readLock().unlock()
        }
    }

    var onProtectSocketCallback: ((socket: Int) -> Boolean)? = null
    var onEstablishVpnCallback: ((
        ip: String,
        prefix: Int,
        ipv6: String,
        ipv6Prefix: Int,
        mtu: Int,
        dns: Array<String>,
        routes: Array<String>,
        rerouteGwIpv4: Boolean,
        rerouteGwIpv6: Boolean,
        excludedRoutes: Array<String>
    ) -> Int)? = null

    @androidx.annotation.Keep
    fun onProtectSocket(socket: Int): Boolean {
        return onProtectSocketCallback?.invoke(socket) ?: true
    }

    @androidx.annotation.Keep
    fun onEstablishVpn(
        ip: String,
        prefix: Int,
        ipv6: String,
        ipv6Prefix: Int,
        mtu: Int,
        dns: Array<String>,
        routes: Array<String>,
        rerouteGwIpv4: Boolean,
        rerouteGwIpv6: Boolean,
        excludedRoutes: Array<String>
    ): Int {
        return onEstablishVpnCallback?.invoke(
            ip, prefix, ipv6, ipv6Prefix, mtu, dns, routes,
            rerouteGwIpv4, rerouteGwIpv6, excludedRoutes
        ) ?: -1
    }
    
    fun setCustomDns(dns1: String?, dns2: String?) {
        if (!handleLock.readLock().tryLock()) return
        try {
            if (nativeHandle != 0L) {
                nativeSetCustomDns(nativeHandle, dns1 ?: "", dns2 ?: "")
            }
        } finally {
            handleLock.readLock().unlock()
        }
    }

    // --- Native methods ---
    
    private external fun nativeInit(): Long
    private external fun nativeConnect(
        handle: Long,
        profile: String,
        username: String,
        password: String,
        isTunMode: Boolean,
        securityLevel: String,
        enforceTls13: Boolean,
        protoOverride: String,
        connTimeout: Int,
        ipv6Enabled: Boolean,
        bypassLanTun: Boolean,
        upstreamProxyHost: String,
        upstreamProxyPort: Int,
        upstreamProxyUser: String,
        upstreamProxyPass: String,
        upstreamProxyAllowCleartext: Boolean
    ): Int
    private external fun nativeDisconnect(handle: Long)
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetCustomDns(handle: Long, dns1: String, dns2: String)
    private external fun nativeOpenConnection(handle: Long, host: String, port: Int, callbackId: Long): Int
    private external fun nativeSendData(handle: Long, connId: Int, data: ByteArray, offset: Int, length: Int): Int
    private external fun nativeAcknowledgeData(handle: Long, connId: Int, length: Int)
    private external fun nativeCloseConnection(handle: Long, connId: Int)
    private external fun nativeOpenUdpConnection(handle: Long, callbackId: Long): Int
    private external fun nativeSendUdpData(handle: Long, connId: Int, host: String, port: Int, data: ByteArray, offset: Int, length: Int): Int
    private external fun nativeCloseUdpConnection(handle: Long, connId: Int)
    private external fun nativeResolveHostname(handle: Long, hostname: String): String?
}
