from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]
profiles_path = ROOT / "data/lpl/team_profiles.json"
profiles = json.loads(profiles_path.read_text(encoding="utf-8"))
blg = profiles["teams"]["BLG"]

# No explicit departure evidence: You remains current BLG manager.
management = list(blg.get("management", []))
if not any(str(row.get("name", "")).lower() == "you" for row in management):
    yuan_index = next((i for i, row in enumerate(management) if "袁玺" in str(row.get("realName", "")) or str(row.get("name", "")) == "袁玺"), -1)
    insert_at = yuan_index + 1 if yuan_index >= 0 else 0
    management.insert(insert_at, {
        "name": "You",
        "role": "MANAGER",
        "displayRole": "经理",
        "realName": "You Chang-Xin (尤长鑫)",
        "source": "2026 第二赛段 BLG 经理公开记录；截至当前未发现明确离任证据"
    })
blg["management"] = management

# Do not show the same person as both current and historical unless an actual prior separate tenure is documented.
history = []
for row in blg.get("history", []):
    name = str(row.get("name", "")).lower()
    real = str(row.get("realName", ""))
    if name == "you" or "尤长鑫" in real:
        continue
    history.append(row)
if history:
    blg["history"] = history
else:
    blg.pop("history", None)

blg["verifiedAt"] = "2026-09-09"
profiles["updatedAt"] = "2026-09-09T02:25:00Z"
profiles_path.write_text(json.dumps(profiles, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

# Make the sync rule explicit: departure mutations require explicit departure language.
sync_path = ROOT / "tools/sync_team_data.py"
sync = sync_path.read_text(encoding="utf-8")
old = 'DEPARTURE_WORDS = ("离队", "离任", "转会至", "不再担任", "结束任职", "合同到期离开")\n'
new = 'DEPARTURE_WORDS = ("离队", "离任", "转会至", "不再担任", "结束任职", "合同到期离开")\n# Current-management removal/archive is allowed only when one of these explicit departure phrases is present.\n'
if old in sync:
    sync = sync.replace(old, new, 1)
sync_path.write_text(sync, encoding="utf-8")

# Correct dev.27 notes if the earlier migration text claimed You was removed.
changelog_path = ROOT / "DEV_CHANGELOG.txt"
changelog = changelog_path.read_text(encoding="utf-8")
changelog = changelog.replace(
    "同时修正 BLG 当前管理资料：袁玺按公开身份标注“赛训总监 / 经理”，移除误混入 BLG 一队经理位的 You/ycx。",
    "同时修正 BLG 当前管理资料：袁玺按公开身份标注“赛训总监 / 经理”；You/尤长鑫继续保留“经理”记录，截至当前未发现明确离任证据，因此不迁入历史荣誉。"
)
changelog_path.write_text(changelog, encoding="utf-8")

print("BLG You restored as current manager")
