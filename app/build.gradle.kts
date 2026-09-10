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

// dev.67 icon-only release: keep the generated launcher artwork as text in Git and decode
// it into build/generated so the exact generated WebP is packaged without adding binary blobs.
val launcherIconB64 = file("src/main/icon/riftlab_launcher.webp.b64")
val generatedLauncherResDir = layout.buildDirectory.dir("generated/riftlabLauncher/res").get().asFile
val generatedLauncherIcon = generatedLauncherResDir.resolve("drawable-nodpi/ic_launcher_riftlab.webp")
if (launcherIconB64.exists()) {
    generatedLauncherIcon.parentFile.mkdirs()
    val bytes = Base64.getDecoder().decode(launcherIconB64.readText().filterNot(Char::isWhitespace))
    if (!generatedLauncherIcon.exists() || !generatedLauncherIcon.readBytes().contentEquals(bytes)) {
        generatedLauncherIcon.writeBytes(bytes)
    }
}

// Update acceleration is app-scoped only: no VPNService, no system proxy and no traffic capture.
// dev.65 stops trusting one fixed public node: the app probes the actual Release APK and picks the
// fastest path for the user's current network, then keeps Range-resume fallback across the pool.
// Override with RIFTLAB_GITHUB_ACCELERATOR_BASE_URLS="https://node-a/|https://node-b/" when needed.
val defaultGithubAcceleratorBaseUrls =
    "https://gh.llkk.cc/|https://cors.isteed.cc/|https://gh.xmly.dev/|https://gh.ddlc.top/|https://ghfast.top/|https://ghproxy.net/"
val githubAcceleratorBaseUrls = providers.gradleProperty("RIFTLAB_GITHUB_ACCELERATOR_BASE_URLS")
    .orElse(providers.gradleProperty("RIFTLAB_GITHUB_ACCELERATOR_BASE_URL"))
    .orElse("")
    .get()
    .trim()
    .ifBlank { defaultGithubAcceleratorBaseUrls }
val githubAcceleratorBaseUrlsLiteral = githubAcceleratorBaseUrls
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.riftlab.app"
    compileSdk = 36
    sourceSets["main"].res.srcDir(generatedLauncherResDir)

    defaultConfig {
        applicationId = "com.riftlab.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 69
        versionName = "1.0.0-dev.69"
        buildConfigField(
            "String",
            "GITHUB_ACCELERATOR_BASE_URLS",
            "\"$githubAcceleratorBaseUrlsLiteral\""
        )
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

    // Cito REST + optional paid WebSocket transport. REST remains the fallback when WSS is not
    // enabled by the account/plan.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Native match-VOD playback. Video bytes are streamed from the upstream CDN and are not cached
    // as RiftLab-owned media files.
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")

    // Riot/league team assets may be PNG, WebP, SVG or redirected CDN resources.
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
