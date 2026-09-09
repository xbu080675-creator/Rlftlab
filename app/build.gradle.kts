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
        versionCode = 44
        versionName = "1.0.0-dev.44"
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

// dev.44: encrypted provider credentials + Tachio Sports API key settings entry.
