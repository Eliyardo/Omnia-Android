# Omnia

An OpenVPN client for Android featuring userspace proxy isolation and system-wide VPN tunneling.

---

## Overview

**Omnia** is a native Android OpenVPN client built using Jetpack Compose, the OpenVPN 3 Core library (C++20), and an embedded lwIP userspace network stack.

The application provides two distinct operational modes:
1. **Local Proxy Mode**: Establishes an encrypted OpenVPN tunnel entirely within userspace using lwIP. It exposes local **SOCKS5** (default port `10880`) and **HTTP CONNECT** (default port `10890`) proxy interfaces without acquiring Android `VpnService` permissions, creating a virtual TUN device, or altering system network routes.
2. **TUN Mode (System-Wide VPN)**: Implements Android `VpnService` to route device-level network traffic through the OpenVPN tunnel, supporting per-application routing policies and dual-stack IPv4/IPv6 leak protection.

---

## Features

### Operational Modes
- **Local Proxy Mode**: Routes application traffic through local SOCKS5 (RFC 1928) and HTTP CONNECT proxy servers. System-wide network traffic remains unaffected.
- **TUN Mode (System-Wide VPN)**: Uses Android's `VpnService` to provide system-level VPN coverage across all network interfaces.

### Application Routing
- **Per-App Routing**: Configures which installed applications route through the VPN tunnel or bypass it directly (available in TUN mode).

### Profile and Credential Management
- **Configuration Import**: Parses standard `.ovpn` configuration files with support for inline certificates (`<ca>`, `<cert>`, `<key>`, `<tls-auth>`, `<tls-crypt>`).
- **Encrypted Storage**: Stores configuration profiles and sensitive authentication credentials using AES-256-GCM encryption backed by Android Keystore and Android Jetpack Security.
- **In-App Profile Editing**: Allows manual modification of remote hostnames, ports, transport protocols (UDP/TCP), and credentials directly within the application with failover remotes preserved.

### Cryptographic Support
- **OpenVPN 3 Core Engine**: Uses the modern OpenVPN 3 C++ client library with OpenSSL 3.
- **Legacy Cipher Fallback**: Opt-in legacy cipher negotiation (BF-CBC, DES, 3DES, CAST5) disabled by default for modern cryptographic security.

### DNS Management
- **DNS Modes**: Supports server-pushed DNS servers with options to configure custom upstream DNS resolvers (e.g., Cloudflare, Google, or custom addresses).
- **Leak Prevention**: Ensures DNS queries in proxy mode resolve through the remote tunnel endpoint and IPv6 routes are protected.

---

## Architecture

### 1. Local Proxy Mode (Userspace lwIP)

```
[ Proxy-Configured Client Applications ]
                   |
     (SOCKS5: 10880 / HTTP: 10890)
                   v
[ Local Proxy Servers (Kotlin Coroutines) ]
                   |
            (JNI Native Bridge)
                   v
[ lwIP Userspace TCP/IP Stack (C++20) ]
                   |
     (socketpair: AF_UNIX, SOCK_DGRAM)
                   v
[ OpenVPN 3 Core Engine (C++20) ]
                   |
      (Encrypted UDP / TCP Tunnel)
                   v
[ Remote OpenVPN Server ]
```

In Local Proxy Mode:
1. `Socks5Server` and `HttpProxyServer` bind to the loopback interface (`127.0.0.1`) by default, or `0.0.0.0` when local network sharing (LAN access) is enabled.
2. Client TCP connections are forwarded across the JNI bridge to an embedded **lwIP (Lightweight IP 2.2.0)** TCP/IP stack.
3. lwIP converts application TCP streams into Layer-3 IP packets and writes them to an anonymous UNIX `socketpair`.
4. The OpenVPN 3 Core engine reads packets from the socketpair, encapsulates and encrypts them, and transmits them to the remote OpenVPN endpoint.
5. No system VPN interface (`tun0`) is allocated, requiring no system-level VPN permissions.

---

### 2. TUN Mode (System-Wide VPN)

```
[ System Applications / OS Network Stack ]
                   |
          (Android IP Routing)
                   v
[ Android VpnService (tun0 interface) ]
                   |
         (TUN File Descriptor)
                   v
[ OpenVPN 3 Core Engine (C++20) ]
                   |
      (Encrypted UDP / TCP Tunnel)
                   v
[ Remote OpenVPN Server ]
```

In TUN Mode (System-Wide VPN):
1. Android's `VpnService` creates a virtual network interface (`tun0`) and establishes system-wide routes.
2. The virtual interface file descriptor is handed directly to the OpenVPN 3 Core engine.
3. OpenVPN 3 handles encryption and packet transport directly with the remote server.
4. Per-app rules are enforced via Android's `VpnService.Builder` application filtering APIs.

---

## Supported Directives

| Directive | Status | Description |
|---|---|---|
| `remote [host] [port] [proto]` | Supported | Configures remote endpoint and port |
| `proto [udp\|tcp]` | Supported | UDP or TCP transport protocol |
| `dev tun` | Supported | Layer 3 tunnel operation |
| `auth-user-pass` | Supported | Interactive, saved, or inline credentials |
| `<ca>`, `ca [file]` | Supported | Certificate Authority validation |
| `<cert>`, `cert [file]` | Supported | Client certificate |
| `<key>`, `key [file]` | Supported | Client private key |
| `<tls-auth>`, `<tls-crypt>` | Supported | Cryptographic packet authorization and encryption |
| `cipher`, `data-ciphers` | Supported | Cryptographic cipher suite selection |
| `data-ciphers-fallback` | Supported | Fallback cipher suite for legacy servers |
| `auth [digest]` | Supported | Packet authentication HMAC algorithm |
| `remote-cert-tls server` | Supported | Server certificate verification |
| `redirect-gateway` | Supported | Default route redirection (TUN mode) |
| `up`, `down`, `script-security` | Rejected / Ignored | Script execution disabled in OpenVPN 3 for security |

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

# Build release split APKs
./gradlew assembleRelease
```

Build outputs are located in:
`app/build/outputs/apk/release/`
- `Omnia-1.0.3-arm64-v8a-release.apk`
- `Omnia-1.0.3-armeabi-v7a-release.apk`
- `Omnia-1.0.3-x86_64-release.apk`
- `Omnia-1.0.3-universal-release.apk`

---

## License

This project is licensed under the GNU General Public License v3.0 (GPLv3). See [LICENSE](LICENSE) for details.
Third-party component licenses are detailed in [DEPENDENCIES_AND_LICENSES.md](DEPENDENCIES_AND_LICENSES.md).
