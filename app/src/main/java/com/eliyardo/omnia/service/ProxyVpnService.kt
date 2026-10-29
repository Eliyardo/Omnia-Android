package com.eliyardo.omnia.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.eliyardo.omnia.OmniaApp
import com.eliyardo.omnia.R
import com.eliyardo.omnia.profile.ProfileManager
import com.eliyardo.omnia.proxy.HttpProxyServer
import com.eliyardo.omnia.proxy.Socks5Server
import com.eliyardo.omnia.settings.AppSettings
import com.eliyardo.omnia.tunnel.TunnelEngine
import com.eliyardo.omnia.tunnel.TunnelState
import com.eliyardo.omnia.tunnel.ProxyState
import com.eliyardo.omnia.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory staging mechanism to pass connection secrets (profile content, username, password)
 * across local process boundaries without putting long-lived or large sensitive credentials into
 * Android Intent extras.
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

class ProxyVpnService : VpnService() {

    companion object {
        private const val TAG = "OmniaVpnService"
        private const val NOTIFICATION_ID = 1

        const val ACTION_CONNECT = "com.eliyardo.omnia.action.CONNECT"
        const val ACTION_DISCONNECT = "com.eliyardo.omnia.action.DISCONNECT"
        const val EXTRA_PROFILE_ID = "profile_id"

        @Deprecated("Credentials and raw profile content are decoupled from Intent extras to prevent Binder size limit and logging issues")
        const val EXTRA_PROFILE_CONTENT = "profile_content"
        @Deprecated("Credentials and raw profile content are decoupled from Intent extras to prevent Binder size limit and logging issues")
        const val EXTRA_USERNAME = "username"
        @Deprecated("Credentials and raw profile content are decoupled from Intent extras to prevent Binder size limit and logging issues")
        const val EXTRA_PASSWORD = "password"

        fun createConnectIntent(
            context: Context,
            profileId: String,
            profileContent: String? = null,
            username: String? = null,
            password: String? = null
        ): Intent {
            // Stage secrets in-memory so they never cross Binder/system boundaries
            ConnectionSessionHolder.stageSession(
                profileId = profileId,
                profileContent = profileContent,
                username = username,
                password = password
            )
            return Intent(context, ProxyVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_PROFILE_ID, profileId)
            }
        }

        fun createDisconnectIntent(context: Context): Intent {
            return Intent(context, ProxyVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
        }
    }

    private val binder = LocalBinder()
    private var serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val tunnelEngine = TunnelEngine()
    private val profileManager by lazy { ProfileManager.getInstance(applicationContext) }
    private var socks5Server: Socks5Server? = null
    private var httpProxyServer: HttpProxyServer? = null
    private val proxyLifecycleMutex = Mutex()
    private var proxyStartJob: Job? = null
    lateinit var settings: AppSettings
    private var vpnInterface: ParcelFileDescriptor? = null

    private val _proxyState = MutableStateFlow(ProxyState())
    val proxyState: StateFlow<ProxyState> = _proxyState.asStateFlow()

    // Cached settings with @Volatile for safe cross-thread memory visibility
    @Volatile private var cachedTunnelMode: String = AppSettings.TUNNEL_MODE_TUN
    @Volatile private var cachedSecurityLevel: String = AppSettings.SECURITY_LEVEL_PREFERRED
    @Volatile private var cachedEnforceTls13: Boolean = false
    @Volatile private var cachedVpnProtocolOverride: String = AppSettings.PROTO_ADAPTIVE
    @Volatile private var cachedConnectionTimeout: Int = AppSettings.TIMEOUT_1M
    @Volatile private var cachedIpv6Enabled: Boolean = true
    @Volatile private var cachedAllowLan: Boolean = false
    @Volatile private var cachedBypassLanTun: Boolean = true
    @Volatile private var cachedBlockTrafficOnDrop: Boolean = true
    @Volatile private var cachedUpstreamProxyEnabled: Boolean = false
    @Volatile private var cachedUpstreamProxyHost: String = ""
    @Volatile private var cachedUpstreamProxyPort: Int = 8080
    @Volatile private var cachedUpstreamProxyUsername: String = ""
    @Volatile private var cachedUpstreamProxyPassword: String = ""
    @Volatile private var cachedUpstreamProxyAllowCleartext: Boolean = false
    @Volatile private var isUserInitiatedDisconnect: Boolean = false
    @Volatile private var isKillSwitchBlocking: Boolean = false
    @Volatile private var cachedDnsProvider: String = AppSettings.DNS_PROVIDER_SERVER
    @Volatile private var cachedCustomDnsPrimary: String = ""
    @Volatile private var cachedCustomDnsSecondary: String = ""
    @Volatile private var cachedSplitTunnelEnabled: Boolean = false
    @Volatile private var cachedSplitTunnelMode: String = AppSettings.SPLIT_TUNNEL_ALLOWLIST
    @Volatile private var cachedSplitTunnelApps: Set<String> = emptySet()

    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 5
    private var listenersJob: Job? = null
    private var lastProfileId: String? = null
    private var lastProfileContent: String? = null
    private var lastUsername: String? = null
    private var lastPassword: String? = null

