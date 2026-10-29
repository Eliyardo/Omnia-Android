/*
 * tunnel_jni.cpp - JNI Bridge for TunnelEngine
 */

#include "tunnel_engine.h"
#include <jni.h>
#include <string>

static std::string jstringToString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* utf = env->GetStringUTFChars(jstr, nullptr);
    if (!utf) return "";
    std::string str(utf);
    env->ReleaseStringUTFChars(jstr, utf);
    return str;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_eliyardo_omnia_tunnel_TunnelEngine_nativeInit(JNIEnv* env, jobject thiz) {
    auto* engine = new TunnelEngine();
    if (!engine->initialize(env, thiz)) {
        delete engine;
        return 0;
    }
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jint JNICALL
Java_com_eliyardo_omnia_tunnel_TunnelEngine_nativeConnect(
        JNIEnv* env, jobject /*thiz*/,
        jlong handle, jstring profile, jstring username, jstring password,
        jboolean isTunMode,
        jstring securityLevel, jboolean enforceTls13,
        jstring protoOverride, jint connTimeout,
        jboolean ipv6Enabled, jboolean bypassLanTun,
        jstring upstreamProxyHost, jint upstreamProxyPort,
        jstring upstreamProxyUser, jstring upstreamProxyPass,
        jboolean upstreamProxyAllowCleartext) {
    auto* engine = reinterpret_cast<TunnelEngine*>(handle);
    if (!engine) return -1;

    std::string prof = jstringToString(env, profile);
    std::string user = jstringToString(env, username);
    std::string pass = jstringToString(env, password);
    std::string secLevel = jstringToString(env, securityLevel);
    std::string proto = jstringToString(env, protoOverride);
    std::string upHost = jstringToString(env, upstreamProxyHost);
    std::string upUser = jstringToString(env, upstreamProxyUser);
    std::string upPass = jstringToString(env, upstreamProxyPass);

    return engine->connect(prof, user, pass,
                           isTunMode == JNI_TRUE,
                           secLevel,
                           enforceTls13 == JNI_TRUE,
                           proto,
                           (int)connTimeout,
                           ipv6Enabled == JNI_TRUE,
                           bypassLanTun == JNI_TRUE,
                           upHost,
                           (int)upstreamProxyPort,
                           upUser,
                           upPass,
                           upstreamProxyAllowCleartext == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_eliyardo_omnia_tunnel_TunnelEngine_nativeDisconnect(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* engine = reinterpret_cast<TunnelEngine*>(handle);
    if (engine) {
        engine->disconnect();
    }
}

JNIEXPORT void JNICALL
Java_com_eliyardo_omnia_tunnel_TunnelEngine_nativeDestroy(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* engine = reinterpret_cast<TunnelEngine*>(handle);
    if (engine) {
        engine->destroy();
        delete engine;
    }
}

JNIEXPORT jint JNICALL
Java_com_eliyardo_omnia_tunnel_TunnelEngine_nativeOpenConnection(
        JNIEnv* env, jobject /*thiz*/,
        jlong handle, jstring host, jint port, jlong callback_id) {
    auto* engine = reinterpret_cast<TunnelEngine*>(handle);
    if (!engine) return -1;

    std::string h = jstringToString(env, host);
    return engine->openConnection(h, port, callback_id);
}

JNIEXPORT jint JNICALL
Java_com_eliyardo_omnia_tunnel_TunnelEngine_nativeSendData(
        JNIEnv* env, jobject /*thiz*/,
        jlong handle, jint conn_id, jbyteArray data, jint offset, jint length) {
    auto* engine = reinterpret_cast<TunnelEngine*>(handle);
    if (!engine || !data || length <= 0) return -1;

    jsize arrayLen = env->GetArrayLength(data);
    if (offset < 0 || length <= 0 || offset + length > arrayLen) return -1;

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return -1;

    int res = engine->sendData(conn_id, reinterpret_cast<const uint8_t*>(bytes), offset, length);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return res;
}

JNIEXPORT void JNICALL
Java_com_eliyardo_omnia_tunnel_TunnelEngine_nativeAcknowledgeData(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint conn_id, jint length) {
    auto* engine = reinterpret_cast<TunnelEngine*>(handle);
    if (engine) {
        engine->acknowledgeData(conn_id, length);
    }
}

JNIEXPORT void JNICALL
Java_com_eliyardo_omnia_tunnel_TunnelEngine_nativeCloseConnection(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint conn_id) {
    auto* engine = reinterpret_cast<TunnelEngine*>(handle);
    if (engine) {
        engine->closeConnection(conn_id);
    }
}

JNIEXPORT jint JNICALL
Java_com_eliyardo_omnia_tunnel_TunnelEngine_nativeOpenUdpConnection(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong handle, jlong callback_id) {
    auto* engine = reinterpret_cast<TunnelEngine*>(handle);
    if (!engine) return -1;
    return engine->openUdpConnection(callback_id);
}

JNIEXPORT jint JNICALL
Java_com_eliyardo_omnia_tunnel_TunnelEngine_nativeSendUdpData(
        JNIEnv* env, jobject /*thiz*/,
        jlong handle, jint conn_id, jstring host, jint port, jbyteArray data, jint offset, jint length) {
    auto* engine = reinterpret_cast<TunnelEngine*>(handle);
    if (!engine || !data || length < 0) return -1;

    jsize arrayLen = env->GetArrayLength(data);
    if (offset < 0 || length < 0 || offset + length > arrayLen) return -1;

    std::string h = jstringToString(env, host);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return -1;

    int res = engine->sendUdpData(conn_id, h, port, reinterpret_cast<const uint8_t*>(bytes), offset, length);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return res;
}

JNIEXPORT void JNICALL
Java_com_eliyardo_omnia_tunnel_TunnelEngine_nativeCloseUdpConnection(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint conn_id) {
    auto* engine = reinterpret_cast<TunnelEngine*>(handle);
    if (engine) {
        engine->closeUdpConnection(conn_id);
    }
}

JNIEXPORT jstring JNICALL
Java_com_eliyardo_omnia_tunnel_TunnelEngine_nativeResolveHostname(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring hostname) {
    auto* engine = reinterpret_cast<TunnelEngine*>(handle);
    if (!engine) return nullptr;

    std::string host = jstringToString(env, hostname);
    std::string ip = engine->resolveHostname(host);
    if (ip.empty()) return nullptr;

    return env->NewStringUTF(ip.c_str());
}

JNIEXPORT void JNICALL
Java_com_eliyardo_omnia_tunnel_TunnelEngine_nativeSetCustomDns(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring dns1, jstring dns2) {
    auto* engine = reinterpret_cast<TunnelEngine*>(handle);
    if (engine) {
        std::string d1 = jstringToString(env, dns1);
        std::string d2 = jstringToString(env, dns2);
        engine->setCustomDns(d1, d2);
    }
}

} // extern "C"
