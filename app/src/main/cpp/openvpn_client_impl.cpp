/*
 * openvpn_client_impl.cpp - OpenVPN 3 Core client adapter for proxy-only mode
 */

#include "openvpn_client_impl.h"
#include "tunnel_engine.h"

#include <android/log.h>
#include <unistd.h>

#define LOG_TAG "OpenVpnClient"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

OpenVpnClientImpl::OpenVpnClientImpl(TunnelEngine* engine, int tunFd)
    : m_engine(engine), m_tunFd(tunFd) {
}

OpenVpnClientImpl::~OpenVpnClientImpl() {
}

bool OpenVpnClientImpl::tun_builder_new() {
    m_assignedIp.clear();
    m_assignedIpv6.clear();
    m_gateway.clear();
    m_dnsServers.clear();
    m_routes.clear();
    m_excludedRoutes.clear();
    m_rerouteGwIpv4 = false;
    m_rerouteGwIpv6 = false;
    m_rerouteGwFlags = 0;
    m_prefixLength = 24;
    m_prefixLengthIpv6 = 0;
    m_mtu = 1500;
    return true;
}

bool OpenVpnClientImpl::tun_builder_set_layer(int layer) {
    LOGD("tun_builder_set_layer: %d", layer);
    return true;
}

bool OpenVpnClientImpl::tun_builder_set_remote_address(const std::string& address, bool ipv6) {
    LOGD("tun_builder_set_remote_address: %s (ipv6=%d)", address.c_str(), ipv6);
    return true;
}

bool OpenVpnClientImpl::tun_builder_add_address(const std::string& address, int prefix_length,
                                               const std::string& gateway, bool ipv6, bool net30) {
    LOGI("tun_builder_add_address: %s/%d gw=%s (ipv6=%d, net30=%d)",
         address.c_str(), prefix_length, gateway.c_str(), ipv6, net30);
    if (ipv6) {
        m_assignedIpv6 = address;
        m_prefixLengthIpv6 = prefix_length;
    } else {
        m_assignedIp = address;
        m_prefixLength = prefix_length;
        m_gateway = gateway;
    }
    return true;
}

bool OpenVpnClientImpl::tun_builder_set_route_metric_default(int metric) {
    return true;
}

bool OpenVpnClientImpl::tun_builder_reroute_gw(bool ipv4, bool ipv6, unsigned int flags) {
    LOGD("tun_builder_reroute_gw: ipv4=%d ipv6=%d flags=0x%x", ipv4, ipv6, flags);
    m_rerouteGwIpv4 = ipv4;
    m_rerouteGwIpv6 = ipv6;
    m_rerouteGwFlags = flags;
    return true;
}

bool OpenVpnClientImpl::tun_builder_add_route(const std::string& address, int prefix_length,
                                             int metric, bool ipv6) {
    LOGD("tun_builder_add_route: %s/%d metric=%d ipv6=%d", address.c_str(), prefix_length, metric, ipv6);
    m_routes.push_back(address + "/" + std::to_string(prefix_length));
    return true;
}

bool OpenVpnClientImpl::tun_builder_exclude_route(const std::string& address, int prefix_length,
                                                 int metric, bool ipv6) {
    LOGD("tun_builder_exclude_route: %s/%d metric=%d ipv6=%d", address.c_str(), prefix_length, metric, ipv6);
    m_excludedRoutes.push_back(address + "/" + std::to_string(prefix_length));
    return true;
}

bool OpenVpnClientImpl::tun_builder_set_dns_options(const openvpn::DnsOptions& dns) {
    m_dnsServers.clear();
    for (const auto& [priority, srv] : dns.servers) {
        for (const auto& addr : srv.addresses) {
            if (!addr.address.empty()) {
                m_dnsServers.push_back(addr.address);
                LOGI("tun_builder_set_dns_options: added DNS server %s", addr.address.c_str());
            }
        }
    }
    return true;
}

bool OpenVpnClientImpl::tun_builder_set_mtu(int mtu) {
    m_mtu = mtu;
    LOGD("tun_builder_set_mtu: %d", mtu);
    return true;
}

bool OpenVpnClientImpl::tun_builder_set_session_name(const std::string& name) {
    return true;
}

bool OpenVpnClientImpl::tun_builder_add_proxy_bypass(const std::string& bypass_host) {
    return true;
}

bool OpenVpnClientImpl::tun_builder_set_proxy_auto_config_url(const std::string& url) {
    return true;
}

bool OpenVpnClientImpl::tun_builder_set_proxy_http(const std::string& host, int port) {
    return true;
}

bool OpenVpnClientImpl::tun_builder_set_proxy_https(const std::string& host, int port) {
    return true;
}

bool OpenVpnClientImpl::tun_builder_add_wins_server(const std::string& address) {
    return true;
}

bool OpenVpnClientImpl::tun_builder_set_allow_family(int af, bool allow) {
    return true;
}

bool OpenVpnClientImpl::tun_builder_set_allow_local_dns(bool allow) {
    return true;
}

