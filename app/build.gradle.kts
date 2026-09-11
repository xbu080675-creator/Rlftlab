import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val devSigningB64 = rootProject.file("signing/riftlab-dev.keystore.b64")
val devSigningStore = layout.buildDirectory.file("signing/riftlab-dev.keystore").get().asFile

// dev.67 launcher art and the public DEV keystore are text-backed generated build inputs. Keep one
// materializer and run it both at configuration time and after :app:clean. Without the post-clean
// re-materialization, `gradle clean :app:assembleDebug` deletes build/generated + build/signing after
// configuration and AAPT/signing later see missing files.
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
        versionCode = 81
        versionName = "1.0.0-dev.81"
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

// `clean` runs after project configuration when requested in the same Gradle invocation, so restore
// generated inputs once its deletion phase finishes before resource linking/signing tasks execute.
tasks.named("clean").configure {
    doLast { materializeTextBackedBuildInputs() }
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
// Final dev.54 verified-awards trigger: global MVP mirror wiring included in this build.

// dev.55: Cito becomes a first-class full-chain provider: schedule/team/standings supplement,
// quota-aware REST live fallback, optional WSS transport, raw provider archive, and global postgame backfill.

// dev.56: tournament rulebook + draw/slot governance, verified LPL qualifier correction, and non-overwriting official confirmations.
// Final dev.56 build/OTA trigger after governance UI integration.

// dev.57: annual Tournament Research editions: version/update/rules/draw/schedule unified per year for international events and regional leagues.

// dev.58: fix annual research compilation and add mainland-first dual-channel OTA with verified GitHub fallback.

// dev.59: GitHub remains the only build/version/release source. The app tries GitHub directly first, then temporarily enables a GitHub-only accelerator for manifest/APK requests, supports Range resume, and releases the accelerated connection immediately after the update request.

// dev.60: add a lifecycle-driven animated LIVE badge and a unified domestic/global broadcast jump hub. GAME_LIVE shows LIVE; EVENT_LIVE/BETWEEN_GAMES stay distinct as ON AIR. Add LoL Esports, YouTube, Twitch and X global entries alongside Bilibili/Huya.

// dev.61: repair mainland OTA acceleration after gh-proxy.com stalled on large Release assets. Use GHFast as primary, GHProxy.net as secondary, keep direct GitHub fallback, preserve Range resume and all package/signature verification.

// dev.62: add a local BP HUD simulator before live draft WebSocket integration. Split RiftScreen into a full-screen FLAG_NOT_TOUCHABLE visual HUD plus a tiny touchable control dock, preserve the lower broadcast-safe area, and simulate pick/matchup/counter transitions without polluting real archives.

// dev.63: turn Draft HUD into a user-owned layout. Keep a safe default, add an EDIT/LOCK workflow, draggable modules, per-module scale/alpha/visibility, reset, normalized coordinates, independent landscape/portrait profiles, and full touch pass-through whenever locked.

// dev.64: polish the watch HUD after real-screen testing. Auto-hide the finished DRAFT LOCKED status after a brief confirmation, keep it visible while editing, and compress matchup intelligence into a two-line horizontal strip to reduce broadcast obstruction.

// dev.65: make GitHub OTA acceleration network-adaptive. Probe the real APK through direct GitHub and multiple GitHub-only accelerators, rank by measured throughput, download from the fastest path, keep Range resume/fallback, and stop sending no-cache on immutable versioned APK assets so CDN caches can actually help.

// dev.66: keep Gitee OTA retired. Continue GitHub canonical dev-latest + request-scoped adaptive GitHub acceleration; refresh project/development documentation.

// dev.67: icon-only repack. No product/runtime changes; package the new RiftLab launcher icon and republish OTA for normal-network download testing without VPN/system proxy.

// dev.68: begin the comprehensive-data line: normalized Tournament/Series/Game/Team/Player graph, provenance and explicit coverage gaps; existing providers remain the source of truth.

// dev.69: persist Tournament Edition identities and explicit archival slots so old seasons remain queryable and can be enriched without being overwritten by the current tournament window.

// dev.70: separate annual Championship Points from tournament standings and model reverse-queryable qualification routes with explicit official/provider/derived evidence.

// dev.80: shared Cito realtime bus + broadcast-safe Global/Fight HUD phone simulator.

// dev.81: full BLG vs AL BP-to-match simulation and full-width Chinese event-xray tactical layer.
