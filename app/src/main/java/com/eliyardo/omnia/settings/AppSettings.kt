package com.eliyardo.omnia.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class AppSettings(private val context: Context) {
    
    companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode") // "system", "dark", "light"
        val TUNNEL_MODE = stringPreferencesKey("tunnel_mode") // "proxy" or "tun"
        val SOCKS5_PORT = intPreferencesKey("socks5_port")
        val HTTP_PORT = intPreferencesKey("http_port")
        val PROXY_AUTH_ENABLED = booleanPreferencesKey("proxy_auth_enabled")
        val PROXY_USERNAME = stringPreferencesKey("proxy_username")
        val PROXY_PASSWORD = stringPreferencesKey("proxy_password")
        val DNS_MODE = stringPreferencesKey("dns_mode") // "remote" or "local"
        val DNS_PROVIDER = stringPreferencesKey("dns_provider") // "server", "cloudflare", "google", "quad9", "adguard", "custom"
        val CUSTOM_DNS_PRIMARY = stringPreferencesKey("custom_dns_primary")
        val CUSTOM_DNS_SECONDARY = stringPreferencesKey("custom_dns_secondary")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val RECONNECT_DELAY_SECONDS = intPreferencesKey("reconnect_delay_seconds")
        val LOG_LEVEL = intPreferencesKey("log_level")
        val BIND_ADDRESS = stringPreferencesKey("bind_address")
        val ALLOW_LAN = booleanPreferencesKey("allow_lan")
        val IPV6_ENABLED = booleanPreferencesKey("ipv6_enabled")
        val LEGACY_CRYPTO = booleanPreferencesKey("legacy_crypto")
        val BLOCK_TRAFFIC_ON_DROP = booleanPreferencesKey("block_traffic_on_drop")

        // Protocol Override & Connection Timeout
        val VPN_PROTOCOL_OVERRIDE = stringPreferencesKey("vpn_protocol_override") // "adaptive", "default", "udp", "tcp"
        val CONNECTION_TIMEOUT = intPreferencesKey("connection_timeout") // 0, 10, 30, 60, 120

        // Security & TLS
        val SECURITY_LEVEL = stringPreferencesKey("security_level") // "preferred", "legacy", "insecure"
        val ENFORCE_TLS_1_3 = booleanPreferencesKey("enforce_tls_1_3")

        // UI & Tunnel Controls
        val CONFIRM_ON_DISCONNECT = booleanPreferencesKey("confirm_on_disconnect")
        val BYPASS_LAN_TUN = booleanPreferencesKey("bypass_lan_tun")

        // Upstream HTTP Proxy
        val UPSTREAM_PROXY_ENABLED = booleanPreferencesKey("upstream_proxy_enabled")
        val UPSTREAM_PROXY_HOST = stringPreferencesKey("upstream_proxy_host")
        val UPSTREAM_PROXY_PORT = intPreferencesKey("upstream_proxy_port")
        val UPSTREAM_PROXY_USERNAME = stringPreferencesKey("upstream_proxy_username")
        val UPSTREAM_PROXY_PASSWORD = stringPreferencesKey("upstream_proxy_password")
        val UPSTREAM_PROXY_ALLOW_CLEARTEXT = booleanPreferencesKey("upstream_proxy_allow_cleartext")

        // Enterprise Device ID
        val DEVICE_ID = stringPreferencesKey("device_id")

        // Split Tunneling (Per-App Routing in TUN mode)
        val SPLIT_TUNNEL_ENABLED = booleanPreferencesKey("split_tunnel_enabled")
        val SPLIT_TUNNEL_MODE = stringPreferencesKey("split_tunnel_mode") // "allowlist" or "bypass"
        val SPLIT_TUNNEL_APPS = stringSetPreferencesKey("split_tunnel_apps")
        
        const val THEME_SYSTEM = "system"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"

        const val TUNNEL_MODE_PROXY = "proxy"
        const val TUNNEL_MODE_TUN = "tun"

        const val PROTO_ADAPTIVE = "adaptive"
        const val PROTO_DEFAULT = "default"
        const val PROTO_UDP = "udp"
        const val PROTO_TCP = "tcp"

        const val TIMEOUT_10S = 10
        const val TIMEOUT_30S = 30
        const val TIMEOUT_1M = 60
        const val TIMEOUT_2M = 120
        const val TIMEOUT_CONTINUOUS = 0

        const val SECURITY_LEVEL_PREFERRED = "preferred"
        const val SECURITY_LEVEL_LEGACY = "legacy"
        const val SECURITY_LEVEL_INSECURE = "insecure"

        const val DNS_MODE_REMOTE = "remote"
        const val DNS_MODE_LOCAL = "local"

        const val DNS_PROVIDER_SERVER = "server"
        const val DNS_PROVIDER_CLOUDFLARE = "cloudflare"
        const val DNS_PROVIDER_GOOGLE = "google"
        const val DNS_PROVIDER_QUAD9 = "quad9"
        const val DNS_PROVIDER_ADGUARD = "adguard"
        const val DNS_PROVIDER_CUSTOM = "custom"

        const val SPLIT_TUNNEL_ALLOWLIST = "allowlist"
        const val SPLIT_TUNNEL_BYPASS = "bypass"
        
        const val DEFAULT_SOCKS5_PORT = 10880
        const val DEFAULT_HTTP_PORT = 10890

        /**
         * Returns a pair of DNS IP addresses (primary, secondary) based on provider.
         * If provider is "server", returns null so OpenVPN-pushed DNS is used.
         */
        fun resolveDnsIps(provider: String, customPrimary: String, customSecondary: String): Pair<String, String>? {
            return when (provider) {
                DNS_PROVIDER_CLOUDFLARE -> Pair("1.1.1.1", "1.0.0.1")
                DNS_PROVIDER_GOOGLE -> Pair("8.8.8.8", "8.8.4.4")
                DNS_PROVIDER_QUAD9 -> Pair("9.9.9.9", "149.112.112.112")
                DNS_PROVIDER_ADGUARD -> Pair("94.140.14.14", "94.140.15.15")
                DNS_PROVIDER_CUSTOM -> {
                    val p = customPrimary.trim().ifEmpty { "1.1.1.1" }
                    val s = customSecondary.trim().ifEmpty { "1.0.0.1" }
                    Pair(p, s)
                }
                else -> null // Use OpenVPN server-pushed DNS
            }
        }
    }
    
    val themeMode: Flow<String> = context.dataStore.data.map { it[THEME_MODE] ?: THEME_SYSTEM }
    val tunnelMode: Flow<String> = context.dataStore.data.map { it[TUNNEL_MODE] ?: TUNNEL_MODE_TUN }
    val socks5Port: Flow<Int> = context.dataStore.data.map { it[SOCKS5_PORT] ?: DEFAULT_SOCKS5_PORT }
    val httpPort: Flow<Int> = context.dataStore.data.map { it[HTTP_PORT] ?: DEFAULT_HTTP_PORT }
    val proxyAuthEnabled: Flow<Boolean> = context.dataStore.data.map { it[PROXY_AUTH_ENABLED] ?: false }
    val proxyUsername: Flow<String> = context.dataStore.data.map { it[PROXY_USERNAME] ?: "" }
    val proxyPassword: Flow<String> = context.dataStore.data.map {
        val raw = it[PROXY_PASSWORD] ?: ""
        if (raw.isNotEmpty()) {
            val decrypted = com.eliyardo.omnia.security.KeystoreHelper.decrypt(raw)
            // If Keystore decryption fails, treat as invalidated and return empty (never leak raw ciphertext)
            decrypted
        } else ""
    }
    val dnsMode: Flow<String> = context.dataStore.data.map { it[DNS_MODE] ?: DNS_MODE_REMOTE }
    val dnsProvider: Flow<String> = context.dataStore.data.map { it[DNS_PROVIDER] ?: DNS_PROVIDER_SERVER }
    val customDnsPrimary: Flow<String> = context.dataStore.data.map { it[CUSTOM_DNS_PRIMARY] ?: "1.1.1.1" }
    val customDnsSecondary: Flow<String> = context.dataStore.data.map { it[CUSTOM_DNS_SECONDARY] ?: "1.0.0.1" }
    val autoReconnect: Flow<Boolean> = context.dataStore.data.map { it[AUTO_RECONNECT] ?: true }
    val reconnectDelaySeconds: Flow<Int> = context.dataStore.data.map { it[RECONNECT_DELAY_SECONDS] ?: 5 }
    val logLevel: Flow<Int> = context.dataStore.data.map { it[LOG_LEVEL] ?: 3 }
    val bindAddress: Flow<String> = context.dataStore.data.map { it[BIND_ADDRESS] ?: "127.0.0.1" }
    val allowLan: Flow<Boolean> = context.dataStore.data.map { it[ALLOW_LAN] ?: false }
    val ipv6Enabled: Flow<Boolean> = context.dataStore.data.map { it[IPV6_ENABLED] ?: true }
    val legacyCrypto: Flow<Boolean> = context.dataStore.data.map { it[LEGACY_CRYPTO] ?: false }
    val blockTrafficOnDrop: Flow<Boolean> = context.dataStore.data.map { it[BLOCK_TRAFFIC_ON_DROP] ?: true }

    val vpnProtocolOverride: Flow<String> = context.dataStore.data.map { it[VPN_PROTOCOL_OVERRIDE] ?: PROTO_ADAPTIVE }
    val connectionTimeout: Flow<Int> = context.dataStore.data.map { it[CONNECTION_TIMEOUT] ?: TIMEOUT_1M }
    val securityLevel: Flow<String> = context.dataStore.data.map { it[SECURITY_LEVEL] ?: SECURITY_LEVEL_LEGACY }
    val enforceTls13: Flow<Boolean> = context.dataStore.data.map { it[ENFORCE_TLS_1_3] ?: false }
    val confirmOnDisconnect: Flow<Boolean> = context.dataStore.data.map { it[CONFIRM_ON_DISCONNECT] ?: false }
    val bypassLanTun: Flow<Boolean> = context.dataStore.data.map { it[BYPASS_LAN_TUN] ?: true }

    val upstreamProxyEnabled: Flow<Boolean> = context.dataStore.data.map { it[UPSTREAM_PROXY_ENABLED] ?: false }
    val upstreamProxyHost: Flow<String> = context.dataStore.data.map { it[UPSTREAM_PROXY_HOST] ?: "" }
    val upstreamProxyPort: Flow<Int> = context.dataStore.data.map { it[UPSTREAM_PROXY_PORT] ?: 8080 }
    val upstreamProxyUsername: Flow<String> = context.dataStore.data.map { it[UPSTREAM_PROXY_USERNAME] ?: "" }
    val upstreamProxyPassword: Flow<String> = context.dataStore.data.map {
        val raw = it[UPSTREAM_PROXY_PASSWORD] ?: ""
        if (raw.isNotEmpty()) {
            com.eliyardo.omnia.security.KeystoreHelper.decrypt(raw)
        } else ""
    }
    val upstreamProxyAllowCleartext: Flow<Boolean> = context.dataStore.data.map { it[UPSTREAM_PROXY_ALLOW_CLEARTEXT] ?: true }

    val deviceId: Flow<String> = context.dataStore.data.map { it[DEVICE_ID] ?: "" }

    val splitTunnelEnabled: Flow<Boolean> = context.dataStore.data.map { it[SPLIT_TUNNEL_ENABLED] ?: false }
    val splitTunnelMode: Flow<String> = context.dataStore.data.map { it[SPLIT_TUNNEL_MODE] ?: SPLIT_TUNNEL_ALLOWLIST }
    val splitTunnelApps: Flow<Set<String>> = context.dataStore.data.map { it[SPLIT_TUNNEL_APPS] ?: emptySet() }
    
    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }

    suspend fun setTunnelMode(mode: String) {
        context.dataStore.edit { it[TUNNEL_MODE] = mode }
    }

    suspend fun setSocks5Port(port: Int) {
        context.dataStore.edit { it[SOCKS5_PORT] = port }
    }
    
    suspend fun setHttpPort(port: Int) {
        context.dataStore.edit { it[HTTP_PORT] = port }
    }
    
    suspend fun setProxyAuthEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PROXY_AUTH_ENABLED] = enabled }
    }

    suspend fun setProxyUsername(username: String) {
        context.dataStore.edit { it[PROXY_USERNAME] = username.trim() }
    }

    suspend fun setProxyPassword(password: String) {
        context.dataStore.edit {
            it[PROXY_PASSWORD] = if (password.isNotEmpty()) {
                com.eliyardo.omnia.security.KeystoreHelper.encrypt(password)
            } else ""
        }
    }

    suspend fun setProxyAuth(enabled: Boolean, username: String? = null, password: String? = null) {
        context.dataStore.edit {
            it[PROXY_AUTH_ENABLED] = enabled
            if (username != null) {
                it[PROXY_USERNAME] = username.trim()
            }
            if (password != null) {
                it[PROXY_PASSWORD] = if (password.isNotEmpty()) {
                    com.eliyardo.omnia.security.KeystoreHelper.encrypt(password)
                } else ""
            }
        }
    }

    suspend fun setBlockTrafficOnDrop(block: Boolean) {
        context.dataStore.edit { it[BLOCK_TRAFFIC_ON_DROP] = block }
    }
    
    suspend fun setDnsMode(mode: String) {
        context.dataStore.edit { it[DNS_MODE] = mode }
    }

    suspend fun setDnsProvider(provider: String) {
        context.dataStore.edit { it[DNS_PROVIDER] = provider }
    }

    suspend fun setCustomDns(primary: String, secondary: String) {
        context.dataStore.edit {
            it[CUSTOM_DNS_PRIMARY] = primary
            it[CUSTOM_DNS_SECONDARY] = secondary
        }
    }
    
    suspend fun setAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_RECONNECT] = enabled }
    }
    
    suspend fun setReconnectDelay(seconds: Int) {
        context.dataStore.edit { it[RECONNECT_DELAY_SECONDS] = seconds }
    }
    
    suspend fun setLogLevel(level: Int) {
        context.dataStore.edit { it[LOG_LEVEL] = level }
    }
    
    suspend fun setBindAddress(address: String) {
        context.dataStore.edit { it[BIND_ADDRESS] = address }
    }
    
    suspend fun setAllowLan(enabled: Boolean) {
        context.dataStore.edit { it[ALLOW_LAN] = enabled }
    }
    
    suspend fun setIpv6Enabled(enabled: Boolean) {
        context.dataStore.edit { it[IPV6_ENABLED] = enabled }
    }
    
    suspend fun setLegacyCrypto(enabled: Boolean) {
        context.dataStore.edit {
            it[LEGACY_CRYPTO] = enabled
            it[SECURITY_LEVEL] = if (enabled) SECURITY_LEVEL_LEGACY else SECURITY_LEVEL_PREFERRED
        }
    }

    suspend fun setSplitTunnelEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SPLIT_TUNNEL_ENABLED] = enabled }
    }

    suspend fun setSplitTunnelMode(mode: String) {
        context.dataStore.edit { it[SPLIT_TUNNEL_MODE] = mode }
    }

    suspend fun toggleSplitTunnelApp(packageName: String) {
        context.dataStore.edit { pref ->
            val current = pref[SPLIT_TUNNEL_APPS] ?: emptySet()
            if (current.contains(packageName)) {
                pref[SPLIT_TUNNEL_APPS] = current - packageName
            } else {
                pref[SPLIT_TUNNEL_APPS] = current + packageName
            }
        }
    }

    suspend fun setSplitTunnelApps(apps: Set<String>) {
        context.dataStore.edit { it[SPLIT_TUNNEL_APPS] = apps }
    }

    suspend fun setVpnProtocolOverride(proto: String) {
        context.dataStore.edit { it[VPN_PROTOCOL_OVERRIDE] = proto }
    }

    suspend fun setConnectionTimeout(seconds: Int) {
        context.dataStore.edit { it[CONNECTION_TIMEOUT] = seconds }
    }

    suspend fun setSecurityLevel(level: String) {
        context.dataStore.edit {
            it[SECURITY_LEVEL] = level
            it[LEGACY_CRYPTO] = (level != SECURITY_LEVEL_PREFERRED)
        }
    }

    suspend fun setEnforceTls13(enforce: Boolean) {
        context.dataStore.edit { it[ENFORCE_TLS_1_3] = enforce }
    }

    suspend fun setConfirmOnDisconnect(confirm: Boolean) {
        context.dataStore.edit { it[CONFIRM_ON_DISCONNECT] = confirm }
    }

    suspend fun setBypassLanTun(bypass: Boolean) {
        context.dataStore.edit { it[BYPASS_LAN_TUN] = bypass }
    }

    suspend fun setUpstreamProxy(
        enabled: Boolean,
        host: String? = null,
        port: Int? = null,
        username: String? = null,
        password: String? = null,
        allowCleartext: Boolean? = null
    ) {
        context.dataStore.edit {
            it[UPSTREAM_PROXY_ENABLED] = enabled
            if (host != null) it[UPSTREAM_PROXY_HOST] = host.trim()
            if (port != null) it[UPSTREAM_PROXY_PORT] = port
            if (username != null) it[UPSTREAM_PROXY_USERNAME] = username.trim()
            if (password != null) {
                it[UPSTREAM_PROXY_PASSWORD] = if (password.isNotEmpty()) {
                    com.eliyardo.omnia.security.KeystoreHelper.encrypt(password)
                } else ""
            }
            if (allowCleartext != null) it[UPSTREAM_PROXY_ALLOW_CLEARTEXT] = allowCleartext
        }
    }

    suspend fun getOrCreateDeviceId(): String {
        val current: String? = context.dataStore.data.first()[DEVICE_ID]
        if (!current.isNullOrBlank()) return current
        val newId = java.util.UUID.randomUUID().toString()
        context.dataStore.edit { it[DEVICE_ID] = newId }
        return newId
    }

    suspend fun setDeviceId(id: String) {
        context.dataStore.edit { it[DEVICE_ID] = id.trim() }
    }

    /**
     * Returns a pair of DNS IP addresses (primary, secondary) based on provider.
     * If provider is "server", returns null so OpenVPN-pushed DNS is used.
     */
    fun resolveDnsIps(provider: String, customPrimary: String, customSecondary: String): Pair<String, String>? {
        return Companion.resolveDnsIps(provider, customPrimary, customSecondary)
    }
}
