#!/usr/bin/env python3
from pathlib import Path

p = Path('app/build.gradle.kts')
text = p.read_text(encoding='utf-8')
old = '''val devSigningB64 = rootProject.file("signing/riftlab-dev.keystore.b64")
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
'''
new = '''val devSigningB64 = rootProject.file("signing/riftlab-dev.keystore.b64")
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
'''
if old not in text:
    raise SystemExit('generated input block not found')
text = text.replace(old, new, 1)
anchor = '''dependencies {
'''
insert = '''// `clean` runs after project configuration when requested in the same Gradle invocation, so restore
// generated inputs once its deletion phase finishes before resource linking/signing tasks execute.
tasks.named("clean").configure {
    doLast { materializeTextBackedBuildInputs() }
}

dependencies {
'''
if anchor not in text:
    raise SystemExit('dependencies anchor not found')
text = text.replace(anchor, insert, 1)
p.write_text(text, encoding='utf-8')
print('clean-build generated input repair prepared')
