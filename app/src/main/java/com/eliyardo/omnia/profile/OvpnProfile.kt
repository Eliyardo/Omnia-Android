package com.eliyardo.omnia.profile

import java.util.UUID

data class OvpnProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val rawContent: String,
    val importedAt: Long = System.currentTimeMillis(),
    val remoteHost: String = "",
    val remotePort: Int = 1194,
    val protocol: String = "udp", // "udp" or "tcp"
    val hasAuth: Boolean = false,
    val inlineUsername: String? = null,
    val inlinePassword: String? = null,
    val hasCa: Boolean = false,
    val hasCert: Boolean = false,
    val hasKey: Boolean = false,
    val hasTlsAuth: Boolean = false,
    val hasTlsCrypt: Boolean = false,
    val caFingerprint: String? = null,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val isCorrupted: Boolean = false,
    val decryptionError: String? = null
) {
    val isValid: Boolean get() = errors.isEmpty() && hasCa && !isCorrupted
    val hasInlineCredentials: Boolean get() = !inlineUsername.isNullOrBlank() && !inlinePassword.isNullOrBlank()
}

data class ProfileValidationResult(
    val profile: OvpnProfile?,
    val errors: List<String>,
    val warnings: List<String>,
    val unsupportedDirectives: List<String>
)
