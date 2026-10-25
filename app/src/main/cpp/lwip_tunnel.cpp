/*
 * lwip_tunnel.cpp - lwIP userspace TCP/IP stack integration
 *
 * This manages the virtual network interface that connects to the OpenVPN tunnel
 * via a socketpair. All proxy TCP connections flow through this stack.
 */

#include "lwip_tunnel.h"
#include "tunnel_engine.h"

#include <android/log.h>
#include <sys/socket.h>
#include <unistd.h>
#include <poll.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>
#include <arpa/inet.h>
#include <condition_variable>
#include <chrono>

#define LOG_TAG "LwipTunnel"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

/* ============================================================
 * LwipTunnel
 * ============================================================ */

extern "C" u32_t sys_now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<u32_t>(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

LwipTunnel* g_activeLwipTunnel = nullptr;

LwipTunnel::LwipTunnel(TunnelEngine* eng) : engine(eng) {
    g_activeLwipTunnel = this;
    memset(&tunNetif, 0, sizeof(tunNetif));
}

LwipTunnel::~LwipTunnel() {
    if (g_activeLwipTunnel == this) {
        g_activeLwipTunnel = nullptr;
    }
    shutdown();
}

bool LwipTunnel::init(int fd) {
    tunFd = fd;

    /* Make tun fd non-blocking for poll-based event loop */
    int flags = fcntl(tunFd, F_GETFL, 0);
    fcntl(tunFd, F_SETFL, flags | O_NONBLOCK);

    /* Initialize lwIP stack once per process */
    static std::once_flag s_lwipInitOnce;
    std::call_once(s_lwipInitOnce, []() {
        lwip_init();
        LOGI("lwIP initialized (version %s)", LWIP_VERSION_STRING);
    });
    initialized = true;
    return true;
}

void LwipTunnel::configure(const std::string& localIp, int prefixLen,
                           const std::string& gateway,
                           const std::string& dns1, const std::string& dns2) {
    std::lock_guard<std::recursive_mutex> lock(lwipMutex);
    if (!initialized) return;

    ip4_addr_t ip, mask, gw;

    ip4addr_aton(localIp.c_str(), &ip);
    ip4addr_aton(gateway.c_str(), &gw);

    /* Calculate netmask from prefix length */
    uint32_t maskVal = (prefixLen <= 0 || prefixLen > 32) ? 0 : (~0U << (32 - prefixLen));
    ip4_addr_set_u32(&mask, htonl(maskVal));

    /* Add or update the virtual network interface */
    tunNetif.state = this;  /* Store LwipTunnel* for callbacks */

    if (tunNetif.flags & NETIF_FLAG_UP) {
        netif_set_addr(&tunNetif, &ip, &mask, &gw);
    } else {
        netif_add(&tunNetif, &ip, &mask, &gw, this, netifInitFunc, ip_input);
        netif_set_default(&tunNetif);
        netif_set_up(&tunNetif);
        netif_set_link_up(&tunNetif);
    }

    /* Configure DNS servers */
    if (!dns1.empty()) {
        ip_addr_t dnsAddr;
        ipaddr_aton(dns1.c_str(), &dnsAddr);
        dns_setserver(0, &dnsAddr);
    }
    if (!dns2.empty()) {
        ip_addr_t dnsAddr;
        ipaddr_aton(dns2.c_str(), &dnsAddr);
        dns_setserver(1, &dnsAddr);
    }

    LOGI("lwIP configured: IP=%s/%d GW=%s DNS=%s,%s",
         localIp.c_str(), prefixLen, gateway.c_str(),
         dns1.c_str(), dns2.c_str());
}

void LwipTunnel::setDnsServers(const std::string& dns1, const std::string& dns2) {
    std::lock_guard<std::recursive_mutex> lock(lwipMutex);
    if (!initialized) return;

    if (!dns1.empty()) {
        ip_addr_t dnsAddr;
        ipaddr_aton(dns1.c_str(), &dnsAddr);
        dns_setserver(0, &dnsAddr);
    }
    if (!dns2.empty()) {
        ip_addr_t dnsAddr;
        ipaddr_aton(dns2.c_str(), &dnsAddr);
        dns_setserver(1, &dnsAddr);
    }

    LOGI("lwIP DNS servers updated dynamically: DNS1=%s, DNS2=%s",
         dns1.c_str(), dns2.c_str());
}

void LwipTunnel::poll() {
    std::lock_guard<std::recursive_mutex> lock(lwipMutex);
    if (!initialized) return;
    sys_check_timeouts();
}

void LwipTunnel::processIncomingPackets() {
    std::lock_guard<std::recursive_mutex> lock(lwipMutex);
    if (!initialized || tunFd < 0) return;

    /* Non-blocking read of IP packets from the tun fd */
    uint8_t buffer[4096];

    for (int i = 0; i < 64; i++) {  /* Process up to 64 packets per call */
        ssize_t len = read(tunFd, buffer, sizeof(buffer));
        if (len <= 0) {
            if (len < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
                LOGE("Error reading from tun fd: %s", strerror(errno));
            }
            break;
        }

        /* Allocate pbuf and feed to lwIP */
        struct pbuf* p = pbuf_alloc(PBUF_RAW, (u16_t)len, PBUF_POOL);
        if (p) {
            pbuf_take(p, buffer, (u16_t)len);
            if (tunNetif.input && tunNetif.input(p, &tunNetif) != ERR_OK) {
                pbuf_free(p);
                LOGW("lwIP rejected incoming packet (len=%zd)", len);
            } else if (!tunNetif.input) {
                pbuf_free(p);
            }
        } else {
            LOGW("pbuf_alloc failed for incoming packet (len=%zd)", len);
        }
    }
}

int LwipTunnel::connectTcp(int connId, const std::string& host, int port) {
    std::lock_guard<std::recursive_mutex> lock(lwipMutex);
    if (!initialized) return -1;

    /* Create TCP PCB */
    struct tcp_pcb* pcb = tcp_new();
    if (!pcb) {
        LOGE("tcp_new() failed for connection %d", connId);
        return -1;
    }

    /* Store connId as argument for callbacks */
    intptr_t connIdPtr = connId;
    tcp_arg(pcb, reinterpret_cast<void*>(connIdPtr));
    tcp_err(pcb, tcpErrCb);
    tcp_recv(pcb, tcpRecvCb);
    tcp_sent(pcb, tcpSentCb);

    /* Store PCB in the connection object */
    TunnelConnection* conn = engine->getConnection(connId);
    if (conn) {
        conn->tcp_pcb = pcb;
    }

    /* Resolve the host and connect */
    ip_addr_t destAddr;

    /* Try parsing as IP address first */
    if (ipaddr_aton(host.c_str(), &destAddr)) {
        /* Direct IP address */
        err_t err = tcp_connect(pcb, &destAddr, (u16_t)port, tcpConnectedCb);
        if (err != ERR_OK) {
            LOGE("tcp_connect failed: %d", err);
            tcp_abort(pcb);
            return -1;
        }
        LOGD("TCP connecting to %s:%d (connId=%d)", host.c_str(), port, connId);
    } else {
        /* Need DNS resolution through the tunnel */
        ip_addr_t resolved;
        err_t dns_err = dns_gethostbyname(host.c_str(), &resolved, dnsFoundCb,
                                           reinterpret_cast<void*>(connIdPtr));
        if (dns_err == ERR_OK) {
            /* Already cached */
            err_t err = tcp_connect(pcb, &resolved, (u16_t)port, tcpConnectedCb);
            if (err != ERR_OK) {
                LOGE("tcp_connect after DNS failed: %d", err);
                tcp_abort(pcb);
                return -1;
            }
        } else if (dns_err == ERR_INPROGRESS) {
            /* DNS query sent through tunnel, will callback when done */
            LOGD("DNS resolution in progress for %s (connId=%d)", host.c_str(), connId);
            /* Store port for use after DNS resolves - use tcp_arg space */
            /* We'll need to complete the connect in dnsFoundCb */
        } else {
            LOGE("DNS lookup failed for %s: %d", host.c_str(), dns_err);
            tcp_abort(pcb);
            return -1;
        }
    }

    return 0;
}

int LwipTunnel::sendTcpData(int connId, const uint8_t* data, int length) {
    std::lock_guard<std::recursive_mutex> lock(lwipMutex);
    TunnelConnection* conn = engine->getConnection(connId);
    if (!conn || !conn->tcp_pcb || conn->closing.load()) return -1;

    struct tcp_pcb* pcb = static_cast<struct tcp_pcb*>(conn->tcp_pcb);

    /* Check available send buffer */
    u16_t available = tcp_sndbuf(pcb);
    int toSend = (length < available) ? length : available;

    if (toSend <= 0) {
        /* Buffer full, queue for later */
        std::lock_guard<std::mutex> lock(conn->sendMutex);
        if (conn->sendBuffer.size() > 1048576) {
            LOGE("Send buffer overflow (>1MB) for connId=%d", connId);
            return -1;
        }
        conn->sendBuffer.insert(conn->sendBuffer.end(), data, data + length);
        return length; /* Report as sent (buffered) */
    }

    err_t err = tcp_write(pcb, data, (u16_t)toSend, TCP_WRITE_FLAG_COPY);
    if (err != ERR_OK) {
        LOGE("tcp_write failed: %d (connId=%d)", err, connId);
        return -1;
    }

    tcp_output(pcb);

    /* Buffer remaining if any */
    if (toSend < length) {
        std::lock_guard<std::mutex> lock(conn->sendMutex);
        if (conn->sendBuffer.size() > 1048576) {
            LOGE("Send buffer overflow (>1MB) for connId=%d", connId);
            return -1;
        }
        conn->sendBuffer.insert(conn->sendBuffer.end(), data + toSend, data + length);
    }

    return length;
}

void LwipTunnel::acknowledgeTcpData(int connId, int length) {
    std::lock_guard<std::recursive_mutex> lock(lwipMutex);
    TunnelConnection* conn = engine->getConnection(connId);
    if (!conn || !conn->tcp_pcb || conn->closing.load()) return;

    struct tcp_pcb* pcb = static_cast<struct tcp_pcb*>(conn->tcp_pcb);
    tcp_recved(pcb, (u16_t)length);
}

void LwipTunnel::closeTcp(int connId) {
    std::lock_guard<std::recursive_mutex> lock(lwipMutex);
    TunnelConnection* conn = engine->getConnection(connId);
    if (!conn) return;

    struct tcp_pcb* pcb = static_cast<struct tcp_pcb*>(conn->tcp_pcb);
    conn->tcp_pcb = nullptr; /* Mark null FIRST to avoid race */

    if (!pcb) return; /* Already freed by lwIP error callback! */

    tcp_arg(pcb, nullptr);
    tcp_recv(pcb, nullptr);
    tcp_err(pcb, nullptr);
    tcp_sent(pcb, nullptr);

    err_t err = tcp_close(pcb);
    if (err != ERR_OK) {
        tcp_abort(pcb);
    }
}

int LwipTunnel::openUdp(int connId) {
    std::lock_guard<std::recursive_mutex> lock(lwipMutex);
    if (!initialized) return -1;

    struct udp_pcb* pcb = udp_new();
    if (!pcb) {
        LOGE("udp_new() failed for connId %d", connId);
        return -1;
    }

    err_t err = udp_bind(pcb, IP_ADDR_ANY, 0);
    if (err != ERR_OK) {
        LOGE("udp_bind failed: %d for connId %d", err, connId);
        udp_remove(pcb);
        return -1;
    }

    intptr_t connIdPtr = connId;
    udp_recv(pcb, udpRecvCb, reinterpret_cast<void*>(connIdPtr));

    TunnelConnection* conn = engine->getConnection(connId);
    if (conn) {
        conn->udp_pcb = pcb;
        conn->is_udp = true;
        conn->connected.store(true);
    }

    LOGD("UDP PCB opened and bound to local port %u (connId=%d)", pcb->local_port, connId);
    return 0;
}

int LwipTunnel::sendUdpData(int connId, const std::string& host, int port, const uint8_t* data, int length) {
    std::lock_guard<std::recursive_mutex> lock(lwipMutex);
    if (!initialized || length < 0) return -1;

    TunnelConnection* conn = engine->getConnection(connId);
    if (!conn || !conn->udp_pcb || conn->closing.load()) return -1;

    struct udp_pcb* pcb = static_cast<struct udp_pcb*>(conn->udp_pcb);

    ip_addr_t destAddr;
    if (!ipaddr_aton(host.c_str(), &destAddr)) {
        LOGW("sendUdpData: invalid destination IP '%s'", host.c_str());
        return -1;
    }

    struct pbuf* p = pbuf_alloc(PBUF_TRANSPORT, (u16_t)length, PBUF_RAM);
    if (!p) {
        LOGE("sendUdpData: pbuf_alloc failed (len=%d)", length);
        return -1;
    }

    if (length > 0) {
        pbuf_take(p, data, (u16_t)length);
    }

    err_t err = udp_sendto_if(pcb, p, &destAddr, (u16_t)port, &tunNetif);
    pbuf_free(p);

    if (err != ERR_OK) {
        LOGE("udp_sendto_if failed: %d (connId=%d, port=%d)", err, connId, port);
        return -1;
    }

    return length;
}

void LwipTunnel::closeUdp(int connId) {
    std::lock_guard<std::recursive_mutex> lock(lwipMutex);
    TunnelConnection* conn = engine->getConnection(connId);
    if (!conn) return;

    struct udp_pcb* pcb = static_cast<struct udp_pcb*>(conn->udp_pcb);
    conn->udp_pcb = nullptr;

    if (!pcb) return;

    udp_recv(pcb, nullptr, nullptr);
    udp_remove(pcb);
    LOGD("UDP PCB closed for connId=%d", connId);
}

struct DnsWaitContext {
    std::mutex mtx;
    std::condition_variable cv;
    bool done = false;
    std::string ip;
};

std::string LwipTunnel::resolveHost(const std::string& hostname) {
    if (!initialized || hostname.empty()) return "";

    ip_addr_t addr;
    /* Try synchronous resolution (cached) */
    {
        std::lock_guard<std::recursive_mutex> lock(lwipMutex);
        err_t err = dns_gethostbyname(hostname.c_str(), &addr, nullptr, nullptr);
        if (err == ERR_OK) {
            char buf[64];
            ipaddr_ntoa_r(&addr, buf, sizeof(buf));
            return std::string(buf);
        }
    }

    auto sharedCtx = std::make_shared<DnsWaitContext>();
    auto* rawCtxPtr = new std::shared_ptr<DnsWaitContext>(sharedCtx);

    {
        std::lock_guard<std::recursive_mutex> lock(lwipMutex);
        err_t err = dns_gethostbyname(hostname.c_str(), &addr, [](const char* name, const ip_addr_t* ipaddr, void* arg) {
            auto* pShared = static_cast<std::shared_ptr<DnsWaitContext>*>(arg);
            if (pShared && *pShared) {
                auto ctx = *pShared;
                {
                    std::lock_guard<std::mutex> lk(ctx->mtx);
                    if (ipaddr) {
                        char buf[64];
                        ipaddr_ntoa_r(ipaddr, buf, sizeof(buf));
                        ctx->ip = buf;
                    }
                    ctx->done = true;
                }
                ctx->cv.notify_one();
            }
            delete pShared;
        }, rawCtxPtr);

        if (err == ERR_OK) {
            delete rawCtxPtr;
            char buf[64];
            ipaddr_ntoa_r(&addr, buf, sizeof(buf));
            return std::string(buf);
        } else if (err != ERR_INPROGRESS) {
            delete rawCtxPtr;
            return "";
        }
    }

    std::unique_lock<std::mutex> lk(sharedCtx->mtx);
    sharedCtx->cv.wait_for(lk, std::chrono::milliseconds(3000), [&sharedCtx] {
        return sharedCtx->done;
    });

    return sharedCtx->ip;
}

void LwipTunnel::shutdown() {
    std::lock_guard<std::recursive_mutex> lock(lwipMutex);
    if (!initialized) return;

    netif_set_down(&tunNetif);
    netif_remove(&tunNetif);

    initialized = false;
    LOGI("lwIP shut down");
}

void LwipTunnel::writeToTun(struct pbuf* p) {
    if (tunFd < 0 || !p) return;

    /* Linearize the pbuf chain if needed */
    uint8_t buffer[4096];
    u16_t totalLen = p->tot_len;
    if (totalLen > sizeof(buffer)) {
        LOGW("Packet too large: %u bytes", totalLen);
        return;
    }

    pbuf_copy_partial(p, buffer, totalLen, 0);

    ssize_t written = write(tunFd, buffer, totalLen);
    if (written < 0) {
        if (errno != EAGAIN && errno != EWOULDBLOCK) {
            LOGE("Error writing to tun fd: %s", strerror(errno));
        }
    }
}

/* ============================================================
 * lwIP static callbacks (trampolines)
 * ============================================================ */

err_t LwipTunnel::netifInitFunc(struct netif* netif) {
    netif->name[0] = 't';
    netif->name[1] = 'u';
    netif->output = netifOutputFunc;
#if LWIP_IPV6
    netif->output_ip6 = netifOutput6Func;
#endif
    netif->mtu = 1500;
    netif->flags = NETIF_FLAG_BROADCAST | NETIF_FLAG_LINK_UP | NETIF_FLAG_UP;
    return ERR_OK;
}

err_t LwipTunnel::netifOutputFunc(struct netif* netif, struct pbuf* p, const ip4_addr_t* ipaddr) {
    LwipTunnel* self = static_cast<LwipTunnel*>(netif->state);
    if (self) {
        self->writeToTun(p);
    }
    return ERR_OK;
}

err_t LwipTunnel::netifOutput6Func(struct netif* netif, struct pbuf* p, const ip6_addr_t* ipaddr) {
    LwipTunnel* self = static_cast<LwipTunnel*>(netif->state);
    if (self) {
        self->writeToTun(p);
    }
    return ERR_OK;
}

err_t LwipTunnel::tcpConnectedCb(void* arg, struct tcp_pcb* tpcb, err_t err) {
    intptr_t connId = reinterpret_cast<intptr_t>(arg);
    if (err != ERR_OK) {
        LOGE("TCP connection failed: %d (connId=%d)", err, (int)connId);
        return ERR_OK;
    }

    LOGD("TCP connected (connId=%d)", (int)connId);

    if (g_activeLwipTunnel && g_activeLwipTunnel->engine) {
        g_activeLwipTunnel->engine->onLwipConnected((int)connId);
    }
    return ERR_OK;
}

err_t LwipTunnel::tcpRecvCb(void* arg, struct tcp_pcb* tpcb, struct pbuf* p, err_t err) {
    intptr_t connId = reinterpret_cast<intptr_t>(arg);

    if (!p) {
        /* Connection closed by remote */
        LOGD("TCP connection closed by remote (connId=%d)", (int)connId);
        if (g_activeLwipTunnel && g_activeLwipTunnel->engine) {
            g_activeLwipTunnel->engine->onLwipConnectionClosed((int)connId, "Remote closed");
        }
        return ERR_OK;
    }

    if (err != ERR_OK) {
        pbuf_free(p);
        return err;
    }

    /* Copy data from pbuf chain */
    uint8_t buffer[32768];
    u16_t totalLen = (p->tot_len < (u16_t)sizeof(buffer)) ? p->tot_len : (u16_t)sizeof(buffer);
    pbuf_copy_partial(p, buffer, totalLen, 0);

    /* Free pbuf memory immediately.
     * Note: We intentionally do NOT call tcp_recved() here.
     * Backpressure flow control: tcp_recved() is invoked via acknowledgeTcpData()
     * after the Kotlin proxy client consumes and flushes the data. */
    pbuf_free(p);

    if (g_activeLwipTunnel && g_activeLwipTunnel->engine) {
        g_activeLwipTunnel->engine->onLwipDataReceived((int)connId, buffer, totalLen);
    }

    return ERR_OK;
}

void LwipTunnel::tcpErrCb(void* arg, err_t err) {
    intptr_t connId = reinterpret_cast<intptr_t>(arg);

    const char* errStr;
    switch (err) {
        case ERR_ABRT: errStr = "Connection aborted"; break;
        case ERR_RST: errStr = "Connection reset"; break;
        case ERR_CLSD: errStr = "Connection closed"; break;
        case ERR_CONN: errStr = "Connection refused"; break;
        case ERR_TIMEOUT: errStr = "Connection timed out"; break;
        default: errStr = "Unknown error"; break;
    }

    LOGE("TCP error on connId=%d: %s (%d)", (int)connId, errStr, err);

    if (g_activeLwipTunnel && g_activeLwipTunnel->engine) {
        /* PCB is already freed by lwIP when error callback is called */
        TunnelConnection* conn = g_activeLwipTunnel->engine->getConnection((int)connId);
        if (conn) {
            conn->tcp_pcb = nullptr; /* PCB already freed */
        }
        g_activeLwipTunnel->engine->onLwipError((int)connId, errStr);
    }
}

err_t LwipTunnel::tcpSentCb(void* arg, struct tcp_pcb* tpcb, u16_t len) {
    intptr_t connId = reinterpret_cast<intptr_t>(arg);

    /* Try to flush buffered data */
    if (g_activeLwipTunnel && g_activeLwipTunnel->engine) {
        TunnelConnection* conn = g_activeLwipTunnel->engine->getConnection((int)connId);
        if (conn) {
            std::lock_guard<std::mutex> lock(conn->sendMutex);
            if (!conn->sendBuffer.empty()) {
                u16_t available = tcp_sndbuf(tpcb);
                int toSend = (conn->sendBuffer.size() < available)
                             ? (int)conn->sendBuffer.size() : available;
                if (toSend > 0) {
                    err_t err = tcp_write(tpcb, conn->sendBuffer.data(),
                                          (u16_t)toSend, TCP_WRITE_FLAG_COPY);
                    if (err == ERR_OK) {
                        conn->sendBuffer.erase(conn->sendBuffer.begin(),
                                               conn->sendBuffer.begin() + toSend);
                        tcp_output(tpcb);
                    }
                }
            }
        }
    }

    return ERR_OK;
}

void LwipTunnel::udpRecvCb(void* arg, struct udp_pcb* pcb, struct pbuf* p,
                          const ip_addr_t* addr, u16_t port) {
    if (!p) return;

    intptr_t connId = reinterpret_cast<intptr_t>(arg);

    char ipStr[64] = {0};
    if (addr) {
        ipaddr_ntoa_r(addr, ipStr, sizeof(ipStr));
    }

    uint8_t buffer[65536];
    u16_t totalLen = (p->tot_len < (u16_t)sizeof(buffer)) ? p->tot_len : (u16_t)sizeof(buffer);
    pbuf_copy_partial(p, buffer, totalLen, 0);

    pbuf_free(p);

    if (g_activeLwipTunnel && g_activeLwipTunnel->engine) {
        g_activeLwipTunnel->engine->onLwipUdpReceived((int)connId, ipStr, (int)port, buffer, (int)totalLen);
    }
}

void LwipTunnel::dnsFoundCb(const char* name, const ip_addr_t* ipaddr, void* callback_arg) {
    if (!g_activeLwipTunnel) return;
    std::lock_guard<std::recursive_mutex> lock(g_activeLwipTunnel->lwipMutex);

    intptr_t connId = reinterpret_cast<intptr_t>(callback_arg);

    if (!ipaddr) {
        LOGE("DNS resolution failed for %s (connId=%d)", name ? name : "?", (int)connId);
        if (g_activeLwipTunnel->engine) {
            TunnelConnection* conn = g_activeLwipTunnel->engine->getConnection((int)connId);
            if (conn && conn->tcp_pcb) {
                struct tcp_pcb* pcb = static_cast<struct tcp_pcb*>(conn->tcp_pcb);
                conn->tcp_pcb = nullptr;
                tcp_abort(pcb);
            }
            g_activeLwipTunnel->engine->onLwipError((int)connId, "DNS resolution failed");
        }
        return;
    }

    char addrBuf[64];
    ipaddr_ntoa_r(ipaddr, addrBuf, sizeof(addrBuf));
    LOGD("DNS resolved %s -> %s (connId=%d)", name ? name : "?", addrBuf, (int)connId);

    /* Complete the TCP connection */
    if (g_activeLwipTunnel && g_activeLwipTunnel->engine) {
        TunnelConnection* conn = g_activeLwipTunnel->engine->getConnection((int)connId);
        if (conn && conn->tcp_pcb) {
            struct tcp_pcb* pcb = static_cast<struct tcp_pcb*>(conn->tcp_pcb);
            err_t err = tcp_connect(pcb, ipaddr, (u16_t)conn->port, tcpConnectedCb);
            if (err != ERR_OK) {
                LOGE("tcp_connect after DNS failed: %d (connId=%d)", err, (int)connId);
                tcp_abort(pcb);
                conn->tcp_pcb = nullptr;
                g_activeLwipTunnel->engine->onLwipError((int)connId, "Connection failed after DNS");
            }
        }
    }
}
