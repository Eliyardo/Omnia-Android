cmake_minimum_required(VERSION 3.22.1)
project("omnia" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fstack-protector-strong -D_FORTIFY_SOURCE=2 -fno-strict-overflow")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fexceptions -frtti -Wall -Wno-unused-parameter -fstack-protector-strong -D_FORTIFY_SOURCE=2 -fno-strict-overflow")

# ============================================================
# OpenVPN 3 & Third-Party Definitions
# ============================================================
add_definitions(
    -DASIO_STANDALONE
    -DUSE_ASIO
    -DUSE_OPENSSL
    -DHAVE_LZ4
    -DOPENVPN_PLATFORM_ANDROID
    -DOPENVPN_IO_REQUIRES_STOP
    -DOPENVPN_ALLOW_INSECURE_CERTPROFILE
)

# OpenSSL KDAB prebuilts
set(OPENSSL_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/android_openssl/ssl_3)
set(OPENSSL_INCLUDE_DIR ${OPENSSL_ROOT}/include)
set(OPENSSL_LIB_DIR ${OPENSSL_ROOT}/${ANDROID_ABI})

# ============================================================
# lwIP Configuration
# ============================================================
set(LWIP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lwip/src)

set(LWIP_SOURCES
    ${LWIP_DIR}/core/init.c
    ${LWIP_DIR}/core/def.c
    ${LWIP_DIR}/core/dns.c
    ${LWIP_DIR}/core/inet_chksum.c
    ${LWIP_DIR}/core/ip.c
    ${LWIP_DIR}/core/mem.c
    ${LWIP_DIR}/core/memp.c
    ${LWIP_DIR}/core/netif.c
    ${LWIP_DIR}/core/pbuf.c
    ${LWIP_DIR}/core/raw.c
    ${LWIP_DIR}/core/stats.c
    ${LWIP_DIR}/core/sys.c
    ${LWIP_DIR}/core/altcp.c
    ${LWIP_DIR}/core/altcp_alloc.c
    ${LWIP_DIR}/core/altcp_tcp.c
    ${LWIP_DIR}/core/tcp.c
    ${LWIP_DIR}/core/tcp_in.c
    ${LWIP_DIR}/core/tcp_out.c
    ${LWIP_DIR}/core/timeouts.c
    ${LWIP_DIR}/core/udp.c
    ${LWIP_DIR}/core/ipv4/autoip.c
    ${LWIP_DIR}/core/ipv4/dhcp.c
    ${LWIP_DIR}/core/ipv4/icmp.c
    ${LWIP_DIR}/core/ipv4/igmp.c
    ${LWIP_DIR}/core/ipv4/ip4.c
    ${LWIP_DIR}/core/ipv4/ip4_addr.c
    ${LWIP_DIR}/core/ipv4/ip4_frag.c
    ${LWIP_DIR}/core/ipv6/dhcp6.c
    ${LWIP_DIR}/core/ipv6/icmp6.c
    ${LWIP_DIR}/core/ipv6/inet6.c
    ${LWIP_DIR}/core/ipv6/ip6.c
    ${LWIP_DIR}/core/ipv6/ip6_addr.c
    ${LWIP_DIR}/core/ipv6/ip6_frag.c
    ${LWIP_DIR}/core/ipv6/mld6.c
    ${LWIP_DIR}/core/ipv6/nd6.c
)

# ============================================================
# OpenVPN 3 Core & dependencies
# ============================================================
set(OPENVPN3_DIR ${CMAKE_CURRENT_SOURCE_DIR}/openvpn3)
set(OPENVPN3_SOURCES
    ${OPENVPN3_DIR}/openvpn/crypto/data_epoch.cpp
    ${OPENVPN3_DIR}/client/ovpncli.cpp
)

set(LZ4_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lz4)
set(LZ4_SOURCES
    ${LZ4_DIR}/lib/lz4.c
)

set(FMT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/fmt)
set(FMT_SOURCES
    ${FMT_DIR}/src/format.cc
    ${FMT_DIR}/src/os.cc
)

# ============================================================
# Application native library
# ============================================================
add_library(omnia SHARED
    tunnel_jni.cpp
    tunnel_engine.cpp
    tunnel_engine.h
    openvpn_client_impl.cpp
    openvpn_client_impl.h
    lwip_tunnel.cpp
    lwip_tunnel.h
    ${LWIP_SOURCES}
    ${LZ4_SOURCES}
    ${FMT_SOURCES}
    ${OPENVPN3_SOURCES}
)

target_include_directories(omnia PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/lwip_config
    ${LWIP_DIR}/include
    ${OPENVPN3_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/asio/asio/include
    ${LZ4_DIR}/lib
    ${FMT_DIR}/include
    ${OPENSSL_INCLUDE_DIR}
)

find_library(log-lib log)
find_library(z-lib z)

target_link_libraries(omnia
    ${OPENSSL_LIB_DIR}/libssl.a
    ${OPENSSL_LIB_DIR}/libcrypto.a
    ${log-lib}
    ${z-lib}
)

# Suppress third-party warnings
set_source_files_properties(${LWIP_SOURCES} ${LZ4_SOURCES} ${FMT_SOURCES} ${OPENVPN3_SOURCES} PROPERTIES COMPILE_FLAGS "-w")
