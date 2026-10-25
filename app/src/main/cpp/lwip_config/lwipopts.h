/*
 * lwIP configuration for Omnia
 * Userspace TCP/IP stack configuration - NO_SYS mode
 */

#ifndef LWIPOPTS_H
#define LWIPOPTS_H

/* --- Core lwIP settings for NO_SYS (bare-metal / single-thread event loop) --- */
#define NO_SYS                      1
#define LWIP_NOASSERT               1
#define LWIP_SOCKET                 0
#define LWIP_NETCONN                0
#define SYS_LIGHTWEIGHT_PROT        0
#define LWIP_ARP                    0
#define LWIP_ETHERNET               0

/* --- Memory configuration --- */
#define MEM_ALIGNMENT               4
#define MEM_SIZE                    (1024 * 1024)   /* 1 MB heap */
#define MEMP_NUM_PBUF               512
#define MEMP_NUM_UDP_PCB            128
#define MEMP_NUM_TCP_PCB            256
#define MEMP_NUM_TCP_PCB_LISTEN     16
#define MEMP_NUM_TCP_SEG            1024
#define MEMP_NUM_REASSDATA          16
#define IP_REASS_MAX_PBUFS          64
#define MEMP_NUM_SYS_TIMEOUT        32

/* --- Pbuf settings --- */
#define PBUF_POOL_SIZE              512
#define PBUF_POOL_BUFSIZE           1600

/* --- TCP settings --- */
#define LWIP_TCP                    1
#define TCP_MSS                     1460
#define TCP_SND_BUF                 (64 * TCP_MSS)
#define TCP_SND_QUEUELEN            (4 * TCP_SND_BUF / TCP_MSS)
#define TCP_WND                     (64 * TCP_MSS)
#define TCP_MAXRTX                  12
#define TCP_SYNMAXRTX               6
#define TCP_QUEUE_OOSEQ             1
#define TCP_OVERSIZE                TCP_MSS
#define LWIP_TCP_SACK_OUT           1
#define LWIP_WND_SCALE              1
#define TCP_RCV_SCALE               4
#define LWIP_TCP_TIMESTAMPS         0

/* --- UDP settings --- */
#define LWIP_UDP                    1

/* --- IP settings --- */
#define LWIP_IPV4                   1
#define LWIP_IPV6                   1
#define LWIP_ICMP                   1
#define LWIP_RAW                    1
#define IP_FORWARD                  0
#define IP_REASSEMBLY               1
#define IP_FRAG                     1
#define IP_DEFAULT_TTL              64

/* --- DNS settings --- */
#define LWIP_DNS                    1
#define DNS_TABLE_SIZE              8
#define DNS_MAX_NAME_LENGTH         256
#define DNS_MAX_SERVERS             4
#define DNS_MAX_RETRIES             4

/* --- Checksum --- */
#define LWIP_CHECKSUM_ON_COPY       1
#define CHECKSUM_GEN_IP             1
#define CHECKSUM_GEN_TCP            1
#define CHECKSUM_GEN_UDP            1
#define CHECKSUM_CHECK_IP           1
#define CHECKSUM_CHECK_TCP          1
#define CHECKSUM_CHECK_UDP          1

/* --- Callbacks --- */
#define LWIP_CALLBACK_API           1

/* --- Stats (disabled for performance) --- */
#define LWIP_STATS                  0
#define LWIP_STATS_DISPLAY          0

/* --- Debug (disabled by default) --- */
#define LWIP_DEBUG                  0
#define LWIP_DBG_MIN_LEVEL          LWIP_DBG_LEVEL_ALL
#define LWIP_DBG_TYPES_ON           LWIP_DBG_OFF

/* --- Platform --- */
#define LWIP_ERRNO_STDINCLUDE       1
#include <stdlib.h>
#define LWIP_RAND()                 ((u32_t)arc4random())

/* --- Timers --- */
#define LWIP_TIMERS                 1
#define LWIP_TIMERS_CUSTOM          0

#endif /* LWIPOPTS_H */
