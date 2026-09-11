from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text)


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one anchor, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


# Global-first product copy. Regional providers remain supplements only.
session = "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt"
write(session, read(session).replace("订阅赛区", "赛事订阅"))

schedule_ui = "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt"
write(schedule_ui, read(schedule_ui).replace("赛区订阅已同步", "赛事订阅已同步"))

live_source = "app/src/main/java/com/riftlab/app/data/LolEsportsDataSources.kt"
write(
    live_source,
    read(live_source).replace(
        'and currently returns no JSON for this LPL series. Query near wall-clock "now" instead,',
        'and can return no JSON for delayed series. Query near wall-clock "now" instead,',
    ),
)

# dev.72 release metadata.
gradle = "app/build.gradle.kts"
replace_once(gradle, 'versionCode = 71', 'versionCode = 72')
replace_once(gradle, 'versionName = "1.0.0-dev.71"', 'versionName = "1.0.0-dev.72"')

write(
    "DEV_CURRENT_CHANGELOG.txt",
    "dev.72：完成全球赛事数据与实时链路一致性收口。Unified Schedule 以 Riot 全球赛程 + Cito + International Mirror 合并，首页默认范围改为全球赛事，LPL Comm/TJStats 仅作为 LPL 区域补充；WSCI/RFT 国际赛事镜像、WSCI/WSCL 身份隔离与跨 Provider 去重并入统一目录。Tournament Edition 持久化每届 Patch、Rules、Draw、Qualification，Qualification 明确拆分 Direct Qualification / Championship Points / Regional Qualifier / Participant Origin。Replay/Timeline 接入可拖动历史经济轴、Riot VOD 时间偏移与来源证据；实时事件统一使用 verified/derived provenance，跨 Provider 切源以 Schedule Target + Gx 作为稳定身份，不再因 provider-local gameId 换源误判换局，晚接入标记 CAPTURE START。Live player 与历史归档统一保留 team/side/target identity；非 Riot 的 provider-only 国际赛不伪绑定 Riot LiveStats，缺失 roster/standings/patch/awards 继续保持未知。版本升级至 1.0.0-dev.72 / versionCode 72。\n"
)

# Permanent sync/build workflows must reflect global architecture.
intl_workflow = ".github/workflows/international-events-sync.yml"
replace_once(intl_workflow, "branches: [ dev72-integration-batch6 ]", "branches: [ main ]")

android_workflow = ".github/workflows/android-build.yml"
android = read(android_workflow)
android = android.replace(
    "    branches: [ main, master, feat/schedule-center, dev55-cito-fullchain ]",
    "    branches: [ main ]",
)
start = android.find("      - name: Probe Riot LoL Esports schedule center source\n")
end = android.find("      - uses: gradle/actions/setup-gradle@v4\n")
if start < 0 or end < 0 or end <= start:
    raise SystemExit("android-build.yml: could not locate legacy LPL probe block")
global_audit = '''      - name: Verify global esports data plane
        shell: bash
        run: |
          set -euo pipefail
          grep -q 'fetchGlobalSchedule' app/src/main/java/com/riftlab/app/data/LolEsportsApiClient.kt
          grep -q 'GLOBAL_MAJOR_LEAGUE_SLUGS' app/src/main/java/com/riftlab/app/data/LolEsportsApiClient.kt
          grep -q 'CitoScheduleSupplementProvider' app/src/main/java/com/riftlab/app/data/LolEsportsDataSources.kt
          grep -q 'InternationalEventMirrorProvider.fetchMatches' app/src/main/java/com/riftlab/app/data/LolEsportsDataSources.kt
          grep -q 'LeagueSubscriptionOption("GLOBAL", "全球赛事")' app/src/main/java/com/riftlab/app/ui/LeagueSubscriptionUi.kt
          grep -q 'rft-event:' app/src/main/java/com/riftlab/app/data/InternationalEventMirrorProvider.kt
'''
android = android[:start] + global_audit + android[end:]
android = android.replace("name: RiftLab-dev5-schedule-center", "name: RiftLab-dev72-global")
write(android_workflow, android)

# Remove all dev.72 temporary workflows/patchers/probes. Production sync remains.
for p in (ROOT / ".github/workflows").glob("dev72-*.yml"):
    p.unlink()
for p in (ROOT / "tools").glob("*dev72*"):
    p.unlink()
ci_dir = ROOT / ".ci"
if ci_dir.exists():
    for p in ci_dir.glob("dev72-*"):
        p.unlink()

print("dev72 global release finalization r2 applied")
