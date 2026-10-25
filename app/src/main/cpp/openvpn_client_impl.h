/*
 * openvpn_client_impl.h - OpenVPN 3 Core client adapter for proxy-only mode
 */

#ifndef OPENVPN_CLIENT_IMPL_H
#define OPENVPN_CLIENT_IMPL_H

#include <string>
#include <vector>
#include <memory>

#include <client/ovpncli.hpp>

class TunnelEngine;

class OpenVpnClientImpl : public openvpn::ClientAPI::OpenVPNClient {
public:
    OpenVpnClientImpl(TunnelEngine* engine, int tunFd);
    ~OpenVpnClientImpl() override;

    // TunBuilderBase overrides
    bool tun_builder_new() override;
    bool tun_builder_set_layer(int layer) override;
    bool tun_builder_set_remote_address(const std::string& address, bool ipv6) override;
    bool tun_builder_add_address(const std::string& address, int prefix_length,
                                 const std::string& gateway, bool ipv6, bool net30) override;
    bool tun_builder_set_route_metric_default(int metric) override;
    bool tun_builder_reroute_gw(bool ipv4, bool ipv6, unsigned int flags) override;
    bool tun_builder_add_route(const std::string& address, int prefix_length,
                               int metric, bool ipv6) override;
    bool tun_builder_exclude_route(const std::string& address, int prefix_length,
                                   int metric, bool ipv6) override;
    bool tun_builder_set_dns_options(const openvpn::DnsOptions& dns) override;
    bool tun_builder_set_mtu(int mtu) override;
    bool tun_builder_set_session_name(const std::string& name) override;
    bool tun_builder_add_proxy_bypass(const std::string& bypass_host) override;
    bool tun_builder_set_proxy_auto_config_url(const std::string& url) override;
    bool tun_builder_set_proxy_http(const std::string& host, int port) override;
    bool tun_builder_set_proxy_https(const std::string& host, int port) override;
    bool tun_builder_add_wins_server(const std::string& address) override;
    bool tun_builder_set_allow_family(int af, bool allow) override;
    bool tun_builder_set_allow_local_dns(bool allow) override;
    int tun_builder_establish() override;
    bool tun_builder_persist() override;

    // OpenVPNClient pure virtual / callback overrides
    bool pause_on_connection_timeout() override;
    bool socket_protect(openvpn_io::detail::socket_type socket, std::string remote, bool ipv6) override;
    void event(const openvpn::ClientAPI::Event& ev) override;
    void acc_event(const openvpn::ClientAPI::AppCustomControlMessageEvent& ev) override;
    void log(const openvpn::ClientAPI::LogInfo& li) override;
    void external_pki_cert_request(openvpn::ClientAPI::ExternalPKICertRequest& req) override;
    void external_pki_sign_request(openvpn::ClientAPI::ExternalPKISignRequest& req) override;

private:
    TunnelEngine* m_engine;
    int m_tunFd;

    std::string m_assignedIp;
    int m_prefixLength = 24;
    std::string m_assignedIpv6;
    int m_prefixLengthIpv6 = 0;
    std::string m_gateway;
    std::vector<std::string> m_dnsServers;
    std::vector<std::string> m_routes;
    std::vector<std::string> m_excludedRoutes;
    bool m_rerouteGwIpv4 = false;
    bool m_rerouteGwIpv6 = false;
    unsigned int m_rerouteGwFlags = 0;
    int m_mtu = 1500;
};

#endif // OPENVPN_CLIENT_IMPL_H
