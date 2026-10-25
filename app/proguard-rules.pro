# Proguard / R8 rules for Omnia

# Keep JNI callbacks and native methods in TunnelEngine
-keep class com.eliyardo.omnia.tunnel.TunnelEngine** {
    native <methods>;
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
    *;
}

-keep class com.eliyardo.omnia.tunnel.TunnelState** { *; }
-keep class com.eliyardo.omnia.tunnel.ProxyState** { *; }

# Keep profile data classes
-keep class com.eliyardo.omnia.profile.** { *; }

# Keep AndroidX Security Crypto and Tink
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
-dontwarn org.checkerframework.**

# Keep Coroutines and Flow
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }

# Keep Compose runtime
-keep class androidx.compose.runtime.** { *; }
