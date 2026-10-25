/*
 * tunnel_engine.h - Core tunnel engine that bridges lwIP with the OpenVPN tunnel
 *
 * Architecture:
 *   Proxy (Kotlin) <-> JNI <-> TunnelEngine <-> lwIP <-> socketpair <-> OpenVPN 3 Core
 */

#ifndef TUNNEL_ENGINE_H
#define TUNNEL_ENGINE_H

#include <jni.h>
#include <string>
#include <memory>
#include <atomic>
#include <mutex>
#include <unordered_map>
#include <functional>
#include <thread>
#include <vector>

/* Forward declarations */
struct TunnelConnection;
class OpenVpnClientImpl;

/*
 * Callback interface from native -> Java
 */
struct TunnelCallbacks {
    JavaVM* jvm = nullptr;
    jobject engineObj = nullptr;  /* Global ref to TunnelEngine instance */

    jmethodID onVpnStateChanged = nullptr;
    jmethodID onTunnelConnected = nullptr;
    jmethodID onTunnelDataReceived = nullptr;
    jmethodID onTunnelUdpDataReceived = nullptr;
    jmethodID onTunnelConnectionClosed = nullptr;
    jmethodID onStatsUpdated = nullptr;
    jmethodID onLog = nullptr;
    jmethodID onProtectSocket = nullptr;
    jmethodID onEstablishVpn = nullptr;

    JNIEnv* getEnv();
    void log(int level, const char* fmt, ...);
    void stateChanged(int state, const std::string& message);
    void tunnelConnected(int connId);
    void tunnelDataReceived(int connId, const uint8_t* data, int length);
    void tunnelUdpDataReceived(int connId, const std::string& remoteHost, int remotePort, const uint8_t* data, int length);
    void tunnelConnectionClosed(int connId, const std::string& reason);
    void statsUpdated(uint64_t bytesIn, uint64_t bytesOut);
    bool protectSocket(int socket);
    int establishVpn(const std::string& ip, int prefix,
                     const std::string& ipv6, int prefixIpv6,
                     int mtu,
                     const std::vector<std::string>& dns,
                     const std::vector<std::string>& routes,
                     bool rerouteGwIpv4,
                     bool rerouteGwIpv6,
                     const std::vector<std::string>& excludedRoutes);
};

/*
 * Represents a single proxied TCP connection through the tunnel
 */
struct TunnelConnection {
    int id = -1;
    std::string host;
    int port = 0;

    /* lwIP TCP PCB pointer (stored as void* to avoid lwIP header dep here) */
    void* tcp_pcb = nullptr;
    /* lwIP UDP PCB pointer */
    void* udp_pcb = nullptr;
    bool is_udp = false;

    /* Buffered data from proxy waiting to be sent through lwIP */
    std::vector<uint8_t> sendBuffer;
    std::mutex sendMutex;

    /* State */
    std::atomic<bool> connected{false};
    std::atomic<bool> closing{false};

    long callbackId = 0;
};

/*
 * Main tunnel engine
 */
class TunnelEngine {
public:
    TunnelEngine();
    ~TunnelEngine();

    bool initialize(JNIEnv* env, jobject engineObj);
    int connect(const std::string& profile, const std::string& username, const std::string& password,
                bool isTunMode = false,
                const std::string& securityLevel = "legacy",
                bool enforceTls13 = false,
                const std::string& protoOverride = "adaptive",
                int connTimeout = 60,
                bool ipv6Enabled = true,
                bool bypassLanTun = true,
                const std::string& upstreamProxyHost = "",
                int upstreamProxyPort = 0,
                const std::string& upstreamProxyUser = "",
                const std::string& upstreamProxyPass = "",
                bool upstreamProxyAllowCleartext = true,
                const std::string& deviceId = "");
    void disconnect();
    void destroy();
    void setCustomDns(const std::string& dns1, const std::string& dns2) {
        customDns1 = dns1;
        customDns2 = dns2;
    }

    int openConnection(const std::string& host, int port, long callbackId);
    int sendData(int connId, const uint8_t* data, int offset, int length);
    void acknowledgeData(int connId, int length);
    void closeConnection(int connId);

    int openUdpConnection(long callbackId);
    int sendUdpData(int connId, const std::string& host, int port, const uint8_t* data, int offset, int length);
    void closeUdpConnection(int connId);

    std::string resolveHostname(const std::string& hostname);

    /* Called by lwIP callbacks */
    void onLwipConnected(int connId);
    void onLwipDataReceived(int connId, const uint8_t* data, int length);
    void onLwipUdpReceived(int connId, const std::string& remoteHost, int remotePort, const uint8_t* data, int length);
    void onLwipConnectionClosed(int connId, const std::string& reason);
    void onLwipError(int connId, const std::string& error);

    /* Called when OpenVPN establishes the tunnel interface */
    void onTunnelEstablished(const std::string& ip, int prefix, const std::string& gw,
                             const std::vector<std::string>& dns);

    TunnelConnection* getConnection(int connId);

    TunnelCallbacks callbacks;

private:
    std::atomic<bool> running{false};
    std::atomic<int> nextConnId{1};

    std::recursive_mutex connectionsMutex;
    std::unordered_map<int, std::unique_ptr<TunnelConnection>> connections;

    /* Transport thread for lwIP event loop */
    std::thread lwipThread;
    std::string customDns1;
    std::string customDns2;

    int tunFd[2] = {-1, -1};  /* socketpair: [0]=core side, [1]=lwip side */
    int vpnModeFd = -1;       /* Android OS tun interface fd (when in TUN mode) */

    /* OpenVPN 3 Core client and thread */
    std::unique_ptr<OpenVpnClientImpl> ovpnClient;
    std::thread ovpnThread;
    void runOpenVpn(const std::string& profile, const std::string& username, const std::string& password,
                    const std::string& securityLevel, bool enforceTls13,
                    const std::string& protoOverride, int connTimeout,
                    bool ipv6Enabled, bool bypassLanTun,
                    const std::string& upstreamProxyHost, int upstreamProxyPort,
                    const std::string& upstreamProxyUser, const std::string& upstreamProxyPass,
                    bool upstreamProxyAllowCleartext,
                    const std::string& deviceId);

    /* Traffic stats */
    std::atomic<uint64_t> bytesIn{0};
    std::atomic<uint64_t> bytesOut{0};
    std::thread statsThread;
    void statsLoop();

    void lwipEventLoop();
    void startLwip();
    void stopLwip();
};

#endif /* TUNNEL_ENGINE_H */