    inner class LocalBinder : Binder() {
        fun getService(): ProxyVpnService = this@ProxyVpnService
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent != null && VpnService.SERVICE_INTERFACE == intent.action) {
            return super.onBind(intent)
        }
        return binder
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN permission revoked by system or another VPN application")
        disconnect()
        super.onRevoke()
    }

    private fun ensureServiceScope(): CoroutineScope {
        if (serviceScope.coroutineContext[Job]?.isActive != true) {
            serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
            setupServiceListeners()
        } else if (listenersJob?.isActive != true) {
            setupServiceListeners()
        }
        return serviceScope
    }

    private fun setupCallbacks() {
        tunnelEngine.initialize()
        tunnelEngine.onProtectSocketCallback = { socket ->
            var ok = protect(socket)
            Log.i(TAG, "VpnService.protect(socket=$socket) returned $ok")
            if (!ok) {
                try {
                    val cm = getSystemService(ConnectivityManager::class.java)
                    val activeNet = cm.activeNetwork
                    if (activeNet != null) {
                        val caps = cm.getNetworkCapabilities(activeNet)
                        if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            val pfd = ParcelFileDescriptor.fromFd(socket)
                            try {
                                activeNet.bindSocket(pfd.fileDescriptor)
                                ok = true
                                Log.i(TAG, "Bound socket $socket directly to active physical network $activeNet")
                            } finally {
                                val detached = try { pfd.detachFd() } catch (_: Exception) { -1 }
                                if (detached != -1 && detached != socket) {
                                    // If fromFd created a duplicate descriptor, close the duplicate
                                    try { ParcelFileDescriptor.adoptFd(detached).close() } catch (_: Exception) {}
                                }
                                try { pfd.close() } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback physical network bind failed for socket $socket", e)
                }
            }
            ok
        }
        tunnelEngine.onEstablishVpnCallback = { ip, prefix, ipv6, ipv6Prefix, mtu, dns, routes, rerouteGwIpv4, rerouteGwIpv6, excludedRoutes ->
            establishVpnInterface(ip, prefix, ipv6, ipv6Prefix, mtu, dns, routes, rerouteGwIpv4, rerouteGwIpv6, excludedRoutes)
        }
    }

    private fun setupServiceListeners() {
        listenersJob?.cancel()
        listenersJob = serviceScope.launch {
            launch { settings.tunnelMode.collect { cachedTunnelMode = it } }
            launch { settings.securityLevel.collect { cachedSecurityLevel = it } }
            launch { settings.enforceTls13.collect { cachedEnforceTls13 = it } }
            launch { settings.vpnProtocolOverride.collect { cachedVpnProtocolOverride = it } }
            launch { settings.connectionTimeout.collect { cachedConnectionTimeout = it } }
            launch { settings.ipv6Enabled.collect { cachedIpv6Enabled = it } }
            launch { settings.allowLan.collect { cachedAllowLan = it } }
            launch { settings.bypassLanTun.collect { cachedBypassLanTun = it } }
            launch { settings.blockTrafficOnDrop.collect { cachedBlockTrafficOnDrop = it } }
            launch { settings.upstreamProxyEnabled.collect { cachedUpstreamProxyEnabled = it } }
            launch { settings.upstreamProxyHost.collect { cachedUpstreamProxyHost = it } }
            launch { settings.upstreamProxyPort.collect { cachedUpstreamProxyPort = it } }
            launch { settings.upstreamProxyUsername.collect { cachedUpstreamProxyUsername = it } }
            launch { settings.upstreamProxyPassword.collect { cachedUpstreamProxyPassword = it } }
            launch { settings.upstreamProxyAllowCleartext.collect { cachedUpstreamProxyAllowCleartext = it } }
            launch {
                settings.dnsProvider.collect {
                    cachedDnsProvider = it
                    applyDnsSettings()
                }
            }
            launch {
                settings.customDnsPrimary.collect {
                    cachedCustomDnsPrimary = it
                    applyDnsSettings()
                }
            }
            launch {
                settings.customDnsSecondary.collect {
                    cachedCustomDnsSecondary = it
                    applyDnsSettings()
                }
            }
            launch { settings.splitTunnelEnabled.collect { cachedSplitTunnelEnabled = it } }
            launch { settings.splitTunnelMode.collect { cachedSplitTunnelMode = it } }
            launch { settings.splitTunnelApps.collect { cachedSplitTunnelApps = it } }

            launch {
                tunnelEngine.stats.collect { stats ->
                    if (tunnelEngine.state.value is TunnelState.Connected) {
                        updateNotification(tunnelEngine.state.value, stats)
                    }
                }
            }

            launch {
                tunnelEngine.state.collect { state ->
                    updateNotification(state, tunnelEngine.stats.value)
                    when (state) {
                        is TunnelState.Connected -> {
                            reconnectAttempts = 0
                            val isProxyMode = cachedTunnelMode == AppSettings.TUNNEL_MODE_PROXY
                            if (isProxyMode) {
                                startProxyServers()
                            } else {
                                stopProxyServers()
                            }
                        }
                        is TunnelState.Disconnected -> {
                            reconnectAttempts = 0
                            stopProxyServers()
                        }
                        is TunnelState.Error -> {
                            stopProxyServers()
                            handleError(state)
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(applicationContext)
        ensureServiceScope()
        setupServiceListeners()
        setupCallbacks()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_CONNECT -> {
                    isUserInitiatedDisconnect = false
                    isKillSwitchBlocking = false

                    val profileIdExtra = intent.getStringExtra(EXTRA_PROFILE_ID)
                    val stagedSession = ConnectionSessionHolder.consumeSession(profileIdExtra)

                    val profileId = stagedSession?.profileId ?: profileIdExtra
                    val username = stagedSession?.username ?: @Suppress("DEPRECATION") intent.getStringExtra(EXTRA_USERNAME)
                    val password = stagedSession?.password ?: @Suppress("DEPRECATION") intent.getStringExtra(EXTRA_PASSWORD)

                    var profileContent = stagedSession?.profileContent
                    if (profileContent.isNullOrBlank()) {
                        @Suppress("DEPRECATION")
                        profileContent = intent.getStringExtra(EXTRA_PROFILE_CONTENT) ?: ""
                    }

                    lastProfileId = profileId
                    lastProfileContent = profileContent
                    lastUsername = username
                    lastPassword = password

                    safeStartForeground(createNotification("Connecting..."))

                    val scope = ensureServiceScope()
                    setupCallbacks()

                    scope.launch {
                        if (profileContent.isNullOrBlank() && !profileId.isNullOrBlank()) {
                            profileContent = withContext(Dispatchers.IO) {
                                profileManager.getProfileContent(profileId)
                            }
                            lastProfileContent = profileContent
                        }

                        applyDnsSettings()

                        val isTunMode = settings.tunnelMode.first() == AppSettings.TUNNEL_MODE_TUN
                        val securityLevel = settings.securityLevel.first()
                        val enforceTls13 = settings.enforceTls13.first()
                        val protoOverride = settings.vpnProtocolOverride.first()
                        val connTimeout = settings.connectionTimeout.first()
                        val ipv6Enabled = settings.ipv6Enabled.first()
                        val allowLan = settings.allowLan.first()
                        val bypassLanTun = settings.bypassLanTun.first()
                        val upstreamProxyEnabled = settings.upstreamProxyEnabled.first()
                        val upstreamProxyHost = if (upstreamProxyEnabled) settings.upstreamProxyHost.first() else ""
                        val upstreamProxyPort = if (upstreamProxyEnabled) settings.upstreamProxyPort.first() else 0
                        val upstreamProxyUser = if (upstreamProxyEnabled) settings.upstreamProxyUsername.first() else ""
                        val upstreamProxyPass = if (upstreamProxyEnabled) settings.upstreamProxyPassword.first() else ""
                        val upstreamProxyCleartext = settings.upstreamProxyAllowCleartext.first()

                        cachedTunnelMode = if (isTunMode) AppSettings.TUNNEL_MODE_TUN else AppSettings.TUNNEL_MODE_PROXY
                        cachedSecurityLevel = securityLevel
                        cachedEnforceTls13 = enforceTls13
                        cachedVpnProtocolOverride = protoOverride
                        cachedConnectionTimeout = connTimeout
                        cachedIpv6Enabled = ipv6Enabled
                        cachedAllowLan = allowLan
                        cachedBypassLanTun = bypassLanTun
                        cachedUpstreamProxyEnabled = upstreamProxyEnabled
                        cachedUpstreamProxyHost = upstreamProxyHost
                        cachedUpstreamProxyPort = upstreamProxyPort
                        cachedUpstreamProxyUsername = upstreamProxyUser
                        cachedUpstreamProxyPassword = upstreamProxyPass
                        cachedUpstreamProxyAllowCleartext = upstreamProxyCleartext

                        Log.i(TAG, "Starting connection: isTunMode=$isTunMode secLevel=$securityLevel tls13=$enforceTls13 proto=$protoOverride timeout=$connTimeout ipv6=$ipv6Enabled bypassLanTun=$bypassLanTun")

                        // Clean up cross-mode state before connecting
                        if (!isTunMode) {
                            try {
                                vpnInterface?.close()
                                vpnInterface = null
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not close stale vpnInterface", e)
                            }
                        } else {
                            stopProxyServers()
                        }

                        val contentToConnect = profileContent
                        if (!contentToConnect.isNullOrEmpty()) {
                            withContext(Dispatchers.IO) {
                                tunnelEngine.connect(
                                    profileContent = contentToConnect,
                                    username = username,
                                    password = password,
                                    isTunMode = isTunMode,
                                    securityLevel = securityLevel,
                                    enforceTls13 = enforceTls13,
                                    protoOverride = protoOverride,
                                    connTimeout = connTimeout,
                                    ipv6Enabled = ipv6Enabled,
                                    bypassLanTun = bypassLanTun,
                                    upstreamProxyHost = upstreamProxyHost,
                                    upstreamProxyPort = upstreamProxyPort,
                                    upstreamProxyUser = upstreamProxyUser,
                                    upstreamProxyPass = upstreamProxyPass,
                                    upstreamProxyAllowCleartext = upstreamProxyCleartext
                                )
                            }
                        }
                    }
                }
                ACTION_DISCONNECT -> {
                    disconnect()
                }
                else -> {
                    // Do not show ongoing notification if idle
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal error in onStartCommand", t)
            saveCrashLog(t)
        }

        return if (isKillSwitchBlocking || tunnelEngine.state.value is com.eliyardo.omnia.tunnel.TunnelState.Connected || tunnelEngine.state.value is com.eliyardo.omnia.tunnel.TunnelState.Connecting) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    private fun establishVpnInterface(
        assignedIp: String,
        prefix: Int,
        assignedIpv6: String?,
        ipv6Prefix: Int,
        mtu: Int,
        pushedDns: Array<String>,
        pushedRoutes: Array<String>,
        rerouteGwIpv4: Boolean,
        rerouteGwIpv6: Boolean,
        excludedRoutes: Array<String>
    ): Int {
        return try {
            val builder = Builder()
                .setSession("Omnia")
                .setMtu(if (mtu > 0) mtu else 1500)

            val configureIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, com.eliyardo.omnia.ui.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setConfigureIntent(configureIntent)

            val actualIp = assignedIp.ifEmpty { "10.8.0.2" }
            val actualPrefix = if (prefix in 1..32) prefix else 24
            builder.addAddress(actualIp, actualPrefix)

            // IPv6 Configuration & Leak Prevention (fail-closed)
            // Even if the user disables IPv6 or server provides none, route ::/0 to a dummy blackhole
            // so dual-stack Android devices never leak IPv6 traffic outside the VPN.
            try {
                if (cachedIpv6Enabled && !assignedIpv6.isNullOrBlank()) {
                    val actualIpv6Prefix = if (ipv6Prefix in 1..128) ipv6Prefix else 64
                    builder.addAddress(assignedIpv6, actualIpv6Prefix)
                    builder.addRoute("::", 0)
                    Log.i(TAG, "Configured pushed IPv6 address: $assignedIpv6/$actualIpv6Prefix and route ::/0")
                } else {
                    builder.addAddress("fd00::1", 128)
                    builder.addRoute("::", 0)
                    Log.i(TAG, "Configured IPv6 leak-protection blackhole fd00::1/128 and route ::/0")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not configure IPv6 route on VpnService.Builder", e)
            }

            // Server-Pushed & Default Routes
            for (r in pushedRoutes) {
                try {
                    val parts = r.split("/")
                    if (parts.size == 2) {
                        val routeIp = parts[0]
                        val routePrefix = parts[1].toIntOrNull() ?: continue
                        builder.addRoute(routeIp, routePrefix)
                        Log.i(TAG, "Added pushed route: $routeIp/$routePrefix")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed adding pushed route $r", e)
                }
            }

            // If redirect-gateway is indicated or no specific routes were pushed, install default route
            if (rerouteGwIpv4 || pushedRoutes.isEmpty()) {
                try {
                    builder.addRoute("0.0.0.0", 0)
                    Log.i(TAG, "Added default IPv4 route 0.0.0.0/0 (rerouteGwIpv4=$rerouteGwIpv4, pushedRoutes=${pushedRoutes.size})")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed adding default IPv4 route 0.0.0.0/0", e)
                }
            }

            // API 33+ Excluded Routes (e.g. bypassing local subnets or bypass-dhcp)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val toExclude = mutableSetOf<String>()
                toExclude.addAll(excludedRoutes)
                if (cachedBypassLanTun) {
                    val localSubnets = getActiveLocalSubnets()
                    if (localSubnets.isNotEmpty()) {
                        toExclude.addAll(localSubnets)
                    } else {
                        // Fallback: exclude link-local only to prevent leaking RFC1918 VPN routes (Claude N6)
                        toExclude.add("169.254.0.0/16")
                        toExclude.add("fe80::/10")
                    }
                }
                for (ex in toExclude) {
                    try {
                        val parts = ex.split("/")
                        if (parts.size == 2) {
                            val addr = java.net.InetAddress.getByName(parts[0])
                            val pfx = parts[1].toIntOrNull() ?: continue
                            builder.excludeRoute(android.net.IpPrefix(addr, pfx))
                            Log.i(TAG, "Excluded route: $ex")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed excluding route $ex", e)
                    }
                }
            }

            // DNS Configuration - guarantee at least one valid DNS server is added to prevent DNS leaks
            var dnsAdded = false
            val dnsPair = settings.resolveDnsIps(cachedDnsProvider, cachedCustomDnsPrimary, cachedCustomDnsSecondary)
            if (dnsPair != null) {
                try {
                    builder.addDnsServer(dnsPair.first)
                    dnsAdded = true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add primary custom DNS server: ${dnsPair.first}", e)
                }
                if (dnsPair.second.isNotBlank()) {
                    try {
                        builder.addDnsServer(dnsPair.second)
                        dnsAdded = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add secondary custom DNS server: ${dnsPair.second}", e)
                    }
                }
            }

            if (!dnsAdded && pushedDns.isNotEmpty()) {
                pushedDns.forEach { dnsIp ->
                    try {
                        builder.addDnsServer(dnsIp)
                        dnsAdded = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add pushed DNS server: $dnsIp", e)
                    }
                }
            }

            if (!dnsAdded) {
                Log.w(TAG, "No valid custom or pushed DNS servers; enforcing 1.1.1.1 fallback to prevent DNS leak")
                builder.addDnsServer("1.1.1.1")
            }

            // Per-App Split Tunneling
            if (cachedSplitTunnelEnabled && cachedSplitTunnelApps.isNotEmpty()) {
                if (cachedSplitTunnelMode == AppSettings.SPLIT_TUNNEL_ALLOWLIST) {
                    for (pkg in cachedSplitTunnelApps) {
                        try {
                            builder.addAllowedApplication(pkg)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not add allowed app $pkg", e)
                        }
                    }
                } else {
                    for (pkg in cachedSplitTunnelApps) {
                        try {
                            builder.addDisallowedApplication(pkg)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not add disallowed app $pkg", e)
                        }
                    }
                }
            }

            // Establish new VPN interface before closing old blackhole interface to prevent leak window
            val oldInterface = vpnInterface
            val pfd = builder.establish() ?: run {
                Log.e(TAG, "VpnService.Builder.establish() returned null")
                return -1
            }
            vpnInterface = pfd
            try { oldInterface?.close() } catch (_: Exception) {}
            val dupPfd = pfd.dup()
            val fd = dupPfd?.detachFd() ?: -1
            Log.i(TAG, "VpnService.Builder.establish() successful: fd=$fd")
            fd
        } catch (e: Exception) {
            Log.e(TAG, "Failed establishing VpnService builder", e)
            -1
        }
    }

    private suspend fun applyDnsSettings() {
        try {
            val dnsMode = settings.dnsMode.first()
            if (dnsMode == AppSettings.DNS_MODE_REMOTE) {
                val provider = settings.dnsProvider.first()
                val prim = settings.customDnsPrimary.first()
                val sec = settings.customDnsSecondary.first()
                val dnsPair = settings.resolveDnsIps(provider, prim, sec)
                if (dnsPair != null) {
                    tunnelEngine.setCustomDns(dnsPair.first, dnsPair.second)
                } else {
                    tunnelEngine.setCustomDns(null, null)
                }
            } else {
                tunnelEngine.setCustomDns(null, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed applying DNS settings", e)
        }
    }

    private fun saveCrashLog(t: Throwable) {
        try {
            val sw = java.io.StringWriter()
            val pw = java.io.PrintWriter(sw)
            t.printStackTrace(pw)
            val logContent = "Service Crash: ${t.message}\n$sw"
            OmniaApp.writeCrashLog(this, logContent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing crash log in service", e)
        }
    }

    private fun getActiveLocalSubnets(): List<String> {
        val result = mutableListOf<String>()
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return result
            val activeNetwork = cm.activeNetwork ?: return result
            val linkProps = cm.getLinkProperties(activeNetwork) ?: return result
            for (la in linkProps.linkAddresses) {
                val addr = la.address
                val prefix = la.prefixLength
                if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                    if (prefix <= 0 || prefix > 32) continue
                    val ipInt = java.nio.ByteBuffer.wrap(addr.address).int
                    val mask = -1 shl (32 - prefix)
                    val netInt = ipInt and mask
                    val netAddr = java.net.InetAddress.getByAddress(
                        java.nio.ByteBuffer.allocate(4).putInt(netInt).array()
                    ).hostAddress
                    if (netAddr != null) {
                        result.add("$netAddr/$prefix")
                    }
                } else if (addr is java.net.Inet6Address && addr.isLinkLocalAddress) {
                    result.add("fe80::/10")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed inspecting local subnets for LAN bypass", e)
        }
        return result
    }

    fun disconnect() {
        isUserInitiatedDisconnect = true
        isKillSwitchBlocking = false
        val scope = ensureServiceScope()
        scope.launch(Dispatchers.IO) {
            try {
                reconnectAttempts = 0
                reconnectJob?.cancel()
                lastProfileId = null
                lastProfileContent = null
                lastUsername = null
                lastPassword = null
                ConnectionSessionHolder.clear()
                proxyStartJob?.cancel()
                proxyStartJob = null
                stopProxyServersInternal()
                tunnelEngine.disconnect()
                try {
                    vpnInterface?.close()
                    vpnInterface = null
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Error in service disconnect()", e)
            } finally {
                withContext(Dispatchers.Main) {
                    try {
                        val nm = getSystemService(NotificationManager::class.java)
                        nm?.cancel(NOTIFICATION_ID)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping foreground service", e)
                    }
                }
            }
        }
    }

    private fun startProxyServers() {
        proxyStartJob?.cancel()
        proxyStartJob = serviceScope.launch(Dispatchers.IO) {
            proxyLifecycleMutex.withLock {
                if (!isActive || tunnelEngine.state.value !is TunnelState.Connected || cachedTunnelMode != AppSettings.TUNNEL_MODE_PROXY) {
                    Log.d(TAG, "Skipping proxy start: inactive or not connected in proxy mode")
                    return@withLock
                }

                try {
                    socks5Server?.stop()
                    httpProxyServer?.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping previous proxy server instance", e)
                } finally {
                    socks5Server = null
                    httpProxyServer = null
                }

                val socks5Port = settings.socks5Port.first()
                val httpPort = settings.httpPort.first()
                val rawAuthEnabled = settings.proxyAuthEnabled.first()
                val authUser = settings.proxyUsername.first()
                val authPass = settings.proxyPassword.first()
                val remoteDns = settings.dnsMode.first() == AppSettings.DNS_MODE_REMOTE
                val rawBindAddr = settings.bindAddress.first()
                val allowLan = settings.allowLan.first()

                if (!isActive) return@withLock

                val effectiveBindAddr = if (allowLan) "0.0.0.0" else rawBindAddr
                if (effectiveBindAddr == "0.0.0.0" && (!rawAuthEnabled || authUser.isBlank() || authPass.isEmpty())) {
                    Log.w(TAG, "LAN access is enabled on 0.0.0.0 without proxy authentication. Any device on the local network can connect.")
                }
                val effectiveAuth = rawAuthEnabled

                val s5 = Socks5Server(
                    tunnelEngine = tunnelEngine,
                    bindAddress = effectiveBindAddr,
                    port = socks5Port,
                    authRequired = effectiveAuth,
                    authUsername = authUser,
                    authPassword = authPass,
                    remoteDns = remoteDns
                )

                val http = HttpProxyServer(
                    tunnelEngine = tunnelEngine,
                    bindAddress = effectiveBindAddr,
                    port = httpPort,
                    authRequired = effectiveAuth,
                    authUsername = authUser,
                    authPassword = authPass,
                    remoteDns = remoteDns
                )

                if (!isActive) return@withLock

                val socks5Started = s5.start()
                val httpStarted = http.start()

                if (!isActive) {
                    s5.stop()
                    http.stop()
                    return@withLock
                }

                socks5Server = s5
                httpProxyServer = http

                _proxyState.value = ProxyState(
                    socks5Running = socks5Started,
                    socks5Port = socks5Port,
                    httpRunning = httpStarted,
                    httpPort = httpPort
                )

                Log.i(TAG, "Proxy servers started: SOCKS5=$socks5Started HTTP=$httpStarted")
            }
        }
    }

    private fun stopProxyServers() {
        proxyStartJob?.cancel()
        proxyStartJob = null
        serviceScope.launch(Dispatchers.IO) {
            stopProxyServersInternal()
        }
    }

    private suspend fun stopProxyServersInternal() {
        proxyLifecycleMutex.withLock {
            try {
                socks5Server?.stop()
                httpProxyServer?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping proxy servers", e)
            } finally {
                socks5Server = null
                httpProxyServer = null
                _proxyState.value = ProxyState()
            }
        }
    }

    private fun handleError(error: TunnelState.Error) {
        if (!error.isRecoverable) {
            Log.w(TAG, "Unrecoverable tunnel error: ${error.message}")
            return
        }
        serviceScope.launch {
            val autoReconnect = settings.autoReconnect.first()
            val targetContent = lastProfileContent ?: lastProfileId?.let {
                withContext(Dispatchers.IO) { profileManager.getProfileContent(it) }
            }
            if (autoReconnect && targetContent != null) {
                if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                    Log.w(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached. Aborting auto-reconnect.")
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(NOTIFICATION_ID, createNotification("Connection Failed", "Max retries reached. Tap Disconnect."))
                    return@launch
                }
                reconnectAttempts++
                val delaySeconds = settings.reconnectDelaySeconds.first().coerceAtLeast(1)
                val backoffFactor = 1L shl (reconnectAttempts - 1).coerceAtMost(6) // 1, 2, 4, 8, 16...
                val delayMs = (delaySeconds * 1000L * backoffFactor).coerceAtMost(60_000L)
                Log.i(TAG, "Scheduling auto-reconnect attempt $reconnectAttempts in ${delayMs / 1000}s...")
                reconnectJob?.cancel()
                reconnectJob = serviceScope.launch {
                    delay(delayMs)
                    Log.i(TAG, "Executing auto-reconnect attempt $reconnectAttempts...")
                    applyDnsSettings()
                    val isTun = settings.tunnelMode.first() == AppSettings.TUNNEL_MODE_TUN
                    val securityLevel = settings.securityLevel.first()
                    val enforceTls13 = settings.enforceTls13.first()
                    val protoOverride = settings.vpnProtocolOverride.first()
                    val connTimeout = settings.connectionTimeout.first()
                    val ipv6Enabled = settings.ipv6Enabled.first()
                    val bypassLanTun = settings.bypassLanTun.first()
                    val upstreamProxyEnabled = settings.upstreamProxyEnabled.first()
                    val upstreamProxyHost = if (upstreamProxyEnabled) settings.upstreamProxyHost.first() else ""
                    val upstreamProxyPort = if (upstreamProxyEnabled) settings.upstreamProxyPort.first() else 0
                    val upstreamProxyUser = if (upstreamProxyEnabled) settings.upstreamProxyUsername.first() else ""
                    val upstreamProxyPass = if (upstreamProxyEnabled) settings.upstreamProxyPassword.first() else ""
                    val upstreamProxyCleartext = settings.upstreamProxyAllowCleartext.first()

                    withContext(Dispatchers.IO) {
                        tunnelEngine.connect(
                            profileContent = targetContent,
                            username = lastUsername,
                            password = lastPassword,
                            isTunMode = isTun,
                            securityLevel = securityLevel,
                            enforceTls13 = enforceTls13,
                            protoOverride = protoOverride,
                            connTimeout = connTimeout,
                            ipv6Enabled = ipv6Enabled,
                            bypassLanTun = bypassLanTun,
                            upstreamProxyHost = upstreamProxyHost,
                            upstreamProxyPort = upstreamProxyPort,
                            upstreamProxyUser = upstreamProxyUser,
                            upstreamProxyPass = upstreamProxyPass,
                            upstreamProxyAllowCleartext = upstreamProxyCleartext
                        )
                    }
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        connectivityCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Default network available: $network")
                try {
                    setUnderlyingNetworks(arrayOf(network))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set underlying network", e)
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Default network lost: $network")
                try {
                    setUnderlyingNetworks(null)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear underlying network", e)
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                try {
                    setUnderlyingNetworks(arrayOf(network))
                } catch (_: Exception) {}
            }
        }

        try {
            cm.registerDefaultNetworkCallback(connectivityCallback!!)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.i(TAG, "Underlying network monitoring active. isAlwaysOn=$isAlwaysOn, isLockdownEnabled=$isLockdownEnabled")
            }
        } catch (e: Exception) {
            Log.w(TAG, "registerDefaultNetworkCallback failed, falling back to general request", e)
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, connectivityCallback!!)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout received (startId=$startId, fgsType=$fgsType). Stopping cleanly.")
        disconnect()
        stopSelf()
    }

    private fun safeStartForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground with SPECIAL_USE, attempting fallback", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Fatal error starting foreground service", e2)
            }
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSec / (1024f * 1024f))
            bytesPerSec >= 1024L -> String.format(java.util.Locale.US, "%.1f KB/s", bytesPerSec / 1024f)
            else -> "$bytesPerSec B/s"
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024f * 1024f * 1024f))
            bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024f * 1024f))
            bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024f)
            else -> "$bytes B"
        }
    }

    private fun createNotification(status: String, subText: String? = null): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, ProxyVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, OmniaApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Omnia")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_notification, "Disconnect", disconnectPendingIntent)

        if (!subText.isNullOrBlank()) {
            builder.setSubText(subText)
        }

        return builder.build()
    }

    private fun updateNotification(state: TunnelState, stats: TunnelEngine.TrafficStats = tunnelEngine.stats.value) {
        try {
            val nm = getSystemService(NotificationManager::class.java)

            // Manage interface teardown and notification on Disconnected or Error states
            if (state is TunnelState.Disconnected) {
                if (isKillSwitchBlocking && !isUserInitiatedDisconnect) {
                    // OpenVPN 3 core emits Disconnected after Error; keep kill-switch block intact (Claude N5)
                    val errorNotification = createNotification(
                        "VPN Lost — Traffic Blocked",
                        "Kill-switch active. Tap Disconnect to restore network."
                    )
                    nm?.notify(NOTIFICATION_ID, errorNotification)
                    return
                }
                try {
                    vpnInterface?.close()
                    vpnInterface = null
                } catch (_: Exception) {}
                nm?.cancel(NOTIFICATION_ID)
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Exception) {}
                return
            } else if (state is TunnelState.Error) {
                val isTun = cachedTunnelMode == AppSettings.TUNNEL_MODE_TUN
                if (isTun && cachedBlockTrafficOnDrop && vpnInterface != null && !isUserInitiatedDisconnect) {
                    isKillSwitchBlocking = true
                } else {
                    isKillSwitchBlocking = false
                    try {
                        vpnInterface?.close()
                        vpnInterface = null
                    } catch (_: Exception) {}
                }
                val errorTitle = if (isKillSwitchBlocking) "VPN Lost — Traffic Blocked" else "Connection Error"
                val errorSub = if (isKillSwitchBlocking) "Kill-switch active. Tap Disconnect to restore network." else state.message
                val errorNotification = createNotification(errorTitle, errorSub)
                nm?.notify(NOTIFICATION_ID, errorNotification)
                return
            }

            val (status, subText) = when (state) {
                is TunnelState.Connecting -> Pair("Connecting to OpenVPN...", null)
                is TunnelState.Connected -> {
                    val modeLabel = if (cachedTunnelMode == AppSettings.TUNNEL_MODE_PROXY) "Proxy Mode" else "System-Wide VPN"
                    val speedStr = "↓ ${formatSpeed(stats.bytesInPerSec)}  ↑ ${formatSpeed(stats.bytesOutPerSec)}"
                    val totalStr = "$modeLabel · ↓ ${formatBytes(stats.bytesIn)}  ↑ ${formatBytes(stats.bytesOut)}"
                    Pair(speedStr, totalStr)
                }
                is TunnelState.Reconnecting -> Pair("Reconnecting (attempt ${state.attempt})...", null)
                else -> return
            }
            nm?.notify(NOTIFICATION_ID, createNotification(status, subText))
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification", e)
        }
    }

    override fun onDestroy() {
        connectivityCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.cancel(NOTIFICATION_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        reconnectJob?.cancel()
        listenersJob?.cancel()
        proxyStartJob?.cancel()
        proxyStartJob = null
        try {
            socks5Server?.stop()
            httpProxyServer?.stop()
        } catch (_: Exception) {}
        socks5Server = null
        httpProxyServer = null
        _proxyState.value = ProxyState()
        lastProfileId = null
        lastProfileContent = null
        lastUsername = null
        lastPassword = null
        ConnectionSessionHolder.clear()
        tunnelEngine.destroy()
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {}
        serviceScope.coroutineContext[Job]?.cancelChildren()
        super.onDestroy()
    }
}
