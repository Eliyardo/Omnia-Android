import java.security.KeyStore

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.eliyardo.omnia"
    compileSdk = 35

    val vCode = (project.findProperty("versionCode") as? String)?.toIntOrNull()
        ?: System.getenv("VERSION_CODE")?.toIntOrNull() ?: 10101
    val vName = (project.findProperty("versionName") as? String)
        ?: System.getenv("VERSION_NAME") ?: "1.1.1"

    defaultConfig {
        applicationId = "com.eliyardo.omnia"
        minSdk = 29
        targetSdk = 35
        versionCode = vCode
        versionName = vName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    val releaseStorePath = System.getenv("KEYSTORE_PATH")
        ?: (project.findProperty("RELEASE_STORE_FILE") as? String)
    val releaseStorePassword = (System.getenv("KEYSTORE_PASSWORD")
        ?: (project.findProperty("RELEASE_STORE_PASSWORD") as? String))?.trim()
    var releaseKeyAlias = (System.getenv("KEY_ALIAS")
        ?: (project.findProperty("RELEASE_KEY_ALIAS") as? String))?.trim()
    val releaseKeyPassword = (System.getenv("KEY_PASSWORD")
        ?: (project.findProperty("RELEASE_KEY_PASSWORD") as? String))?.trim()

    var hasReleaseSigning = releaseStorePath != null && file(releaseStorePath).exists() &&
            !releaseStorePassword.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

    if (hasReleaseSigning) {
        val ksFile = file(releaseStorePath!!)
        try {
            val ks = KeyStore.getInstance(
                if (ksFile.extension.equals("p12", ignoreCase = true)) "PKCS12" else KeyStore.getDefaultType()
            )
            ksFile.inputStream().use { ks.load(it, releaseStorePassword!!.toCharArray()) }
            val aliases = ks.aliases().toList()
            println("=== Available Keystore Aliases: $aliases ===")
            if (releaseKeyAlias.isNullOrBlank() || !ks.containsAlias(releaseKeyAlias)) {
                val matching = aliases.firstOrNull { a -> ks.isKeyEntry(a) } ?: aliases.firstOrNull()
                if (matching != null) {
                    println("=== Auto-resolved Key Alias to: '$matching' (provided was '$releaseKeyAlias') ===")
                    releaseKeyAlias = matching
                }
            }
        } catch (e: Exception) {
            println("=== Warning inspecting keystore: ${e.message} ===")
        }
    }

    val isValidSigning = hasReleaseSigning && !releaseKeyAlias.isNullOrBlank()

    signingConfigs {
        if (isValidSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            if (isValidSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                val requireReleaseSigning = System.getenv("REQUIRE_RELEASE_SIGNING") == "true"
                if (requireReleaseSigning) {
                    throw GradleException("Release build requested with REQUIRE_RELEASE_SIGNING=true, but release keystore or credentials are missing/invalid!")
                }
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    applicationVariants.all {
        outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == "ABI" }?.identifier ?: "universal"
            (output as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "Omnia-${versionName}-${abi}-${buildType.name}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.2.12479018"
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
