<p align="center">
  <img src="docs/icon.svg" width="96" height="96" alt="Omnia Logo" />
</p>

<h1 align="center">Omnia</h1>

<p align="center">
  An OpenVPN client for Android featuring userspace proxy interfaces and system-wide VPN tunneling.
</p>

---

## Overview

**Omnia** is an open-source Android client for OpenVPN networks, built with Jetpack Compose, the OpenVPN 3 Core client library (C++20), and an embedded lwIP userspace network stack.

The application operates in two distinct modes:

1. **Proxy Mode (Userspace lwIP)**: Runs an OpenVPN tunnel entirely inside the application's process without requesting Android `VpnService` permission or altering system routing tables. Network packets from the tunnel are processed via lwIP and exposed locally as standard **SOCKS5** (default port `10880`) and **HTTP CONNECT** (default port `10890`) proxy endpoints.
2. **TUN Mode (System-Wide VPN)**: Leverages Android's native `VpnService` framework to route device-level network traffic through the OpenVPN tunnel, supporting per-app split tunneling, dual-stack IPv6 leak prevention, and local LAN exclusion.

---

## Features

### Operational Modes
- **Local Proxy Mode**: Exposes local SOCKS5 (RFC 1928) and HTTP CONNECT proxy interfaces. Client applications (browsers, terminal tools, communication apps) can be pointed directly to the local proxy port, leaving other system network traffic unaffected.
- **TUN Mode (System-Wide VPN)**: Routes all system network traffic through the tunnel via Android's `VpnService`.

### Cryptographic Configuration & Protocol Controls
- **OpenVPN 3 Core & OpenSSL 3**: Backed by the official OpenVPN 3 C++ client engine and OpenSSL 3 for protocol processing.
- **3-Tier Cryptographic Security Levels**:
  - *Preferred*: Restricts ciphers to modern AEAD suites (AES-256-GCM, AES-128-GCM, CHACHA20-POLY1305), enforces strict certificate verification, and disallows SHA-1 signatures.
  - *Legacy (Default)*: Permits standard 128-bit ciphers and legacy certificates for compatibility with existing network infrastructure.
  - *Insecure*: Disables certificate profile restrictions when connecting to legacy servers with outdated algorithms.
- **Enforce TLS 1.3**: Optional toggle to require a minimum of TLS 1.3 for the control channel handshake.
- **Protocol Override**: Configurable transport selection:
  - *Adaptive (Default)*: Respects the profile's configuration while allowing fallback to alternate protocols defined by server entries.
  - *Config Default*: Strictly respects profile directives without intervention.
  - *Force UDP*: Enforces UDP transport across all configured remote endpoints.
  - *Force TCP*: Forces TCP connection attempts for environments where UDP traffic is filtered.
- **Handshake Timeout & Continuous Retry**: Configurable connection attempt timeouts (`10s`, `30s`, `1m` default, `2m`), or `Continuous Retry` to maintain persistence across volatile network conditions.
- **Confirmation on Disconnect**: Optional confirmation dialog before tearing down an active session to prevent inadvertent disconnection.

### Network Routing & Integration
- **Per-App Split Tunneling**: Available in TUN mode. Supports both allowlist (only selected applications use the tunnel) and denylist (selected applications bypass the tunnel) rules.
- **Bypass Local LAN**: Excludes RFC 1918 private IPv4 subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`) and local IPv6 subnets (`fc00::/7`, `fe80::/10`) using `excludeRoute` on Android 13+ (API 33+), ensuring local network devices (printers, gateways, home servers) remain reachable.
- **Upstream HTTP Proxy**: Connects to OpenVPN servers via an upstream HTTP proxy using HTTP CONNECT tunneling, with optional Basic authentication. Functions across both TUN and Proxy modes.

### DNS Resolution & Leak Prevention
- **Upstream DNS Modes**: Supports server-pushed resolvers (`dhcp-option DNS`) and preconfigured overrides (Cloudflare, Google Public DNS, Quad9, AdGuard, or custom static IP addresses).
- **Dynamic Live DNS Updating**: In userspace Proxy Mode, changes to DNS resolvers in settings apply dynamically to lwIP's DNS resolver without requiring tunnel disconnection.
- **Leak Protection in Proxy Mode**: Target domain names are forwarded across the JNI bridge and resolved directly inside the tunnel via lwIP, preventing DNS requests from leaking through local physical network interfaces.
- **Dual-Stack IPv6 Leak Protection**: In TUN mode, configures a dummy IPv6 ULA route (`fd00::1/128` and `::/0`) whenever IPv6 is unconfigured or disabled, ensuring dual-stack Android devices do not leak `AAAA` queries over physical interfaces.

### Profile & Credential Management
- **Profile Import**: Parses standard `.ovpn` configuration files, supporting inline blocks (`<ca>`, `<cert>`, `<key>`, `<tls-auth>`, `<tls-crypt>`) and external certificate references.
- **Encrypted Credential Storage**: Passwords and proxy credentials are encrypted at rest using AES-256-GCM backed by the Android Keystore.
- **In-App Profile Editor**: Allows modifying remote addresses, ports, protocols, and authentication credentials with preservation of secondary remote endpoints.
- **Enterprise Device ID**: Generates a persistent hardware UUID for server-side MDM policies and device tracking.

---

## Architecture

### 1. Local Proxy Mode (Userspace lwIP)

```
[ Client Application (e.g., Browser) ]
                 |
     (SOCKS5: 10880 / HTTP: 10890)
                 v
