package com.eliyardo.omnia.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eliyardo.omnia.R
import com.eliyardo.omnia.settings.AppSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var showSplitTunnelScreen by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }

    if (showSplitTunnelScreen) {
        SplitTunnelScreen(
            viewModel = viewModel,
            onBack = { showSplitTunnelScreen = false }
        )
        return
    }

    // Intercept hardware and gesture back buttons to return to main screen
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val themeMode by viewModel.themeMode.collectAsState()
    val tunnelMode by viewModel.tunnelMode.collectAsState()
    val socks5Port by viewModel.settings.socks5Port.collectAsState(initial = AppSettings.DEFAULT_SOCKS5_PORT)
    val httpPort by viewModel.settings.httpPort.collectAsState(initial = AppSettings.DEFAULT_HTTP_PORT)
    val proxyAuthEnabled by viewModel.settings.proxyAuthEnabled.collectAsState(initial = false)
    val proxyUsername by viewModel.settings.proxyUsername.collectAsState(initial = "")
    val proxyPassword by viewModel.settings.proxyPassword.collectAsState(initial = "")
    val dnsMode by viewModel.settings.dnsMode.collectAsState(initial = AppSettings.DNS_MODE_REMOTE)
    val dnsProvider by viewModel.settings.dnsProvider.collectAsState(initial = AppSettings.DNS_PROVIDER_SERVER)
    val customDnsPrimary by viewModel.settings.customDnsPrimary.collectAsState(initial = "1.1.1.1")
    val customDnsSecondary by viewModel.settings.customDnsSecondary.collectAsState(initial = "1.0.0.1")
    val autoReconnect by viewModel.settings.autoReconnect.collectAsState(initial = true)
    val reconnectDelay by viewModel.settings.reconnectDelaySeconds.collectAsState(initial = 5)
    val allowLan by viewModel.settings.allowLan.collectAsState(initial = false)
    val ipv6Enabled by viewModel.settings.ipv6Enabled.collectAsState(initial = true)
    val legacyCrypto by viewModel.settings.legacyCrypto.collectAsState(initial = false)
    val blockTrafficOnDrop by viewModel.settings.blockTrafficOnDrop.collectAsState(initial = true)
    val isSplitEnabled by viewModel.settings.splitTunnelEnabled.collectAsState(initial = false)
    val selectedApps by viewModel.settings.splitTunnelApps.collectAsState(initial = emptySet())

    val vpnProtocolOverride by viewModel.settings.vpnProtocolOverride.collectAsState(initial = AppSettings.PROTO_ADAPTIVE)
    val connectionTimeout by viewModel.settings.connectionTimeout.collectAsState(initial = AppSettings.TIMEOUT_1M)
    val securityLevel by viewModel.settings.securityLevel.collectAsState(initial = AppSettings.SECURITY_LEVEL_PREFERRED)
    val enforceTls13 by viewModel.settings.enforceTls13.collectAsState(initial = false)
    val confirmOnDisconnect by viewModel.settings.confirmOnDisconnect.collectAsState(initial = false)
    val bypassLanTun by viewModel.settings.bypassLanTun.collectAsState(initial = true)

    val upstreamProxyEnabled by viewModel.settings.upstreamProxyEnabled.collectAsState(initial = false)
    val upstreamProxyHost by viewModel.settings.upstreamProxyHost.collectAsState(initial = "")
    val upstreamProxyPort by viewModel.settings.upstreamProxyPort.collectAsState(initial = 8080)
    val upstreamProxyUsername by viewModel.settings.upstreamProxyUsername.collectAsState(initial = "")
    val upstreamProxyPassword by viewModel.settings.upstreamProxyPassword.collectAsState(initial = "")
    val upstreamProxyAllowCleartext by viewModel.settings.upstreamProxyAllowCleartext.collectAsState(initial = false)

    var socks5PortText by remember(socks5Port) { mutableStateOf(socks5Port.toString()) }
    var httpPortText by remember(httpPort) { mutableStateOf(httpPort.toString()) }
    var proxyUserText by remember(proxyUsername) { mutableStateOf(proxyUsername) }
    var proxyPassText by remember(proxyPassword) { mutableStateOf(proxyPassword) }
    var customPrimaryText by remember(customDnsPrimary) { mutableStateOf(customDnsPrimary) }
    var customSecondaryText by remember(customDnsSecondary) { mutableStateOf(customDnsSecondary) }
    var reconnectDelayText by remember(reconnectDelay) { mutableStateOf(reconnectDelay.toString()) }

    var upstreamHostText by remember(upstreamProxyHost) { mutableStateOf(upstreamProxyHost) }
    var upstreamPortText by remember(upstreamProxyPort) { mutableStateOf(upstreamProxyPort.toString()) }
    var upstreamUserText by remember(upstreamProxyUsername) { mutableStateOf(upstreamProxyUsername) }
    var upstreamPassText by remember(upstreamProxyPassword) { mutableStateOf(upstreamProxyPassword) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Configuration & Preferences",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ================= SECTION 1: APPEARANCE =================
            SettingsSectionCard(
                icon = Icons.Default.Palette,
                title = "Appearance",
                subtitle = "Choose your color palette"
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ThemeOptionSegment(
                        label = "System",
                        icon = Icons.Default.BrightnessAuto,
                        isSelected = themeMode == AppSettings.THEME_SYSTEM,
                        modifier = Modifier.weight(1f),
                        onClick = { scope.launch { viewModel.settings.setThemeMode(AppSettings.THEME_SYSTEM) } }
                    )
                    ThemeOptionSegment(
                        label = "Dark",
                        icon = Icons.Default.DarkMode,
                        isSelected = themeMode == AppSettings.THEME_DARK,
                        modifier = Modifier.weight(1f),
                        onClick = { scope.launch { viewModel.settings.setThemeMode(AppSettings.THEME_DARK) } }
                    )
                    ThemeOptionSegment(
                        label = "Light",
                        icon = Icons.Default.LightMode,
                        isSelected = themeMode == AppSettings.THEME_LIGHT,
                        modifier = Modifier.weight(1f),
                        onClick = { scope.launch { viewModel.settings.setThemeMode(AppSettings.THEME_LIGHT) } }
                    )
                }
            }

            // ================= SECTION 2: ROUTING MODE (PROXY vs TUN) =================
            SettingsSectionCard(
                icon = Icons.Default.VpnLock,
                title = "Tunnel Operation Mode",
                subtitle = "Select between userspace proxy or system-wide VPN"
            ) {
                val isTun = tunnelMode == AppSettings.TUNNEL_MODE_TUN
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Left: TUN Mode
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                scope.launch { viewModel.settings.setTunnelMode(AppSettings.TUNNEL_MODE_TUN) }
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isTun) MaterialTheme.colorScheme.surface else Color.Transparent,
                        border = if (isTun) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "TUN Mode",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isTun) FontWeight.Bold else FontWeight.Medium,
                                color = if (isTun) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "System-Wide Routing",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Right: Proxy Mode
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                scope.launch { viewModel.settings.setTunnelMode(AppSettings.TUNNEL_MODE_PROXY) }
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = if (!isTun) MaterialTheme.colorScheme.surface else Color.Transparent,
                        border = if (!isTun) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Proxy Mode",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (!isTun) FontWeight.Bold else FontWeight.Medium,
                                color = if (!isTun) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "SOCKS5 & HTTP",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // If TUN Mode: Show Per-App Routing (Split Tunneling) Button
                if (isTun) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showSplitTunnelScreen = true },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Apps,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Per-App Routing (Split Tunneling)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isSplitEnabled) {
                                        "${selectedApps.size} apps configured"
                                    } else {
                                        "All phone apps route through VPN (Tap to configure)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Open",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Bypass Local LAN Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Bypass Local LAN",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Exclude local network traffic (IPv4 private subnets & IPv6 local links) so local devices and printers remain accessible",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = bypassLanTun,
                            onCheckedChange = { enabled ->
                                scope.launch { viewModel.settings.setBypassLanTun(enabled) }
                            }
                        )
                    }
                }
            }

            // ================= SECTION 3: PROXY PORTS (PROXY MODE) =================
            SettingsSectionCard(
                icon = Icons.AutoMirrored.Filled.AltRoute,
                title = "Proxy Configuration",
                subtitle = "Local listening ports for proxy clients"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val isPortCollision = socks5PortText.trim().isNotEmpty() && socks5PortText.trim() == httpPortText.trim()

                    OutlinedTextField(
                        value = socks5PortText,
                        onValueChange = { v ->
                            socks5PortText = v
                            v.toIntOrNull()?.let { port ->
                                if (port in 1024..65535 && port != httpPort) {
                                    scope.launch { viewModel.settings.setSocks5Port(port) }
                                }
                            }
                        },
                        label = { Text("SOCKS5 Port") },
                        placeholder = { Text("10880") },
                        isError = isPortCollision,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    )

                    OutlinedTextField(
                        value = httpPortText,
                        onValueChange = { v ->
                            httpPortText = v
                            v.toIntOrNull()?.let { port ->
                                if (port in 1024..65535 && port != socks5Port) {
                                    scope.launch { viewModel.settings.setHttpPort(port) }
                                }
                            }
                        },
                        label = { Text("HTTP Port") },
                        placeholder = { Text("10890") },
                        isError = isPortCollision,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                if (socks5PortText.trim().isNotEmpty() && socks5PortText.trim() == httpPortText.trim()) {
                    Text(
                        text = "SOCKS5 and HTTP ports cannot be the same.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            socks5PortText = "10880"
                            httpPortText = "10890"
                            scope.launch {
                                viewModel.settings.setSocks5Port(10880)
                                viewModel.settings.setHttpPort(10890)
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text("Reset to Default (10880 / 10890)", style = MaterialTheme.typography.labelSmall)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Proxy Authentication
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Require Proxy Authentication",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Require username & password to use the proxy",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = proxyAuthEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    viewModel.settings.setProxyAuthEnabled(enabled)
                                    if (!enabled && allowLan) {
                                        viewModel.settings.setAllowLan(false)
                                        viewModel.settings.setBindAddress("127.0.0.1")
                                    }
                                }
                            }
                        )
                    }

                    AnimatedVisibility(
                        visible = proxyAuthEnabled,
                        enter = fadeIn(animationSpec = tween(120)) + expandVertically(
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                            expandFrom = Alignment.Top
                        ),
                        exit = fadeOut(animationSpec = tween(80)) + shrinkVertically(
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                            shrinkTowards = Alignment.Top
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clipToBounds()
                                .padding(top = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = proxyUserText,
                                onValueChange = { v ->
                                    proxyUserText = v
                                    scope.launch {
                                        viewModel.settings.setProxyUsername(v)
                                    }
                                },
                                label = { Text("Proxy Username") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )

                            OutlinedTextField(
                                value = proxyPassText,
                                onValueChange = { v ->
                                    proxyPassText = v
                                    scope.launch {
                                        viewModel.settings.setProxyPassword(v)
                                    }
                                },
                                label = { Text("Proxy Password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )

                            if (proxyUserText.isBlank() || proxyPassText.isBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 2.dp, start = 4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Enter both username and password to enforce authentication.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 2.dp, start = 4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Proxy authentication is active and enforced.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Upstream HTTP Proxy
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Upstream HTTP Proxy",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Route VPN tunnel connection through an upstream HTTP proxy",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = upstreamProxyEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    viewModel.settings.setUpstreamProxy(
                                        enabled = enabled,
                                        host = upstreamHostText,
                                        port = upstreamPortText.toIntOrNull() ?: 8080,
                                        username = upstreamUserText,
                                        password = upstreamPassText
                                    )
                                }
                            }
                        )
                    }

                    AnimatedVisibility(
                        visible = upstreamProxyEnabled,
                        enter = fadeIn(animationSpec = tween(120)) + expandVertically(
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                            expandFrom = Alignment.Top
                        ),
                        exit = fadeOut(animationSpec = tween(80)) + shrinkVertically(
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                            shrinkTowards = Alignment.Top
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clipToBounds()
                                .padding(top = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = upstreamHostText,
                                    onValueChange = { v ->
                                        upstreamHostText = v
                                        scope.launch {
                                            viewModel.settings.setUpstreamProxy(
                                                enabled = upstreamProxyEnabled,
                                                host = v
                                            )
                                        }
                                    },
                                    label = { Text("Proxy Host / IP") },
                                    placeholder = { Text("192.168.1.100") },
                                    singleLine = true,
                                    modifier = Modifier.weight(2f),
                                    shape = RoundedCornerShape(10.dp)
                                )

                                OutlinedTextField(
                                    value = upstreamPortText,
                                    onValueChange = { v ->
                                        upstreamPortText = v
                                        v.toIntOrNull()?.let { p ->
                                            if (p in 1..65535) {
                                                scope.launch {
                                                    viewModel.settings.setUpstreamProxy(
                                                        enabled = upstreamProxyEnabled,
                                                        port = p
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    label = { Text("Port") },
                                    placeholder = { Text("8080") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }

                            OutlinedTextField(
                                value = upstreamUserText,
                                onValueChange = { v ->
                                    upstreamUserText = v
                                    scope.launch {
                                        viewModel.settings.setUpstreamProxy(
                                            enabled = upstreamProxyEnabled,
                                            username = v
                                        )
                                    }
                                },
                                label = { Text("Username (Optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )

                            OutlinedTextField(
                                value = upstreamPassText,
                                onValueChange = { v ->
                                    upstreamPassText = v
                                    scope.launch {
                                        viewModel.settings.setUpstreamProxy(
                                            enabled = upstreamProxyEnabled,
                                            password = v
                                        )
                                    }
                                },
                                label = { Text("Password (Optional)") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Allow Cleartext Authentication",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Permits HTTP Basic authentication headers",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = upstreamProxyAllowCleartext,
                                    onCheckedChange = { allowed ->
                                        scope.launch {
                                            viewModel.settings.setUpstreamProxy(
                                                enabled = upstreamProxyEnabled,
                                                allowCleartext = allowed
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ================= SECTION 4: DNS CONFIGURATION =================
            SettingsSectionCard(
                icon = Icons.Default.Dns,
                title = "DNS Resolvers",
                subtitle = "Select DNS resolver used inside the tunnel"
            ) {
                // Clean DNS options list with server pushed on top
                val dnsOptions = listOf(
                    Triple(AppSettings.DNS_PROVIDER_SERVER, "Server Pushed (Default)", "Uses DNS provided by OpenVPN server config"),
                    Triple(AppSettings.DNS_PROVIDER_CLOUDFLARE, "Cloudflare DNS", "1.1.1.1 · 1.0.0.1"),
                    Triple(AppSettings.DNS_PROVIDER_GOOGLE, "Google Public DNS", "8.8.8.8 · 8.8.4.4"),
                    Triple(AppSettings.DNS_PROVIDER_QUAD9, "Quad9 DNS", "9.9.9.9 · 149.112.112.112"),
                    Triple(AppSettings.DNS_PROVIDER_ADGUARD, "AdGuard DNS", "94.140.14.14 · 94.140.15.15"),
                    Triple(AppSettings.DNS_PROVIDER_CUSTOM, "Custom DNS", "Specify primary & secondary IP")
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    dnsOptions.forEach { (id, title, ips) ->
                        val selected = dnsProvider == id
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch { viewModel.settings.setDnsProvider(id) }
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                            border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = { scope.launch { viewModel.settings.setDnsProvider(id) } },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = ips,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (dnsProvider == AppSettings.DNS_PROVIDER_CUSTOM) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = customPrimaryText,
                                onValueChange = { v ->
                                    customPrimaryText = v
                                    scope.launch { viewModel.settings.setCustomDns(v, customSecondaryText) }
                                },
                                label = { Text("Primary DNS") },
                                placeholder = { Text("1.1.1.1") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            )

                            OutlinedTextField(
                                value = customSecondaryText,
                                onValueChange = { v ->
                                    customSecondaryText = v
                                    scope.launch { viewModel.settings.setCustomDns(customPrimaryText, v) }
                                },
                                label = { Text("Secondary DNS") },
                                placeholder = { Text("1.0.0.1") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }
                }
            }

            // ================= SECTION 5: NETWORK & SHARING =================
            SettingsSectionCard(
                icon = Icons.Default.Router,
                title = "Network & Sharing",
                subtitle = "Local interface and IPv6"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Allow LAN Access",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Listen on 0.0.0.0 so other devices on Wi-Fi can connect",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = allowLan,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                viewModel.settings.setAllowLan(enabled)
                                viewModel.settings.setBindAddress(if (enabled) "0.0.0.0" else "127.0.0.1")
                            }
                        }
                    )
                }

                if (allowLan) {
                    val isEffectiveLan = proxyAuthEnabled && proxyUserText.isNotBlank() && proxyPassText.isNotEmpty()
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isEffectiveLan) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isEffectiveLan) Icons.Default.Shield else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isEffectiveLan) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isEffectiveLan) {
                                    "LAN sharing active: Bound to 0.0.0.0 with proxy authentication enabled."
                                } else {
                                    "Security Warning: Anyone on your local Wi-Fi can connect without credentials. Enabling Proxy Authentication above is recommended."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isEffectiveLan) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "IPv6 Support",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Enable IPv6 packets through the tunnel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = ipv6Enabled,
                        onCheckedChange = { enabled ->
                            scope.launch { viewModel.settings.setIpv6Enabled(enabled) }
                        }
                    )
                }
            }

            // ================= SECTION 6: OPENVPN COMPATIBILITY & PROTOCOL =================
            SettingsSectionCard(
                icon = Icons.Default.VpnKey,
                title = "OpenVPN Core & Protocol",
                subtitle = "Transport protocol, timeouts, security levels & MDM"
            ) {
                // 1. Protocol Override
                Text(
                    text = "VPN Protocol Override",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Select transport protocol or let engine adapt automatically",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val protoOptions = listOf(
                    Triple(AppSettings.PROTO_ADAPTIVE, "Adaptive (Default)", "Tries UDP first, seamlessly falls back to TCP"),
                    Triple(AppSettings.PROTO_DEFAULT, "Config Default", "Strictly uses the protocol declared in the .ovpn profile"),
                    Triple(AppSettings.PROTO_UDP, "Force UDP", "Fastest speed and lowest latency"),
                    Triple(AppSettings.PROTO_TCP, "Force TCP", "Bypasses restrictive network firewalls")
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    protoOptions.forEach { (proto, title, desc) ->
                        val selected = vpnProtocolOverride == proto
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch { viewModel.settings.setVpnProtocolOverride(proto) }
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                            border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = { scope.launch { viewModel.settings.setVpnProtocolOverride(proto) } },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 2. Connection Timeout & Continuous Retry
                Text(
                    text = "Connection Timeout",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Maximum time to establish connection before timing out",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val timeoutOptions = listOf(
                    Triple(
                        AppSettings.TIMEOUT_10S,
                        "10 Seconds",
                        "Fail fast on unreachable or offline server endpoints"
                    ),
                    Triple(
                        AppSettings.TIMEOUT_30S,
                        "30 Seconds",
                        "Balanced timeout for mobile cellular networks"
                    ),
                    Triple(
                        AppSettings.TIMEOUT_1M,
                        "1 Minute (Default)",
                        "Recommended standard OpenVPN handshake timeout"
                    ),
                    Triple(
                        AppSettings.TIMEOUT_2M,
                        "2 Minutes",
                        "Extended timeout for slow, congested, or high-latency links"
                    ),
                    Triple(
                        AppSettings.TIMEOUT_CONTINUOUS,
                        "Continuous Retry",
                        "Never time out; keep attempting to connect indefinitely"
                    )
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    timeoutOptions.forEach { (sec, title, desc) ->
                        val selected = connectionTimeout == sec
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch { viewModel.settings.setConnectionTimeout(sec) }
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                            border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = { scope.launch { viewModel.settings.setConnectionTimeout(sec) } },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 3. Cryptographic Security Level (3-Tier)
                Text(
                    text = "Cryptographic Security Level",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Controls cipher strength and legacy algorithm acceptance",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val secLevels = listOf(
                    Triple(
                        AppSettings.SECURITY_LEVEL_PREFERRED,
                        "Preferred (Default)",
                        "Modern ciphers only (AES-GCM, ChaCha20-Poly1305). Enforces strong cryptography and strict verification."
                    ),
                    Triple(
                        AppSettings.SECURITY_LEVEL_LEGACY,
                        "Legacy",
                        "Allows AES-CBC, BF-CBC, and older cert profiles for compatibility with legacy OpenVPN servers."
                    ),
                    Triple(
                        AppSettings.SECURITY_LEVEL_INSECURE,
                        "Insecure",
                        "Accepts deprecated TLS certificates and weak cipher suites. Not recommended."
                    )
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    secLevels.forEach { (level, title, desc) ->
                        val selected = securityLevel == level
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch { viewModel.settings.setSecurityLevel(level) }
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                            border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = { scope.launch { viewModel.settings.setSecurityLevel(level) } },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 4. Enforce TLS 1.3
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enforce TLS 1.3",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Require TLS 1.3 for handshake. Connection will fail if server does not support TLS 1.3.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enforceTls13,
                        onCheckedChange = { enabled ->
                            scope.launch { viewModel.settings.setEnforceTls13(enabled) }
                        }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 5. Auto-Reconnect
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Reconnect",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Automatically reconnect if the connection drops unexpectedly",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoReconnect,
                        onCheckedChange = { enabled ->
                            scope.launch { viewModel.settings.setAutoReconnect(enabled) }
                        }
                    )
                }

                if (autoReconnect) {
                    OutlinedTextField(
                        value = reconnectDelayText,
                        onValueChange = { v ->
                            reconnectDelayText = v
                            v.toIntOrNull()?.let { delay ->
                                if (delay in 1..120) {
                                    scope.launch { viewModel.settings.setReconnectDelay(delay) }
                                }
                            }
                        },
                        label = { Text("Reconnect Delay (seconds)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 6. Kill Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Block Traffic on Drop (Kill Switch)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Keeps virtual network active when VPN disconnects unexpectedly to prevent unencrypted traffic leaks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = blockTrafficOnDrop,
                        onCheckedChange = { enabled ->
                            scope.launch { viewModel.settings.setBlockTrafficOnDrop(enabled) }
                        }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 7. Confirm on Disconnect
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Confirm on Disconnect",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Show confirmation dialog before terminating active connection",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = confirmOnDisconnect,
                        onCheckedChange = { enabled ->
                            scope.launch { viewModel.settings.setConfirmOnDisconnect(enabled) }
                        }
                    )
                }
            }

            // ================= SECTION 7: ABOUT =================
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(16.dp)
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header: Logo + "About Omnia" + Version
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_omnia_logo),
                            contentDescription = "Omnia Logo",
                            modifier = Modifier.size(38.dp),
                            tint = Color.Unspecified
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "About Omnia",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Version ${com.eliyardo.omnia.BuildConfig.VERSION_NAME} · Release Build",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Architecture & Stack Details
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AboutDetailRow(
                            label = "Core Engine",
                            value = "OpenVPN 3 Core (C++ / OpenSSL 3.x)"
                        )
                        AboutDetailRow(
                            label = "Proxy Stack",
                            value = "Userspace lwIP TCP/IP + SOCKS5 (TCP/UDP) & HTTP"
                        )
                        AboutDetailRow(
                            label = "Tunnel Engine",
                            value = "Android VpnService + Per-App Split Routing"
                        )
                        AboutDetailRow(
                            label = "SOCKS5 Protocol",
                            value = "RFC 1928 compliant (TCP CONNECT & UDP ASSOCIATE)"
                        )
                        AboutDetailRow(
                            label = "License",
                            value = "GNU AGPLv3 (Free & Open Source)"
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // GitHub / Source Code row
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                try {
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://github.com/Eliyardo/Omnia-Android")
                                    )
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Source Code",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "GitHub",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Open Source Licenses row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showLicensesDialog = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Open Source Licenses",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "GNU AGPLv3",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showLicensesDialog) {
        LicensesDialog(onDismiss = { showLicensesDialog = false })
    }
}

@Composable
private fun SettingsSectionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            content()
        }
    }
}

@Composable
private fun ThemeOptionSegment(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AboutDetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = "Open Source Licenses",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Omnia is licensed under the GNU Affero General Public License v3.0 (AGPL-3.0). It incorporates the following open-source software libraries:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                LicenseItem(
                    name = "Omnia Client",
                    license = "GNU Affero General Public License v3.0 (AGPL-3.0)",
                    copyright = "Copyright © 2024 Eliyardo & Omnia Contributors"
                )
                LicenseItem(
                    name = "OpenVPN 3 Core Library",
                    license = "AGPL-3.0 / MPL-2.0 (with OpenSSL Exception)",
                    copyright = "Copyright © 2012-2024 OpenVPN Inc."
                )
                LicenseItem(
                    name = "lwIP (Lightweight IP)",
                    license = "BSD-3-Clause",
                    copyright = "Copyright © 2001-2004 Swedish Institute of Computer Science"
                )
                LicenseItem(
                    name = "OpenSSL (KDAB Android Port)",
                    license = "Apache-2.0",
                    copyright = "Copyright © 1998-2023 The OpenSSL Project Authors\nCopyright © 2013-2023 KDAB"
                )
                LicenseItem(
                    name = "Asio C++ Library",
                    license = "Boost Software License 1.0 (BSL-1.0)",
                    copyright = "Copyright © 2003-2023 Christopher M. Kohlhoff"
                )
                LicenseItem(
                    name = "{fmt} Formatting Library",
                    license = "MIT License",
                    copyright = "Copyright © 2012-present Victor Zverovich and {fmt} contributors"
                )
                LicenseItem(
                    name = "LZ4 Compression Library",
                    license = "BSD-2-Clause",
                    copyright = "Copyright © 2011-2020 Yann Collet"
                )
                LicenseItem(
                    name = "AndroidX & Jetpack Compose",
                    license = "Apache-2.0",
                    copyright = "Copyright © 2005-2024 The Android Open Source Project"
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun LicenseItem(name: String, license: String, copyright: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(text = license, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(text = copyright, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
