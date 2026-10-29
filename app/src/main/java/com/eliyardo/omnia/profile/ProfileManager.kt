package com.eliyardo.omnia.profile

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ProfileManager(private val context: Context) {

    companion object {
        private const val TAG = "ProfileManager"
        private const val PROFILES_DIR = "profiles"
        private const val ACTIVE_PROFILE_FILE = "active_profile_id"
        private const val MAX_PROFILE_BYTES = 1024 * 1024 // 1 MB cap to prevent OOM
        private val AUTH_USER_PASS_TAG_REGEX = "(?i)</?auth-user-pass>".toRegex()

        @Volatile
        private var instance: ProfileManager? = null

        fun getInstance(context: Context): ProfileManager {
            return instance ?: synchronized(this) {
                instance ?: ProfileManager(context.applicationContext).also { instance = it }
            }
        }

        fun updateRawContent(
            original: String,
            host: String,
            port: Int,
            proto: String,
            username: String?,
            password: String?
        ): String {
            // Sanitize all inputs against CRLF, spaces/arguments, and XML-style block directive injection
            val safeHost = host.trim().replace("\r", "").replace("\n", "").split(Regex("\\s+")).firstOrNull() ?: ""
            val safeProto = proto.trim().replace("\r", "").replace("\n", "").lowercase()
            val safeUsername = username?.trim()?.replace("\r", "")?.replace("\n", "")
                ?.replace(AUTH_USER_PASS_TAG_REGEX, "")
            val safePassword = password?.replace("\r", "")?.replace("\n", "")
                ?.replace(AUTH_USER_PASS_TAG_REGEX, "")

            val lines = original.lines()
            var remoteUpdated = false
            var protoUpdated = false
            val cleanedLines = mutableListOf<String>()

            var inAuthBlock = false

            for (line in lines) {
                val trimmed = line.trim()
                val lower = trimmed.lowercase()

                // Handle inline auth block
                if (lower.startsWith("<auth-user-pass>")) {
                    inAuthBlock = true
                    continue
                }
                if (lower.startsWith("</auth-user-pass>")) {
                    inAuthBlock = false
                    continue
                }
                if (inAuthBlock) {
                    // Prevent unclosed <auth-user-pass> tags from swallowing the entire rest of the file
                    if (trimmed.startsWith("<") || trimmed.startsWith("remote ", ignoreCase = true) || trimmed.startsWith("proto ", ignoreCase = true)) {
                        inAuthBlock = false
                    } else {
                        continue // Skip old username/password lines
                    }
                }

                // Update only the primary remote directive; preserve subsequent remotes for failover
                if (lower.startsWith("remote ") || lower == "remote") {
                    if (!remoteUpdated && safeHost.isNotBlank()) {
                        cleanedLines.add("remote $safeHost $port")
                        remoteUpdated = true
                    } else {
                        cleanedLines.add(line)
                    }
                    continue
                }

                // Update primary proto directive; preserve others
                if (lower.startsWith("proto ") || lower == "proto") {
                    if (!protoUpdated && safeProto.isNotBlank()) {
                        cleanedLines.add("proto $safeProto")
                        protoUpdated = true
                    } else {
                        cleanedLines.add(line)
                    }
                    continue
                }

                val directive = lower.split("\\s+".toRegex())[0]

                // Strip unsupported directives
                if (directive in OvpnParser.UNSUPPORTED_DIRECTIVES) {
                    continue
                }

                // Handle standalone auth-user-pass (exact token match so other directives prefixed with auth-user-pass are preserved)
                if (directive == "auth-user-pass") {
                    continue
                }

                cleanedLines.add(line)
            }

            // If remote was not in the file, prepend it
            if (!remoteUpdated && safeHost.isNotBlank()) {
                cleanedLines.add(0, "remote $safeHost $port")
            }

            // If proto was not in the file, add it
            if (!protoUpdated && safeProto.isNotBlank()) {
                cleanedLines.add(1, "proto $safeProto")
            }

            // Add updated credentials if username provided
            if (!safeUsername.isNullOrBlank()) {
                cleanedLines.add("<auth-user-pass>")
                cleanedLines.add(safeUsername)
                cleanedLines.add(safePassword ?: "")
                cleanedLines.add("</auth-user-pass>")
            }

            return cleanedLines.joinToString("\n")
        }
    }

    private val parser = OvpnParser()
    private val profilesDir = File(context.filesDir, PROFILES_DIR)

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val _profiles = MutableStateFlow<List<OvpnProfile>>(emptyList())
    val profiles: StateFlow<List<OvpnProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow<OvpnProfile?>(null)
    val activeProfile: StateFlow<OvpnProfile?> = _activeProfile.asStateFlow()

    private val mutex = Mutex()
    private val loadedDeferred = CompletableDeferred<Unit>()

    init {
        profilesDir.mkdirs()
        CoroutineScope(Dispatchers.IO).launch {
            loadProfiles()
        }
    }

    suspend fun awaitLoaded() {
        loadedDeferred.await()
    }

    @Synchronized
    fun loadProfiles() {
        try {
            val loaded = mutableListOf<OvpnProfile>()
            val metaFiles = profilesDir.listFiles { _, name -> name.endsWith(".meta") } ?: emptyArray()

            for (metaFile in metaFiles) {
                try {
                    val id = metaFile.nameWithoutExtension
                    val contentFile = File(profilesDir, "$id.ovpn")
                    if (!contentFile.exists()) {
                        metaFile.delete()
                        continue
                    }

                    val metaLines = metaFile.readLines()
                    val name = metaLines.getOrNull(0) ?: "Unknown"
                    val importedAt = metaLines.getOrNull(1)?.toLongOrNull() ?: 0L

                    var content = readEncryptedProfile(id)
                    if (content == null) {
                        // Check if plaintext migration is possible (e.g. from v1.0.0 unencrypted file)
                        val rawText = try { contentFile.readText() } catch (_: Exception) { "" }
                        if (rawText.contains("client") || rawText.contains("remote ") || rawText.contains("dev tun")) {
                            try {
                                writeEncryptedProfile(id, rawText)
                                content = rawText
                                Log.i(TAG, "Successfully migrated plaintext profile $id to encrypted storage")
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed migrating plaintext profile $id", e)
                            }
                        }
                    }

                    if (content != null) {
                        val result = parser.parse(content, name)
                        result.profile?.let { profile ->
                            loaded.add(profile.copy(id = id, name = name, importedAt = importedAt))
                        }
                    } else {
                        // Keystore key invalid or ciphertext corrupted: surface clearly in UI rather than dropping silently (M8)
                        Log.w(TAG, "Profile $id could not be decrypted with current Keystore key; surfacing as corrupted")
                        loaded.add(
                            OvpnProfile(
                                id = id,
                                name = name,
                                rawContent = "",
                                importedAt = importedAt,
                                isCorrupted = true,
                                decryptionError = "Profile cannot be decrypted with current Android Keystore key",
                                errors = listOf("Decryption failed")
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load profile: ${metaFile.name}", e)
                }
            }

            _profiles.value = loaded

            // Load active profile ID
            val activeIdFile = File(profilesDir, ACTIVE_PROFILE_FILE)
            if (activeIdFile.exists()) {
                val activeId = activeIdFile.readText().trim()
                _activeProfile.value = loaded.find { it.id == activeId } ?: loaded.firstOrNull { !it.isCorrupted }
            } else if (loaded.isNotEmpty()) {
                _activeProfile.value = loaded.firstOrNull { !it.isCorrupted }
            }
        } finally {
            if (!loadedDeferred.isCompleted) {
                loadedDeferred.complete(Unit)
            }
        }
    }

    /**
     * Import directly from in-memory content (prevents TOCTOU URI re-read gaps - M1)
     */
    suspend fun importProfileContent(
        content: String,
        fileName: String,
        forcedName: String? = null
    ): ProfileValidationResult = mutex.withLock {
        awaitLoaded()
        withContext(Dispatchers.IO) {
            val contentBytes = content.toByteArray(Charsets.UTF_8)
            if (contentBytes.size > MAX_PROFILE_BYTES) {
                return@withContext ProfileValidationResult(
                    null,
                    listOf("Profile exceeds the 1 MB safety limit. Import rejected."),
                    emptyList(),
                    emptyList()
                )
            }

            val targetName = forcedName ?: fileName
            val result = parser.parse(content, targetName)
            if (result.profile != null) {
                // Ensure unique name so we never silently overwrite an existing profile
                var uniqueName = result.profile.name
                val existingNames = _profiles.value.map { it.name }.toSet()
                var counter = 1
                while (uniqueName in existingNames) {
                    uniqueName = "${result.profile.name} ($counter)"
                    counter++
                }

                val savedProfile = result.profile.copy(name = uniqueName)
                saveProfile(savedProfile)

                val updatedProfiles = _profiles.value.toMutableList()
                updatedProfiles.add(savedProfile)
                _profiles.value = updatedProfiles

                // Only auto-activate if there is no active profile currently set
                if (_activeProfile.value == null) {
                    setActiveProfile(savedProfile.id)
                }

                return@withContext result.copy(profile = savedProfile)
            }

            result
        }
    }

    suspend fun importProfile(uri: Uri, forcedName: String? = null): ProfileValidationResult = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri)
                ?: return@withContext ProfileValidationResult(
                    null, listOf("Cannot open file from URI: $uri"), emptyList(), emptyList()
                )

            // Read with strict 1 MB bound to prevent memory exhaustion
            val buffer = ByteArray(MAX_PROFILE_BYTES + 1)
            var totalRead = 0
            inputStream.use { stream ->
                while (totalRead < buffer.size) {
                    val bytesRead = stream.read(buffer, totalRead, buffer.size - totalRead)
                    if (bytesRead < 0) break
                    totalRead += bytesRead
                }
            }

            if (totalRead > MAX_PROFILE_BYTES) {
                return@withContext ProfileValidationResult(
                    null,
                    listOf("Profile exceeds the 1 MB safety limit. Import rejected."),
                    emptyList(),
                    emptyList()
                )
            }

            val content = String(buffer, 0, totalRead, Charsets.UTF_8)
            val fileName = forcedName ?: getFileName(uri)

            importProfileContent(content, fileName, forcedName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import profile", e)
            ProfileValidationResult(
                null, listOf("Import failed: ${e.message}"), emptyList(), emptyList()
            )
        }
    }

    private fun saveProfile(profile: OvpnProfile) {
        // Save encrypted profile content at rest (fails closed)
        writeEncryptedProfile(profile.id, profile.rawContent)

        // Save metadata
        val metaFile = File(profilesDir, "${profile.id}.meta")
        metaFile.writeText("${profile.name}\n${profile.importedAt}")
    }

    private fun writeEncryptedProfile(id: String, content: String) {
        val targetFile = File(profilesDir, "$id.ovpn")
        val stagingDir = File(profilesDir, ".staging")
        if (!stagingDir.exists()) {
            stagingDir.mkdirs()
        }
        val stagingFile = File(stagingDir, "$id.ovpn")
        if (stagingFile.exists()) {
            stagingFile.delete()
        }

        try {
            // AndroidX EncryptedFile binds mFile.getName() as AAD into the streaming AEAD ciphertext.
            // By staging the file in .staging/$id.ovpn, mFile.getName() is identical to the target ($id.ovpn),
            // preserving AES-GCM authentication when atomically moved into profilesDir/$id.ovpn (Claude N1).
            val encryptedFile = EncryptedFile.Builder(
                context,
                stagingFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()

            encryptedFile.openFileOutput().use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                try {
                    (output as? FileOutputStream)?.fd?.sync()
                } catch (_: Exception) {}
            }

            // Atomic replace: if move succeeds, targetFile is updated atomically.
            // If encryption fails before this point, targetFile is NEVER touched (H8).
            Files.move(
                stagingFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed for profile $id. Preserving existing profile if present.", e)
            if (stagingFile.exists()) {
                try { stagingFile.delete() } catch (_: Exception) {}
            }
            throw IOException("Secure profile encryption failed; profile storage aborted", e)
        }
    }

    private fun readEncryptedProfile(id: String): String? {
        val file = File(profilesDir, "$id.ovpn")
        if (!file.exists()) return null

        return try {
            val encryptedFile = EncryptedFile.Builder(
                context,
                file,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()

            encryptedFile.openFileInput().use { input ->
                input.bufferedReader().readText()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed for profile $id", e)
            null
        }
    }

    fun getProfileContent(profileId: String): String? {
        return readEncryptedProfile(profileId)
    }

    fun setActiveProfile(profileId: String) {
        val found = _profiles.value.find { it.id == profileId }
        _activeProfile.value = found
        val activeIdFile = File(profilesDir, ACTIVE_PROFILE_FILE)
        if (found != null) {
            activeIdFile.writeText(profileId)
        } else {
            if (activeIdFile.exists()) activeIdFile.delete()
        }
    }

    suspend fun deleteProfile(profileId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val ovpnFile = File(profilesDir, "$profileId.ovpn")
                val tmpFile = File(profilesDir, "$profileId.tmp")
                val stagingFile = File(File(profilesDir, ".staging"), "$profileId.ovpn")
                val metaFile = File(profilesDir, "$profileId.meta")
                ovpnFile.delete()
                tmpFile.delete()
                stagingFile.delete()
                metaFile.delete()
                Log.d(TAG, "Deleted profile $profileId files")
            } catch (e: Exception) {
                Log.e(TAG, "Failed deleting profile files from disk", e)
            }

            val updatedProfiles = _profiles.value.filter { it.id != profileId }
            _profiles.value = updatedProfiles

            if (_activeProfile.value?.id == profileId) {
                val nextActive = updatedProfiles.firstOrNull { !it.isCorrupted }
                _activeProfile.value = nextActive
                val activeIdFile = File(profilesDir, ACTIVE_PROFILE_FILE)
                if (nextActive != null) {
                    activeIdFile.writeText(nextActive.id)
                } else {
                    if (activeIdFile.exists()) activeIdFile.delete()
                }
            }
        }
    }

    suspend fun updateProfile(
        id: String,
        newName: String,
        host: String,
        port: Int,
        proto: String,
        username: String?,
        password: String?
    ): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val oldContent = readEncryptedProfile(id) ?: return@withContext false
            val newContent = updateRawContent(oldContent, host, port, proto, username, password)

            val parseResult = parser.parse(newContent, newName)
            val profile = parseResult.profile ?: run {
                Log.e(TAG, "Failed parsing updated profile: ${parseResult.errors}")
                return@withContext false
            }

            val existing = _profiles.value.find { it.id == id }
            val updatedProfile = profile.copy(
                id = id,
                name = newName,
                importedAt = existing?.importedAt ?: System.currentTimeMillis()
            )
            try {
                saveProfile(updatedProfile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save updated profile", e)
                return@withContext false
            }

            val updatedList = _profiles.value.map {
                if (it.id == id) updatedProfile else it
            }
            _profiles.value = updatedList

            if (_activeProfile.value?.id == id) {
                _activeProfile.value = updatedProfile
            }
            true
        }
    }

    fun updateRawContent(
        original: String,
        host: String,
        port: Int,
        proto: String,
        username: String?,
        password: String?
    ): String = Companion.updateRawContent(original, host, port, proto, username, password)

    private fun getFileName(uri: Uri): String {
        var name = "imported.ovpn"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    val displayName = cursor.getString(nameIndex)
                    if (!displayName.isNullOrBlank()) {
                        name = displayName
                    }
                }
            }
        } catch (_: Exception) {}
        return name
    }
}
