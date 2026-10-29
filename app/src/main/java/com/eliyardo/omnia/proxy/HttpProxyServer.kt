package com.eliyardo.omnia.proxy

import android.util.Log
import com.eliyardo.omnia.settings.AppSettings
import com.eliyardo.omnia.tunnel.TunnelEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class HttpProxyServer(
    private val tunnelEngine: TunnelEngine,
    private val bindAddress: String = "127.0.0.1",
    private val port: Int = AppSettings.DEFAULT_HTTP_PORT,
    private val authRequired: Boolean = false,
    private val authUsername: String = "",
    private val authPassword: String = "",
    private val remoteDns: Boolean = true
) {
    companion object {
        private const val TAG = "HttpProxyServer"
        private const val BUFFER_SIZE = 32768
        private const val CONNECT_TIMEOUT_MS = 30000
        private const val PRE_AUTH_TIMEOUT_MS = 5000
        private const val RELAY_IDLE_TIMEOUT_MS = 900_000 // 15-minute idle timeout to balance long-lived sessions and anti-DoS
        private const val MAX_HEADER_SIZE = 8192
        private const val MAX_CONCURRENT_CONNECTIONS = 64
        private const val CHANNEL_CAPACITY = 128
    }
    
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val activeConnections = AtomicInteger(0)
    private val activeSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val proxyExecutor = java.util.concurrent.Executors.newFixedThreadPool(MAX_CONCURRENT_CONNECTIONS)
    private val proxyDispatcher = proxyExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(proxyDispatcher + SupervisorJob())
    
    val isRunning: Boolean get() = running.get()
    val connectionCount: Int get() = activeConnections.get()
    
    fun start(): Boolean {
        if (running.get()) return true
        
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(bindAddress, port))
            serverSocket = ss
            running.set(true)
            
            scope.launch {
                Log.i(TAG, "HTTP proxy server started on $bindAddress:$port")
                acceptLoop(ss)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP proxy on $bindAddress:$port", e)
            false
        }
    }
    
    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.d(TAG, "Error closing server socket", e)
        }
        serverSocket = null
        for (sock in activeSockets) {
            try { sock.close() } catch (_: Exception) {}
        }
        activeSockets.clear()
        scope.cancel()
        proxyExecutor.shutdownNow()
        Log.i(TAG, "HTTP proxy server stopped")
    }
    
    private suspend fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            try {
                val clientSocket = withContext(proxyDispatcher) { ss.accept() }
                if (activeConnections.get() >= MAX_CONCURRENT_CONNECTIONS) {
                    try {
                        val out = clientSocket.getOutputStream()
                        out.write("HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                        clientSocket.close()
                    } catch (_: Exception) {}
                    continue
                }
                activeSockets.add(clientSocket)
                activeConnections.incrementAndGet()
                scope.launch {
                    try {
                        handleClient(clientSocket)
                    } catch (e: Exception) {
                        if (running.get()) {
                            Log.d(TAG, "Client handler error: ${e.message}")
                        }
                    } finally {
                        try { clientSocket.close() } catch (_: Exception) {}
                        activeSockets.remove(clientSocket)
                        activeConnections.decrementAndGet()
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "Accept error: ${e.message}")
                    delay(100)
                }
            }
        }
    }
    
    private suspend fun handleClient(clientSocket: Socket) = withContext(proxyDispatcher) {
        // Enforce short pre-authentication timeout to prevent slot exhaustion
        clientSocket.soTimeout = PRE_AUTH_TIMEOUT_MS
        val input = clientSocket.getInputStream()
        val output = clientSocket.getOutputStream()
        
        // Read HTTP request line and headers
        val headerBytes = readHttpHeaders(input) ?: run {
            sendErrorResponse(output, 400, "Bad Request")
            return@withContext
        }
        
        val headerString = String(headerBytes, Charsets.US_ASCII)
        val lines = headerString.split("\r\n")
        if (lines.isEmpty()) {
            sendErrorResponse(output, 400, "Bad Request")
            return@withContext
        }
        
        val requestLine = lines[0]
        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            sendErrorResponse(output, 400, "Bad Request")
            return@withContext
        }
        
        val method = parts[0].uppercase()
        val target = parts[1]
        
        // Check authentication
        if (authRequired) {
            val authHeader = lines.find { it.startsWith("Proxy-Authorization:", ignoreCase = true) }
            if (!validateAuth(authHeader)) {
                sendErrorResponse(output, 407, "Proxy Authentication Required",
                    "Proxy-Authenticate: Basic realm=\"Omnia\"\r\n")
                return@withContext
            }
        }
        
        when (method) {
            "CONNECT" -> handleConnect(clientSocket, input, output, target)
            "GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH" -> {
                handlePlainHttp(clientSocket, input, output, method, target, lines, headerBytes)
            }
            else -> sendErrorResponse(output, 405, "Method Not Allowed")
        }
    }
    
    private fun validateAuth(authHeader: String?): Boolean {
        if (authHeader == null) return false
        // Fail-closed: refuse auth if server credentials are not properly set
        if (authUsername.isBlank() || authPassword.isBlank()) return false
        
        val parts = authHeader.split(":", limit = 2)
        if (parts.size < 2) return false
        
        val value = parts[1].trim()
        if (!value.startsWith("Basic ", ignoreCase = true)) return false
        
        return try {
            val decoded = String(android.util.Base64.decode(
                value.substring(6), android.util.Base64.DEFAULT
            ))
            val colonIndex = decoded.indexOf(':')
            if (colonIndex < 0) return false
            
            val username = decoded.substring(0, colonIndex)
            val password = decoded.substring(colonIndex + 1)
            
            // Reject empty user/password headers
            if (username.isEmpty() || password.isEmpty()) return false
            
            val userMatches = java.security.MessageDigest.isEqual(
                username.toByteArray(Charsets.UTF_8),
                authUsername.toByteArray(Charsets.UTF_8)
            )
            val passMatches = java.security.MessageDigest.isEqual(
                password.toByteArray(Charsets.UTF_8),
                authPassword.toByteArray(Charsets.UTF_8)
            )
            userMatches && passMatches
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun handleConnect(
        clientSocket: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        target: String
    ) {
        // Parse host:port with bracketed IPv6 support
        val (host, port) = if (target.startsWith("[")) {
            val closeBracket = target.indexOf(']')
            if (closeBracket < 0) {
                sendErrorResponse(clientOutput, 400, "Bad Request - malformed IPv6 host")
                return
            }
            val h = target.substring(1, closeBracket)
            val colon = target.indexOf(':', closeBracket)
            val p = if (colon > 0) target.substring(colon + 1).toIntOrNull() else null
            Pair(h, p)
        } else {
            val colon = target.lastIndexOf(':')
            val h = if (colon >= 0) target.substring(0, colon) else target
            val p = if (colon >= 0) target.substring(colon + 1).toIntOrNull() else null
            Pair(h, p)
        }

        if (port == null || port !in 1..65535) {
            sendErrorResponse(clientOutput, 400, "Bad Request - invalid port")
            return
        }
        
        Log.d(TAG, "CONNECT $host:$port")
        
        val resolvedHost = if (remoteDns) {
            host // Pass domain for remote resolution
        } else {
            try {
                withContext(Dispatchers.IO) {
                    InetAddress.getByName(host).hostAddress
                } ?: run {
                    sendErrorResponse(clientOutput, 502, "Bad Gateway - DNS resolution failed")
                    return
                }
            } catch (e: Exception) {
                sendErrorResponse(clientOutput, 502, "Bad Gateway - DNS resolution failed")
                return
            }
        }
        
        // Create tunnel connection
        val completableDeferred = CompletableDeferred<Boolean>()
        val dataChannel = Channel<ByteArray>(CHANNEL_CAPACITY)
        val connectionClosed = AtomicBoolean(false)
        
        val callback = object : TunnelEngine.TunnelConnectionCallback {
            override fun onConnected(connId: Int) {
                completableDeferred.complete(true)
            }
            
            override fun onDataReceived(connId: Int, data: ByteArray, offset: Int, length: Int) {
                val copy = data.copyOfRange(offset, offset + length)
                val result = dataChannel.trySend(copy)
                if (result.isFailure && !connectionClosed.get()) {
                    Log.w(TAG, "Proxy relay channel full or closed (connId=$connId); aborting connection to prevent data loss")
                    connectionClosed.set(true)
                    dataChannel.close()
                    tunnelEngine.closeConnection(connId)
                    try { clientSocket.close() } catch (_: Exception) {}
                }
            }
            
            override fun onConnectionClosed(connId: Int, reason: String) {
                connectionClosed.set(true)
                if (!completableDeferred.isCompleted) completableDeferred.complete(false)
                dataChannel.close()
            }
            
            override fun onError(connId: Int, error: String) {
                connectionClosed.set(true)
                if (!completableDeferred.isCompleted) completableDeferred.complete(false)
                dataChannel.close()
            }
        }
        
        val connId = tunnelEngine.openConnection(resolvedHost, port, callback)
        if (connId < 0) {
            sendErrorResponse(clientOutput, 502, "Bad Gateway - tunnel connection failed")
            return
        }
        
        val connected = try {
            withTimeout(CONNECT_TIMEOUT_MS.toLong()) {
                completableDeferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            tunnelEngine.closeConnection(connId)
            sendErrorResponse(clientOutput, 504, "Gateway Timeout")
            return
        }
        
        if (!connected) {
            tunnelEngine.closeConnection(connId)
            sendErrorResponse(clientOutput, 502, "Bad Gateway - connection refused")
            return
        }
        
        // Send 200 Connection Established and set relay idle timeout to prevent leaking connections
        clientOutput.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        clientOutput.flush()
        clientSocket.soTimeout = RELAY_IDLE_TIMEOUT_MS
        
        // Bidirectional relay (same as SOCKS5)
        try {
            coroutineScope {
                val clientToTunnel = launch(proxyDispatcher) {
                    try {
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (!connectionClosed.get() && running.get()) {
                            val read = clientInput.read(buffer)
                            if (read <= 0) break
                            val sent = tunnelEngine.sendData(connId, buffer, 0, read)
                            if (sent < 0) {
                                Log.w(TAG, "sendData failed / buffer overflow; closing conn $connId")
                                break
                            }
                        }
                    } catch (e: IOException) { }
                    finally { tunnelEngine.closeConnection(connId) }
                }
                
                val tunnelToClient = launch(proxyDispatcher) {
                    try {
                        for (data in dataChannel) {
                            clientOutput.write(data)
                            clientOutput.flush()
                            tunnelEngine.acknowledgeData(connId, data.size)
                        }
                    } catch (e: IOException) { }
                    finally { try { clientSocket.close() } catch (_: Exception) {} }
                }
                
                select<Unit> {
                    clientToTunnel.onJoin {}
                    tunnelToClient.onJoin {}
                }
                
                clientToTunnel.cancel()
                tunnelToClient.cancel()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Relay ended: ${e.message}")
        } finally {
            tunnelEngine.closeConnection(connId)
        }
    }
    
    private suspend fun handlePlainHttp(
        clientSocket: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        method: String,
        target: String,
        headerLines: List<String>,
        rawHeaders: ByteArray
    ) {
        // For plain HTTP proxy requests (not CONNECT), we need to:
        // 1. Parse the absolute URI to get host:port
        // 2. Connect through tunnel
        // 3. Forward the request (rewriting the URI to relative)
        // 4. Relay the response
        
        val uri = try {
            java.net.URI(target)
        } catch (e: Exception) {
            sendErrorResponse(clientOutput, 400, "Bad Request - invalid URI")
            return
        }
        
        val hostRaw = uri.host ?: run {
            sendErrorResponse(clientOutput, 400, "Bad Request - missing host")
            return
        }
        val host = if (hostRaw.startsWith("[") && hostRaw.endsWith("]")) {
            hostRaw.substring(1, hostRaw.length - 1)
        } else hostRaw
        val port = if (uri.port > 0) uri.port else 80
        val rawPath = uri.rawPath
        val path = (if (rawPath.isNullOrEmpty()) "/" else rawPath) + (if (uri.rawQuery != null) "?${uri.rawQuery}" else "")
        
        Log.d(TAG, "HTTP $method $host:$port$path")
        
        // Connect through tunnel
        val completableDeferred = CompletableDeferred<Boolean>()
        val dataChannel = Channel<ByteArray>(CHANNEL_CAPACITY)
        val connectionClosed = AtomicBoolean(false)
        
        val callback = object : TunnelEngine.TunnelConnectionCallback {
            override fun onConnected(connId: Int) { completableDeferred.complete(true) }
            override fun onDataReceived(connId: Int, data: ByteArray, offset: Int, length: Int) {
                val copy = data.copyOfRange(offset, offset + length)
                val result = dataChannel.trySend(copy)
                if (result.isFailure && !connectionClosed.get()) {
                    Log.w(TAG, "Plain HTTP relay channel full or closed (connId=$connId); aborting connection to prevent data loss")
                    connectionClosed.set(true)
                    dataChannel.close()
                    tunnelEngine.closeConnection(connId)
                    try { clientSocket.close() } catch (_: Exception) {}
                }
            }
            override fun onConnectionClosed(connId: Int, reason: String) {
                connectionClosed.set(true)
                if (!completableDeferred.isCompleted) completableDeferred.complete(false)
                dataChannel.close()
            }
            override fun onError(connId: Int, error: String) {
                connectionClosed.set(true)
                if (!completableDeferred.isCompleted) completableDeferred.complete(false)
                dataChannel.close()
            }
        }
        
        val resolvedHost = if (remoteDns) host else {
            try { withContext(Dispatchers.IO) { InetAddress.getByName(host).hostAddress } ?: host }
            catch (e: Exception) { host }
        }
        
        val connId = tunnelEngine.openConnection(resolvedHost, port, callback)
        if (connId < 0) {
            sendErrorResponse(clientOutput, 502, "Bad Gateway")
            return
        }
        
        val connected = try {
            withTimeout(CONNECT_TIMEOUT_MS.toLong()) { completableDeferred.await() }
        } catch (e: TimeoutCancellationException) {
            tunnelEngine.closeConnection(connId)
            sendErrorResponse(clientOutput, 504, "Gateway Timeout")
            return
        }
        
        if (!connected) {
            tunnelEngine.closeConnection(connId)
            sendErrorResponse(clientOutput, 502, "Bad Gateway")
            return
        }
        
        // Rewrite request line to relative URI and forward
        val version = headerLines[0].split(" ").last()
        val rewrittenRequest = StringBuilder()
        rewrittenRequest.append("$method $path $version\r\n")
        
        for (i in 1 until headerLines.size) {
            val line = headerLines[i]
            if (line.startsWith("Proxy-", ignoreCase = true)) continue
            if (line.startsWith("Connection:", ignoreCase = true)) continue
            rewrittenRequest.append("$line\r\n")
        }
        rewrittenRequest.append("Connection: close\r\n\r\n")
        
        tunnelEngine.sendData(connId, rewrittenRequest.toString().toByteArray())
        
        // Set relay idle timeout for active plain HTTP streaming session
        clientSocket.soTimeout = RELAY_IDLE_TIMEOUT_MS
        
        // Relay response back
        try {
            coroutineScope {
                val clientToTunnel = launch(proxyDispatcher) {
                    try {
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (!connectionClosed.get() && running.get()) {
                            val read = clientInput.read(buffer)
                            if (read <= 0) break
                            val sent = tunnelEngine.sendData(connId, buffer, 0, read)
                            if (sent < 0) {
                                Log.w(TAG, "sendData failed / buffer overflow; closing conn $connId")
                                break
                            }
                        }
                    } catch (e: IOException) { }
                    finally { tunnelEngine.closeConnection(connId) }
                }
                
                val tunnelToClient = launch(proxyDispatcher) {
                    try {
                        for (data in dataChannel) {
                            clientOutput.write(data)
                            clientOutput.flush()
                            tunnelEngine.acknowledgeData(connId, data.size)
                        }
                    } catch (e: IOException) { }
                    finally { try { clientSocket.close() } catch (_: Exception) {} }
                }
                
                select<Unit> {
                    clientToTunnel.onJoin {}
                    tunnelToClient.onJoin {}
                }
                clientToTunnel.cancel()
                tunnelToClient.cancel()
            }
        } finally {
            tunnelEngine.closeConnection(connId)
        }
    }
    
    private fun readHttpHeaders(input: InputStream): ByteArray? {
        val buffer = ByteArray(MAX_HEADER_SIZE)
        var offset = 0
        val deadline = System.currentTimeMillis() + PRE_AUTH_TIMEOUT_MS
        val bufferedInput = if (input is java.io.BufferedInputStream) input else java.io.BufferedInputStream(input, 4096)
        
        while (offset < MAX_HEADER_SIZE - 1) {
            if (System.currentTimeMillis() > deadline) {
                return null // Slowloris timeout protection
            }
            val b = bufferedInput.read()
            if (b < 0) return null
            buffer[offset++] = b.toByte()
            
            // Check for end of headers: \r\n\r\n
            if (offset >= 4 &&
                buffer[offset - 4] == '\r'.code.toByte() &&
                buffer[offset - 3] == '\n'.code.toByte() &&
                buffer[offset - 2] == '\r'.code.toByte() &&
                buffer[offset - 1] == '\n'.code.toByte()
            ) {
                return buffer.copyOf(offset)
            }
        }
        
        return null // Header too large
    }
    
    private fun sendErrorResponse(output: OutputStream, code: Int, message: String, extraHeaders: String = "") {
        val body = "<html><body><h1>$code $message</h1></body></html>"
        val response = "HTTP/1.1 $code $message\r\n" +
            "Content-Type: text/html\r\n" +
            "Content-Length: ${body.length}\r\n" +
            "Connection: close\r\n" +
            extraHeaders +
            "\r\n" +
            body
        try {
            output.write(response.toByteArray())
            output.flush()
        } catch (e: IOException) {
            // Client may have already disconnected
        }
    }
}
