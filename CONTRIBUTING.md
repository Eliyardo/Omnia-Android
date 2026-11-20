# Contributing to Omnia-Android

Thank you for your interest in contributing to Omnia-Android. Omnia is a security-focused OpenVPN 3 Core client for Android featuring both a userspace proxy mode (SOCKS5/HTTP via embedded lwIP) and a system-wide VPN mode.

---

## 1. Development Environment Setup

To build and contribute to Omnia-Android, set up the following prerequisites:

- **JDK**: Java Development Kit 17 (Eclipse Temurin or OpenJDK 17).
- **Android SDK**: Compile SDK `35`, Target SDK `35`, Minimum SDK `26` (Android 8.0 Oreo).
- **Android NDK**: Version `27.2.12479018` (NDK r27c).
- **CMake**: Version `3.22.1` or higher.
- **Android Studio**: Android Studio Ladybug / Meerkat or later with NDK support.

### Setting up NDK via SDK Manager
```bash
sdkmanager --install "ndk;27.2.12479018" "cmake;3.22.1"
```

---

## 2. Architecture Overview

Omnia is organized into clean, focused layers:
- `com.eliyardo.omnia.tunnel`: Native OpenVPN 3 Core and lwIP userspace tunnel engine (`TunnelEngine.kt`).
- `com.eliyardo.omnia.proxy`: High-performance SOCKS5 (`Socks5Server.kt`) and HTTP CONNECT (`HttpProxyServer.kt`) proxy servers.
- `com.eliyardo.omnia.profile`: OpenVPN profile parser (`OvpnParser.kt`) and encrypted profile storage (`ProfileManager.kt`).
- `com.eliyardo.omnia.security`: Android Keystore-backed AES-256-GCM encryption (`KeystoreHelper.kt`).
- `com.eliyardo.omnia.service`: Background service (`ProxyVpnService.kt`), connection session staging (`ConnectionSessionHolder.kt`), and notification/network helpers.
- `com.eliyardo.omnia.settings`: Reactive DataStore preferences (`AppSettings.kt`).
- `com.eliyardo.omnia.ui`: Modern Jetpack Compose UI screens, dialogs, and components.
- `app/src/main/cpp`: Native C++20 glue layer connecting OpenVPN 3 Core, lwIP, and JNI.

---

## 3. Security Guidelines & Coding Standards

All code contributions must adhere to strict security best practices:

1. **Credential Handling**:
   - Never pass plaintext passwords or profile secrets through Android `Intent` extras or Binder transactions.
   - Use `ConnectionSessionHolder` with strict TTL expiry for in-memory session staging.
   - Store persistent secrets using `KeystoreHelper` (AES-256-GCM via `AndroidKeyStore`).

2. **Timing-Attack Resistance**:
   - Always compare credentials and security tokens in constant time using `java.security.MessageDigest.isEqual()`.

3. **Input Sanitization & Parser Safety**:
   - All profile imports and edits must pass through `OvpnParser.kt` and CRLF / XML-injection sanitization.
   - Executable script directives (`up`, `down`, `script-security`, `plugin`) are unsupported on Android and must remain strictly stripped from stored configurations.

4. **Native Thread Safety**:
   - Native C++ code must maintain clear lock hierarchies to prevent deadlocks (e.g., lock order between lwIP and OpenVPN event loops).
   - Never use unsafe legacy C functions (`strcpy`, `sprintf`, `gets`). Use standard C++ string formatting and bounded buffers.
   - Native builds must preserve compiler hardening flags: `-fstack-protector-strong`, `-D_FORTIFY_SOURCE=2`, and `-fno-strict-overflow`.

---

## 4. Building and Testing

### Running Unit and Fuzz Tests
```bash
./gradlew test
```
All unit tests in `app/src/test/` (including protocol fuzzing and security policy tests) must pass with zero failures.

### Running Lint
```bash
./gradlew lintDebug
```

### Verifying Prebuilt OpenSSL Binaries
```bash
sha256sum -c app/src/main/cpp/OPENSSL_CHECKSUMS.sha256
```

### Assembling Release Build
```bash
./gradlew assembleRelease
```

---

## 5. Submitting Changes

1. Fork the repository and create a feature branch (`git checkout -b feature/my-feature`).
2. Ensure your changes compile, all tests pass, and code formatting adheres to Kotlin and Compose standards.
3. Commit with concise, descriptive commit messages.
4. Push to your branch and open a Pull Request against `main`.
5. Ensure all CI checks (unit tests, OpenSSL checksum verification, R8 shrinking, assembleRelease) pass cleanly.
