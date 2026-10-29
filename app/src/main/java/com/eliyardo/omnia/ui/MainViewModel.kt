package com.eliyardo.omnia.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eliyardo.omnia.profile.OvpnProfile
import com.eliyardo.omnia.profile.ProfileManager
import com.eliyardo.omnia.profile.ProfileValidationResult
import com.eliyardo.omnia.service.ProxyVpnService
import com.eliyardo.omnia.settings.AppSettings
import com.eliyardo.omnia.tunnel.ProxyState
import com.eliyardo.omnia.tunnel.TunnelEngine
import com.eliyardo.omnia.OmniaApp
import com.eliyardo.omnia.tunnel.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    val profileManager = ProfileManager(application)
    val settings = AppSettings(application)

    val tunnelMode: StateFlow<String> = settings.tunnelMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings.TUNNEL_MODE_TUN
    )

    val themeMode: StateFlow<String> = settings.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings.THEME_SYSTEM
    )

    private var service: ProxyVpnService? = null
    private var bound = false

    private val _crashLog = MutableStateFlow<String?>(null)
    val crashLog: StateFlow<String?> = _crashLog.asStateFlow()

    private val _tunnelState = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    val tunnelState: StateFlow<TunnelState> = _tunnelState.asStateFlow()

    private val _proxyState = MutableStateFlow(ProxyState())
    val proxyState: StateFlow<ProxyState> = _proxyState.asStateFlow()

    private val _importResult = MutableStateFlow<ProfileValidationResult?>(null)
    val importResult: StateFlow<ProfileValidationResult?> = _importResult.asStateFlow()

    data class StagedProfileImport(
        val uri: Uri,
        val fileName: String,
        val rawContent: String,
        val remoteHost: String,
        val remotePort: Int,
        val protocol: String,
        val hasAuth: Boolean,
        val hasCa: Boolean,
        val caFingerprint: String? = null,
        val warnings: List<String>
    )

    private val _stagedImport = MutableStateFlow<StagedProfileImport?>(null)
    val stagedImport: StateFlow<StagedProfileImport?> = _stagedImport.asStateFlow()

    private val _trafficStats = MutableStateFlow(TunnelEngine.TrafficStats())
    val trafficStats: StateFlow<TunnelEngine.TrafficStats> = _trafficStats.asStateFlow()

    private val _showCredentialsDialog = MutableStateFlow(false)
    val showCredentialsDialog: StateFlow<Boolean> = _showCredentialsDialog.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _vpnConsentIntent = MutableStateFlow<Intent?>(null)
    val vpnConsentIntent: StateFlow<Intent?> = _vpnConsentIntent.asStateFlow()

    private val serviceCollectJobs = mutableListOf<Job>()

    fun clearVpnConsent() {
        _vpnConsentIntent.value = null
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? ProxyVpnService.LocalBinder ?: return
            service = localBinder.getService()
            bound = true

            // Cancel any previous collection jobs before launching new ones
            serviceCollectJobs.forEach { it.cancel() }
            serviceCollectJobs.clear()

            serviceCollectJobs.add(viewModelScope.launch {
                service?.tunnelEngine?.state?.collect { state ->
                    _tunnelState.value = state
                }
            })
            serviceCollectJobs.add(viewModelScope.launch {
                service?.proxyState?.collect { state ->
                    _proxyState.value = state
                }
            })
            serviceCollectJobs.add(viewModelScope.launch {
                service?.tunnelEngine?.stats?.collect { stats ->
                    _trafficStats.value = stats
                }
            })
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceCollectJobs.forEach { it.cancel() }
            serviceCollectJobs.clear()
            service = null
            bound = false
            _tunnelState.value = TunnelState.Disconnected
            _proxyState.value = ProxyState()
        }
    }

    init {
        checkPreviousCrash()
        bindToService()
    }

    private fun checkPreviousCrash() {
        try {
            val file = File(getApplication<Application>().filesDir, OmniaApp.CRASH_LOG_FILE)
            if (file.exists()) {
                val content = file.readText()
                if (content.isNotBlank()) {
                    _crashLog.value = content
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading crash log", e)
        }
    }

    fun clearCrashLog() {
        try {
            val file = File(getApplication<Application>().filesDir, OmniaApp.CRASH_LOG_FILE)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing crash log", e)
        }
        _crashLog.value = null
    }

    private fun bindToService() {
        val context = getApplication<Application>()
        val intent = Intent(context, ProxyVpnService::class.java)
        try {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.d(TAG, "Service not available yet", e)
        }
    }

    fun stageExternalImport(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cr = getApplication<Application>().contentResolver
                var name = "imported.ovpn"
                cr.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        val n = cursor.getString(idx)
                        if (!n.isNullOrBlank()) name = n
                    }
                }

                val buffer = ByteArray(1024 * 1024 + 1)
                var total = 0
                cr.openInputStream(uri)?.use { stream ->
                    while (total < buffer.size) {
                        val read = stream.read(buffer, total, buffer.size - total)
                        if (read < 0) break
                        total += read
                    }
                } ?: run {
                    _errorMessage.value = "Cannot open external profile URI"
                    return@launch
                }

                if (total > 1024 * 1024) {
                    _errorMessage.value = "External profile exceeds 1 MB safety limit"
                    return@launch
                }

                val content = String(buffer, 0, total, Charsets.UTF_8)
                val parser = com.eliyardo.omnia.profile.OvpnParser()
                val parsed = parser.parse(content, name)

                if (parsed.errors.isNotEmpty()) {
                    _errorMessage.value = "Invalid profile: ${parsed.errors.joinToString(", ")}"
                    return@launch
                }

                val p = parsed.profile ?: run {
                    _errorMessage.value = "Could not parse profile"
                    return@launch
                }

                val sanitizedName = name
                    .replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1F]"), "_")
                    .take(64)
                    .ifEmpty { "imported.ovpn" }

                _stagedImport.value = StagedProfileImport(
                    uri = uri,
                    fileName = sanitizedName,
                    rawContent = content,
                    remoteHost = p.remoteHost,
                    remotePort = p.remotePort,
                    protocol = p.protocol,
                    hasAuth = p.hasAuth,
                    hasCa = p.hasCa,
                    caFingerprint = p.caFingerprint,
                    warnings = parsed.warnings
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed staging external import", e)
                _errorMessage.value = "Import preview failed: ${e.message}"
            }
        }
    }

    fun confirmStagedImport() {
        val staged = _stagedImport.value ?: return
        _stagedImport.value = null
        viewModelScope.launch {
            // Import directly from the in-memory parsed & verified content (eliminates TOCTOU - M1)
            val result = profileManager.importProfileContent(staged.rawContent, staged.fileName)
            _importResult.value = result
            if (result.errors.isNotEmpty()) {
                _errorMessage.value = result.errors.joinToString("\n")
            }
        }
    }

    fun cancelStagedImport() {
        _stagedImport.value = null
    }

    fun importProfile(uri: Uri) {
        viewModelScope.launch {
            val result = profileManager.importProfile(uri)
            _importResult.value = result
            if (result.errors.isNotEmpty()) {
                _errorMessage.value = result.errors.joinToString("\n")
            }
        }
    }

    fun clearImportResult() {
        _importResult.value = null
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun connect(username: String? = null, password: String? = null) {
        try {
            val profile = profileManager.activeProfile.value ?: run {
                _errorMessage.value = "No profile selected"
                return
            }

            if (profile.isCorrupted) {
                _errorMessage.value = "Selected profile is corrupted (${profile.decryptionError ?: "decryption failed"}). Please re-import or edit this profile."
                return
            }

            if (profile.hasAuth && username == null) {
                if (profile.hasInlineCredentials) {
                    connect(profile.inlineUsername, profile.inlinePassword)
                    return
                }
                _showCredentialsDialog.value = true
                return
            }

            viewModelScope.launch {
                // Perform profile decryption on IO dispatcher instead of blocking main UI thread
                val content = withContext(Dispatchers.IO) {
                    profileManager.getProfileContent(profile.id)
                } ?: run {
                    _errorMessage.value = "Cannot read profile"
                    return@launch
                }

                val isTun = settings.tunnelMode.first() == AppSettings.TUNNEL_MODE_TUN
                if (isTun) {
                    val prepareIntent = android.net.VpnService.prepare(getApplication())
                    if (prepareIntent != null) {
                        _vpnConsentIntent.value = prepareIntent
                        return@launch
                    }
                }

                val context = getApplication<Application>()
                val intent = ProxyVpnService.createConnectIntent(
                    context = context,
                    profileId = profile.id,
                    profileContent = content,
                    username = username,
                    password = password
                )
                try {
                    context.startForegroundService(intent)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to start foreground service", e)
                    _errorMessage.value = "Failed to start service: ${e.message}"
                }

                if (!bound) bindToService()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal error in connect()", t)
            saveCrashLog(t)
            _errorMessage.value = "Connect error: ${t.message}"
        }
    }

    private fun saveCrashLog(t: Throwable) {
        try {
            val sw = java.io.StringWriter()
            val pw = java.io.PrintWriter(sw)
            t.printStackTrace(pw)
            val logContent = "ViewModel Crash: ${t.message}\n$sw"
            OmniaApp.writeCrashLog(getApplication(), logContent)
            _crashLog.value = logContent.take(OmniaApp.MAX_CRASH_LOG_BYTES)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash log", e)
        }
    }

    fun connectWithCredentials(username: String, password: String) {
        _showCredentialsDialog.value = false
        connect(username, password)
    }

    fun dismissCredentialsDialog() {
        _showCredentialsDialog.value = false
    }

    fun disconnect() {
        val context = getApplication<Application>()
        try {
            val intent = ProxyVpnService.createDisconnectIntent(context)
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed sending disconnect intent", e)
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                service?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting service disconnect", e)
            }
        }
    }

    fun selectProfile(profileId: String) {
        profileManager.setActiveProfile(profileId)
    }

    fun updateProfile(
        id: String,
        name: String,
        host: String,
        port: Int,
        proto: String,
        username: String?,
        password: String?
    ) {
        viewModelScope.launch {
            val success = profileManager.updateProfile(id, name, host, port, proto, username, password)
            if (!success) {
                _errorMessage.value = "Failed to update profile"
            }
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            profileManager.deleteProfile(profileId)
        }
    }

    override fun onCleared() {
        serviceCollectJobs.forEach { it.cancel() }
        serviceCollectJobs.clear()
        if (bound) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.d(TAG, "Error unbinding service", e)
            }
        }
        super.onCleared()
    }
}
