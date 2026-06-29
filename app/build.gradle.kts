plugins {
    id("com.android.application")
}

val releaseKeystorePath = System.getenv("H2RAY_KEYSTORE_PATH")

android {
    namespace = "com.h2ray.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.h2ray.app"
        minSdk = 28
        targetSdk = 36
        versionCode = System.getenv("H2RAY_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("H2RAY_VERSION_NAME") ?: "1.0.0.25"
    }

    signingConfigs {
        if (!releaseKeystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("H2RAY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("H2RAY_KEY_ALIAS")
                keyPassword = System.getenv("H2RAY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfigs.findByName("release")?.let {
                signingConfig = it
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(files("libs/libXray.aar"))
    implementation("androidx.core:core:1.17.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.github.mwiede:jsch:2.28.3")
    implementation("net.i2p.crypto:eddsa:0.3.0")
}
