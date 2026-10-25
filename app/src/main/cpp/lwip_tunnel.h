/*
 * lwip_tunnel.h - lwIP integration for the tunnel engine
 * Manages the userspace TCP/IP stack that processes IP packets from/to the OpenVPN tunnel
 */

#ifndef LWIP_TUNNEL_H
#define LWIP_TUNNEL_H

#include "lwip/init.h"
#include "lwip/netif.h"
#include "lwip/tcp.h"
#include "lwip/udp.h"
#include "lwip/dns.h"
#include "lwip/timeouts.h"
#include "lwip/ip_addr.h"
#include "lwip/pbuf.h"
#include "lwip/ip4.h"
#include "lwip/ip6.h"

#include <string>
#include <mutex>
#include <atomic>

/* Forward declaration */
class TunnelEngine;

class LwipTunnel;
extern LwipTunnel* g_activeLwipTunnel;

/*
 * LwipTunnel manages the lwIP stack and its virtual network interface.
 * IP packets flow through a socketpair to/from the OpenVPN core.
 */
class LwipTunnel {
public:
    LwipTunnel(TunnelEngine* engine);
    ~LwipTunnel();

    /* Initialize lwIP and create virtual network interface */
    bool init(int tunFd);

    /* Process pending events - call from event loop */
    void poll();

    /* Configure the virtual interface with parameters from OpenVPN server */
    void configure(const std::string& localIp, int prefixLen,
                   const std::string& gateway,
                   const std::string& dns1, const std::string& dns2);

    /* Dynamically update DNS servers without reconfiguring the virtual interface */
    void setDnsServers(const std::string& dns1, const std::string& dns2);

    /* Create a new outbound TCP connection through the tunnel */
    int connectTcp(int connId, const std::string& host, int port);

    /* Send data through an existing TCP connection */
    int sendTcpData(int connId, const uint8_t* data, int length);

    /* Acknowledge received data to advance TCP receive window (backpressure flow control) */
    void acknowledgeTcpData(int connId, int length);

    /* Close a TCP connection */
    void closeTcp(int connId);

    /* Create a new UDP association through the tunnel */
    int openUdp(int connId);

    /* Send UDP data to destination through the tunnel */
    int sendUdpData(int connId, const std::string& host, int port, const uint8_t* data, int length);

    /* Close a UDP association */
    void closeUdp(int connId);

    /* Resolve hostname using tunnel DNS */
    std::string resolveHost(const std::string& hostname);

    /* Shutdown lwIP */
    void shutdown();

    /* Read IP packets from tun fd and inject into lwIP */
    void processIncomingPackets();

private:
    TunnelEngine* engine;
    struct netif tunNetif;
    int tunFd = -1;
    bool initialized = false;
    std::recursive_mutex lwipMutex;

    /* Static callbacks for lwIP (trampoline to instance methods) */
    static err_t netifInitFunc(struct netif* netif);
    static err_t netifOutputFunc(struct netif* netif, struct pbuf* p, const ip4_addr_t* ipaddr);
    static err_t netifOutput6Func(struct netif* netif, struct pbuf* p, const ip6_addr_t* ipaddr);
    static err_t tcpConnectedCb(void* arg, struct tcp_pcb* tpcb, err_t err);
    static err_t tcpRecvCb(void* arg, struct tcp_pcb* tpcb, struct pbuf* p, err_t err);
    static void tcpErrCb(void* arg, err_t err);
    static err_t tcpSentCb(void* arg, struct tcp_pcb* tpcb, u16_t len);
    static void udpRecvCb(void* arg, struct udp_pcb* pcb, struct pbuf* p, const ip_addr_t* addr, u16_t port);
    static void dnsFoundCb(const char* name, const ip_addr_t* ipaddr, void* callback_arg);

    /* Write IP packet to tun fd (goes to OpenVPN core for encryption) */
    void writeToTun(struct pbuf* p);
};

#endif /* LWIP_TUNNEL_H */
