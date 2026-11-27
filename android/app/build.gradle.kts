plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Native dependencies (libsodium, zstd, xxHash, utf8proc) built by vcpkg for
// arm64-android; see android/native-deps and .github/workflows/android-ci.yml.
val clippDepsDir: String? = (findProperty("clippDepsDir") as String?) ?: System.getenv("CLIPP_DEPS_DIR")

// Stable signing so each new build installs over the previous one. The keystore
// comes from CI secrets; without it the build falls back to the debug key.
val keystoreFile = (findProperty("clippKeystore") as String?)?.let { file(it) }
val keystorePassword = (findProperty("clippKeystorePassword") as String?) ?: ""

android {
    namespace = "net.clipp.android"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "net.clipp.android"
        minSdk = 28
        targetSdk = 35
        versionCode = (findProperty("clippVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = "0.1.0"

        ndk { abiFilters += listOf("arm64-v8a") }

        externalNativeBuild {
            cmake {
                val args = mutableListOf("-DANDROID_STL=c++_static", "-DCLIPP_VERSION=0.1.0.$versionCode")
                if (clippDepsDir != null) args += "-DCLIPP_DEPS_DIR=$clippDepsDir"
                arguments += args
            }
        }
    }

    signingConfigs {
        if (keystoreFile != null && keystoreFile.exists()) {
            create("sideload") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = "clipp"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("sideload") ?: signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
}
