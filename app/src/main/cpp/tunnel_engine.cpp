/*
 * tunnel_engine.cpp - Core tunnel engine implementation
 */

#include "tunnel_engine.h"
#include "lwip_tunnel.h"
#include "openvpn_client_impl.h"

#include <android/log.h>
#include <sys/socket.h>
#include <unistd.h>
#include <poll.h>
#include <cerrno>
#include <cstring>
#include <cstdarg>
#include <signal.h>
#include <fcntl.h>
#include <pthread.h>

#define LOG_TAG "TunnelEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ============================================================
 * TunnelCallbacks implementation
 * ============================================================ */

namespace {
    struct JniThreadAttacher {
        JavaVM* vm = nullptr;
        bool attached = false;
        ~JniThreadAttacher() {
            if (vm && attached) {
                vm->DetachCurrentThread();
                attached = false;
            }
        }
    };
    thread_local JniThreadAttacher tl_jniAttacher;
}

JNIEnv* TunnelCallbacks::getEnv() {
    if (!jvm) return nullptr;
    JNIEnv* env = nullptr;
    int status = jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            tl_jniAttacher.vm = jvm;
            tl_jniAttacher.attached = true;
        } else {
            return nullptr;
        }
    }
    return env;
}

void TunnelCallbacks::log(int level, const char* fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);

    JNIEnv* env = getEnv();
    if (env && engineObj && onLog) {
        jstring msg = env->NewStringUTF(buf);
        env->CallVoidMethod(engineObj, onLog, level, msg);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(msg);
    }

    /* Also log to logcat */
    int prio = ANDROID_LOG_DEBUG;
    if (level <= 1) prio = ANDROID_LOG_ERROR;
    else if (level == 2) prio = ANDROID_LOG_WARN;
    else if (level == 3) prio = ANDROID_LOG_INFO;
    __android_log_print(prio, LOG_TAG, "%s", buf);
}

void TunnelCallbacks::stateChanged(int state, const std::string& message) {
    JNIEnv* env = getEnv();
    if (env && engineObj && onVpnStateChanged) {
        jstring msg = env->NewStringUTF(message.c_str());
        env->CallVoidMethod(engineObj, onVpnStateChanged, state, msg);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(msg);
    }
}

