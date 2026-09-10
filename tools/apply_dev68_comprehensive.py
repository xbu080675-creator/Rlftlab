from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

# Version bump only; OTA transport remains unchanged.
gradle = ROOT / "app/build.gradle.kts"
text = gradle.read_text(encoding="utf-8")
text = text.replace("versionCode = 67", "versionCode = 68", 1)
text = text.replace('versionName = "1.0.0-dev.67"', 'versionName = "1.0.0-dev.68"', 1)
comment = "// dev.68: begin the comprehensive-data line: normalized Tournament/Series/Game/Team/Player graph, provenance and explicit coverage gaps; existing providers remain the source of truth.\n"
if "// dev.68:" not in text:
    text = text.rstrip() + "\n\n" + comment
gradle.write_text(text, encoding="utf-8")

# Make the new graph visible on PRE without changing the existing source cards.
app = ROOT / "app/src/main/java/com/riftlab/app/ui/RiftLabApp.kt"
text = app.read_text(encoding="utf-8")
needle = "        item { SideSelectionPrePanel() }\n\n        item { SectionTitle(\"STARTING ROSTER / 首发\") }"
replacement = "        item { SideSelectionPrePanel() }\n\n        item { SectionTitle(\"DATA COVERAGE / 全面数据\") }\n        item { ComprehensiveDataCoveragePanel() }\n\n        item { SectionTitle(\"STARTING ROSTER / 首发\") }"
if "ComprehensiveDataCoveragePanel()" not in text:
    if needle not in text:
        raise SystemExit("PRE insertion marker not found")
    text = text.replace(needle, replacement, 1)
app.write_text(text, encoding="utf-8")

changelog = (
    "dev.68：启动 RiftLab 全面数据主线。新增统一 Tournament Edition / Series / Game / Team / Player / Roster / PlayerStats / Standings / Qualification / Timeline 数据契约，"
    "加入 OFFICIAL / PROVIDER / DERIVED / LOCAL_CACHE / APK_SEED / USER_INPUT 来源等级与 STATIC / DAILY / HOURLY / MINUTES / REALTIME 刷新等级；"
    "新增 ComprehensiveDataCenter，将现有 Schedule、PRE、LIVE、CompletedGame、Standings 持续归一到同一数据图；新增 12 域 Coverage Report 与赛前可视化面板，"
    "缺失数据明确显示为部分/待同步/来源异常，不用 Mock 或占位值伪装完整。现有 Riot/Cito/OP.GG/Bilibili/本地归档仍为事实源，OTA 逻辑不变。"
    "版本升级至 1.0.0-dev.68 / versionCode 68。"
)
(ROOT / "DEV_CURRENT_CHANGELOG.txt").write_text(changelog + "\n", encoding="utf-8")
full = ROOT / "DEV_CHANGELOG.txt"
old = full.read_text(encoding="utf-8")
if not old.startswith("dev.68："):
    full.write_text(changelog + "\n\n" + old, encoding="utf-8")

# Reconcile the roadmap with the actual dev.67 release and the user's new dev.68 direction.
roadmap = ROOT / "docs/RIFTLAB_DEV_67_86.md"
text = roadmap.read_text(encoding="utf-8")
text = text.replace("> 新基线：`1.0.0-dev.66`", "> 新基线：`1.0.0-dev.67`", 1)
actual67 = '''## dev.67 — Launcher 图标二次更新 / OTA 实机验证

实际交付：这一版刻意保持最小变量，只更新 APK Launcher / Round Launcher 图标并重新打包分发，用于验证 dev.66 → dev.67 的 APP 内 OTA。

- 不改赛事数据、PRE / LIVE / POST、RiftScreen 或 OTA 传输逻辑；
- 中国移动 5G、手机重启并还原网络设置、VPN/系统代理关闭条件下完成实机 OTA；
- 实测下载约 20 MB/s，检查、下载、校验、覆盖安装闭环通过；
- Gitee OTA 继续保持废弃。

该版不再承担原路线中的“治理清账”任务，相关工作并入后续数据治理主线。
'''
text, c1 = re.subn(r"## dev\.67 — .*?(?=\n## dev\.68 —)", actual67.rstrip() + "\n", text, flags=re.S)
if c1 != 1:
    raise SystemExit(f"roadmap dev67 replacement count={c1}")
new68 = '''## dev.68 — 全面数据基线：统一图 + Provenance + Coverage

目标：从这一版开始，把 RiftLab 从“多个页面各自拿数据”升级成长期可扩展的电竞数据图。全面不等于强行填满字段，而是任意实体都能继续沿关系查询，缺口也必须可见。

第一阶段落地：

- 统一 `Tournament Edition → Series → Game → Team → Player` 核心关系；
- 纳入 Roster、Player Game Stats、Standings、Qualification、Timeline 槽位；
- 每条数据保留 authority / freshness / source provenance；
- 统一 12 个覆盖域：赛事、赛程、队伍、选手、阵容、PRE、LIVE、POST、排名、晋级、历史、来源；
- 覆盖状态固定为 `完整 / 部分 / 待同步 / 来源异常 / 不适用`；
- `ComprehensiveDataCenter` 直接监听现有真实 Store，不重复抓同一数据；
- PRE 页面增加真实 Coverage 面板，明确暴露缺失字段；
- LPL、LCK、LCP、LEC、LTA 与 Worlds / MSI / First Stand / EWC 等 family 使用稳定 edition identity 规则；
- 不因当前没有 LIVE 比赛就丢掉 Tournament / Series / Standings 档案关系；
- 不用 Mock、推测、静态占位文本把 Coverage 顶成“完整”。

验收：当前运行中的比赛数据已经可以进入统一 Graph，并能回答“当前拿到了哪些域、还缺哪些域、来源是什么”；后续全球历史目录、资格路径与完整赛后数据在这个统一模型上继续补齐。
'''
text, c2 = re.subn(r"## dev\.68 — .*?(?=\n## dev\.69 —)", new68.rstrip() + "\n", text, flags=re.S)
if c2 != 1:
    raise SystemExit(f"roadmap dev68 replacement count={c2}")
roadmap.write_text(text, encoding="utf-8")

print("dev68 patch applied")
