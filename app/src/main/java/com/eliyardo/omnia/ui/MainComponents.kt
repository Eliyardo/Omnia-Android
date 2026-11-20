package com.eliyardo.omnia.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eliyardo.omnia.R
import com.eliyardo.omnia.profile.OvpnProfile
import com.eliyardo.omnia.settings.AppSettings
import com.eliyardo.omnia.tunnel.TunnelEngine
import com.eliyardo.omnia.tunnel.TunnelState
import com.eliyardo.omnia.ui.theme.StatusConnectedGreen
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.TimeUnit
@Composable
fun ProfileCard(
    profile: OvpnProfile?,
    onImport: () -> Unit,
    onManage: () -> Unit,
    onEdit: () -> Unit
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
        Column(modifier = Modifier.padding(16.dp)) {
            if (profile != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${profile.remoteHost}:${profile.remotePort}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit Profile",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(2.dp))

                    IconButton(
                        onClick = onManage,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = "Switch Profile",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.FileOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "No Profile Selected",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Import an OpenVPN configuration (.ovpn) to start",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import .ovpn", style = MaterialTheme.typography.labelMedium)
                }

                if (profile != null) {
                    OutlinedButton(
                        onClick = onManage,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("All Profiles", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun HeroPowerButton(
    tunnelState: TunnelState,
    hasProfile: Boolean,
    isProxyMode: Boolean = true,
    isDisconnectingExternal: Boolean = false,
    confirmOnDisconnect: Boolean = false,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isConnected = tunnelState is TunnelState.Connected
    val isConnecting = tunnelState is TunnelState.Connecting || tunnelState is TunnelState.Reconnecting
    val isError = tunnelState is TunnelState.Error

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    var isDisconnectingUser by remember { mutableStateOf(false) }

    // When connection status transitions to Disconnected or Error, clear disconnecting flag
    LaunchedEffect(tunnelState) {
        if (tunnelState is TunnelState.Disconnected || tunnelState is TunnelState.Error) {
            isDisconnectingUser = false
        }
    }

    // Auto-timeout guard for disconnecting animation if teardown takes longer than 3.5 seconds
    LaunchedEffect(isDisconnectingUser) {
        if (isDisconnectingUser) {
            delay(3500)
            isDisconnectingUser = false
        }
    }

    val isDisconnecting = isDisconnectingUser || isDisconnectingExternal
    val isSpinning = isConnecting || isDisconnecting

    // Border and theme color transitions
    val targetBorderColor = when {
        isDisconnecting -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f)
        isConnected -> StatusConnectedGreen
        isError -> MaterialTheme.colorScheme.error
        isConnecting -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
    }

    val borderColor by animateColorAsState(
        targetValue = targetBorderColor,
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "borderColor"
    )

    // Smooth spin animation active during connecting/disconnecting
    val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val spinRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isDisconnecting) 800 else 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinRotation"
    )

    // Crossfade between stationary solid circle and spinning arcs
    val spinnerAlpha by animateFloatAsState(
        targetValue = if (isSpinning) 1f else 0f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "spinnerAlpha"
    )
    val solidCircleAlpha by animateFloatAsState(
        targetValue = if (isSpinning) 0f else 1f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "solidCircleAlpha"
    )

    // Sweep angle of perimeter arcs:
    // Disconnecting: 35° (short, fast unraveling)
    // Connecting: 95° (two balanced arcs with gaps)
    val targetSweep = if (isDisconnecting) 35f else 95f

    val arcSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = tween(
            durationMillis = if (isDisconnecting) 200 else 350,
            easing = FastOutSlowInEasing
        ),
        label = "arcSweep"
    )

    // Subtle ambient breathing glow when connected
    val ambientGlowTransition = rememberInfiniteTransition(label = "ambientGlow")
    val ambientGlowAlpha by ambientGlowTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambientGlowAlpha"
    )

    // Pulse wave ring on connect
    val connectPulseTransition = remember(isConnected) { Animatable(0f) }
    LaunchedEffect(isConnected) {
        if (isConnected) {
            connectPulseTransition.snapTo(0f)
            connectPulseTransition.animateTo(
                targetValue = 1f,
                animationSpec = tween(650, easing = FastOutSlowInEasing)
            )
        }
    }

    // Pulse dissipation & echo wave on disconnect
    var wasConnected by remember { mutableStateOf(false) }
    val disconnectPulseTransition = remember { Animatable(0f) }
    LaunchedEffect(tunnelState, isDisconnecting) {
        if (tunnelState is TunnelState.Connected) {
            wasConnected = true
        } else if (tunnelState is TunnelState.Disconnected && wasConnected) {
            wasConnected = false
            disconnectPulseTransition.snapTo(0f)
            disconnectPulseTransition.animateTo(
                targetValue = 1f,
                animationSpec = tween(750, easing = FastOutSlowInEasing)
            )
        } else if (tunnelState is TunnelState.Error) {
            wasConnected = false
        }
    }

    // Highly responsive button scale bounce (handles touch press + state transitions)
    val buttonScale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.93f
            isDisconnecting -> 0.95f
            isConnecting -> 0.98f
            else -> 1.0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "buttonScale"
    )

    val buttonSize = 186.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(buttonSize + 32.dp)
        ) {
            // 1. Ambient breathing halo when connected (smoothly fades out rather than instantly cutting off)
            val targetGlowAlpha = if (isConnected && !isDisconnecting) ambientGlowAlpha else 0f
            val animatedGlowAlpha by animateFloatAsState(
                targetValue = targetGlowAlpha,
                animationSpec = tween(380, easing = FastOutSlowInEasing),
                label = "animatedGlowAlpha"
            )
            if (animatedGlowAlpha > 0.001f) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = StatusConnectedGreen.copy(alpha = animatedGlowAlpha),
                        radius = (buttonSize.toPx() / 2f) + 10.dp.toPx() * (animatedGlowAlpha / 0.16f),
                        center = center
                    )
                }
            }

            // 2. Pulse wave ring on connect (Green expanding power wave)
            if (connectPulseTransition.value > 0f && connectPulseTransition.value < 1f) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val progress = connectPulseTransition.value
                    val alpha = (1f - progress).coerceIn(0f, 1f) * 0.45f
                    val stroke = 3.dp.toPx() * (1f - progress * 0.5f)
                    val radius = (buttonSize.toPx() / 2f) + (14.dp.toPx() * progress)

                    drawCircle(
                        color = StatusConnectedGreen.copy(alpha = alpha),
                        radius = radius,
                        center = center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                    )
                }
            }

            // 3. Pulse dissipation & implosion ring on disconnect (Polished shutdown FX)
            if (disconnectPulseTransition.value > 0f && disconnectPulseTransition.value < 1f) {
                val progress = disconnectPulseTransition.value
                val accentColor = MaterialTheme.colorScheme.secondary
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    // Outward expanding dissipation ring
                    val outwardAlpha = ((1f - progress) * (1f - progress)).coerceIn(0f, 1f) * 0.38f
                    val outwardStroke = 3.5.dp.toPx() * (1f - progress * 0.6f)
                    val outwardRadius = (buttonSize.toPx() / 2f) + (18.dp.toPx() * progress)

                    drawCircle(
                        color = accentColor.copy(alpha = outwardAlpha),
                        radius = outwardRadius,
                        center = center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = outwardStroke)
                    )

                    // Inward contracting echo ring (imploding into perimeter)
                    val inwardProgress = 1f - progress
                    val inwardAlpha = (inwardProgress * 0.20f).coerceIn(0f, 1f)
                    val inwardRadius = (buttonSize.toPx() / 2f) + (8.dp.toPx() * inwardProgress)
                    val inwardStroke = 2.dp.toPx() * inwardProgress

                    drawCircle(
                        color = accentColor.copy(alpha = inwardAlpha),
                        radius = inwardRadius,
                        center = center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = inwardStroke)
                    )
                }
            }

            // Main interactive circular button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(buttonSize)
                    .graphicsLayer {
                        scaleX = buttonScale
                        scaleY = buttonScale
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        if (isConnected || isConnecting) {
                            if (!confirmOnDisconnect || !isConnected) {
                                isDisconnectingUser = true
                            }
                            onDisconnect()
                        } else {
                            onConnect()
                        }
                    }
            ) {
                // Perimeter canvas drawing the border
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 3.5.dp.toPx()
                    val halfStroke = strokeWidth / 2f
                    val arcSize = androidx.compose.ui.geometry.Size(
                        size.width - strokeWidth,
                        size.height - strokeWidth
                    )
                    val topLeft = androidx.compose.ui.geometry.Offset(halfStroke, halfStroke)
                    val radius = (size.width - strokeWidth) / 2f

                    // 1. Stationary solid circle (for Connected, Disconnected, Error) - clean & seamless, no seam dots
                    if (solidCircleAlpha > 0.001f) {
                        drawCircle(
                            color = borderColor.copy(alpha = borderColor.alpha * solidCircleAlpha),
                            radius = radius,
                            center = center,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                        )
                    }

                    // 2. Dynamic spinning arcs (only active during Connecting or Disconnecting)
                    if (spinnerAlpha > 0.001f) {
                        // Faint background guide track
                        drawCircle(
                            color = borderColor.copy(alpha = 0.15f * spinnerAlpha),
                            radius = radius,
                            center = center,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                        )

                        val currentRotation = spinRotation

                        // Arc 1
                        drawArc(
                            color = borderColor.copy(alpha = borderColor.alpha * spinnerAlpha),
                            startAngle = currentRotation,
                            sweepAngle = arcSweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = strokeWidth,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        )

                        // Arc 2 (opposite side)
                        drawArc(
                            color = borderColor.copy(alpha = borderColor.alpha * spinnerAlpha),
                            startAngle = currentRotation + 180f,
                            sweepAngle = arcSweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = strokeWidth,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        )
                    }

                    // 3. Subtle inner aura when actively disconnecting
                    if (isDisconnecting) {
                        drawCircle(
                            color = borderColor.copy(alpha = 0.08f),
                            radius = radius - strokeWidth,
                            center = center
                        )
                    }
                }

                // Inner State Text with smooth transition
                AnimatedContent(
                    targetState = when {
                        isDisconnecting -> "DISCONNECTING"
                        isConnecting -> "CONNECTING"
                        isConnected -> "CONNECTED"
                        isError -> "RETRY"
                        else -> "CONNECT"
                    },
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(200, delayMillis = 40)) +
                                scaleIn(initialScale = 0.90f, animationSpec = tween(200, delayMillis = 40)))
                            .togetherWith(
                                fadeOut(animationSpec = tween(140)) +
                                        scaleOut(targetScale = 1.05f, animationSpec = tween(140))
                            )
                    },
                    label = "buttonContent"
                ) { stateText ->
                    when (stateText) {
                        "CONNECTING", "DISCONNECTING" -> {
                            Text(
                                text = stateText,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.3.sp,
                                color = borderColor,
                                textAlign = TextAlign.Center
                            )
                        }
                        "CONNECTED" -> {
                            Text(
                                text = "CONNECTED",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.5.sp,
                                color = StatusConnectedGreen,
                                textAlign = TextAlign.Center
                            )
                        }
                        "RETRY" -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = "Error",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "RETRY",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.2.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        else -> {
                            Text(
                                text = "CONNECT",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.5.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // State description badge beneath button
        Text(
            text = when {
                isDisconnecting -> "Disconnecting..."
                isConnected -> if (isProxyMode) "Proxy listening" else "System-Wide VPN"
                isConnecting -> if (tunnelState is TunnelState.Reconnecting) "Reconnecting..." else "Connecting..."
                isError -> "Connection failed"
                !hasProfile -> "Import an .ovpn profile to connect"
                else -> "Ready to connect"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = when {
                isConnected -> StatusConnectedGreen
                isError -> MaterialTheme.colorScheme.error
                isConnecting -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun BottomStatusCard(
    tunnelState: TunnelState,
    trafficStats: TunnelEngine.TrafficStats,
    viewModel: MainViewModel,
    onRetry: () -> Unit
) {
    val socks5Port by viewModel.settings.socks5Port.collectAsState(initial = AppSettings.DEFAULT_SOCKS5_PORT)
    val httpPort by viewModel.settings.httpPort.collectAsState(initial = AppSettings.DEFAULT_HTTP_PORT)
    val tunnelMode by viewModel.settings.tunnelMode.collectAsState(initial = AppSettings.TUNNEL_MODE_TUN)
    val splitEnabled by viewModel.settings.splitTunnelEnabled.collectAsState(initial = false)
    val selectedApps by viewModel.settings.splitTunnelApps.collectAsState(initial = emptySet())

    // Fixed height container permanently locks the vertical layout against any jumps
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(116.dp),
        contentAlignment = Alignment.Center
    ) {
        val targetCardState = when {
            tunnelState is TunnelState.Connected -> 1
            tunnelState is TunnelState.Error -> 2
            else -> 0
        }

        AnimatedContent(
            targetState = targetCardState,
            transitionSpec = {
                (fadeIn(animationSpec = tween(280, delayMillis = 40)) + scaleIn(initialScale = 0.98f))
                    .togetherWith(fadeOut(animationSpec = tween(180)) + scaleOut(targetScale = 0.98f))
            },
            label = "bottomStatusCardAnim"
        ) { cardState ->
            when (cardState) {
                1 -> {
                    var elapsedTime by remember { mutableLongStateOf(0L) }

                    LaunchedEffect(tunnelState) {
                        val startTime = System.currentTimeMillis()
                        while (true) {
                            elapsedTime = System.currentTimeMillis() - startTime
                            delay(1000)
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxSize()
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
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(StatusConnectedGreen)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Live Session",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Text(
                            text = formatDuration(elapsedTime),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = StatusConnectedGreen
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(StatusConnectedGreen.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.ArrowDownward,
                                    contentDescription = "Received",
                                    tint = StatusConnectedGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Downloaded",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = formatBytes(trafficStats.bytesIn),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = formatSpeed(trafficStats.bytesInPerSec),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = StatusConnectedGreen
                                    )
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.ArrowUpward,
                                    contentDescription = "Sent",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Uploaded",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = formatBytes(trafficStats.bytesOut),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = formatSpeed(trafficStats.bytesOutPerSec),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        2 -> {
                    Card(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(16.dp)
                            ),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Error,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Connection Error",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            val errorMsg = (tunnelState as? TunnelState.Error)?.message.orEmpty()
                            Text(
                                text = errorMsg.ifEmpty { "Unable to connect to VPN server." },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(
                                    onClick = onRetry,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("Retry Connection", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                else -> {
                    // Disconnected / Idle informative prompt
                    Card(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(16.dp)
                            ),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            val promptText = if (tunnelMode == AppSettings.TUNNEL_MODE_PROXY) {
                                "Proxy mode: Connects to OpenVPN and exposes 127.0.0.1:$socks5Port (SOCKS5) and :$httpPort (HTTP). Other apps use your normal network."
                            } else {
                                val splitText = if (splitEnabled) "${selectedApps.size} apps configured" else "all apps routed"
                                "TUN mode: Routes Android system traffic through OpenVPN ($splitText). Local proxy ports inactive."
                            }
                            Text(
                                text = promptText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

private fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec < 1024) return "$bytesPerSec B/s"
    val kb = bytesPerSec / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB/s", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB/s", mb)
}

