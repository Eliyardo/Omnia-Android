package com.eliyardo.omnia.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.eliyardo.omnia.R
import com.eliyardo.omnia.profile.ProfileManager
import com.eliyardo.omnia.settings.AppSettings
import com.eliyardo.omnia.tunnel.TunnelState
import com.eliyardo.omnia.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@RequiresApi(Build.VERSION_CODES.N)
class OmniaTileService : TileService() {

    companion object {
        private const val TAG = "OmniaTileService"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var proxyService: ProxyVpnService? = null
    private var isBound = false
    private var stateCollectJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? ProxyVpnService.LocalBinder ?: return
            proxyService = localBinder.getService()
            isBound = true

            stateCollectJob?.cancel()
            stateCollectJob = serviceScope.launch {
                proxyService?.tunnelEngine?.state?.collect { state ->
                    updateTileState(state)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            proxyService = null
            isBound = false
            updateTileState(TunnelState.Disconnected)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        try {
            val intent = Intent(this, ProxyVpnService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.w(TAG, "Could not bind to service in onStartListening", e)
        }
    }

    override fun onStopListening() {
        stateCollectJob?.cancel()
        stateCollectJob = null
        if (isBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {}
            isBound = false
            proxyService = null
        }
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val currentService = proxyService

        if (currentService != null && currentService.tunnelEngine.state.value !is TunnelState.Disconnected) {
            // Currently connected or connecting -> Disconnect
            val disconnectIntent = ProxyVpnService.createDisconnectIntent(this)
            try {
                startService(disconnectIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting disconnect intent", e)
            }
            updateTileState(TunnelState.Disconnected)
            return
        }

        // Currently disconnected -> Connect with active profile
        serviceScope.launch {
            val (active, isTun, content) = withContext(Dispatchers.IO) {
                val profileManager = ProfileManager(applicationContext)
                val settings = AppSettings(applicationContext)
                profileManager.awaitLoaded()
                val active = profileManager.activeProfile.value
                val isTun = settings.tunnelMode.first() == AppSettings.TUNNEL_MODE_TUN
                val content = active?.let { profileManager.getProfileContent(it.id) }
                Triple(active, isTun, content)
            }

            if (active == null) {
                // Open app so user can import/select a profile
                val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pending = PendingIntent.getActivity(
                        applicationContext, 0, launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pending)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(launchIntent)
                }
                Toast.makeText(applicationContext, "Please select an Omnia profile first", Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (active.hasAuth && !active.hasInlineCredentials) {
                val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pending = PendingIntent.getActivity(
                        applicationContext, 0, launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pending)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(launchIntent)
                }
                Toast.makeText(applicationContext, "Credentials required. Please connect via app.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            if (isTun) {
                val prepareIntent = VpnService.prepare(applicationContext)
                if (prepareIntent != null) {
                    // Need user consent for VPN
                    val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val pending = PendingIntent.getActivity(
                            applicationContext, 0, launchIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        startActivityAndCollapse(pending)
                    } else {
                        @Suppress("DEPRECATION")
                        startActivityAndCollapse(launchIntent)
                    }
                    return@launch
                }
            }

            if (content.isNullOrBlank()) {
                Toast.makeText(applicationContext, "Profile content unavailable", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val connectIntent = ProxyVpnService.createConnectIntent(
                context = applicationContext,
                profileId = active.id,
                profileContent = content,
                username = active.inlineUsername,
                password = active.inlinePassword
            )

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(connectIntent)
                } else {
                    startService(connectIntent)
                }
                updateTileState(TunnelState.Connecting())
            } catch (e: Exception) {
                Log.e(TAG, "Error launching VPN from Quick Settings tile", e)
                Toast.makeText(applicationContext, "Failed to start tunnel: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateTileState(state: TunnelState) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        tile.label = "Omnia"

        when (state) {
            is TunnelState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Connected"
                }
            }
            is TunnelState.Connecting, is TunnelState.Reconnecting -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Connecting..."
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Disconnected"
                }
            }
        }

        tile.updateTile()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
