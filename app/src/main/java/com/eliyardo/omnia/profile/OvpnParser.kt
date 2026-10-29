package com.eliyardo.omnia.profile

class OvpnParser {

    companion object {
        private val INLINE_TAGS = setOf(
            "ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2",
            "dh", "extra-certs", "pkcs12", "secret", "http-proxy-user-pass",
            "auth-user-pass"
        )

        private val SECURITY_CRITICAL_DIRECTIVES = setOf(
            "ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2",
            "verify-x509-name", "remote-cert-tls", "tls-version-min",
            "cipher", "data-ciphers", "data-ciphers-fallback", "auth", "ncp-ciphers"
        )

        val UNSUPPORTED_DIRECTIVES = setOf(
            "up", "down", "ipchange", "route-up", "route-pre-down",
            "client-connect", "client-disconnect", "learn-address",
            "auth-user-pass-verify", "tls-verify", "script-security",
            "plugin", "socks-proxy", "http-proxy",
            "iproute", "ifconfig-pool-persist"
        )

        private val IGNORED_FOR_PROXY_MODE = setOf(
            "redirect-gateway", "route", "route-ipv6",
            "ifconfig", "ifconfig-ipv6", "topology",
            "dhcp-option", "block-outside-dns"
        )
    }

    fun parse(content: String, fileName: String): ProfileValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val unsupported = mutableListOf<String>()

        if (content.isBlank()) {
            return ProfileValidationResult(null, listOf("Profile is empty"), emptyList(), emptyList())
        }

        val lines = content.lines()
        val sanitizedLines = mutableListOf<String>()
        var remoteHost = ""
        var remotePort = 1194
        var protocol = "udp"
        var hasAuth = false
        var inlineUsername: String? = null
        var inlinePassword: String? = null
        val inlineAuthLines = mutableListOf<String>()

        var hasCa = false
        var hasCert = false
        var hasKey = false
        var hasTlsAuth = false
        var hasTlsCrypt = false
        var hasRemoteCertTls = false
        var hasVerifyX509Name = false
        var inInlineBlock = false
        var currentInlineTag = ""
        val caContentBuilder = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()

