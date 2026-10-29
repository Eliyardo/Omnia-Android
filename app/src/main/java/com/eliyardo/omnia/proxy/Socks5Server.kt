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
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class Socks5Server(
    private val tunnelEngine: TunnelEngine,
    private val bindAddress: String = "127.0.0.1",
    private val port: Int = AppSettings.DEFAULT_SOCKS5_PORT,
    private val authRequired: Boolean = false,
    private val authUsername: String = "",
    private val authPassword: String = "",
    private val remoteDns: Boolean = true
) {
    companion object {
        private const val TAG = "Socks5Server"
        private const val SOCKS_VERSION: Byte = 0x05
        private const val AUTH_NONE: Byte = 0x00
        private const val AUTH_USERNAME_PASSWORD: Byte = 0x02
        private const val AUTH_NO_ACCEPTABLE: Byte = 0xFF.toByte()
        private const val CMD_CONNECT: Byte = 0x01
        private const val CMD_BIND: Byte = 0x02
        private const val CMD_UDP_ASSOCIATE: Byte = 0x03
        private const val ATYP_IPV4: Byte = 0x01
        private const val ATYP_DOMAIN: Byte = 0x03
        private const val ATYP_IPV6: Byte = 0x04
        private const val REPLY_SUCCESS: Byte = 0x00
        private const val REPLY_GENERAL_FAILURE: Byte = 0x01
        private const val REPLY_NOT_ALLOWED: Byte = 0x02
        private const val REPLY_NETWORK_UNREACHABLE: Byte = 0x03
        private const val REPLY_HOST_UNREACHABLE: Byte = 0x04
        private const val REPLY_CONNECTION_REFUSED: Byte = 0x05
        private const val REPLY_TTL_EXPIRED: Byte = 0x06
        private const val REPLY_COMMAND_NOT_SUPPORTED: Byte = 0x07
        private const val REPLY_ADDR_TYPE_NOT_SUPPORTED: Byte = 0x08
        
        private const val BUFFER_SIZE = 32768
        private const val CONNECT_TIMEOUT_MS = 30000
        private const val PRE_AUTH_TIMEOUT_MS = 5000
        private const val RELAY_IDLE_TIMEOUT_MS = 900_000 // 15-minute idle timeout to balance long-lived sessions and anti-DoS
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
                Log.i(TAG, "SOCKS5 server started on $bindAddress:$port")
                acceptLoop(ss)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SOCKS5 server on $bindAddress:$port", e)
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
        Log.i(TAG, "SOCKS5 server stopped")
    }
    
    private suspend fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            try {
                val clientSocket = withContext(proxyDispatcher) { ss.accept() }
                if (activeConnections.get() >= MAX_CONCURRENT_CONNECTIONS) {
                    try {
                        val out = clientSocket.getOutputStream()
                        out.write(byteArrayOf(SOCKS_VERSION, AUTH_NO_ACCEPTABLE))
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
        
        // Step 1: Authentication negotiation
        if (!handleAuth(input, output)) return@withContext
        
        // Step 2: Read request
        val request = readRequest(input) ?: run {
            sendReply(output, REPLY_GENERAL_FAILURE)
            return@withContext
        }
        
        // Step 3: Handle command
        when (request.command) {
            CMD_CONNECT -> handleConnect(clientSocket, input, output, request)
            CMD_UDP_ASSOCIATE -> handleUdpAssociate(clientSocket, input, output, request)
            CMD_BIND -> {
                Log.d(TAG, "BIND not supported")
                sendReply(output, REPLY_COMMAND_NOT_SUPPORTED)
            }
            else -> sendReply(output, REPLY_COMMAND_NOT_SUPPORTED)
        }
    }
    
    internal fun handleAuth(input: InputStream, output: OutputStream): Boolean {
        // Read version and method count
        val version = input.read()
        if (version != SOCKS_VERSION.toInt()) {
            Log.d(TAG, "Invalid SOCKS version: $version")
            return false
        }
        
        val nmethods = input.read()
        if (nmethods <= 0) return false
        
        val methods = ByteArray(nmethods)
        readFully(input, methods)
        
        if (authRequired) {
            if (methods.contains(AUTH_USERNAME_PASSWORD)) {
                output.write(byteArrayOf(SOCKS_VERSION, AUTH_USERNAME_PASSWORD))
                output.flush()
                return handleUsernamePasswordAuth(input, output)
            } else {
                output.write(byteArrayOf(SOCKS_VERSION, AUTH_NO_ACCEPTABLE))
                output.flush()
                return false
            }
        } else {
            if (methods.contains(AUTH_NONE)) {
                output.write(byteArrayOf(SOCKS_VERSION, AUTH_NONE))
                output.flush()
                return true
            } else {
                output.write(byteArrayOf(SOCKS_VERSION, AUTH_NO_ACCEPTABLE))
                output.flush()
                return false
            }
        }
    }
    
    internal fun handleUsernamePasswordAuth(input: InputStream, output: OutputStream): Boolean {
        // Fail-closed: refuse auth if server credentials are not properly configured
        if (authUsername.isBlank() || authPassword.isEmpty()) {
            output.write(byteArrayOf(0x01, 0x01))
            output.flush()
            return false
        }
        
        val authVersion = input.read()
        if (authVersion != 0x01) return false
        
        val ulen = input.read()
        if (ulen <= 0) return false
        val usernameBytes = ByteArray(ulen)
        readFully(input, usernameBytes)
        
        val plen = input.read()
        if (plen <= 0) return false
        val passwordBytes = ByteArray(plen)
        readFully(input, passwordBytes)
        
        val username = String(usernameBytes, Charsets.UTF_8)
        val password = String(passwordBytes, Charsets.UTF_8)
        
        val userMatches = java.security.MessageDigest.isEqual(
            username.toByteArray(Charsets.UTF_8),
            authUsername.toByteArray(Charsets.UTF_8)
        )
        val passMatches = java.security.MessageDigest.isEqual(
            password.toByteArray(Charsets.UTF_8),
            authPassword.toByteArray(Charsets.UTF_8)
        )
        val success = userMatches && passMatches
        output.write(byteArrayOf(0x01, if (success) 0x00 else 0x01))
        output.flush()
        
        // Clear sensitive data
        passwordBytes.fill(0)
        
        return success
    }
    
    internal data class Socks5Request(
        val command: Byte,
        val addressType: Byte,
        val destAddress: String,
        val destPort: Int
    )
    
    internal fun readRequest(input: InputStream): Socks5Request? {
        val version = input.read()
        if (version != SOCKS_VERSION.toInt()) return null
        
        val command = input.read().toByte()
        input.read() // reserved byte
        val addressType = input.read().toByte()
        
        val destAddress = when (addressType) {
            ATYP_IPV4 -> {
                val addr = ByteArray(4)
                readFully(input, addr)
                InetAddress.getByAddress(addr).hostAddress ?: return null
            }
            ATYP_DOMAIN -> {
                val domainLen = input.read()
                if (domainLen <= 0) return null
                val domain = ByteArray(domainLen)
                readFully(input, domain)
                String(domain)
            }
            ATYP_IPV6 -> {
                val addr = ByteArray(16)
                readFully(input, addr)
                InetAddress.getByAddress(addr).hostAddress ?: return null
            }
            else -> return null
        }
        
        val portHigh = input.read()
        val portLow = input.read()
        if (portHigh < 0 || portLow < 0) return null
        val destPort = (portHigh shl 8) or portLow
        
        return Socks5Request(command, addressType, destAddress, destPort)
    }
    
    private suspend fun handleConnect(
        clientSocket: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        request: Socks5Request
    ) {
        Log.d(TAG, "CONNECT ${request.destAddress}:${request.destPort}")
        
        val host = if (remoteDns && request.addressType == ATYP_DOMAIN) {
            // Pass domain name directly - lwIP will resolve through tunnel
            request.destAddress
        } else if (!remoteDns && request.addressType == ATYP_DOMAIN) {
            // Resolve locally
            try {
                withContext(Dispatchers.IO) {
                    InetAddress.getByName(request.destAddress).hostAddress
                } ?: run {
                    sendReply(clientOutput, REPLY_HOST_UNREACHABLE)
                    return
                }
            } catch (e: Exception) {
                sendReply(clientOutput, REPLY_HOST_UNREACHABLE)
                return
            }
        } else {
            request.destAddress
        }
        
        // Create tunnel connection through the OpenVPN tunnel via lwIP
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
                    Log.w(TAG, "SOCKS5 relay channel full or closed (connId=$connId); aborting connection to prevent data loss")
                    connectionClosed.set(true)
                    dataChannel.close()
                    tunnelEngine.closeConnection(connId)
                    try { clientSocket.close() } catch (_: Exception) {}
                }
            }
            
            override fun onConnectionClosed(connId: Int, reason: String) {
                connectionClosed.set(true)
                if (!completableDeferred.isCompleted) {
                    completableDeferred.complete(false)
                }
                dataChannel.close()
            }
            
            override fun onError(connId: Int, error: String) {
                connectionClosed.set(true)
                if (!completableDeferred.isCompleted) {
                    completableDeferred.complete(false)
                }
                dataChannel.close()
            }
        }
        
        val connId = tunnelEngine.openConnection(host, request.destPort, callback)
        if (connId < 0) {
            sendReply(clientOutput, REPLY_GENERAL_FAILURE)
            return
        }
        
        // Wait for connection with timeout
        val connected = try {
            withTimeout(CONNECT_TIMEOUT_MS.toLong()) {
                completableDeferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            tunnelEngine.closeConnection(connId)
            sendReply(clientOutput, REPLY_TTL_EXPIRED)
            return
        }
        
        if (!connected) {
            tunnelEngine.closeConnection(connId)
            sendReply(clientOutput, REPLY_CONNECTION_REFUSED)
            return
        }
        
        // Send success reply and set relay idle timeout to prevent leaking connections
        sendReply(clientOutput, REPLY_SUCCESS)
        clientSocket.soTimeout = RELAY_IDLE_TIMEOUT_MS
        
        // Bidirectional relay
        try {
            coroutineScope {
                // Client -> Tunnel
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
                    } catch (e: IOException) {
                        // Client disconnected
                    } finally {
                        tunnelEngine.closeConnection(connId)
                    }
                }
                
                // Tunnel -> Client
                val tunnelToClient = launch(proxyDispatcher) {
                    try {
                        for (data in dataChannel) {
                            clientOutput.write(data)
                            clientOutput.flush()
                            tunnelEngine.acknowledgeData(connId, data.size)
                        }
                    } catch (e: IOException) {
                        // Client disconnected
                    } finally {
                        try { clientSocket.close() } catch (_: Exception) {}
                    }
                }
                
                // Wait for either direction to finish
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

    private suspend fun handleUdpAssociate(
        clientSocket: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        request: Socks5Request
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, "UDP ASSOCIATE requested from ${clientSocket.inetAddress?.hostAddress ?: "?"}:${clientSocket.port}")

        val bindInetAddr = try {
            InetAddress.getByName(bindAddress)
        } catch (_: Exception) {
            InetAddress.getByName("127.0.0.1")
        }

        val relaySocket = try {
            java.net.DatagramSocket(0, bindInetAddr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind DatagramSocket for UDP ASSOCIATE", e)
            sendReply(clientOutput, REPLY_GENERAL_FAILURE)
            return@withContext
        }

        val advertisedAddr = if (bindInetAddr.isAnyLocalAddress) {
            clientSocket.localAddress.address
        } else {
            bindInetAddr.address
        }

        val associationClosed = AtomicBoolean(false)
        val clientUdpEndpoint = java.util.concurrent.atomic.AtomicReference<InetSocketAddress?>(null)
        val lastActivityTime = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

        val udpCallback = object : TunnelEngine.TunnelUdpCallback {
            override fun onUdpDataReceived(
                connId: Int,
                remoteHost: String,
                remotePort: Int,
                data: ByteArray,
                offset: Int,
                length: Int
            ) {
                if (associationClosed.get() || relaySocket.isClosed) return
                val clientEndpoint = clientUdpEndpoint.get() ?: return
                lastActivityTime.set(System.currentTimeMillis())

                try {
                    val remoteIp = InetAddress.getByName(remoteHost)
                    val ipBytes = remoteIp.address
                    val atyp = if (ipBytes.size == 16) ATYP_IPV6 else ATYP_IPV4

                    val headerLen = 4 + ipBytes.size + 2
                    val outPacketBytes = ByteArray(headerLen + length)
                    outPacketBytes[0] = 0x00
                    outPacketBytes[1] = 0x00
                    outPacketBytes[2] = 0x00 // FRAG = 0
                    outPacketBytes[3] = atyp
                    System.arraycopy(ipBytes, 0, outPacketBytes, 4, ipBytes.size)
                    val portOffset = 4 + ipBytes.size
                    outPacketBytes[portOffset] = ((remotePort shr 8) and 0xFF).toByte()
                    outPacketBytes[portOffset + 1] = (remotePort and 0xFF).toByte()
                    System.arraycopy(data, offset, outPacketBytes, portOffset + 2, length)

                    val outPacket = java.net.DatagramPacket(
                        outPacketBytes,
                        outPacketBytes.size,
                        clientEndpoint
                    )
                    relaySocket.send(outPacket)
                } catch (e: Exception) {
                    if (!associationClosed.get()) {
                        Log.d(TAG, "Error relaying UDP packet to client: ${e.message}")
                    }
                }
            }

            override fun onUdpClosed(connId: Int, reason: String) {
                associationClosed.set(true)
            }
        }

        val connId = tunnelEngine.openUdpConnection(udpCallback)
        if (connId < 0) {
            relaySocket.close()
            sendReply(clientOutput, REPLY_GENERAL_FAILURE)
            return@withContext
        }

        sendReply(clientOutput, REPLY_SUCCESS, advertisedAddr, relaySocket.localPort)
        Log.d(TAG, "UDP ASSOCIATE established: relay on ${bindInetAddr.hostAddress}:${relaySocket.localPort}, connId=$connId")

        // Enable TCP keepalive and polling timeouts to prevent indefinite thread pinning on abandoned connections
        clientSocket.keepAlive = true
        clientSocket.soTimeout = 30_000
        relaySocket.soTimeout = 30_000

        try {
            coroutineScope {
                val udpRelayJob = launch(Dispatchers.IO) {
                    val inBuf = ByteArray(65536)
                    val packet = java.net.DatagramPacket(inBuf, inBuf.size)

                    while (running.get() && !associationClosed.get() && !relaySocket.isClosed) {
                        try {
                            packet.length = inBuf.size // Reset buffer length to prevent truncation
                            relaySocket.receive(packet)
                            lastActivityTime.set(System.currentTimeMillis())
                        } catch (e: java.net.SocketTimeoutException) {
                            if (System.currentTimeMillis() - lastActivityTime.get() > RELAY_IDLE_TIMEOUT_MS) {
                                Log.d(TAG, "UDP associate relay socket idle timeout exceeded")
                                break
                            }
                            continue
                        } catch (e: java.net.SocketException) {
                            break
                        } catch (e: Exception) {
                            if (running.get() && !associationClosed.get()) {
                                Log.w(TAG, "relaySocket receive error: ${e.message}")
                            }
                            break
                        }

                        if (packet.address != clientSocket.inetAddress) {
                            Log.w(TAG, "Dropping UDP packet from unauthorized source: ${packet.address}")
                            continue
                        }

                        val currentEndpoint = clientUdpEndpoint.get()
                        if (currentEndpoint == null) {
                            clientUdpEndpoint.compareAndSet(null, InetSocketAddress(packet.address, packet.port))
                        } else if (currentEndpoint.port != packet.port || currentEndpoint.address != packet.address) {
                            Log.w(TAG, "Dropping UDP packet from unexpected endpoint: ${packet.address}:${packet.port} (expected $currentEndpoint)")
                            continue
                        }

                        val len = packet.length
                        if (len < 10) continue

                        val frag = inBuf[2].toInt() and 0xFF
                        if (frag != 0) {
                            continue
                        }

                        val atyp = inBuf[3]
                        var dstHost: String? = null
                        var dstPort = 0
                        var dataOffset = 0

                        when (atyp) {
                            ATYP_IPV4 -> {
                                if (len < 10) continue
                                dstHost = "${inBuf[4].toInt() and 0xFF}.${inBuf[5].toInt() and 0xFF}.${inBuf[6].toInt() and 0xFF}.${inBuf[7].toInt() and 0xFF}"
                                dstPort = ((inBuf[8].toInt() and 0xFF) shl 8) or (inBuf[9].toInt() and 0xFF)
                                dataOffset = 10
                            }
                            ATYP_DOMAIN -> {
                                val domainLen = inBuf[4].toInt() and 0xFF
                                if (len < 5 + domainLen + 2) continue
                                val domain = String(inBuf, 5, domainLen, Charsets.US_ASCII)
                                val portIdx = 5 + domainLen
                                dstPort = ((inBuf[portIdx].toInt() and 0xFF) shl 8) or (inBuf[portIdx + 1].toInt() and 0xFF)
                                dataOffset = portIdx + 2

                                dstHost = if (remoteDns) {
                                    tunnelEngine.resolveHostname(domain)
                                } else {
                                    try {
                                        InetAddress.getByName(domain).hostAddress
                                    } catch (_: Exception) { null }
                                }
                            }
                            ATYP_IPV6 -> {
                                if (len < 22) continue
                                val ipBytes = inBuf.copyOfRange(4, 20)
                                dstHost = try {
                                    InetAddress.getByAddress(ipBytes).hostAddress
                                } catch (_: Exception) { null }
                                dstPort = ((inBuf[20].toInt() and 0xFF) shl 8) or (inBuf[21].toInt() and 0xFF)
                                dataOffset = 22
                            }
                        }

                        val payloadLen = len - dataOffset
                        if (dstHost != null && dstPort > 0 && payloadLen >= 0) {
                            tunnelEngine.sendUdpData(connId, dstHost, dstPort, inBuf, dataOffset, payloadLen)
                        }
                    }
                }

                val tcpMonitorJob = launch(Dispatchers.IO) {
                    val dummy = ByteArray(64)
                    try {
                        while (running.get() && !associationClosed.get()) {
                            try {
                                val r = clientInput.read(dummy)
                                if (r < 0) {
                                    Log.d(TAG, "TCP control connection closed by client (EOF)")
                                    break
                                }
                            } catch (e: java.net.SocketTimeoutException) {
                                if (System.currentTimeMillis() - lastActivityTime.get() > RELAY_IDLE_TIMEOUT_MS) {
                                    Log.d(TAG, "UDP associate TCP control connection idle timeout exceeded")
                                    break
                                }
                                continue
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "TCP control connection terminated: ${e.message}")
                    } finally {
                        associationClosed.set(true)
                        try { relaySocket.close() } catch (_: Exception) {}
                    }
                }

                select<Unit> {
                    tcpMonitorJob.onJoin {}
                    udpRelayJob.onJoin {}
                }

                tcpMonitorJob.cancel()
                udpRelayJob.cancel()
            }
        } catch (e: Exception) {
            Log.d(TAG, "UDP association terminated: ${e.message}")
        } finally {
            associationClosed.set(true)
            try { relaySocket.close() } catch (_: Exception) {}
            tunnelEngine.closeUdpConnection(connId)
            try { clientSocket.close() } catch (_: Exception) {}
            Log.d(TAG, "UDP association cleaned up for connId=$connId")
        }
    }
    
    private fun sendReply(output: OutputStream, status: Byte, bindAddr: ByteArray = byteArrayOf(0, 0, 0, 0), bindPort: Int = 0) {
        val atyp = if (bindAddr.size == 16) ATYP_IPV6 else ATYP_IPV4
        val reply = ByteBuffer.allocate(6 + bindAddr.size)
        reply.put(SOCKS_VERSION)
        reply.put(status)
        reply.put(0x00) // reserved
        reply.put(atyp)
        reply.put(bindAddr)
        reply.putShort(bindPort.toShort())
        output.write(reply.array())
        output.flush()
    }
    
    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw IOException("Unexpected end of stream")
            offset += read
        }
    }
}
