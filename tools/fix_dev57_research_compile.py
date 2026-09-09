from pathlib import Path

# Keep the original dev.57 research-model fixes idempotent.
p = Path('app/src/main/java/com/riftlab/app/data/TournamentResearch.kt')
s = p.read_text(encoding='utf-8')
s = s.replace('it.all(Char::isDigit)', 'it.all { ch -> ch.isDigit() }')
s = s.replace('.map { it.code.ifBlank { team -> it.name } }', '.map { team -> team.code.ifBlank { team.name } }')
s = s.replace('.map { it.name.ifBlank { stage -> it.slug } }', '.map { stage -> stage.name.ifBlank { stage.slug } }')
p.write_text(s, encoding='utf-8')

# Fix the two compile errors surfaced by Android Build after dev.57 landed.
p = Path('app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt')
s = p.read_text(encoding='utf-8')
needle = 'private fun researchEvidenceColor(value: ResearchEvidence) = when (value) {'
if '@Composable\n' + needle not in s:
    if needle not in s:
        raise SystemExit('researchEvidenceColor anchor not found')
    s = s.replace(needle, '@Composable\n' + needle, 1)
old = 'return Regex("(?:19|20)\\d{2}").find(bucket.title)?.value?.toIntOrNull()'
new = 'return Regex("""(?:19|20)\\d{2}""").find(bucket.title)?.value?.toIntOrNull()'
if old in s:
    s = s.replace(old, new, 1)
elif new not in s:
    raise SystemExit('researchEditionYear regex anchor not found')
p.write_text(s, encoding='utf-8')

# Android Build artifacts must embed the same domestic manifest endpoint as OTA builds.
p = Path('.github/workflows/android-build.yml')
s = p.read_text(encoding='utf-8')
old = '      - name: Build fixed-signed debug APK\n        run: gradle :app:assembleDebug\n'
new = (
    '      - name: Build fixed-signed debug APK\n'
    '        env:\n'
    '          RIFTLAB_OTA_CN_MANIFEST_URL: ${{ vars.RIFTLAB_OTA_CN_MANIFEST_URL }}\n'
    '        run: gradle :app:assembleDebug -PRIFTLAB_OTA_CN_MANIFEST_URL="${RIFTLAB_OTA_CN_MANIFEST_URL:-}"\n'
)
if old in s:
    s = s.replace(old, new, 1)
elif new not in s:
    raise SystemExit('android-build assemble step anchor not found')
p.write_text(s, encoding='utf-8')

# Keep the user-visible developer changelog aligned with the binary version.
p = Path('DEV_CHANGELOG.txt')
s = p.read_text(encoding='utf-8')
entry = (
    'dev.58：修复 dev.57 年度赛事研究页的 Compose 与正则转义编译错误；'
    'OTA 升级为中国大陆对象存储/CDN 优先、GitHub dev-latest 自动兜底的双通道。'
    '国内 manifest 支持相对 APK 路径，并校验 HTTPS、SHA-256、包名、versionCode 与固定 DEV 签名；'
    '国内下载失败时仅在 GitHub 同版本且 SHA-256 一致时自动切换。'
    'CI 支持向 S3-compatible 国内对象存储自动发布版本 APK 与 latest.json；未配置国内存储时明确跳过，不伪造可用源。'
)
if not s.startswith('dev.58：'):
    p.write_text(entry + '\n\n' + s, encoding='utf-8')