int OpenVpnClientImpl::tun_builder_establish() {
    if (m_tunFd >= 0) {
        // Proxy Mode: Use socketpair endpoint
        int dupFd = dup(m_tunFd);
        LOGI("tun_builder_establish [Proxy Mode]: dup(%d) = %d. IP=%s/%d GW=%s",
             m_tunFd, dupFd, m_assignedIp.c_str(), m_prefixLength, m_gateway.c_str());

        if (m_engine) {
            m_engine->onTunnelEstablished(m_assignedIp, m_prefixLength, m_gateway, m_dnsServers);
        }
        return dupFd;
    } else {
        // TUN Mode: Request Android VpnService to establish the interface dynamically
        LOGI("tun_builder_establish [TUN Mode]: Requesting VpnService.establish() with IPv4=%s/%d IPv6=%s/%d MTU=%d",
             m_assignedIp.c_str(), m_prefixLength, m_assignedIpv6.c_str(), m_prefixLengthIpv6, m_mtu);

        if (m_engine) {
            int vpnFd = m_engine->callbacks.establishVpn(
                m_assignedIp, m_prefixLength,
                m_assignedIpv6, m_prefixLengthIpv6,
                m_mtu, m_dnsServers, m_routes,
                m_rerouteGwIpv4, m_rerouteGwIpv6, m_excludedRoutes
            );
            LOGI("tun_builder_establish [TUN Mode]: Received vpnFd=%d (rerouteIpv4=%d, rerouteIpv6=%d, routes=%zu, excluded=%zu)",
                 vpnFd, m_rerouteGwIpv4 ? 1 : 0, m_rerouteGwIpv6 ? 1 : 0, m_routes.size(), m_excludedRoutes.size());
            return vpnFd;
        }
        return -1;
    }
}

bool OpenVpnClientImpl::tun_builder_persist() {
    return false;
}

bool OpenVpnClientImpl::pause_on_connection_timeout() {
    return false;
}

bool OpenVpnClientImpl::socket_protect(openvpn_io::detail::socket_type socket, std::string remote, bool ipv6) {
    if (m_engine) {
        bool ok = m_engine->callbacks.protectSocket((int)socket);
        LOGI("socket_protect(%d, remote=%s, tunFd=%d) -> %d", (int)socket, remote.c_str(), m_tunFd, ok ? 1 : 0);
        // In TUN mode (m_tunFd < 0), socket protection is mandatory so return ok.
        // In Proxy mode (m_tunFd >= 0), attempt protection in case a system VPN was active,
        // but if protect() returns false, do not fail OpenVPN since no VPN interface is active.
        if (m_tunFd < 0) {
            return ok;
        }
    }
    return true;
}

void OpenVpnClientImpl::event(const openvpn::ClientAPI::Event& ev) {
    LOGI("[OpenVPN3 Event] %s: %s (error=%d, fatal=%d)", ev.name.c_str(), ev.info.c_str(), ev.error, ev.fatal);
    if (!m_engine) return;

    if (ev.name == "CONNECTED") {
        m_engine->callbacks.stateChanged(2, "Connected");
    } else if (ev.name == "RECONNECTING" || ev.name == "CLIENT_RESTART") {
        // Non-fatal reconnecting events: mapped to state 3 (Reconnecting)
        m_engine->callbacks.stateChanged(3, "Reconnecting: " + ev.info);
    } else if (ev.name == "DISCONNECTED") {
        m_engine->callbacks.stateChanged(0, "Disconnected");
    } else if (ev.name == "RESOLVE") {
        m_engine->callbacks.stateChanged(1, "Resolving server address...");
    } else if (ev.name == "WAIT") {
        m_engine->callbacks.stateChanged(1, "Waiting for server response...");
    } else if (ev.name == "AUTH") {
        m_engine->callbacks.stateChanged(1, "Authenticating with server...");
    } else if (ev.name == "GET_CONFIG") {
        m_engine->callbacks.stateChanged(1, "Receiving configuration from server...");
    } else if (ev.name == "ASSIGN_IP") {
        m_engine->callbacks.stateChanged(1, "Assigning IP address...");
    } else if (ev.error) {
        // OpenVPN 3 Core classifies non-fatal errors (TRANSPORT_ERROR, TUN_ERROR) as recoverable,
        // triggering automatic reconnection within the core.
        // Only fatal events (ev.fatal == true, AUTH_FAILED, CERT_VERIFY_FAIL) should tear down the session (State 4 Error).
        if (ev.fatal || ev.name == "AUTH_FAILED" || ev.name == "CERT_VERIFY_FAIL") {
            m_engine->callbacks.stateChanged(4, "FATAL: " + ev.name + ": " + ev.info);
        } else if (ev.name == "TRANSPORT_ERROR" || ev.name == "TUN_ERROR") {
            LOGI("[OpenVPN3] Non-fatal error %s, signaling state 3 Reconnecting to allow core automatic recovery", ev.name.c_str());
            m_engine->callbacks.stateChanged(3, "Reconnecting (" + ev.name + "): " + ev.info);
        } else {
            m_engine->callbacks.stateChanged(4, "ERROR: " + ev.name + ": " + ev.info);
        }
    }
}

void OpenVpnClientImpl::acc_event(const openvpn::ClientAPI::AppCustomControlMessageEvent& ev) {
}

void OpenVpnClientImpl::log(const openvpn::ClientAPI::LogInfo& li) {
    LOGD("[OpenVPN3] %s", li.text.c_str());
    if (m_engine) {
        m_engine->callbacks.log(3, "%s", li.text.c_str());
    }
}

void OpenVpnClientImpl::external_pki_cert_request(openvpn::ClientAPI::ExternalPKICertRequest& req) {
    req.error = true;
    req.errorText = "External PKI not supported";
}

void OpenVpnClientImpl::external_pki_sign_request(openvpn::ClientAPI::ExternalPKISignRequest& req) {
    req.error = true;
    req.errorText = "External PKI not supported";
}
