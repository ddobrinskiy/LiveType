plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Debug convenience: bake the local dev endpoint + DEVICE_SECRET into debug
// builds so an adb install is immediately usable without retyping them on the
// phone. Reads worker/.dev.vars (gitignored). Absent file -> empty strings, no
// failure: fresh clones and CI must still build. Only DEVICE_SECRET is read;
// OPENAI_API_KEY never leaves the worker.
val devVarsFile = rootProject.file("../worker/.dev.vars")

fun readDevVar(name: String): String {
    if (!devVarsFile.isFile) return ""
    return devVarsFile.readLines()
        .asSequence()
        .map(String::trim)
        .filterNot { it.isEmpty() || it.startsWith("#") }
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            if (line.substring(0, separator).trim() != name) return@mapNotNull null
            line.substring(separator + 1).trim().trim('"', '\'')
        }
        .lastOrNull()
        .orEmpty()
}

fun javaStringLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val debugDeviceSecret = readDevVar("DEVICE_SECRET")
val debugTokenEndpoint = "http://127.0.0.1:8787/token"

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
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "DEFAULT_TOKEN_ENDPOINT", javaStringLiteral(debugTokenEndpoint))
            buildConfigField("String", "DEFAULT_DEVICE_SECRET", javaStringLiteral(debugDeviceSecret))
        }
        getByName("release") {
            // Release ships no baked credentials — configured by hand on device.
            buildConfigField("String", "DEFAULT_TOKEN_ENDPOINT", "\"\"")
            buildConfigField("String", "DEFAULT_DEVICE_SECRET", "\"\"")
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
