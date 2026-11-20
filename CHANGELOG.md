# Changelog

All notable changes to Omnia-Android are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.1.9] - 2026-10-30

### Security & Concurrency Hardening
- **Teardown Deadlock Prevention**: Replaced blocking read lock acquisitions in `TunnelEngine.kt` with non-blocking `tryLock()` across all connection and data dispatch methods (`sendData`, `sendUdpData`, `closeConnection`, `closeUdpConnection`, `openConnection`, `openUdpConnection`, `acknowledgeData`, `resolveHostname`, `setCustomDns`). This ensures lwIP and OpenVPN teardown callbacks cannot deadlock with `disconnect()` or `destroy()` while joining native threads.
- **SOCKS5 UDP Endpoint Locking**: Guarded UDP associate client endpoint in `Socks5Server.kt` with `compareAndSet(null, ...)` on the first datagram from the client, blocking local endpoint hijacking.
- **Native Compiler Hardening**: Enabled `-fstack-protector-strong`, `-D_FORTIFY_SOURCE=2`, and `-fno-strict-overflow` in CMake across both C and C++ toolchains.
- **Decoupled Keystore Decryption**: Shifted profile decryption in `ProxyVpnService.kt` to `withContext(Dispatchers.IO)` off the main thread.
- **Connection Session TTL Auto-Cleanup**: Added automatic background coroutine cleanup for unconsumed staged credentials in `ConnectionSessionHolder`.
- **Profile Parser Robustness**: Upgraded directive matching to token-based delimiter parsing to prevent false-positive matching while supporting tabs and arbitrary whitespace. Added recovery for unclosed inline auth tags.
- **Descriptor Fallback Protection**: Ensured duplicated file descriptors from `ParcelFileDescriptor.fromFd()` are safely closed while keeping caller descriptors intact.
- **Unified ProfileManager Singleton**: Implemented thread-safe `getInstance(context)` pattern across ViewModel, service, and Quick Settings tile.

---

## [1.1.8] - 2026-10-29

### Fixes & Enhancements
- **Native PCB Lifecycle**: Ensured PCB is closed prior to connection map removal in lwIP tunnel; closed remote TCP connections explicitly.
- **Disconnect Inversion Fix**: Cloned connection list prior to closing PCBs to eliminate lock inversion during disconnect.
- **JNI Handle Synchronization**: Guarded native handle lifecycle with ReadWriteLock.
- **Event Loop Optimization**: Replaced 1ms sleep loop in lwIP with 20ms `poll()` timeout to dramatically reduce CPU consumption.
- **DNS Leak Mitigation**: Enforced fallback resolver (1.1.1.1) in VPN mode when configured DNS servers fail.
- **Seamless Kill Switch**: Established replacement VPN interface before closing old interface to prevent packet leakage during reconfiguration.
- **HTTP Proxy Streaming**: Unified buffered stream for headers and body relay to prevent request body truncation.

---

## [1.1.7] - 2026-10-28

### Security Hardening
- **Credential Decoupling**: Replaced Intent extras with in-memory `ConnectionSessionHolder` to prevent dumpsys and Binder transaction logging.
- **Constant-Time Authentication**: Integrated `MessageDigest.isEqual()` across SOCKS5 and HTTP proxy servers to prevent timing-based side-channel attacks.
- **Pre-Authentication Timeouts**: Enforced 5-second timeout on pre-auth connections to prevent slot exhaustion DoS attacks.
- **Profile Size Limits**: Enforced 1MB maximum profile configuration cap to prevent memory exhaustion.
- **Script Directive Stripping**: Guaranteed `up`, `down`, and `script-security` directives are stripped from stored profiles.

---

## [1.1.6] - 2026-10-27

### Features & Improvements
- **Dual-Mode Engine**: Fully operational Userspace Proxy Mode (SOCKS5/HTTP via lwIP) alongside System TUN VPN Mode (`VpnService`).
- **LAN Sharing**: Optional LAN proxy listener binding with configurable IP address and basic authentication.
- **Kill Switch & Split Tunneling**: Per-app split tunneling selection with package filtering and drop-on-disconnect kill switch.
- **Upstream Proxy Support**: Integrated upstream HTTP proxy forwarding with Basic authentication support.

---

## [1.1.0] - 2026-10-25

### Core Architecture
- Initial production integration of OpenVPN 3 Core C++ library and lwIP TCP/IP stack.
- JNI bridge for traffic statistics, connection control, and hostname resolution.
- Jetpack Compose Material 3 UI with dark/light theme support.
- Encrypted configuration storage backed by Android Keystore.
