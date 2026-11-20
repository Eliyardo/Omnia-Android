# Omnia-Android Threat Model

This document outlines the security architecture, threat model, trust boundaries, and mitigations implemented in **Omnia-Android**.

---

## 1. System Overview & Dual-Mode Architecture

Omnia-Android provides two independent networking modes:

```
┌────────────────────────────────────────────────────────┐
│                      Omnia App                         │
├───────────────────────────┬────────────────────────────┤
│   Mode 1: Userspace Proxy │   Mode 2: System TUN VPN   │
├───────────────────────────┼────────────────────────────┤
│ • Local SOCKS5 (10880)    │ • Android VpnService       │
│ • Local HTTP (10890)      │ • System TUN interface     │
│ • Userspace lwIP stack    │ • Per-app split tunneling  │
│ • OpenVPN 3 C++ Core      │ • Kill switch & DNS leak   │
│ • No VpnService permission│ • IPv6 blackholing         │
└───────────────────────────┴────────────────────────────┘
```

1. **Userspace Proxy Mode**:
   - Operates entirely within the application sandbox without requiring system `VpnService` permission.
   - SOCKS5 and HTTP CONNECT servers bind to loopback (`127.0.0.1`, or `0.0.0.0` when LAN sharing is explicitly enabled).
   - Traffic is fed into an embedded lwIP userspace network stack and forwarded through OpenVPN 3 Core via a UNIX socketpair.
   - Allows running alongside other system VPN applications.

2. **System TUN Mode**:
   - Utilizes Android's `VpnService` framework.
   - Establishes a virtual Layer 3 TUN interface capturing device traffic.
   - Supports per-app split tunneling, LAN bypass, IPv6 leak prevention, and a fail-closed kill switch.

---

## 2. Assets & Data Classification

| Asset | Confidentiality | Integrity | Availability | Storage / Transport |
|---|---|---|---|---|
| **VPN Credentials** (User/Pass) | **Critical** | **High** | Medium | AES-256-GCM via `AndroidKeyStore`; staged in-memory via `ConnectionSessionHolder` with 30s TTL. |
| **Private Keys & Certificates** | **Critical** | **Critical** | High | Encrypted in app private storage (`filesDir/profiles/`) with Keystore-backed AES-256-GCM + AAD. |
| **OpenVPN Profile Configurations** | High | **Critical** | High | AES-256-GCM encrypted; sanitized against CRLF and script directives on import. |
| **Proxy Authentication Credentials** | High | **High** | Medium | Encrypted in Android DataStore preferences via Keystore-backed AES-256-GCM. |
| **In-Flight Network Traffic** | **Critical** | **Critical** | High | Encrypted via OpenVPN TLS data channel (AES-GCM / ChaCha20-Poly1305). |
| **DNS Queries** | High | **Critical** | High | Routed through tunnel in TUN mode; fallback to encrypted or strict upstream resolvers. |

---

## 3. Trust Boundaries

```
[ Local Network / LAN Peers ]
               │
               ▼ (Bound to 0.0.0.0 only if allowLan enabled)
┌─────────────────────────────────────────────────────────┐
│                    Omnia Process                        │
│                                                         │
│   [ Local SOCKS5 / HTTP Proxy Listeners ]               │
│                        │                                │
│                        ▼                                │
│   [ JNI Boundary: TunnelEngine <-> tunnel_jni.cpp ]    │
│                        │                                │
│                        ▼                                │
│   [ Userspace lwIP Stack <-> OpenVPN 3 Core ]           │
│                        │                                │
└────────────────────────┼────────────────────────────────┘
                         │ (Encrypted OpenVPN UDP/TCP Tunnel)
                         ▼
             [ Untrusted Internet / Public Wi-Fi ]
                         │
                         ▼
             [ Remote OpenVPN Server ]
```

### Key Boundaries:
1. **App Sandbox Boundary**: Android OS sandboxing isolates Omnia's process and filesystem from other installed apps.
2. **Intent & Binder Boundary**: Android IPC mechanisms (Intents, Binder) communicate between activities, services, and system servers.
3. **Localhost Network Boundary**: SOCKS5 (10880) and HTTP (10890) sockets accessible to other apps on `127.0.0.1` (or local LAN if configured).
4. **JNI / Native Memory Boundary**: Kotlin memory managed by ART vs. C++ native heap managed by OpenVPN 3 Core and lwIP.
5. **Physical Network Boundary**: Wi-Fi / Cellular physical network interfaces vs. virtual TUN interface.

