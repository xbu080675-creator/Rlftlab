#!/usr/bin/env python3
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data" / "lpl"

OPERATOR_PATCH = {
    "AL": [{"name":"All Gamers","role":"PARENT_ORG","source":"AG.AL / All Gamers public organization record"}],
    "BLG": [{"name":"哔哩哔哩 / Bilibili","role":"OWNER_OR_PARENT","source":"Bilibili Gaming public history"}],
    "TES": [{"name":"滔搏 / Topsports","role":"OWNER_OR_PARENT","source":"Top Esports public history"}],
    "JDG": [{"name":"京东 / JD.com","role":"OWNER_OR_PARENT","source":"JD Gaming public history"}],
    "LGD": [{"name":"LGD电子竞技俱乐部 / LGD Gaming","role":"OPERATOR","source":"LGD public club record"}],
    "EDG": [{"name":"超竞集团 / EDG电子竞技俱乐部","role":"OPERATOR_OR_PARENT","source":"EDG public organization record"}],
    "TT": [{"name":"趣丸科技 / TT电竞","role":"OWNER_OR_OPERATOR","source":"趣丸集团官网 TT电竞公开信息"}],
    "IG": [{"name":"氧望体育","role":"OPERATOR","source":"iG 2024-11-30 重组公告"},{"name":"虎牙直播","role":"STRATEGIC_PARTNER","source":"iG 2024-11-30 重组公告"}],
    "LNG": [{"name":"李宁 / Li-Ning","role":"OWNER_OR_PARENT","source":"LNG public history"}],
    "NIP": [{"name":"NIP Group","role":"OWNER_OR_OPERATOR","source":"NIP Group official history"}],
    "WBG": [{"name":"微博 / Weibo Corporation","role":"OWNER_OR_PARENT","source":"WBG official/public history"}],
    "WE": [{"name":"WE电子竞技俱乐部 / Team WE","role":"OPERATOR","source":"WE官方微博"},{"name":"西安曲江","role":"CO_BRAND_HOME_PARTNER","source":"西安曲江WE官方微博命名"}]
}


def replace_once(text: str, old: str, new: str, path: Path) -> str:
    if old not in text:
        raise RuntimeError(f"pattern missing in {path}: {old[:120]!r}")
    return text.replace(old, new, 1)

# ---- current organization audit ----
profiles_path = DATA / "team_profiles.json"
profiles = json.loads(profiles_path.read_text(encoding="utf-8"))
profiles["updatedAt"] = "2026-09-09T04:45:00Z"
for code, rows in OPERATOR_PATCH.items():
    team = profiles["teams"][code]
    existing = {str(row.get("name", "")): row for row in team.get("operators", []) if isinstance(row, dict)}
    merged = []
    for row in rows:
        merged.append({**existing.get(row["name"], {}), **row})
    for name, row in existing.items():
        if name and all(item["name"] != name for item in merged):
            merged.append(row)
    team["operators"] = merged
