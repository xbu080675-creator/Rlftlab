import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val devSigningB64 = rootProject.file("signing/riftlab-dev.keystore.b64")
val devSigningStore = layout.buildDirectory.file("signing/riftlab-dev.keystore").get().asFile
val launcherIconB64 = file("src/main/icon/riftlab_launcher.webp.b64")
val generatedLauncherResDir = layout.buildDirectory.dir("generated/riftlabLauncher/res").get().asFile
val generatedLauncherIcon = generatedLauncherResDir.resolve("drawable-nodpi/ic_launcher_riftlab.webp")

fun materializeTextBackedBuildInputs() {
    if (devSigningB64.exists()) {
        val bytes = Base64.getDecoder().decode(devSigningB64.readText().filterNot(Char::isWhitespace))
        if (!devSigningStore.exists() || !devSigningStore.readBytes().contentEquals(bytes)) {
            devSigningStore.parentFile.mkdirs()
            devSigningStore.writeBytes(bytes)
        }
    }
    if (launcherIconB64.exists()) {
        val bytes = Base64.getDecoder().decode(launcherIconB64.readText().filterNot(Char::isWhitespace))
        if (!generatedLauncherIcon.exists() || !generatedLauncherIcon.readBytes().contentEquals(bytes)) {
            generatedLauncherIcon.parentFile.mkdirs()
            generatedLauncherIcon.writeBytes(bytes)
        }
    }
}

materializeTextBackedBuildInputs()

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
        versionCode = 91
        versionName = "1.0.0-dev.91"
        buildConfigField("String", "GITHUB_ACCELERATOR_BASE_URLS", "\"$githubAcceleratorBaseUrlsLiteral\"")
    }

    signingConfigs {
        create("riftlabDev") {
            storeFile = devSigningStore
            storePassword = "riftlab-dev"
            keyAlias = "riftlab-dev"
            keyPassword = "riftlab-dev"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("riftlabDev") }
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

tasks.named("clean").configure {
    doLast { materializeTextBackedBuildInputs() }
}

dependencies {
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

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")

    // dev.87+: real optional on-device runtime. The model remains a user-downloaded asset,
    // never bundled into the APK. Pin the runtime so model verification/benchmark behavior is stable.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// dev.91: robust official-roster team identity crosswalk and always-visible feed diagnostics.