---

## 4. Threat Vectors & Mitigations

### 4.1. Credential Exposure via Android System Logs & Binder
- **Threat**: Passing credentials or full `.ovpn` profiles via `Intent` extras exposes them to system logging (`dumpsys`, logcat) and risks `TransactionTooLargeException`.
- **Mitigation**:
  - `ConnectionSessionHolder` stages credentials in-memory within the app process.
  - Sessions automatically expire after a 30-second TTL.
  - Zero sensitive payloads are serialized into `Intent` extras.

### 4.2. Timing-Based Side-Channel Attacks on Proxy Auth
- **Threat**: Standard string comparison (`==`) leaks credential prefixes via timing variance.
- **Mitigation**:
  - Both SOCKS5 and HTTP proxy servers employ `java.security.MessageDigest.isEqual()` for constant-time byte comparisons.

### 4.3. Malicious Profile Import & Command Injection
- **Threat**: Untrusted `.ovpn` files containing malicious script directives (`up`, `down`, `script-security`) or CRLF line injection to execute commands or overwrite configurations.
- **Mitigation**:
  - `OvpnParser.kt` strips all executable script directives (`up`, `down`, `plugin`, `script-security`, `tls-verify`).
  - Strict regex-based CRLF and XML-style block tag sanitization prevents directive injection during profile editing.
  - Profile configurations are capped at 1 MB to prevent memory exhaustion attacks.

### 4.4. Profile Tampering & Ciphertext Swapping
- **Threat**: Tampering with stored encrypted profiles or renaming/swapping profile files on disk.
- **Mitigation**:
  - Stored profiles are encrypted with AES-256-GCM via `AndroidKeyStore`.
  - The profile ID is bound as Additional Authenticated Data (AAD) into the GCM ciphertext, preventing profile swapping attacks.

### 4.5. Proxy Slot Exhaustion & Slowloris DoS
- **Threat**: Attackers or rogue local apps opening connections and holding them open indefinitely without authenticating.
- **Mitigation**:
  - Pre-authentication timeout is strictly enforced at 5,000 ms.
  - Concurrent connections capped at 64.
  - Relay idle timeout enforced at 15 minutes.
  - In SOCKS5 UDP associate mode, client UDP endpoint is locked via atomic `compareAndSet` on the first datagram, preventing local port hijacking.

### 4.6. DNS Leaks and Reconnection Gaps
- **Threat**: DNS requests bypassing the VPN tunnel during network switches or re-connections.
- **Mitigation**:
  - Enforced fallback DNS resolver (1.1.1.1) if profile specifies no DNS or resolvers fail.
  - Seamless interface handover: the replacement VPN interface is established before closing the old interface, eliminating kill-switch gap windows.

### 4.7. Native Memory Corruption
- **Threat**: Buffer overflows, integer overflows, or format string bugs in native C++ code.
- **Mitigation**:
  - Built with modern compiler security flags: `-fstack-protector-strong`, `-D_FORTIFY_SOURCE=2`, `-fno-strict-overflow`.
  - Zero legacy unsafe C string functions (`strcpy`, `sprintf`, `gets`).
  - Prebuilt OpenSSL binaries cryptographically pinned via SHA-256 digests and verified in CI.

---

## 5. Residual Risks & User Considerations

1. **LAN Proxy Sharing**:
   - When "Allow LAN access" is enabled, the proxy servers bind to `0.0.0.0`. HTTP proxy authentication uses standard HTTP Basic (Base64), which is unencrypted on the local Layer 2 network unless encapsulated in TLS. Users are warned when enabling LAN access.
2. **Insecure Security Tier**:
   - The user-selectable "Insecure" security level intentionally allows legacy cryptographic algorithms for compatibility with legacy servers. The default level is "Preferred" (AEAD-only, strict certificates).
3. **Rooted Devices**:
   - On rooted devices, root users can inspect memory or bypass OS sandboxing boundaries. Android Keystore keys with hardware backing (TEE / StrongBox) mitigate key extraction even on compromised devices where hardware security modules are supported.