            // Skip comments and empty lines when not in an inline block
            if (!inInlineBlock && (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";"))) {
                sanitizedLines.add(line)
                continue
            }

            // Handle inline block boundaries
            if (trimmed.startsWith("<") && !trimmed.startsWith("</")) {
                sanitizedLines.add(line)
                val tag = trimmed.removePrefix("<").removeSuffix(">").trim().lowercase()
                if (tag in INLINE_TAGS) {
                    inInlineBlock = true
                    currentInlineTag = tag
                    when (tag) {
                        "ca" -> {
                            hasCa = true
                            caContentBuilder.clear()
                        }
                        "cert" -> hasCert = true
                        "key" -> hasKey = true
                        "tls-auth" -> hasTlsAuth = true
                        "tls-crypt", "tls-crypt-v2" -> hasTlsCrypt = true
                        "auth-user-pass" -> {
                            hasAuth = true
                            inlineAuthLines.clear()
                        }
                    }
                }
                continue
            }

            if (trimmed.startsWith("</")) {
                sanitizedLines.add(line)
                if (currentInlineTag == "auth-user-pass") {
                    if (inlineAuthLines.isNotEmpty()) {
                        inlineUsername = inlineAuthLines.getOrNull(0)
                        inlinePassword = inlineAuthLines.getOrNull(1) ?: ""
                    }
                }
                inInlineBlock = false
                currentInlineTag = ""
                continue
            }

            if (inInlineBlock) {
                sanitizedLines.add(line)
                if (currentInlineTag == "auth-user-pass" && trimmed.isNotEmpty()) {
                    inlineAuthLines.add(trimmed)
                } else if (currentInlineTag == "ca" && trimmed.isNotEmpty()) {
                    caContentBuilder.append(trimmed).append("\n")
                }
                continue
            }

            // Parse directives
            val parts = trimmed.split("\\s+".toRegex())
            val directive = parts[0].lowercase()

            if (directive in UNSUPPORTED_DIRECTIVES) {
                unsupported.add(directive)
                warnings.add("Directive '$directive' is unsupported and will be ignored")
                continue
            }

            sanitizedLines.add(line)

            when (directive) {
                "remote" -> {
                    // Retain primary (first) remote; preserve subsequent remotes in rawContent for failover
                    if (remoteHost.isEmpty()) {
                        if (parts.size >= 2) remoteHost = parts[1]
                        if (parts.size >= 3) remotePort = parts[2].toIntOrNull() ?: 1194
                        if (parts.size >= 4) protocol = parts[3].lowercase()
                    }
                }
                "proto" -> {
                    if (parts.size >= 2) protocol = parts[1].lowercase().removeSuffix("-client")
                }
                "port" -> {
                    if (parts.size >= 2) remotePort = parts[1].toIntOrNull() ?: 1194
                }
                "auth-user-pass" -> {
                    hasAuth = true
                    if (parts.size >= 2) {
                        warnings.add("Referenced auth file '${parts[1]}' - credentials will be prompted if not inline")
                    }
                }
                "ca" -> {
                    if (parts.size >= 2) {
                        if (!hasCa) {
                            errors.add("Referenced external CA file '${parts[1]}' cannot be read on Android; CA certificate must be embedded inline using <ca>...</ca>")
                        }
                    }
                }
                "cert" -> {
                    if (parts.size >= 2) {
                        hasCert = true
                        warnings.add("Referenced cert file '${parts[1]}' - must be included inline")
                    }
                }
                "key" -> {
                    if (parts.size >= 2) {
                        hasKey = true
                        warnings.add("Referenced key file '${parts[1]}' - must be included inline")
                    }
                }
                "tls-auth" -> {
                    if (parts.size >= 2) {
                        hasTlsAuth = true
                    }
                }
                "tls-crypt", "tls-crypt-v2" -> {
                    hasTlsCrypt = true
                }
                "client" -> { /* expected */ }
                "dev" -> {
                    if (parts.size >= 2 && parts[1] != "tun") {
                        warnings.add("Device type '${parts[1]}' specified; only 'tun' (Layer 3) is supported")
                    }
                }
                "tls-client" -> { /* expected */ }
                "resolv-retry" -> { /* handled by engine */ }
                "nobind" -> { /* expected */ }
                "persist-key", "persist-tun" -> { /* noted */ }
                "remote-cert-tls" -> { hasRemoteCertTls = true }
                "verify-x509-name" -> { hasVerifyX509Name = true }
                "cipher", "data-ciphers", "data-ciphers-fallback", "ncp-ciphers", "auth" -> { /* passed through */ }
                "compress", "comp-lzo" -> {
                    warnings.add("Compression ($directive) is supported but may have security implications (VORACLE)")
                }
                "tls-version-min" -> { /* passed through */ }
                "reneg-sec" -> { /* passed through */ }
                "keepalive", "ping", "ping-restart" -> { /* passed through */ }
                "connect-retry", "connect-retry-max" -> { /* passed through */ }
                "mssfix", "tun-mtu", "fragment" -> { /* passed through */ }
                "key-direction" -> { /* passed through */ }
                "remote-random" -> { /* passed through */ }
                "pull" -> { /* expected for client */ }
                "verb" -> { /* passed through */ }
                "mute" -> { /* passed through */ }
                "sndbuf", "rcvbuf" -> { /* passed through */ }
                else -> {
                    if (directive in UNSUPPORTED_DIRECTIVES) {
                        unsupported.add(directive)
                        warnings.add("Directive '$directive' is unsupported and will be ignored")
                    } else if (directive in IGNORED_FOR_PROXY_MODE) {
                        // Not an error - silently ignored in proxy-only mode
                    }
                }
            }
        }

        // Validation
        if (remoteHost.isEmpty()) {
            errors.add("No 'remote' directive found - cannot determine VPN server")
        }

        if (!hasCa) {
            errors.add("No CA certificate found - server certificate cannot be verified")
        }

        if (!hasRemoteCertTls && !hasVerifyX509Name) {
            warnings.add("Profile does not specify 'remote-cert-tls server' or 'verify-x509-name'; MITM verification is weakened")
        }

        if (!protocol.startsWith("udp") && !protocol.startsWith("tcp")) {
            errors.add("Unsupported protocol: $protocol")
        }

        val caFingerprint = if (hasCa && caContentBuilder.isNotEmpty()) {
            computeCaFingerprint(caContentBuilder.toString())
        } else null

        val profileName = fileName
            .removeSuffix(".ovpn")
            .removeSuffix(".conf")
            .replace(Regex("[^a-zA-Z0-9._\\-\\s]"), "_")
            .ifEmpty { "Imported Profile" }

        val profile = if (errors.isEmpty()) {
            OvpnProfile(
                name = profileName,
                rawContent = sanitizedLines.joinToString("\n"),
                remoteHost = remoteHost,
                remotePort = remotePort,
                protocol = protocol,
                hasAuth = hasAuth,
                inlineUsername = inlineUsername,
                inlinePassword = inlinePassword,
                hasCa = hasCa,
                hasCert = hasCert,
                hasKey = hasKey,
                hasTlsAuth = hasTlsAuth,
                hasTlsCrypt = hasTlsCrypt,
                caFingerprint = caFingerprint,
                warnings = warnings,
                errors = errors
            )
        } else null

        return ProfileValidationResult(profile, errors, warnings, unsupported)
    }

    private fun computeCaFingerprint(caPem: String): String? {
        return try {
            // If certificate bundle contains multiple certs, isolate the first certificate block
            val beginTag = "-----BEGIN CERTIFICATE-----"
            val endTag = "-----END CERTIFICATE-----"
            val firstCert = if (caPem.contains(beginTag) && caPem.contains(endTag)) {
                val start = caPem.indexOf(beginTag) + beginTag.length
                val end = caPem.indexOf(endTag, start)
                if (end > start) caPem.substring(start, end) else caPem
            } else {
                caPem
            }
            val clean = firstCert
                .replace(beginTag, "")
                .replace(endTag, "")
                .replace("\\s+".toRegex(), "")
            if (clean.isEmpty()) return null
            val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(bytes)
            digest.joinToString(":") { "%02X".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}
