/* Architecture header for lwIP on Android NDK */
#ifndef ARCH_CC_H
#define ARCH_CC_H

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <sys/time.h>
#include <errno.h>

#define LWIP_NOASSERT 1

/* Define byte order - Android is little-endian on ARM/x86 */
#ifndef BYTE_ORDER
#define BYTE_ORDER LITTLE_ENDIAN
#endif

/* Use the standard types */
typedef uint8_t   u8_t;
typedef int8_t    s8_t;
typedef uint16_t  u16_t;
typedef int16_t   s16_t;
typedef uint32_t  u32_t;
typedef int32_t   s32_t;

typedef uintptr_t mem_ptr_t;

/* Compiler hints */
#define LWIP_NO_STDDEF_H    0
#define LWIP_NO_STDINT_H    0
#define LWIP_NO_INTTYPES_H  0
#define LWIP_NO_LIMITS_H    0

/* Diagnostics */
#include <android/log.h>
#define LWIP_PLATFORM_DIAG(x) do { __android_log_print(ANDROID_LOG_DEBUG, "lwIP", "%s", (x)); } while(0)
#define LWIP_PLATFORM_ASSERT(x) do { __android_log_print(ANDROID_LOG_ERROR, "lwIP", "Assert: %s", (x)); } while(0)

/* Packing */
#define PACK_STRUCT_FIELD(x) x
#define PACK_STRUCT_STRUCT __attribute__((packed))
#define PACK_STRUCT_BEGIN
#define PACK_STRUCT_END

#endif /* ARCH_CC_H */
