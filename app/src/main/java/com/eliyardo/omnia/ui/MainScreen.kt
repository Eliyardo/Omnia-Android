package com.eliyardo.omnia.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eliyardo.omnia.R
import com.eliyardo.omnia.profile.OvpnProfile
import com.eliyardo.omnia.profile.ProfileValidationResult
import com.eliyardo.omnia.settings.AppSettings
import com.eliyardo.omnia.tunnel.TunnelEngine
import com.eliyardo.omnia.tunnel.TunnelState
import com.eliyardo.omnia.ui.theme.StatusConnectedGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()
    val tunnelState by viewModel.tunnelState.collectAsState()
    val activeProfile by viewModel.profileManager.activeProfile.collectAsState()
    val profiles by viewModel.profileManager.profiles.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val showCredentialsDialog by viewModel.showCredentialsDialog.collectAsState()
    val trafficStats by viewModel.trafficStats.collectAsState()
    val crashLog by viewModel.crashLog.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val tunnelMode by viewModel.tunnelMode.collectAsState()
    val stagedImport by viewModel.stagedImport.collectAsState()
    val confirmOnDisconnect by viewModel.settings.confirmOnDisconnect.collectAsState(initial = false)

    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showProfileManager by remember { mutableStateOf(false) }
    var showDisconnectConfirmDialog by remember { mutableStateOf(false) }
    var isDisconnecting by remember { mutableStateOf(false) }
    var profileToEdit by remember { mutableStateOf<OvpnProfile?>(null) }

    LaunchedEffect(tunnelState) {
        if (tunnelState is TunnelState.Disconnected || tunnelState is TunnelState.Error) {
            isDisconnecting = false
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importProfile(it) }
    }

    if (showCredentialsDialog) {
        CredentialsDialog(
            onConfirm = { username, password -> viewModel.connectWithCredentials(username, password) },
            onDismiss = { viewModel.dismissCredentialsDialog() }
        )
    }

    if (showDisconnectConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirmDialog = false },
            title = {
                Text(
                    text = "Disconnect from VPN?",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = "Your connection to ${activeProfile?.name ?: "the VPN server"} will be disconnected, and network traffic will no longer be encrypted.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDisconnectConfirmDialog = false
                        isDisconnecting = true
                        viewModel.disconnect()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Disconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirmDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

    crashLog?.let { logText ->
        AlertDialog(
            onDismissRequest = { viewModel.clearCrashLog() },
            title = { Text("Previous Crash Log", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "The app encountered an unexpected error on a previous run:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            text = logText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Crash Log", logText))
                    Toast.makeText(context, "Crash log copied to clipboard", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearCrashLog() }) {
                    Text("Dismiss")
                }
            }
        )
    }

    importResult?.let { result ->
        if (result.errors.isNotEmpty() || result.warnings.isNotEmpty()) {
            ImportResultDialog(result = result, onDismiss = { viewModel.clearImportResult() })
        } else {
            viewModel.clearImportResult()
        }
    }

    stagedImport?.let { staged ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelStagedImport() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Confirm Profile Import", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "An external application has requested to import a VPN configuration into Omnia. Please verify the connection details:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Profile: ${staged.fileName}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Text("Endpoint: ${staged.remoteHost}:${staged.remotePort} (${staged.protocol.uppercase()})", style = MaterialTheme.typography.labelSmall)
                            Text("CA Certificate: ${if (staged.hasCa) (staged.caFingerprint?.let { "SHA-256 $it" } ?: "Embedded") else "None"}", style = MaterialTheme.typography.labelSmall)
                            Text("Authentication: ${if (staged.hasAuth) "User/Password Required" else "Certificate-Only"}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (staged.warnings.isNotEmpty()) {
                        Text("Warnings:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        staged.warnings.forEach { w ->
                            Text("• $w", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text(
                        text = "Caution: Only import configurations from trusted network providers.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmStagedImport() }
                ) {
                    Text("Import Profile")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelStagedImport() }) {
                    Text("Cancel")
                }
            }
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    AnimatedContent(
        targetState = showSettings,
        transitionSpec = {
            if (targetState) {
                // Main -> Settings: Settings slides in on top from right (zIndex 1f)
                slideInHorizontally(
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                    initialOffsetX = { fullWidth -> fullWidth }
                ).togetherWith(
                    slideOutHorizontally(
                        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                        targetOffsetX = { fullWidth -> -fullWidth / 4 }
                    )
                ).apply {
                    targetContentZIndex = 1f
                }
            } else {
                // Settings -> Main: Settings slides out to right on top, revealing Main
                slideInHorizontally(
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                    initialOffsetX = { fullWidth -> -fullWidth / 4 }
                ).togetherWith(
                    slideOutHorizontally(
                        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                        targetOffsetX = { fullWidth -> fullWidth }
                    )
                ).apply {
                    targetContentZIndex = 0f
                }
            }
        },
        label = "MainSettingsTransition",
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) { inSettings ->
        if (inSettings) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { showSettings = false },
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            )
        } else {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_omnia_logo),
                            contentDescription = "Omnia Logo",
                            modifier = Modifier.size(30.dp),
                            tint = Color.Unspecified
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Omnia",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (tunnelMode == AppSettings.TUNNEL_MODE_PROXY) "SOCKS5 & HTTP PROXY" else "SYSTEM-WIDE VPN",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    // Quick theme toggle
                    IconButton(
                        onClick = {
                            scope.launch {
                                val nextTheme = if (themeMode == AppSettings.THEME_DARK) {
                                    AppSettings.THEME_LIGHT
                                } else {
                                    AppSettings.THEME_DARK
                                }
                                viewModel.settings.setThemeMode(nextTheme)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (themeMode == AppSettings.THEME_DARK) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle Theme",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Settings screen button
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // Profile Selector Card at Top
            ProfileCard(
                profile = activeProfile,
                onImport = { filePickerLauncher.launch(arrayOf("*/*")) },
                onManage = { showProfileManager = true },
                onEdit = { activeProfile?.let { profileToEdit = it } }
            )

            Spacer(modifier = Modifier.weight(1f))

            // Central Hero Power Button
            HeroPowerButton(
                tunnelState = tunnelState,
                hasProfile = activeProfile != null,
                isProxyMode = tunnelMode == AppSettings.TUNNEL_MODE_PROXY,
                isDisconnectingExternal = isDisconnecting,
                confirmOnDisconnect = confirmOnDisconnect,
                onConnect = { viewModel.connect() },
                onDisconnect = {
                    if (confirmOnDisconnect && tunnelState is TunnelState.Connected) {
                        showDisconnectConfirmDialog = true
                    } else {
                        isDisconnecting = true
                        viewModel.disconnect()
                    }
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            // Bottom Session Telemetry / Status Card
            BottomStatusCard(
                tunnelState = tunnelState,
                trafficStats = trafficStats,
                viewModel = viewModel,
                onRetry = { viewModel.connect() }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Dedicated Profiles Management Bottom Sheet / Dialog
        if (showProfileManager) {
            ProfilesManagerDialog(
                profiles = profiles,
                activeProfileId = activeProfile?.id,
                onSelect = { id ->
                    viewModel.selectProfile(id)
                    showProfileManager = false
                },
                onEdit = { profile ->
                    profileToEdit = profile
                },
                onDelete = { id ->
                    viewModel.deleteProfile(id)
                },
                onImport = {
                    filePickerLauncher.launch(arrayOf("*/*"))
                },
                onDismiss = { showProfileManager = false }
            )
        }

        // Profile Editor Dialog
        profileToEdit?.let { prof ->
            EditProfileDialog(
                profile = prof,
                onSave = { id, name, host, port, proto, user, pass ->
                    viewModel.updateProfile(id, name, host, port, proto, user, pass)
                    profileToEdit = null
                },
                onDismiss = { profileToEdit = null }
            )
        }
    }
}
}
}