profiles_path.write_text(json.dumps(profiles, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

# ---- application context for offline seeds ----
app_path = ROOT / "app/src/main/java/com/riftlab/app/RiftLabApplication.kt"
app = app_path.read_text(encoding="utf-8")
if "lateinit var appContext" not in app:
    app = replace_once(
        app,
        "class RiftLabApplication : Application(), ImageLoaderFactory {\n    override fun onCreate() {\n        super.onCreate()\n        RiotPersistedMirror.initialize(this)\n    }",
        "class RiftLabApplication : Application(), ImageLoaderFactory {\n    companion object {\n        lateinit var appContext: android.content.Context\n            private set\n    }\n\n    override fun onCreate() {\n        super.onCreate()\n        appContext = applicationContext\n        RiotPersistedMirror.initialize(this)\n    }",
        app_path,
    )
app_path.write_text(app, encoding="utf-8")

# ---- dynamic team data: Raw first, CDN second, APK seed last ----
dyn_path = ROOT / "app/src/main/java/com/riftlab/app/data/DynamicTeamDataProvider.kt"
dyn = dyn_path.read_text(encoding="utf-8")
if "import com.riftlab.app.RiftLabApplication" not in dyn:
    dyn = dyn.replace("package com.riftlab.app.data\n\n", "package com.riftlab.app.data\n\nimport com.riftlab.app.RiftLabApplication\n", 1)
dyn = dyn.replace(
    '            "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/lpl/team_profiles.json",\n            "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/lpl/team_profiles.json"',
    '            "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/lpl/team_profiles.json",\n            "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/lpl/team_profiles.json"'
)
dyn = dyn.replace(
    '            "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/lpl/people.json",\n            "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/lpl/people.json"',
    '            "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/lpl/people.json",\n            "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/lpl/people.json"'
)
if 'loadBundled("team_profiles.json")' not in dyn:
    dyn = replace_once(
        dyn,
        '        throw lastError ?: IllegalStateException("team directory unavailable")\n    }\n\n    private fun peopleDirectory(): JSONObject {',
        '        loadBundled("team_profiles.json")?.let { root ->\n            if (root.optInt("schemaVersion", 0) > 0 && root.optJSONObject("teams") != null) {\n                cache.set(CachedDirectory(now, root))\n                return root\n            }\n        }\n        throw lastError ?: IllegalStateException("team directory unavailable")\n    }\n\n    private fun peopleDirectory(): JSONObject {',
        dyn_path,
    )
if 'loadBundled("people.json")' not in dyn:
    dyn = replace_once(
        dyn,
        '        throw lastError ?: IllegalStateException("people directory unavailable")\n    }\n\n    private fun getJson(endpoint: String): JSONObject {',
        '        loadBundled("people.json")?.let { root ->\n            if (root.optInt("schemaVersion", 0) > 0 && root.optJSONObject("people") != null) {\n                peopleCache.set(CachedDirectory(now, root))\n                return root\n            }\n        }\n        throw lastError ?: IllegalStateException("people directory unavailable")\n    }\n\n    private fun loadBundled(name: String): JSONObject? = runCatching {\n        RiftLabApplication.appContext.assets.open(name).bufferedReader().use { JSONObject(it.readText()) }\n    }.getOrNull()\n\n    private fun getJson(endpoint: String): JSONObject {',
        dyn_path,
    )
dyn_path.write_text(dyn, encoding="utf-8")

# ---- repository merges Team Archive independently from Riot getTeams ----
repo_path = ROOT / "app/src/main/java/com/riftlab/app/data/TeamDetailRepository.kt"
repo = repo_path.read_text(encoding="utf-8")
if "val archive: TeamArchiveSupplement" not in repo:
    repo = replace_once(repo, '    val organizationSummary: String = "",\n    val history: List<TeamHistoryRef> = emptyList(),', '    val organizationSummary: String = "",\n    val archive: TeamArchiveSupplement = TeamArchiveSupplement(),\n    val history: List<TeamHistoryRef> = emptyList(),', repo_path)
if "private val archiveProvider" not in repo:
    repo = replace_once(repo, '    private val dynamicProvider = DynamicTeamDataProvider()\n    private val lineupProvider = OpggMatchSupplementProvider()', '    private val dynamicProvider = DynamicTeamDataProvider()\n    private val archiveProvider = TeamArchiveProvider()\n    private val lineupProvider = OpggMatchSupplementProvider()', repo_path)
if "private val archiveCache" not in repo:
    repo = replace_once(repo, '    private val historyCache = linkedMapOf<String, List<TeamHistoryRef>>()\n    private var loadJob: Job? = null', '    private val historyCache = linkedMapOf<String, List<TeamHistoryRef>>()\n    private val archiveCache = linkedMapOf<String, TeamArchiveSupplement>()\n    private var loadJob: Job? = null', repo_path)
if 'archive = archiveCache[key]' not in repo:
    repo = replace_once(repo, '                profileStatus = "管理层 · 已缓存，后台检查 RiftLab Dynamic Data",\n                history = historyCache[key].orEmpty(),', '                profileStatus = "管理层 · 已缓存，后台检查 RiftLab Dynamic Data",\n                archive = archiveCache[key] ?: TeamArchiveSupplement(),\n                history = historyCache[key].orEmpty(),', repo_path)
if "val archiveSupplement =" not in repo:
    repo = replace_once(repo, '            val staffSupplement = dynamicSupplement.staff\n            val profileSupplement = dynamicSupplement.profile', '            val archiveSupplement = runCatching { archiveProvider.fetch(effectiveTeam, dynamicBase) }\n                .getOrElse { archiveCache[key] ?: TeamArchiveSupplement(sourceMode = "error") }\n            val staffSupplement = dynamicSupplement.staff\n            val profileSupplement = dynamicSupplement.profile', repo_path)
    repo = replace_once(repo, '            historyCache[key] = dynamicSupplement.history\n            if (image.isNotBlank()) {', '            historyCache[key] = dynamicSupplement.history\n            archiveCache[key] = archiveSupplement\n            if (image.isNotBlank()) {', repo_path)
    repo = replace_once(repo, '                organizationSummary = dynamicSupplement.organizationSummary,\n                history = dynamicSupplement.history,', '                organizationSummary = dynamicSupplement.organizationSummary,\n                archive = archiveSupplement,\n                history = dynamicSupplement.history,', repo_path)
if "val archive = runCatching { archiveProvider.fetch(team, cached) }" not in repo:
    repo = replace_once(repo, '            val dynamic = runCatching { dynamicProvider.fetch(team, cached) }.getOrNull() ?: return@launch\n            val updated = cached.copy(', '            val dynamic = runCatching { dynamicProvider.fetch(team, cached) }.getOrNull() ?: return@launch\n            val archive = runCatching { archiveProvider.fetch(team, cached) }.getOrElse { archiveCache[key] ?: TeamArchiveSupplement() }\n            val updated = cached.copy(', repo_path)
    repo = replace_once(repo, '            historyCache[key] = dynamic.history\n\n            val current = _state.value', '            historyCache[key] = dynamic.history\n            archiveCache[key] = archive\n\n            val current = _state.value', repo_path)
    repo = replace_once(repo, '                organizationSummary = dynamic.organizationSummary,\n                history = dynamic.history,', '                organizationSummary = dynamic.organizationSummary,\n                archive = archive,\n                history = dynamic.history,', repo_path)
repo_path.write_text(repo, encoding="utf-8")

# ---- UI becomes full team archive page ----
ui_path = ROOT / "app/src/main/java/com/riftlab/app/ui/TeamDetailUi.kt"
ui = ui_path.read_text(encoding="utf-8")
for imp in ("TeamAlumniRef", "TeamHonorRef", "TeamLineageRef", "TeamOrganizationRef"):
    line = f"import com.riftlab.app.data.{imp}\n"
    if line not in ui:
        ui = ui.replace("import com.riftlab.app.data.TeamHistoryRef\n", "import com.riftlab.app.data.TeamHistoryRef\n" + line, 1)
old_org = '''        if (state.organizationSummary.isNotBlank()) {
            item { TeamSectionTitle("ORGANIZATION / 当前运营") }
            item { TeamStatus("运营主体：${state.organizationSummary}") }
        }
'''
new_org = '''        val archive = state.archive
        val identity = archive.identity
        if (identity.foundedAt.isNotBlank() || identity.lolDivisionFoundedAt.isNotBlank()) {
            item { TeamSectionTitle("TEAM ARCHIVE / 战队档案") }
            item { TeamArchiveCard(identity.foundedAt, identity.lolDivisionFoundedAt, identity.region, identity.city, archive.updatedAt) }
        }

        val orgRows = (archive.operators + archive.parentOrganizations).distinctBy { "${it.name}|${it.role}" }
        if (orgRows.isNotEmpty() || state.organizationSummary.isNotBlank()) {
            item { TeamSectionTitle("ORGANIZATION / 当前运营") }
            if (orgRows.isNotEmpty()) {
                items(orgRows, key = { "org-${it.name}-${it.role}" }) { org -> TeamOrganizationRow(org) }
            } else {
                item { TeamStatus("运营主体：${state.organizationSummary}") }
            }
        }

        if (archive.peopleInCharge.isNotEmpty()) {
            item { TeamSectionTitle("RESPONSIBLE / 负责人") }
            items(archive.peopleInCharge, key = { "responsible-${it.name}-${it.role}" }) { org -> TeamOrganizationRow(org) }
        }
'''
if "TEAM ARCHIVE / 战队档案" not in ui:
    ui = replace_once(ui, old_org, new_org, ui_path)
coaching_marker = '        item { TeamSectionTitle("COACHING STAFF / 教练组") }\n'
if "HONORS / 战队荣誉" not in ui:
    block = '''        if (archive.alumni.isNotEmpty()) {
            item { TeamSectionTitle("ALUMNI / 历史人员") }
            items(archive.alumni, key = { "alumni-${it.name}-${it.role}-${it.leftAt}" }) { alumni -> TeamAlumniRow(alumni) }
        }

        if (archive.honors.isNotEmpty()) {
            item { TeamSectionTitle("HONORS / 战队荣誉") }
            items(archive.honors, key = { "honor-${it.year}-${it.event}-${it.placement}" }) { honor -> TeamHonorRow(honor) }
        }

        if (archive.lineage.isNotEmpty()) {
            item { TeamSectionTitle("LINEAGE / 战队沿革与前身") }
            items(archive.lineage, key = { "lineage-${it.name}-${it.from}-${it.relation}" }) { lineage -> TeamLineageRow(lineage) }
        }

'''
    ui = replace_once(ui, coaching_marker, block + coaching_marker, ui_path)
helper_marker = '@Composable\nprivate fun SocialLinkRow(links: List<EsportsSocialLink>, compact: Boolean = false) {'
if "private fun TeamArchiveCard(" not in ui:
    helpers = '''@Composable
private fun TeamArchiveCard(foundedAt: String, lolFoundedAt: String, region: String, city: String, updatedAt: String) {
    Column(
        Modifier.fillMaxWidth().background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .border(1.dp, RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (foundedAt.isNotBlank()) Text("俱乐部 / 当前品牌成立 · $foundedAt", color = RiftText, fontSize = 10.sp)
        if (lolFoundedAt.isNotBlank()) Text("英雄联盟谱系起点 · $lolFoundedAt", color = RiftText, fontSize = 10.sp)
        val place = listOf(region, city).filter { it.isNotBlank() }.joinToString(" · ")
        if (place.isNotBlank()) Text("地区 · $place", color = RiftMuted, fontSize = 9.sp)
        if (updatedAt.isNotBlank()) Text("档案核验 · $updatedAt", color = RiftMuted, fontSize = 8.sp)
    }
}

@Composable
private fun TeamOrganizationRow(org: TeamOrganizationRef) {
    Row(
        Modifier.fillMaxWidth().background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .border(1.dp, RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(org.name, color = RiftText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (org.source.isNotBlank()) Text(org.source, color = RiftMuted, fontSize = 7.sp, maxLines = 2)
        }
        Text(org.displayRole.ifBlank { organizationRoleLabel(org.role) }, color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TeamHonorRow(honor: TeamHonorRef) {
    Row(
        Modifier.fillMaxWidth().background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .border(1.dp, RiftCyan.copy(alpha = 0.25f), CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(honor.year, color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(46.dp))
        Column(Modifier.weight(1f)) {
            Text(honor.event, color = RiftText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (honor.tier.isNotBlank()) Text(honor.tier, color = RiftMuted, fontSize = 7.sp)
        }
        Text(honor.placement, color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TeamLineageRow(lineage: TeamLineageRef) {
    Column(
        Modifier.fillMaxWidth().background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .border(1.dp, RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp)).padding(11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(lineage.name, color = RiftText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(lineageRelationLabel(lineage.relation), color = RiftCyan, fontSize = 8.sp)
        }
        val range = listOf(lineage.from, lineage.to.ifBlank { "至今" }).filter { it.isNotBlank() }.joinToString(" → ")
        if (range.isNotBlank()) Text(range, color = RiftMuted, fontSize = 8.sp)
        if (lineage.operator.isNotBlank()) Text("所属 / 运营 · ${lineage.operator}", color = RiftMuted, fontSize = 8.sp)
        if (lineage.note.isNotBlank()) Text(lineage.note, color = RiftMuted, fontSize = 8.sp)
        if (lineage.scope.isNotBlank()) Text("范围 · ${lineage.scope}", color = RiftMuted, fontSize = 7.sp)
    }
}

@Composable
private fun TeamAlumniRow(alumni: TeamAlumniRef) {
    Row(
        Modifier.fillMaxWidth().background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .border(1.dp, RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(alumni.name, color = RiftText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (alumni.realName.isNotBlank()) Text(alumni.realName, color = RiftMuted, fontSize = 8.sp)
            val range = listOf(alumni.joinedAt, alumni.leftAt).filter { it.isNotBlank() }.joinToString(" → ")
            if (range.isNotBlank()) Text(range, color = RiftMuted, fontSize = 8.sp)
        }
        Text(staffRoleLabel(alumni.role), color = RiftCyan, fontSize = 9.sp)
    }
}

private fun organizationRoleLabel(role: String): String = when (playerToken(role)) {
    "PARENTORG", "OWNERORPARENT" -> "上层组织"
    "OPERATOR", "OWNEROROPERATOR", "OPERATORORPARENT" -> "运营主体"
    "STRATEGICPARTNER" -> "战略合作"
    "COBRANDHOMEPARTNER" -> "联合命名 / 主场"
    else -> staffRoleLabel(role)
}

private fun lineageRelationLabel(value: String): String = when (playerToken(value)) {
    "CURRENT" -> "当前"
    "REBRANDED" -> "更名"
    "ACQUIREDANDREBRANDED" -> "收购 / 更名"
    "SLOTACQUIRED" -> "席位继承"
    "MERGERANDREBRAND" -> "合并 / 更名"
    "ORGACQUIRED" -> "组织收购"
    "ORGPREDECESSOR" -> "组织前身"
    "ORGCONTINUITY" -> "品牌延续"
    else -> value.replace('_', ' ')
}

@Composable
private fun SocialLinkRow(links: List<EsportsSocialLink>, compact: Boolean = false) {'''
    ui = replace_once(ui, helper_marker, helpers, ui_path)
ui_path.write_text(ui, encoding="utf-8")

# ---- version / changelog ----
gradle_path = ROOT / "app/build.gradle.kts"
gradle = gradle_path.read_text(encoding="utf-8")
gradle = re.sub(r'versionCode = \d+', 'versionCode = 30', gradle, count=1)
gradle = re.sub(r'versionName = "[^"]+"', 'versionName = "1.0.0-dev.30"', gradle, count=1)
gradle = re.sub(r'// dev\.\d+:.*$', '// dev.30: full Team Archive schema, audited organization lineage and bundled dynamic team seeds.', gradle, flags=re.M)
gradle_path.write_text(gradle, encoding="utf-8")

change_path = ROOT / "DEV_CHANGELOG.txt"
old_change = change_path.read_text(encoding="utf-8")
top = (
    "dev.30：重构战队页面为 Team Archive / 战队档案。新增俱乐部/英雄联盟谱系成立时间、当前运营主体与上层组织、负责人、现役首发/替补、管理层、教练组、历史荣誉、战队荣誉、近期赛程以及前身/收购/更名/席位继承沿革。"
    "对当前 12 支 LPL 战队执行运营主体与谱系审计，并把 AL/BLG/TES/JDG/LGD/EDG/TT/IG/LNG/NIP/WBG/WE 的运营关系写入独立 team_archive.json。"
    "修复动态战队数据在移动网络不可达时退回旧 Kotlin 快照导致 AG爱笑、袁玺等现任管理人员消失的问题：team_profiles.json、people.json、team_archive.json 现在随 APK 内置，远程更新改为 GitHub Raw 优先、jsDelivr 次级、本地种子最终兜底；不再让 CDN 旧缓存覆盖新人员。"
    "历史人员 alumni 结构已纳入档案模型；未被可靠来源核实的旧选手/教练不会凭空补写。"
)
if not old_change.startswith("dev.30："):
    change_path.write_text(top + "\n\n" + old_change, encoding="utf-8")

# ---- OTA bundles all team data seeds ----
ota_path = ROOT / ".github/workflows/ota-direct.yml"
ota = ota_path.read_text(encoding="utf-8")
old_bundle = '''      - name: Bundle Riot mirror seed
        shell: bash
        run: |
          if [ -f data/lpl/riot_persisted_mirror.json ]; then
            mkdir -p app/src/main/assets
            cp data/lpl/riot_persisted_mirror.json app/src/main/assets/riot_persisted_mirror.json
          fi
'''
new_bundle = '''      - name: Bundle resilient data seeds
        shell: bash
        run: |
          mkdir -p app/src/main/assets
          for name in riot_persisted_mirror.json team_profiles.json people.json team_archive.json; do
            if [ -f "data/lpl/$name" ]; then
              cp "data/lpl/$name" "app/src/main/assets/$name"
            fi
          done
'''
if "Bundle resilient data seeds" not in ota:
    ota = replace_once(ota, old_bundle, new_bundle, ota_path)
ota_path.write_text(ota, encoding="utf-8")

# validation
expected = {"AL","BLG","TES","JDG","LGD","EDG","TT","IG","LNG","NIP","WBG","WE"}
archive = json.loads((DATA / "team_archive.json").read_text(encoding="utf-8"))
assert set(archive["teams"]) == expected
assert all(archive["teams"][code].get("operators") for code in expected)
json.loads(profiles_path.read_text(encoding="utf-8"))
json.loads((DATA / "people.json").read_text(encoding="utf-8"))
print("dev30 migration prepared")