void TunnelCallbacks::tunnelConnected(int connId) {
    JNIEnv* env = getEnv();
    if (env && engineObj && onTunnelConnected) {
        env->CallVoidMethod(engineObj, onTunnelConnected, connId);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
}

void TunnelCallbacks::tunnelDataReceived(int connId, const uint8_t* data, int length) {
    JNIEnv* env = getEnv();
    if (env && engineObj && onTunnelDataReceived) {
        jbyteArray jdata = env->NewByteArray(length);
        env->SetByteArrayRegion(jdata, 0, length, reinterpret_cast<const jbyte*>(data));
        env->CallVoidMethod(engineObj, onTunnelDataReceived, connId, jdata, 0, length);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(jdata);
    }
}

void TunnelCallbacks::tunnelUdpDataReceived(int connId, const std::string& remoteHost, int remotePort, const uint8_t* data, int length) {
    JNIEnv* env = getEnv();
    if (env && engineObj && onTunnelUdpDataReceived) {
        jstring jHost = env->NewStringUTF(remoteHost.c_str());
        jbyteArray jdata = env->NewByteArray(length);
        env->SetByteArrayRegion(jdata, 0, length, reinterpret_cast<const jbyte*>(data));
        env->CallVoidMethod(engineObj, onTunnelUdpDataReceived, connId, jHost, (jint)remotePort, jdata, 0, length);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(jHost);
        env->DeleteLocalRef(jdata);
    }
}

void TunnelCallbacks::tunnelConnectionClosed(int connId, const std::string& reason) {
    JNIEnv* env = getEnv();
    if (env && engineObj && onTunnelConnectionClosed) {
        jstring msg = env->NewStringUTF(reason.c_str());
        env->CallVoidMethod(engineObj, onTunnelConnectionClosed, connId, msg);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(msg);
    }
}

void TunnelCallbacks::statsUpdated(uint64_t bytesIn, uint64_t bytesOut) {
    JNIEnv* env = getEnv();
    if (env && engineObj && onStatsUpdated) {
        env->CallVoidMethod(engineObj, onStatsUpdated, (jlong)bytesIn, (jlong)bytesOut);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
}

bool TunnelCallbacks::protectSocket(int socket) {
    JNIEnv* env = getEnv();
    if (!env || !engineObj || !onProtectSocket) return false;
    jboolean res = env->CallBooleanMethod(engineObj, onProtectSocket, (jint)socket);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return res == JNI_TRUE;
}

int TunnelCallbacks::establishVpn(const std::string& ip, int prefix,
                                 const std::string& ipv6, int prefixIpv6,
                                 int mtu,
                                 const std::vector<std::string>& dns,
                                 const std::vector<std::string>& routes,
                                 bool rerouteGwIpv4,
                                 bool rerouteGwIpv6,
                                 const std::vector<std::string>& excludedRoutes) {
    JNIEnv* env = getEnv();
    if (!env || !engineObj || !onEstablishVpn) return -1;

    jstring jip = env->NewStringUTF(ip.c_str());
    jstring jipv6 = env->NewStringUTF(ipv6.c_str());
    jclass strCls = env->FindClass("java/lang/String");

    jobjectArray jdns = env->NewObjectArray((jsize)dns.size(), strCls, nullptr);
    for (size_t i = 0; i < dns.size(); ++i) {
        jstring s = env->NewStringUTF(dns[i].c_str());
        env->SetObjectArrayElement(jdns, (jsize)i, s);
        env->DeleteLocalRef(s);
    }

    jobjectArray jroutes = env->NewObjectArray((jsize)routes.size(), strCls, nullptr);
    for (size_t i = 0; i < routes.size(); ++i) {
        jstring s = env->NewStringUTF(routes[i].c_str());
        env->SetObjectArrayElement(jroutes, (jsize)i, s);
        env->DeleteLocalRef(s);
    }

    jobjectArray jexcluded = env->NewObjectArray((jsize)excludedRoutes.size(), strCls, nullptr);
    for (size_t i = 0; i < excludedRoutes.size(); ++i) {
        jstring s = env->NewStringUTF(excludedRoutes[i].c_str());
        env->SetObjectArrayElement(jexcluded, (jsize)i, s);
        env->DeleteLocalRef(s);
    }

    jint fd = env->CallIntMethod(engineObj, onEstablishVpn,
                                 jip, (jint)prefix, jipv6, (jint)prefixIpv6, (jint)mtu,
                                 jdns, jroutes,
                                 rerouteGwIpv4 ? JNI_TRUE : JNI_FALSE,
                                 rerouteGwIpv6 ? JNI_TRUE : JNI_FALSE,
                                 jexcluded);

    env->DeleteLocalRef(jip);
    env->DeleteLocalRef(jipv6);
    env->DeleteLocalRef(jdns);
    env->DeleteLocalRef(jroutes);
    env->DeleteLocalRef(jexcluded);
    env->DeleteLocalRef(strCls);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -1;
    }
    return (int)fd;
}

/* ============================================================
 * TunnelEngine implementation
 * ============================================================ */

static std::unique_ptr<LwipTunnel> g_lwipTunnel;

TunnelEngine::TunnelEngine() {
    tunFd[0] = -1;
    tunFd[1] = -1;
}

TunnelEngine::~TunnelEngine() {
    destroy();
}

bool TunnelEngine::initialize(JNIEnv* env, jobject engineObj) {
    env->GetJavaVM(&callbacks.jvm);
    callbacks.engineObj = env->NewGlobalRef(engineObj);

    jclass cls = env->GetObjectClass(engineObj);
    callbacks.onVpnStateChanged = env->GetMethodID(cls, "onVpnStateChanged", "(ILjava/lang/String;)V");
    callbacks.onTunnelConnected = env->GetMethodID(cls, "onTunnelConnected", "(I)V");
    callbacks.onTunnelDataReceived = env->GetMethodID(cls, "onTunnelDataReceived", "(I[BII)V");
    callbacks.onTunnelUdpDataReceived = env->GetMethodID(cls, "onTunnelUdpDataReceived", "(ILjava/lang/String;I[BII)V");
    callbacks.onTunnelConnectionClosed = env->GetMethodID(cls, "onTunnelConnectionClosed", "(ILjava/lang/String;)V");
    callbacks.onStatsUpdated = env->GetMethodID(cls, "onStatsUpdated", "(JJ)V");
    callbacks.onLog = env->GetMethodID(cls, "onLog", "(ILjava/lang/String;)V");
    callbacks.onProtectSocket = env->GetMethodID(cls, "onProtectSocket", "(I)Z");
    callbacks.onEstablishVpn = env->GetMethodID(cls, "onEstablishVpn", "(Ljava/lang/String;ILjava/lang/String;II[Ljava/lang/String;[Ljava/lang/String;ZZ[Ljava/lang/String;)I");

    if (env->ExceptionCheck()) {
        LOGE("Exception occurred while resolving TunnelEngine methods");
        env->ExceptionClear();
    }

    LOGI("TunnelEngine initialized");
    return true;
}

int TunnelEngine::connect(const std::string& profile, const std::string& username, const std::string& password,
                          bool isTunMode,
                          const std::string& securityLevel, bool enforceTls13,
                          const std::string& protoOverride, int connTimeout,
                          bool ipv6Enabled, bool bypassLanTun,
                          const std::string& upstreamProxyHost, int upstreamProxyPort,
                          const std::string& upstreamProxyUser, const std::string& upstreamProxyPass,
                          bool upstreamProxyAllowCleartext,
                          const std::string& deviceId) {
    if (running.load()) {
        disconnect();
    }

    // Safely join any previous threads to prevent SIGABRT or deadlock
    auto currentId = std::this_thread::get_id();
    if (statsThread.joinable() && statsThread.get_id() != currentId) {
        try { statsThread.join(); } catch (...) {}
    }
    if (ovpnThread.joinable() && ovpnThread.get_id() != currentId) {
        try { ovpnThread.join(); } catch (...) {}
    }
    if (lwipThread.joinable() && lwipThread.get_id() != currentId) {
        try { lwipThread.join(); } catch (...) {}
    }

    callbacks.stateChanged(1, "Initializing tunnel...");
    callbacks.log(3, "Starting OpenVPN connection...");

    running.store(true);

    int clientFd = -1;

    if (isTunMode) {
        // TUN Mode (Android VpnService direct kernel interface)
        // clientFd is -1 so tun_builder_establish will request VpnService.establish() dynamically
        LOGI("Starting OpenVPN in TUN (System-Wide VPN) mode");
        clientFd = -1;
    } else {
        // Proxy Mode (Userspace lwIP + SOCKS5/HTTP)
        LOGI("Starting OpenVPN in Proxy-Only mode via socketpair & lwIP");
        if (tunFd[0] >= 0) { close(tunFd[0]); tunFd[0] = -1; }
        if (tunFd[1] >= 0) { close(tunFd[1]); tunFd[1] = -1; }

        if (socketpair(AF_UNIX, SOCK_DGRAM, 0, tunFd) < 0) {
            LOGE("Failed to create socketpair: %s", strerror(errno));
            callbacks.stateChanged(4, "Failed to create tunnel socketpair");
            running.store(false);
            return -1;
        }

        int bufSize = 512 * 1024;
        setsockopt(tunFd[0], SOL_SOCKET, SO_RCVBUF, &bufSize, sizeof(bufSize));
        setsockopt(tunFd[0], SOL_SOCKET, SO_SNDBUF, &bufSize, sizeof(bufSize));
        setsockopt(tunFd[1], SOL_SOCKET, SO_RCVBUF, &bufSize, sizeof(bufSize));
        setsockopt(tunFd[1], SOL_SOCKET, SO_SNDBUF, &bufSize, sizeof(bufSize));

        clientFd = tunFd[0];

        /* Start lwIP on tunFd[1] */
        startLwip();
    }

    /* Launch OpenVPN 3 Core client on clientFd */
    ovpnClient = std::make_unique<OpenVpnClientImpl>(this, clientFd);
    ovpnThread = std::thread(&TunnelEngine::runOpenVpn, this, profile, username, password,
                             securityLevel, enforceTls13, protoOverride, connTimeout,
                             ipv6Enabled, bypassLanTun,
                             upstreamProxyHost, upstreamProxyPort,
                             upstreamProxyUser, upstreamProxyPass,
                             upstreamProxyAllowCleartext, deviceId);
    statsThread = std::thread(&TunnelEngine::statsLoop, this);

    return 0;
}

void TunnelEngine::runOpenVpn(const std::string& profile, const std::string& username, const std::string& password,
                              const std::string& securityLevel, bool enforceTls13,
                              const std::string& protoOverride, int connTimeout,
                              bool ipv6Enabled, bool bypassLanTun,
                              const std::string& upstreamProxyHost, int upstreamProxyPort,
                              const std::string& upstreamProxyUser, const std::string& upstreamProxyPass,
                              bool upstreamProxyAllowCleartext,
                              const std::string& deviceId) {
    pthread_setname_np(pthread_self(), "ovpn-worker");

    if (!ovpnClient) return;

    try {
        openvpn::ClientAPI::Config config;
        config.content = profile;

        // Security level & cryptographic cipher suite policy
        if (securityLevel == "preferred") {
            config.enableLegacyAlgorithms = false;
            config.enableNonPreferredDCAlgorithms = false;
            config.tlsCertProfileOverride = "preferred";
            callbacks.log(3, "Security Level: Preferred (Modern AEAD ciphers enforced, compression disabled)");
        } else if (securityLevel == "insecure") {
            config.enableLegacyAlgorithms = true;
            config.enableNonPreferredDCAlgorithms = true;
#ifdef OPENVPN_ALLOW_INSECURE_CERTPROFILE
            config.tlsCertProfileOverride = "insecure";
#else
            config.tlsCertProfileOverride = "legacy";
#endif
            callbacks.log(2, "Security Level: Insecure (Permits legacy ciphers, weak digests, and insecure certificates)");
        } else {
            // Default: "legacy"
            config.enableLegacyAlgorithms = true;
            config.enableNonPreferredDCAlgorithms = true;
            config.tlsCertProfileOverride = "legacy";
            callbacks.log(3, "Security Level: Legacy (Standard compatibility: AES-CBC & TLS 1.2+ permitted)");
        }

        // Enforce TLS 1.3
        if (enforceTls13) {
            config.tlsVersionMinOverride = "tls_1_3";
            callbacks.log(3, "Enforcing TLS 1.3 minimum version");
        } else {
            config.tlsVersionMinOverride = "";
        }

        // Transport protocol override
        if (protoOverride == "adaptive") {
            config.protoOverride = "adaptive";
            callbacks.log(3, "Protocol mode: Adaptive (UDP preferred with auto TCP fallback)");
        } else if (protoOverride == "udp") {
            config.protoOverride = "udp";
            callbacks.log(3, "Protocol mode: Force UDP");
        } else if (protoOverride == "tcp") {
            config.protoOverride = "tcp";
            callbacks.log(3, "Protocol mode: Force TCP");
        } else {
            config.protoOverride = "";
            callbacks.log(3, "Protocol mode: Config default");
        }

        // Connection timeout (0 = continuous retry, or seconds)
        config.connTimeout = connTimeout;
        if (connTimeout == 0) {
            callbacks.log(3, "Connection timeout: Continuous retry");
        } else {
            callbacks.log(3, "Connection timeout: %d seconds", connTimeout);
        }

        // Upstream HTTP Proxy
        if (!upstreamProxyHost.empty() && upstreamProxyPort > 0) {
            config.proxyHost = upstreamProxyHost;
            config.proxyPort = std::to_string(upstreamProxyPort);
            config.proxyUsername = upstreamProxyUser;
            config.proxyPassword = upstreamProxyPass;
            config.proxyAllowCleartextAuth = upstreamProxyAllowCleartext;
            callbacks.log(3, "Upstream HTTP Proxy active: %s:%d", upstreamProxyHost.c_str(), upstreamProxyPort);
        }

        // Enterprise Device ID
        if (!deviceId.empty()) {
            config.hwAddrOverride = deviceId;
        }

        // LAN Access / Route Emulation
        config.allowLocalLanAccess = bypassLanTun;
#ifdef OPENVPN_PLATFORM_ANDROID
        config.enableRouteEmulation = true;
#endif

        config.allowUnusedAddrFamilies = ipv6Enabled ? "yes" : "no";
        config.defaultKeyDirection = -1;
        config.tunPersist = false;
        config.synchronousDnsLookup = false;
        config.googleDnsFallback = false;

        callbacks.log(3, "Evaluating OpenVPN configuration profile...");
        openvpn::ClientAPI::EvalConfig eval = ovpnClient->eval_config(config);
        if (eval.error) {
            LOGE("OpenVPN eval_config error: %s", eval.message.c_str());
            callbacks.stateChanged(4, "Config error: " + eval.message);
            callbacks.log(1, "Config error: %s", eval.message.c_str());
            return;
        }

        callbacks.log(3, "Profile valid: remote %s:%s (%s)",
                      eval.remoteHost.c_str(), eval.remotePort.c_str(), eval.remoteProto.c_str());

        if (!username.empty() || !password.empty()) {
            openvpn::ClientAPI::ProvideCreds creds;
            creds.username = username;
            creds.password = password;
            ovpnClient->provide_creds(creds);
            callbacks.log(3, "Applied credentials for user [REDACTED]");
        }

        callbacks.stateChanged(1, "Connecting to " + eval.remoteHost + "...");
        openvpn::ClientAPI::Status status = ovpnClient->connect();
        if (status.error) {
            LOGE("OpenVPN connect finished with error: %s (%s)", status.message.c_str(), status.status.c_str());
            if (running.load()) {
                callbacks.stateChanged(4, status.message.empty() ? status.status : status.message);
            }
        } else {
            LOGI("OpenVPN connect session finished cleanly");
        }
    } catch (const std::exception& e) {
        LOGE("Exception in OpenVPN thread: %s", e.what());
        if (running.load()) {
            callbacks.stateChanged(4, std::string("VPN error: ") + e.what());
        }
    } catch (...) {
        LOGE("Unknown native exception in OpenVPN thread");
        if (running.load()) {
            callbacks.stateChanged(4, "Unknown native VPN error");
        }
    }
}

void TunnelEngine::onTunnelEstablished(const std::string& ip, int prefix, const std::string& gw,
                                      const std::vector<std::string>& dns) {
    std::string actualIp = ip.empty() ? "10.8.0.2" : ip;
    std::string actualGw = gw.empty() ? "10.8.0.1" : gw;
    int actualPrefix = (prefix <= 0 || prefix > 32) ? 24 : prefix;

    std::string dns1 = !customDns1.empty() ? customDns1 : (dns.size() > 0 ? dns[0] : "1.1.1.1");
    std::string dns2 = !customDns2.empty() ? customDns2 : (dns.size() > 1 ? dns[1] : "8.8.8.8");

    if (g_lwipTunnel) {
        g_lwipTunnel->configure(actualIp, actualPrefix, actualGw, dns1, dns2);
    }

    callbacks.log(3, "Tunnel interface configured: IP=%s/%d GW=%s DNS=%s,%s",
                  actualIp.c_str(), actualPrefix, actualGw.c_str(), dns1.c_str(), dns2.c_str());
}

void TunnelEngine::disconnect() {
    running.store(false);

    auto currentId = std::this_thread::get_id();
    if (statsThread.joinable() && statsThread.get_id() != currentId) {
        try { statsThread.join(); } catch (...) {}
    }

    LOGI("Disconnecting tunnel engine...");
    callbacks.stateChanged(0, "Disconnecting...");

    /* 1. Shutdown socketpair endpoints to instantly unblock any pending socket I/O */
    if (tunFd[0] >= 0) {
        shutdown(tunFd[0], SHUT_RDWR);
    }
    if (tunFd[1] >= 0) {
        shutdown(tunFd[1], SHUT_RDWR);
    }

    /* 2. Stop OpenVPN client session */
    if (ovpnClient) {
        try { ovpnClient->stop(); } catch (...) {}
    }
    if (ovpnThread.joinable() && ovpnThread.get_id() != currentId) {
        try { ovpnThread.join(); } catch (...) {}
    }
    ovpnClient.reset();

    /* 3. Close and clear active proxy connections */
    {
        std::lock_guard<std::recursive_mutex> lock(connectionsMutex);
        for (auto& [id, conn] : connections) {
            if (conn) {
                conn->closing.store(true);
                if (g_lwipTunnel) {
                    if (conn->is_udp) {
                        g_lwipTunnel->closeUdp(id);
                    } else {
                        g_lwipTunnel->closeTcp(id);
                    }
                }
            }
        }
        connections.clear();
    }

    /* 4. Stop lwIP event loop */
    stopLwip();

    /* 5. Close socketpair fds */
    if (tunFd[0] >= 0) { close(tunFd[0]); tunFd[0] = -1; }
    if (tunFd[1] >= 0) { close(tunFd[1]); tunFd[1] = -1; }

    /* 6. Close vpnModeFd if in TUN mode */
    if (vpnModeFd >= 0) {
        close(vpnModeFd);
        vpnModeFd = -1;
    }

    bytesIn.store(0);
    bytesOut.store(0);

    callbacks.stateChanged(0, "Disconnected");
    callbacks.log(3, "Tunnel disconnected successfully");
    LOGI("Tunnel engine disconnected successfully");
}

void TunnelEngine::statsLoop() {
    pthread_setname_np(pthread_self(), "ovpn-stats");
    LOGI("Stats monitoring loop started");
    while (running.load()) {
        for (int i = 0; i < 20 && running.load(); ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
        if (!running.load()) break;

        long long in = 0, out = 0;
        if (ovpnClient) {
            try {
                auto ts = ovpnClient->tun_stats();
                in = ts.bytesIn;
                out = ts.bytesOut;
                if (in == 0 && out == 0) {
                    auto tr = ovpnClient->transport_stats();
                    in = tr.bytesIn;
                    out = tr.bytesOut;
                }
            } catch (...) {}
        }
        if (in == 0 && out == 0) {
            in = bytesIn.load();
            out = bytesOut.load();
        }

        callbacks.statsUpdated(in, out);
    }
    LOGI("Stats monitoring loop stopped");
}

void TunnelEngine::destroy() {
    disconnect();

    JNIEnv* env = callbacks.getEnv();
    if (env && callbacks.engineObj) {
        env->DeleteGlobalRef(callbacks.engineObj);
        callbacks.engineObj = nullptr;
    }
}

int TunnelEngine::openConnection(const std::string& host, int port, long callbackId) {
    if (!running.load() || !g_lwipTunnel) {
        LOGE("Cannot open connection: tunnel not running");
        return -1;
    }

    int connId = nextConnId.fetch_add(1);

    auto conn = std::make_unique<TunnelConnection>();
    conn->id = connId;
    conn->host = host;
    conn->port = port;
    conn->callbackId = callbackId;

    {
        std::lock_guard<std::recursive_mutex> lock(connectionsMutex);
        connections[connId] = std::move(conn);
    }

    LOGD("Opening connection %d to %s:%d", connId, host.c_str(), port);

    int result = g_lwipTunnel->connectTcp(connId, host, port);
    if (result < 0) {
        LOGE("Failed to initiate TCP connection %d", connId);
        std::lock_guard<std::recursive_mutex> lock(connectionsMutex);
        connections.erase(connId);
        return -1;
    }

    return connId;
}

int TunnelEngine::sendData(int connId, const uint8_t* data, int offset, int length) {
    if (!running.load() || !g_lwipTunnel) return -1;

    TunnelConnection* conn = getConnection(connId);
    if (!conn || conn->closing.load()) return -1;

    int sent = g_lwipTunnel->sendTcpData(connId, data + offset, length);
    if (sent > 0) {
        bytesOut.fetch_add(sent);
    }
    return sent;
}

void TunnelEngine::acknowledgeData(int connId, int length) {
    if (!running.load() || !g_lwipTunnel || length <= 0) return;
    g_lwipTunnel->acknowledgeTcpData(connId, length);
}

void TunnelEngine::closeConnection(int connId) {
    std::lock_guard<std::recursive_mutex> lock(connectionsMutex);
    auto it = connections.find(connId);
    if (it == connections.end()) return;

    TunnelConnection* conn = it->second.get();
    if (!conn) return;

    if (conn->closing.exchange(true)) return; /* Already closing */

    if (g_lwipTunnel) {
        g_lwipTunnel->closeTcp(connId);
    }

    connections.erase(it);
}

int TunnelEngine::openUdpConnection(long callbackId) {
    if (!running.load() || !g_lwipTunnel) {
        LOGE("Cannot open UDP connection: tunnel not running");
        return -1;
    }

    int connId = nextConnId.fetch_add(1);

    auto conn = std::make_unique<TunnelConnection>();
    conn->id = connId;
    conn->is_udp = true;
    conn->callbackId = callbackId;

    {
        std::lock_guard<std::recursive_mutex> lock(connectionsMutex);
        connections[connId] = std::move(conn);
    }

    int result = g_lwipTunnel->openUdp(connId);
    if (result < 0) {
        LOGE("Failed to open lwIP UDP for connId %d", connId);
        std::lock_guard<std::recursive_mutex> lock(connectionsMutex);
        connections.erase(connId);
        return -1;
    }

    return connId;
}

int TunnelEngine::sendUdpData(int connId, const std::string& host, int port, const uint8_t* data, int offset, int length) {
    if (!running.load() || !g_lwipTunnel) return -1;

    TunnelConnection* conn = getConnection(connId);
    if (!conn || conn->closing.load()) return -1;

    int sent = g_lwipTunnel->sendUdpData(connId, host, port, data + offset, length);
    if (sent > 0) {
        bytesOut.fetch_add(sent);
    }
    return sent;
}

void TunnelEngine::closeUdpConnection(int connId) {
    std::lock_guard<std::recursive_mutex> lock(connectionsMutex);
    auto it = connections.find(connId);
    if (it == connections.end()) return;

    TunnelConnection* conn = it->second.get();
    if (!conn) return;

    if (conn->closing.exchange(true)) return;

    if (g_lwipTunnel) {
        g_lwipTunnel->closeUdp(connId);
    }

    connections.erase(it);
}

std::string TunnelEngine::resolveHostname(const std::string& hostname) {
    if (!running.load() || !g_lwipTunnel) return "";
    return g_lwipTunnel->resolveHost(hostname);
}

TunnelConnection* TunnelEngine::getConnection(int connId) {
    std::lock_guard<std::recursive_mutex> lock(connectionsMutex);
    auto it = connections.find(connId);
    if (it != connections.end()) return it->second.get();
    return nullptr;
}

void TunnelEngine::onLwipConnected(int connId) {
    TunnelConnection* conn = getConnection(connId);
    if (conn) {
        conn->connected.store(true);
        callbacks.tunnelConnected(connId);
    }
}

void TunnelEngine::onLwipDataReceived(int connId, const uint8_t* data, int length) {
    bytesIn.fetch_add(length);
    callbacks.tunnelDataReceived(connId, data, length);
}

void TunnelEngine::onLwipUdpReceived(int connId, const std::string& remoteHost, int remotePort, const uint8_t* data, int length) {
    bytesIn.fetch_add(length);
    callbacks.tunnelUdpDataReceived(connId, remoteHost, remotePort, data, length);
}

void TunnelEngine::onLwipConnectionClosed(int connId, const std::string& reason) {
    callbacks.tunnelConnectionClosed(connId, reason);
    {
        std::lock_guard<std::recursive_mutex> lock(connectionsMutex);
        connections.erase(connId);
    }
}

void TunnelEngine::onLwipError(int connId, const std::string& error) {
    LOGE("lwIP error on connection %d: %s", connId, error.c_str());
    callbacks.tunnelConnectionClosed(connId, error);
    {
        std::lock_guard<std::recursive_mutex> lock(connectionsMutex);
        connections.erase(connId);
    }
}

void TunnelEngine::startLwip() {
    g_lwipTunnel = std::make_unique<LwipTunnel>(this);
    if (!g_lwipTunnel->init(tunFd[1])) {
        LOGE("Failed to initialize lwIP");
        callbacks.stateChanged(4, "Failed to initialize network stack");
        return;
    }

    /* Initial default configuration */
    g_lwipTunnel->configure("10.8.0.2", 24, "10.8.0.1", "8.8.8.8", "1.1.1.1");

    /* Start lwIP event loop thread */
    lwipThread = std::thread(&TunnelEngine::lwipEventLoop, this);
}

void TunnelEngine::stopLwip() {
    if (lwipThread.joinable()) {
        try { lwipThread.join(); } catch (...) {}
    }
    if (g_lwipTunnel) {
        g_lwipTunnel->shutdown();
        g_lwipTunnel.reset();
    }
}

void TunnelEngine::lwipEventLoop() {
    LOGI("lwIP event loop started");

    pthread_setname_np(pthread_self(), "lwip-loop");

    try {
        while (running.load()) {
            /* Process incoming IP packets from tun fd and TCP timers */
            if (g_lwipTunnel) {
                g_lwipTunnel->processIncomingPackets();
                g_lwipTunnel->poll();
            }

            /* Small 1ms sleep to avoid pegging CPU while preserving low latency */
            usleep(1000);
        }
    } catch (const std::exception& e) {
        LOGE("Exception in lwIP event loop: %s", e.what());
    } catch (...) {
        LOGE("Unknown exception in lwIP event loop");
    }

    LOGI("lwIP event loop exited");
}
