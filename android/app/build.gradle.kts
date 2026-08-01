plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.dobrinskiy.livetype"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.dobrinskiy.livetype"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        // Required for BuildConfig.DEBUG, which gates the cleartext http://
        // token endpoint down to debug builds (see isAllowedTokenEndpoint).
        buildConfig = true
    }

    buildTypes {
        // The debug build additionally picks up
        // src/debug/res/xml/network_security_config.xml, which permits
        // cleartext to loopback only so `wrangler dev` over `adb reverse`
        // keeps working. The release build uses the HTTPS-only config from
        // src/main.
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
