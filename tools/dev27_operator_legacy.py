from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]


def replace(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"target not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# --- DynamicTeamDataProvider: operator-only org model + historical management archive ---
path = "app/src/main/java/com/riftlab/app/data/DynamicTeamDataProvider.kt"
replace(path,
'''internal data class TeamDynamicSupplement(
    val profile: TeamProfileSupplement,
    val staff: TeamStaffSupplement,
    val organizationSummary: String = "",
    val legalSummary: String = "",
    val verifiedAt: String = "",
    val sourceMode: String = "remote"
)
''',
'''internal data class TeamHistoryRef(
    val name: String,
    val formerRole: String,
    val realName: String = "",
    val honoraryTitle: String = "RiftLab 荣誉成员",
    val source: String = "",
    val note: String = ""
)

internal data class TeamDynamicSupplement(
    val profile: TeamProfileSupplement,
    val staff: TeamStaffSupplement,
    val organizationSummary: String = "",
    val history: List<TeamHistoryRef> = emptyList(),
    val verifiedAt: String = "",
    val sourceMode: String = "remote"
)
''')

replace(path,
'''        val management = parseStaff(node.optJSONArray("management"), sourceLabel)
        val staff = parseStaff(node.optJSONArray("staff"), sourceLabel)
        val operators = parseOperators(node.optJSONArray("operators"))
        val corporate = node.optJSONObject("corporate")
        val legalEntity = corporate?.optString("legalEntity").orEmpty()
        val legalRepresentative = corporate?.optString("legalRepresentative").orEmpty()
        val corporateNote = corporate?.optString("note").orEmpty()
''',
'''        val management = parseStaff(node.optJSONArray("management"), sourceLabel)
        val staff = parseStaff(node.optJSONArray("staff"), sourceLabel)
        val operators = parseOperators(node.optJSONArray("operators"))
        val history = parseHistory(node.optJSONArray("history"), sourceLabel)
''')

replace(path,
'''        val legalSummary = when {
            legalEntity.isNotBlank() && legalRepresentative.isNotBlank() ->
                "$legalEntity · 法定代表人：$legalRepresentative"
            corporateNote.isNotBlank() -> corporateNote
            else -> ""
        }

        return TeamDynamicSupplement(
''',
'''        return TeamDynamicSupplement(
''')

replace(path,
'''            organizationSummary = operators,
            legalSummary = legalSummary,
            verifiedAt = verifiedAt,
''',
'''            organizationSummary = operators,
            history = history,
            verifiedAt = verifiedAt,
''')

replace(path,
'''    private fun parseOperators(rows: JSONArray?): String {
''',
'''    private fun parseHistory(rows: JSONArray?, source: String): List<TeamHistoryRef> {
        if (rows == null) return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                if (item.optBoolean("current", false)) continue
                val name = item.optString("name").trim()
                val formerRole = item.optString("formerRole").ifBlank { item.optString("role") }.trim()
                if (name.isBlank() || formerRole.isBlank()) continue
                add(
                    TeamHistoryRef(
                        name = name,
                        formerRole = formerRole,
                        realName = item.optString("realName"),
                        honoraryTitle = item.optString("honoraryTitle").ifBlank { "RiftLab 荣誉成员" },
                        source = item.optString("source").ifBlank { source },
                        note = item.optString("note")
                    )
                )
            }
        }
    }

    private fun parseOperators(rows: JSONArray?): String {
''')

# --- TeamDetailRepository: carry history, drop irrelevant corporate/legal surface ---
path = "app/src/main/java/com/riftlab/app/data/TeamDetailRepository.kt"
replace(path,
'''    val profileStatus: String = "管理层 / 社交资料尚未同步",
    val organizationSummary: String = "",
    val legalSummary: String = "",
    val profileSourceMode: String = "",
''',
'''    val profileStatus: String = "管理层 / 社交资料尚未同步",
    val organizationSummary: String = "",
    val history: List<TeamHistoryRef> = emptyList(),
    val profileSourceMode: String = "",
''')
replace(path,
'''    private val starterCache = linkedMapOf<String, Set<String>>()
''',
'''    private val starterCache = linkedMapOf<String, Set<String>>()
    private val historyCache = linkedMapOf<String, List<TeamHistoryRef>>()
''')
replace(path,
'''                profileStatus = "管理层 · 已缓存，后台检查 RiftLab Dynamic Data",
                profileSourceMode = "cache"
''',
'''                profileStatus = "管理层 · 已缓存，后台检查 RiftLab Dynamic Data",
                history = historyCache[key].orEmpty(),
                profileSourceMode = "cache"
''')
replace(path,
'''            if (details != null) cache[key] = details
            if (starters.size >= 5) starterCache[key] = starters
''',
'''            if (details != null) cache[key] = details
            if (starters.size >= 5) starterCache[key] = starters
            historyCache[key] = dynamicSupplement.history
''')
replace(path,
'''                profileStatus = profileSupplement.status,
                organizationSummary = dynamicSupplement.organizationSummary,
                legalSummary = dynamicSupplement.legalSummary,
                profileSourceMode = dynamicSupplement.sourceMode,
''',
'''                profileStatus = profileSupplement.status,
                organizationSummary = dynamicSupplement.organizationSummary,
                history = dynamicSupplement.history,
                profileSourceMode = dynamicSupplement.sourceMode,
''')
replace(path,
'''            cache[key] = updated

            val current = _state.value
''',
'''            cache[key] = updated
            historyCache[key] = dynamic.history

            val current = _state.value
''')
replace(path,
'''                profileStatus = dynamic.profile.status,
                organizationSummary = dynamic.organizationSummary,
                legalSummary = dynamic.legalSummary,
                profileSourceMode = dynamic.sourceMode
''',
'''                profileStatus = dynamic.profile.status,
                organizationSummary = dynamic.organizationSummary,
                history = dynamic.history,
                profileSourceMode = dynamic.sourceMode
''')

# --- TeamDetailUi: no工商; add explicit RiftLab historical honor section ---
path = "app/src/main/java/com/riftlab/app/ui/TeamDetailUi.kt"
replace(path,
'''import com.riftlab.app.data.TeamDetailRepository
''',
'''import com.riftlab.app.data.TeamDetailRepository
import com.riftlab.app.data.TeamHistoryRef
''')
replace(path,
'''        if (state.legalSummary.isNotBlank()) {
            item { TeamSectionTitle("CORPORATE / 工商信息") }
            item { TeamStatus(state.legalSummary) }
        }

''',
'''')
replace(path,
'''        item { TeamSectionTitle("COACHING STAFF / 教练组") }
''',
'''        if (state.history.isNotEmpty()) {
            item { TeamSectionTitle("RIFT LEGACY / 历史荣誉") }
            items(state.history, key = { "legacy-${it.name}-${it.formerRole}" }) { legacy ->
                TeamHistoryRow(legacy)
            }
            item { TeamSourceNote("RiftLab 历史档案称号，不代表俱乐部官方现任职务或官方授予头衔。") }
        }

        item { TeamSectionTitle("COACHING STAFF / 教练组") }
''')
replace(path,
'''@Composable
private fun SocialLinkRow(links: List<EsportsSocialLink>, compact: Boolean = false) {
''',
'''@Composable
private fun TeamHistoryRow(history: TeamHistoryRef) {
    Row(
        Modifier.fillMaxWidth()
            .background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .border(1.dp, RiftCyan.copy(alpha = 0.22f), CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).background(RiftPanelAlt, CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("誉", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(history.name, color = RiftText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (history.realName.isNotBlank()) Text(history.realName, color = RiftMuted, fontSize = 8.sp, maxLines = 1)
            if (history.note.isNotBlank()) Text(history.note, color = RiftMuted, fontSize = 8.sp, maxLines = 2)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(history.honoraryTitle, color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Text("曾任${staffRoleLabel(history.formerRole)}", color = RiftMuted, fontSize = 8.sp)
        }
    }
}

@Composable
private fun SocialLinkRow(links: List<EsportsSocialLink>, compact: Boolean = false) {
''')

# --- Team sync: preserve departing management into history and detect explicit operator changes ---
path = "tools/sync_team_data.py"
replace(path,
'''IMPORTANT_WORDS = ("人员变动公告", "大名单", "离队", "加入", "加盟", "转会", "主教练", "经理", "领队", "监督", "董事长", "重组")
''',
'''IMPORTANT_WORDS = (
    "人员变动公告", "大名单", "离队", "加入", "加盟", "转会", "主教练", "经理", "领队",
    "监督", "董事长", "重组", "收购", "运营主体", "运营方", "旗下", "更名"
)
''')
replace(path,
'''    query = f'site:weibo.com "{official_name}" ("人员变动公告" OR "大名单" OR "离队" OR "加入" OR "转会" OR "重组")'
''',
'''    query = f'site:weibo.com "{official_name}" ("人员变动公告" OR "大名单" OR "离队" OR "加入" OR "转会" OR "重组" OR "收购" OR "运营主体")'
''')
replace(path,
'''def apply_official_event(team: dict[str, Any], item: dict[str, str]) -> list[str]:
''',
'''def _operator_name(value: str) -> str:
    value = clean_html(value).strip(" ，。；;：:（）()[]【】")
    value = re.sub(r"(?:电子竞技俱乐部|电竞俱乐部)$", "", value).strip()
    return value[:40]


def extract_operators(text: str) -> list[str]:
    found: list[str] = []
    patterns = [
        re.compile(r"运营主体(?:变更为|为|[:：])\\s*(?P<op>[^，。；;]{2,30})"),
        re.compile(r"运营方(?:变更为|为|[:：])\\s*(?P<op>[^，。；;]{2,30})"),
        re.compile(r"由(?P<op>[\\u4e00-\\u9fffA-Za-z0-9·]{2,24})(?:负责)?运营"),
        re.compile(r"(?:正式)?被(?P<op>[\\u4e00-\\u9fffA-Za-z0-9·]{2,24})(?:完成)?收购"),
        re.compile(r"成为(?P<op>[\\u4e00-\\u9fffA-Za-z0-9·]{2,24})旗下"),
    ]
    for pattern in patterns:
        for match in pattern.finditer(text):
            name = _operator_name(match.group("op"))
            if len(name) >= 2:
                found.append(name)

    reorg = re.search(
        r"与(?P<a>[\\u4e00-\\u9fffA-Za-z0-9·]{2,20}?)(?:及关联方)?、(?P<b>[\\u4e00-\\u9fffA-Za-z0-9·]{2,20}?)(?:就|共同).*?重组",
        text,
    )
    if reorg:
        for key in ("a", "b"):
            name = _operator_name(reorg.group(key))
            if len(name) >= 2:
                found.append(name)

    return list(dict.fromkeys(found))


def archive_management(team: dict[str, Any], row: dict[str, Any], item: dict[str, str]) -> None:
    history = team.setdefault("history", [])
    name = str(row.get("name", "")).strip()
    role = str(row.get("role", "")).strip()
    if not name or not role:
        return
    duplicate = next(
        (
            old for old in history
            if str(old.get("name", "")).lower() == name.lower()
            and str(old.get("role", old.get("formerRole", ""))).upper() == role.upper()
        ),
        None,
    )
    if duplicate:
        duplicate["current"] = False
        duplicate.setdefault("honoraryTitle", "RiftLab 荣誉成员")
        duplicate["source"] = item.get("link", "")
        return
    history.append({
        "name": name,
        "realName": str(row.get("realName", "")),
        "role": role,
        "current": False,
        "honoraryTitle": "RiftLab 荣誉成员",
        "source": item.get("link", ""),
        "note": "离任后转入历史荣誉档案",
    })


def apply_official_event(team: dict[str, Any], item: dict[str, str]) -> list[str]:
''')
replace(path,
'''            if len(kept) != len(old_rows):
                removed = [row.get("name", "") for row in old_rows if row not in kept]
                team[bucket] = kept
                for name in removed:
                    changes.append(f"remove {bucket}:{name}")
''',
'''            if len(kept) != len(old_rows):
                removed_rows = [row for row in old_rows if row not in kept]
                team[bucket] = kept
                for row in removed_rows:
                    name = row.get("name", "")
                    if bucket == "management":
                        archive_management(team, row, item)
                        changes.append(f"archive management:{name}")
                    else:
                        changes.append(f"remove {bucket}:{name}")
''')
replace(path,
'''    return changes


def same_staff(a: list[dict[str, Any]], b: list[dict[str, Any]]) -> bool:
''',
'''    operator_names = extract_operators(text)
    if operator_names:
        current_names = [str(row.get("name", "")).strip() for row in team.get("operators", []) if row.get("name")]
        if current_names != operator_names:
            team["operators"] = [
                {"name": name, "role": "OPERATOR", "source": "官方公告 · auto-sync"}
                for name in operator_names
            ]
            changes.append("operators:" + " × ".join(operator_names))

    return changes


def same_staff(a: list[dict[str, Any]], b: list[dict[str, Any]]) -> bool:
''')
replace(path,
'''    for code, team in teams.items():
        for bucket in ("management", "staff"):
''',
'''    for code, team in teams.items():
        for bucket in ("management", "staff"):
''')
# append validation for optional history just before main
replace(path,
'''                if key in seen:
                    raise ValueError(f"{code}.{bucket} duplicate {name}/{role}")
                seen.add(key)


def main() -> int:
''',
'''                if key in seen:
                    raise ValueError(f"{code}.{bucket} duplicate {name}/{role}")
                seen.add(key)
        history = team.get("history", [])
        if not isinstance(history, list):
            raise ValueError(f"{code}.history must be array")
        for row in history:
            if not str(row.get("name", "")).strip() or not str(row.get("role", row.get("formerRole", ""))).strip():
                raise ValueError(f"{code}.history contains empty name/role")


def main() -> int:
''')

# --- data schema cleanup: no corporate/credit-code track; preserve IG founder as legacy honor ---
data_path = ROOT / "data/lpl/team_profiles.json"
data = json.loads(data_path.read_text(encoding="utf-8"))
policy = data.setdefault("refreshPolicy", {})
policy.pop("corporateCheckHours", None)
policy["operatorMonitorHours"] = 3
for team in data.get("teams", {}).values():
    team.pop("corporate", None)
    for row in team.get("history", []):
        row.setdefault("honoraryTitle", "RiftLab 荣誉成员")
        if row.get("role") == "FOUNDER":
            row["honoraryTitle"] = "创始人 · RiftLab 荣誉成员"
data["updatedAt"] = "2026-09-09T02:15:00Z"
data_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

# --- bump app and concise changelog ---
replace("app/build.gradle.kts",
'''        versionCode = 26
        versionName = "1.0.0-dev.26"
''',
'''        versionCode = 27
        versionName = "1.0.0-dev.27"
''')
replace("app/build.gradle.kts",
'''// dev.26: remote-first dynamic team directory with scheduled source synchronization.''',
'''// dev.27: operator-focused organisation data plus historical management honor archive.''')

changelog = ROOT / "DEV_CHANGELOG.txt"
old = changelog.read_text(encoding="utf-8")
entry = (
    "dev.27：战队组织资料进一步收敛为电竞真正关心的“运营主体”，移除客户端工商/法人展示链路，不采集社会信用代码等无关字段。"
    "Team Data Sync 扩展监控重组、收购、运营主体/运营方变更等官方公告；只有明确措辞才自动改写 operators，不确定事件继续进入待核验状态。"
    "管理负责人离任时不再从数据库直接消失，而是从 current management 自动迁移到 RIFT LEGACY / 历史荣誉，保留曾任职务、姓名与离任来源，并显示“RiftLab 荣誉成员 · 曾任XX”。"
    "该荣誉标签属于 RiftLab 历史档案语义，不冒充俱乐部官方授予头衔；已有明确历史身份者可叠加展示，例如 IG 王思聪显示“创始人 · RiftLab 荣誉成员”。\n\n"
)
changelog.write_text(entry + old, encoding="utf-8")

print("dev27 operator/legacy migration applied")
