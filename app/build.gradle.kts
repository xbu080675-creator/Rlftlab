import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val devSigningB64 = rootProject.file("signing/riftlab-dev.keystore.b64")
val devSigningStore = layout.buildDirectory.file("signing/riftlab-dev.keystore").get().asFile
if (!devSigningStore.exists() && devSigningB64.exists()) {
    devSigningStore.parentFile.mkdirs()
    devSigningStore.writeBytes(Base64.getDecoder().decode(devSigningB64.readText().trim()))
}

android {
    namespace = "com.riftlab.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.riftlab.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 54
        versionName = "1.0.0-dev.54"
    }

    // Public DEV identity: used only so test builds can overwrite each other.
    // Production releases must use a separate private release key from CI secrets.
    signingConfigs {
        create("riftlabDev") {
            storeFile = devSigningStore
            storePassword = "riftlab-dev"
            keyAlias = "riftlab-dev"
            keyPassword = "riftlab-dev"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("riftlabDev")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Compose 1.11.x stays compatible with compileSdk 36.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Native match-VOD playback. Video bytes are streamed from the upstream CDN and are not cached
    // as RiftLab-owned media files.
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")

    // Riot/league team assets may be PNG, WebP, SVG or redirected CDN resources.
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// dev.48: subscription-driven homepage targeting, league-safe post-match routing, in-app official VOD playback with sensor fullscreen, startup/replay performance tuning.
// dev.48 verified build passed; publish through dev-latest OTA.

// dev.49: clarify academy team names, restore global series score, isolate overseas timeline from Bilibili, and harden in-app official YouTube VOD playback.
// Final dev.49 CI/OTA trigger after replay and academy-label fixes.

// dev.50: retire LDL from current subscriptions, promote Worlds 2026, add Demacia Cup Global Invitational and WSCL international catalog entries.
// Final dev.50 trigger: schedule center opens at directory/subscribed league instead of auto-jumping to NSCL or another active competition.

// dev.51: major international events prefer official Bilibili China VOD while retaining Riot/YouTube; persistent real-UA YouTube WebView session for normal verification/login.
// Final dev.51 CI/OTA trigger after dual-source replay and YouTube session handling.

// dev.52: Riot GCD-backed global management/coaching identification plus stable hardware-accelerated portrait YouTube embed rendering.
// Final dev.52 trigger after GCD mirror population and role-label verification.

// dev.53: Bilibili official VOD lookup accepts reversed team order and retries negative search cache.
// Final dev.53 trigger after validating KT vs T1 / T1 vs KT official VOD matching.

// dev.54: global completed-series operator archive from OP.GG terminal data; unblock Riot/OP.GG history backfill game enumeration; labelled global MVP Point fallback.
// Final dev.54 CI/OTA trigger after global final-series and operator-history routing changes.
