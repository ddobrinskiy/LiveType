import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Debug convenience: bake the local dev endpoint + one device secret into debug
// builds so an adb install is immediately usable without retyping them on the
// phone. Reads worker/.dev.vars (gitignored). Absent file -> empty strings, no
// failure: fresh clones and CI must still build. Only a device secret is read
// (see readDebugDeviceSecret); OPENAI_API_KEY never leaves the worker.
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

// buildConfigField pastes its value into BuildConfig.java verbatim, so anything
// going in has to be a valid Java string literal. Newlines matter here: the
// keyword list is multi-line, and a raw \n would end the literal mid-statement
// and break compilation.
fun javaStringLiteral(value: String): String = buildString {
    append('"')
    for (character in value) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

// Debug convenience #2: bake the maintained vocabulary list into debug builds
// as the default keywords, so a fresh install already knows the terms. Same
// rules as DEVICE_SECRET — debug only, and a missing file is not an error.
//
// data/keywords.txt is gitignored (personal vocabulary, public repo); only
// data/keywords.txt.age is committed. See scripts/keywords-{en,de}crypt.sh.
//
// providers.fileContents() is what makes the value fresh: it registers the file
// as a configuration-time input, so editing the list invalidates Gradle's
// configuration cache instead of silently baking a stale list into the APK.
// A plain File.readText() here would not be tracked.
val keywordsFile = rootProject.layout.projectDirectory.file("../data/keywords.txt")

val debugKeywords: String = providers.fileContents(keywordsFile).asText.orNull
    ?.lineSequence()
    ?.map(String::trim)
    // '#' comments and blank lines exist so a human can annotate the file.
    ?.filterNot { it.isEmpty() || it.startsWith("#") }
    ?.distinct()
    ?.joinToString("\n")
    .orEmpty()

/**
 * The secret a debug build bakes in, so an `adb install` is usable without
 * retyping it. Prefers the legacy single `DEVICE_SECRET`; otherwise takes the
 * first `DEVICE_SECRET_<NAME>` entry in the file, which is the convention the
 * Worker reads — one variable per device. Put your own phone's first.
 *
 * No device name is hard-coded here on purpose: this file is public and the
 * names are the maintainer's.
 */
fun readDebugDeviceSecret(): String {
    val legacy = readDevVar("DEVICE_SECRET")
    if (legacy.isNotEmpty()) return legacy
    if (!devVarsFile.isFile) return ""
    return devVarsFile.readLines()
        .asSequence()
        .map(String::trim)
        .filterNot { it.isEmpty() || it.startsWith("#") }
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            if (!line.substring(0, separator).trim().startsWith("DEVICE_SECRET_")) {
                return@mapNotNull null
            }
            line.substring(separator + 1).trim().trim('"', '\'').ifEmpty { null }
        }
        .firstOrNull()
        .orEmpty()
}

val debugDeviceSecret = readDebugDeviceSecret()
val debugTokenEndpoint = "http://127.0.0.1:8787/token"

// The deployed worker's URL is deliberately NOT in the repo: this is a public
// repository and a worker URL is a live endpoint someone could hammer. It
// lives in worker/.dev.vars (gitignored) beside the secrets, and is baked into
// debug builds only. Absent file or key -> "", which leaves the prod option in
// the settings dropdown disabled exactly as it was before deployment.
val prodTokenEndpoint = readDevVar("PROD_TOKEN_ENDPOINT")

// Release signing. android/keystore.properties is gitignored and holds only a
// path and a password; the keystore itself lives outside the repo (see the
// file's own comment). Absent file -> no signingConfig at all, and
// assembleRelease falls back to producing app-release-unsigned.apk. That is
// deliberate: a fresh clone and CI must still build a release without holding
// the maintainer's key.
val keystorePropertiesFile = rootProject.file("keystore.properties")

val keystoreProperties: Properties? =
    if (keystorePropertiesFile.isFile) {
        Properties().apply {
            keystorePropertiesFile.inputStream().use { load(it) }
        }
    } else {
        null
    }

android {
    namespace = "dev.dobrinskiy.livetype"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.dobrinskiy.livetype"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3"
    }

    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
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
        getByName("debug") {
            buildConfigField("String", "DEFAULT_TOKEN_ENDPOINT", javaStringLiteral(debugTokenEndpoint))
            buildConfigField("String", "DEFAULT_DEVICE_SECRET", javaStringLiteral(debugDeviceSecret))
            buildConfigField("String", "DEFAULT_KEYWORDS", javaStringLiteral(debugKeywords))
            buildConfigField("String", "PROD_TOKEN_ENDPOINT", javaStringLiteral(prodTokenEndpoint))
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            // Release ships no baked credentials and no personal vocabulary —
            // configured by hand on device.
            buildConfigField("String", "DEFAULT_TOKEN_ENDPOINT", "\"\"")
            buildConfigField("String", "DEFAULT_DEVICE_SECRET", "\"\"")
            buildConfigField("String", "DEFAULT_KEYWORDS", "\"\"")
            buildConfigField("String", "PROD_TOKEN_ENDPOINT", "\"\"")
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