[ Local Proxy Servers (Kotlin Coroutines) ]
                 |
          (JNI Native Call)
                 v
[ lwIP Userspace TCP/IP Stack (C++20) ]
                 |
   (socketpair: AF_UNIX, SOCK_DGRAM)
                 v
[ OpenVPN 3 Core Engine (C++20) ]
                 |
     (Encrypted Tunnel Payload)
                 v
[ Remote OpenVPN Server ]
```

1. Local proxy servers bind to `127.0.0.1` (or `0.0.0.0` when LAN access is enabled).
2. Incoming connections pass through the JNI bridge into **lwIP (Lightweight IP 2.2.0)**.
3. lwIP manages TCP/UDP state machines in userspace and produces raw IP frames written to a UNIX `socketpair`.
4. OpenVPN 3 Core encrypts the frames and transmits them across the physical transport socket.
5. No system `VpnService` interface is created, allowing simultaneous operation alongside other VPNs or network tools.

---

### 2. TUN Mode (System-Wide VPN)

```
[ Android Applications / OS Network Stack ]
                    |
           (Android IP Routing)
                    v
 [ Android VpnService (tun0 interface) ]
                    |
          (TUN File Descriptor)
                    v
  [ OpenVPN 3 Core Engine (C++20) ]
                    |
      (Encrypted Tunnel Payload)
                    v
  [ Remote OpenVPN Server ]
```

1. Android's `VpnService` allocates a virtual network interface (`tun0`) and configures system IP routing tables.
2. The virtual interface descriptor is passed directly to OpenVPN 3 Core.
3. OpenVPN 3 encrypts outbound packets and decrypts inbound packets.
4. Per-app split tunneling rules are applied at the OS level via `VpnService.Builder`.

---

## Supported Directives

| Directive | Status | Description |
|---|---|---|
| `remote [host] [port] [proto]` | Supported | Remote server endpoint and port |
| `proto [udp\|tcp]` | Supported | Transport protocol selection |
| `dev tun` | Supported | Layer 3 IP tunnel mode |
| `auth-user-pass` | Supported | Password authentication (inline or interactive) |
| `<ca>`, `ca [file]` | Supported | Certificate Authority validation |
| `<cert>`, `cert [file]` | Supported | Client certificate |
| `<key>`, `key [file]` | Supported | Client private key |
| `<tls-auth>`, `<tls-crypt>` | Supported | HMAC packet authorization and metadata encryption |
| `cipher`, `data-ciphers` | Supported | Cipher suite selection (AES-GCM, ChaCha20, etc.) |
| `data-ciphers-fallback` | Supported | Fallback cipher suite for legacy servers |
| `auth [digest]` | Supported | Control channel packet authentication HMAC algorithm |
| `remote-cert-tls server` | Supported | Server certificate identity verification |
| `redirect-gateway` | Supported | System default gateway redirection (TUN mode) |
| `dhcp-option DNS [ip]` | Supported | Dynamic DNS server configuration |
| `up`, `down`, `script-security` | Ignored | External script execution is disabled for sandbox safety |

---

## Building from Source

### Prerequisites
- JDK 17
- Android SDK Platform 35
- Android NDK (`27.2.12479018`)
- CMake 3.22.1+

### Build Commands
```bash
# Clone the repository
git clone https://github.com/Eliyardo/Omnia-Android.git
cd Omnia-Android

# Run unit tests
./gradlew test

# Build release APKs (ABI splits + universal)
./gradlew assembleRelease
```

Release APKs are output to `app/build/outputs/apk/release/`:
- `Omnia-1.1.2-arm64-v8a-release.apk`
- `Omnia-1.1.2-armeabi-v7a-release.apk`
- `Omnia-1.1.2-x86_64-release.apk`
- `Omnia-1.1.2-universal-release.apk`

---

## License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**. See [LICENSE](LICENSE) for details.
Third-party component licenses are detailed in [DEPENDENCIES_AND_LICENSES.md](DEPENDENCIES_AND_LICENSES.md).
