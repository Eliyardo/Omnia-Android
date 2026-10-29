# Dependency and License Inventory

This document lists all direct and transitive third-party libraries, native components, and frameworks utilized by **Omnia**, along with their respective license models and copyright notices.

---

## 1. Native / C & C++ Dependencies

| Component | Version | Role / Purpose | License | Source / Upstream |
|---|---|---|---|---|
| **lwIP (Lightweight IP)** | 2.2.0 | Userspace TCP/IP stack providing virtual Layer 3 IP networking and TCP state machine | BSD-3-Clause | [git.savannah.nongnu.org/git/lwip.git](https://git.savannah.nongnu.org/git/lwip.git) |
| **OpenVPN 3 Core Library** | Upstream / C++20 | Protocol state machine, TLS handshake, cryptographic packet encapsulation | AGPL-3.0 / MPL-2.0 with OpenSSL Exception | [github.com/OpenVPN/openvpn3](https://github.com/OpenVPN/openvpn3) |
| **Asio C++ Library** | 1.28.0 | Cross-platform C++ library for network and low-level I/O programming | BSL-1.0 (Boost Software License) | [think-async.com/Asio](https://think-async.com/Asio) |
| **{fmt} Library** | 10.1.0 | Fast and safe formatting library for C++ | MIT License | [github.com/fmtlib/fmt](https://github.com/fmtlib/fmt) |
| **LZ4** | 1.9.4 | Fast lossless compression algorithm used in OpenVPN data channel | BSD-2-Clause | [github.com/lz4/lz4](https://github.com/lz4/lz4) |
| **OpenSSL** | 3.1.8 (KDAB) | Cryptographic primitives, TLS handshake, certificate verification | Apache-2.0 | [github.com/KDAB/android_openssl](https://github.com/KDAB/android_openssl) |
| **Android NDK** | r27c (27.2.12479018) | C/C++ cross-compilation toolchain for Android ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`) | Apache-2.0 / BSD / MIT | [developer.android.com/ndk](https://developer.android.com/ndk) |

### License Compliance Notes

- **Omnia**: Distributed under the **GNU Affero General Public License Version 3 (AGPLv3)**.
- **OpenVPN 3 Core**: Dual-licensed under the GNU Affero General Public License Version 3 (AGPLv3) and the Mozilla Public License Version 2.0 (MPL-2.0). Omnia exercises the open-source licensing path with full source code distribution, directly matching OpenVPN 3 Core's native AGPLv3 copyleft terms.
- **lwIP**: Licensed under the permissive BSD-3-Clause license. Copyright (c) 2001-2004 Swedish Institute of Computer Science.
- **Asio**: Distributed under the Boost Software License, Version 1.0. Copyright (c) 2003-2023 Christopher M. Kohlhoff.
- **{fmt}**: Distributed under the MIT license. Copyright (c) 2012 - present, Victor Zverovich and {fmt} contributors.
- **LZ4**: Library (`lib/`) licensed under the BSD 2-Clause License. Copyright (c) 2011-2020, Yann Collet.
- **OpenSSL & KDAB**: Licensed under the Apache License 2.0. Copyright (c) 1998-2023 The OpenSSL Project Authors. Copyright (c) 2013-2023 Klarälvdalens Datakonsult AB (KDAB).

---

## 2. Android Framework & Jetpack Dependencies

| Library Artifact | Version | Role / Purpose | License |
|---|---|---|---|
| `androidx.core:core-ktx` | 1.15.0 | Kotlin extensions for Android Core APIs | Apache-2.0 |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.8.7 | Android lifecycle management | Apache-2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.8.7 | ViewModel integration for Compose | Apache-2.0 |
| `androidx.activity:activity-compose` | 1.9.3 | Activity integration for Jetpack Compose | Apache-2.0 |
| `androidx.compose:compose-bom` | 2024.12.01 | Compose Bill of Materials | Apache-2.0 |
| `androidx.compose.ui:ui` | BOM | Compose UI framework | Apache-2.0 |
| `androidx.compose.material3:material3` | BOM | Material 3 UI design system | Apache-2.0 |
| `androidx.compose.material:material-icons-extended` | BOM | Extended Material Design icons | Apache-2.0 |
| `androidx.datastore:datastore-preferences` | 1.1.1 | Reactive key-value configuration storage | Apache-2.0 |
| `androidx.security:security-crypto` | 1.1.0-alpha06 | Android Keystore backed `EncryptedFile` and `MasterKey` | Apache-2.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 | Asynchronous networking, coroutines runtime | Apache-2.0 |

---

## 3. Testing Dependencies

| Library Artifact | Version | Role / Purpose | License |
|---|---|---|---|
| `junit:junit` | 4.13.2 | Unit testing framework | Eclipse Public License 1.0 |
| `androidx.test.ext:junit` | 1.2.1 | Android JUnit test runner | Apache-2.0 |
| `androidx.test.espresso:espresso-core` | 3.6.1 | UI and instrumentation testing | Apache-2.0 |
| `androidx.compose.ui:ui-test-junit4` | BOM | Compose UI test harness | Apache-2.0 |
