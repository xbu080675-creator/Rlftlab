from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"target not found in {path}: {old[:160]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Models: person identity, remote avatar, employment timeline.
path = "app/src/main/java/com/riftlab/app/data/Models.kt"
replace(path,
'''data class EsportsStaffRef(
    val name: String,
    val role: String,
    val source: String,
    val realName: String = "",
    val displayRole: String = "",
    val socialLinks: List<EsportsSocialLink> = emptyList()
)
''',
'''data class EsportsCareerRef(
    val team: String,
    val role: String,
    val displayRole: String = "",
    val current: Boolean = false,
    val startDate: String = "",
    val endDate: String = "",
    val source: String = ""
)

data class EsportsStaffRef(
    val name: String,
    val role: String,
    val source: String,
    val realName: String = "",
    val displayRole: String = "",
    val socialLinks: List<EsportsSocialLink> = emptyList(),
    val personId: String = "",
    val imageUrl: String = "",
    val avatarSource: String = "",
    val careerHistory: List<EsportsCareerRef> = emptyList()
)
''')

# Dynamic data provider: merge team directory with canonical people directory.
path = "app/src/main/java/com/riftlab/app/data/DynamicTeamDataProvider.kt"
replace(path,
'''internal data class TeamHistoryRef(
    val name: String,
    val formerRole: String,
    val realName: String = "",
    val honoraryTitle: String = "RiftLab 荣誉成员",
    val source: String = "",
    val note: String = ""
)
''',
'''internal data class TeamHistoryRef(
    val name: String,
    val formerRole: String,
    val realName: String = "",
    val honoraryTitle: String = "RiftLab 荣誉成员",
    val source: String = "",
    val note: String = "",
    val personId: String = "",
    val imageUrl: String = "",
    val avatarSource: String = "",
    val careerHistory: List<EsportsCareerRef> = emptyList()
)
''')
replace(path,
'''        private val ENDPOINTS = listOf(
            "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/lpl/team_profiles.json",
            "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/lpl/team_profiles.json"
        )

        private data class CachedDirectory(val fetchedAt: Long, val root: JSONObject)
        private val cache = AtomicReference<CachedDirectory?>(null)
''',
'''        private val ENDPOINTS = listOf(
            "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/lpl/team_profiles.json",
            "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/lpl/team_profiles.json"
        )
        private val PEOPLE_ENDPOINTS = listOf(
            "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/lpl/people.json",
            "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/lpl/people.json"
        )

        private data class CachedDirectory(val fetchedAt: Long, val root: JSONObject)
        private val cache = AtomicReference<CachedDirectory?>(null)
        private val peopleCache = AtomicReference<CachedDirectory?>(null)
''')
replace(path,
'''        val management = parseStaff(node.optJSONArray("management"), sourceLabel)
        val staff = parseStaff(node.optJSONArray("staff"), sourceLabel)
        val operators = parseOperators(node.optJSONArray("operators"))
        val history = parseHistory(node.optJSONArray("history"), sourceLabel)
''',
'''        val peopleRoot = runCatching { peopleDirectory() }.getOrNull()
        val management = parseStaff(node.optJSONArray("management"), sourceLabel, code, peopleRoot)
        val staff = parseStaff(node.optJSONArray("staff"), sourceLabel, code, peopleRoot)
        val operators = parseOperators(node.optJSONArray("operators"))
        val history = parseHistory(node.optJSONArray("history"), sourceLabel, code, peopleRoot)
''')
replace(path,
'''    private fun getJson(endpoint: String): JSONObject {
''',
'''    private fun peopleDirectory(): JSONObject {
        val now = System.currentTimeMillis()
        peopleCache.get()?.takeIf { now - it.fetchedAt < CACHE_TTL_MS }?.let { return it.root }

        var lastError: Throwable? = null
        for (endpoint in PEOPLE_ENDPOINTS) {
            val result = runCatching { getJson(endpoint) }
            result.onSuccess { root ->
                if (root.optInt("schemaVersion", 0) <= 0 || root.optJSONObject("people") == null) {
                    lastError = IllegalStateException("people directory schema invalid")
                } else {
                    peopleCache.set(CachedDirectory(now, root))
                    return root
                }
            }.onFailure { lastError = it }
        }
        throw lastError ?: IllegalStateException("people directory unavailable")
    }

    private fun getJson(endpoint: String): JSONObject {
''')
replace(path,
'''    private fun parseStaff(rows: JSONArray?, source: String): List<EsportsStaffRef> {
        if (rows == null) return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val role = item.optString("role").trim()
                if (name.isBlank() || role.isBlank()) continue
                add(
                    EsportsStaffRef(
                        name = name,
                        role = role,
                        source = item.optString("source").ifBlank { source },
                        realName = item.optString("realName"),
                        displayRole = item.optString("displayRole")
                    )
                )
            }
        }
    }

    private fun parseHistory(rows: JSONArray?, source: String): List<TeamHistoryRef> {
''',
'''    private fun parseStaff(
        rows: JSONArray?,
        source: String,
        teamCode: String,
        peopleRoot: JSONObject?
    ): List<EsportsStaffRef> {
        if (rows == null) return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val role = item.optString("role").trim()
                if (name.isBlank() || role.isBlank()) continue
                val realName = item.optString("realName")
                val person = findPerson(peopleRoot, teamCode, name, realName)
                val avatar = person?.second?.optJSONObject("avatar")
                add(
                    EsportsStaffRef(
                        name = name,
                        role = role,
                        source = item.optString("source").ifBlank { source },
                        realName = realName,
                        displayRole = item.optString("displayRole"),
                        personId = person?.first.orEmpty(),
                        imageUrl = avatar?.optString("url").orEmpty(),
                        avatarSource = avatar?.optString("source").orEmpty(),
                        careerHistory = parseCareer(person?.second?.optJSONArray("employments"))
                    )
                )
            }
        }
    }

    private fun parseHistory(
        rows: JSONArray?,
        source: String,
        teamCode: String,
        peopleRoot: JSONObject?
    ): List<TeamHistoryRef> {
''')
replace(path,
'''                add(
                    TeamHistoryRef(
                        name = name,
                        formerRole = formerRole,
                        realName = item.optString("realName"),
                        honoraryTitle = item.optString("honoraryTitle").ifBlank { "RiftLab 荣誉成员" },
                        source = item.optString("source").ifBlank { source },
                        note = item.optString("note")
                    )
                )
''',
'''                val realName = item.optString("realName")
                val person = findPerson(peopleRoot, teamCode, name, realName)
                val avatar = person?.second?.optJSONObject("avatar")
                add(
                    TeamHistoryRef(
                        name = name,
                        formerRole = formerRole,
                        realName = realName,
                        honoraryTitle = item.optString("honoraryTitle").ifBlank { "RiftLab 荣誉成员" },
                        source = item.optString("source").ifBlank { source },
                        note = item.optString("note"),
                        personId = person?.first.orEmpty(),
                        imageUrl = avatar?.optString("url").orEmpty(),
                        avatarSource = avatar?.optString("source").orEmpty(),
                        careerHistory = parseCareer(person?.second?.optJSONArray("employments"))
                    )
                )
''')
replace(path,
'''    private fun parseOperators(rows: JSONArray?): String {
''',
'''    private fun findPerson(
        peopleRoot: JSONObject?,
        teamCode: String,
        name: String,
        realName: String
    ): Pair<String, JSONObject>? {
        peopleRoot ?: return null
        val lookup = peopleRoot.optJSONObject("lookup") ?: return null
        val people = peopleRoot.optJSONObject("people") ?: return null
        val keys = listOf(
            "$teamCode|${personToken(name)}",
            "$teamCode|${personToken(realName)}",
            "*|${personToken(name)}",
            "*|${personToken(realName)}"
        ).filterNot { it.endsWith("|") }
        val id = keys.firstNotNullOfOrNull { key -> lookup.optString(key).takeIf { it.isNotBlank() } } ?: return null
        return people.optJSONObject(id)?.let { id to it }
    }

    private fun parseCareer(rows: JSONArray?): List<EsportsCareerRef> {
        if (rows == null) return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                val team = item.optString("team").trim()
                val role = item.optString("role").trim()
                if (team.isBlank() || role.isBlank()) continue
                add(
                    EsportsCareerRef(
                        team = team,
                        role = role,
                        displayRole = item.optString("displayRole"),
                        current = item.optBoolean("current", false),
                        startDate = item.optString("startDate"),
                        endDate = item.optString("endDate"),
                        source = item.optString("source")
                    )
                )
            }
        }
    }

    private fun personToken(value: String): String =
        value.uppercase().filter { it.isLetterOrDigit() }

    private fun parseOperators(rows: JSONArray?): String {
''')

# Team detail UI: real staff/management/history avatars, with career hints.
path = "app/src/main/java/com/riftlab/app/ui/TeamDetailUi.kt"
replace(path,
'''        Box(
            Modifier.size(34.dp).background(RiftPanelAlt, CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(if (management) "管" else "教", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
''',
'''        PersonAvatar(
            imageUrl = staff.imageUrl,
            label = staff.name,
            fallbackGlyph = if (management) "管" else "教",
            modifier = Modifier.size(34.dp)
        )
''')
replace(path,
'''            if (staff.realName.isNotBlank()) Text(staff.realName, color = RiftMuted, fontSize = 8.sp, maxLines = 1)
            if (staff.socialLinks.isNotEmpty()) {
''',
'''            if (staff.realName.isNotBlank()) Text(staff.realName, color = RiftMuted, fontSize = 8.sp, maxLines = 1)
            val former = staff.careerHistory.filterNot { it.current }.takeLast(2)
            if (former.isNotEmpty()) {
                Text(
                    "履历 · " + former.joinToString(" · ") { "${it.team} ${it.displayRole.ifBlank { staffRoleLabel(it.role) }}" },
                    color = RiftMuted,
                    fontSize = 7.sp,
                    maxLines = 1
                )
            }
            if (staff.socialLinks.isNotEmpty()) {
''')
replace(path,
'''            Text(staff.displayRole.ifBlank { staffRoleLabel(staff.role) }, color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Text(staff.source, color = RiftMuted, fontSize = 7.sp)
''',
'''            Text(staff.displayRole.ifBlank { staffRoleLabel(staff.role) }, color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            if (staff.avatarSource.isNotBlank()) Text("头像 · ${staff.avatarSource}", color = RiftMuted, fontSize = 6.sp)
            Text(staff.source, color = RiftMuted, fontSize = 7.sp)
''')
replace(path,
'''        Box(
            Modifier.size(34.dp).background(RiftPanelAlt, CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("誉", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
''',
'''        PersonAvatar(
            imageUrl = history.imageUrl,
            label = history.name,
            fallbackGlyph = "誉",
            modifier = Modifier.size(34.dp)
        )
''')
replace(path,
'''@Composable
private fun TeamStaffRow(staff: EsportsStaffRef, management: Boolean) {
''',
'''@Composable
private fun PersonAvatar(
    imageUrl: String,
    label: String,
    fallbackGlyph: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier.background(RiftPanelAlt, CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl.isBlank()) {
            Text(fallbackGlyph, color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        } else {
            SubcomposeAsyncImage(
                model = imageUrl,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = {
                    Text(fallbackGlyph, color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                },
                error = {
                    Text(fallbackGlyph, color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                },
                success = { SubcomposeAsyncImageContent() }
            )
        }
    }
}

@Composable
private fun TeamStaffRow(staff: EsportsStaffRef, management: Boolean) {
''')
replace(path,
'''            if (history.note.isNotBlank()) Text(history.note, color = RiftMuted, fontSize = 8.sp, maxLines = 2)
''',
'''            if (history.note.isNotBlank()) Text(history.note, color = RiftMuted, fontSize = 8.sp, maxLines = 2)
            val timeline = history.careerHistory.takeLast(2)
            if (timeline.isNotEmpty()) {
                Text(
                    "履历 · " + timeline.joinToString(" · ") { "${it.team} ${it.displayRole.ifBlank { staffRoleLabel(it.role) }}" },
                    color = RiftMuted,
                    fontSize = 7.sp,
                    maxLines = 1
                )
            }
''')

# dev.28 version + concise release note.
path = "app/build.gradle.kts"
replace(path, '        versionCode = 27\n        versionName = "1.0.0-dev.27"', '        versionCode = 28\n        versionName = "1.0.0-dev.28"')
replace(path,
'// dev.27: operator-focused organisation data, historical management honor archive, and verified multi-role titles.\n',
'// dev.28: canonical people entities, sourced staff avatars, and employment timelines.\n')

path = "DEV_CHANGELOG.txt"
p = ROOT / path
text = p.read_text(encoding="utf-8")
entry = "dev.28：战队管理层/教练组升级为独立人物实体库。新增 people.json，将人物身份、别名、头像来源和任职履历与战队快照分离；App 会按战队+姓名解析人物实体，管理层、教练组和历史荣誉均支持远程头像，头像失败时仍回退“管/教/誉”占位。任职记录保留 current、职务、开始/结束时间和来源，跨队/复职不再靠覆盖旧字段。后台新增人物同步器，从 team_profiles 自动维护人物与履历，并以官方/认证社媒优先、第三方资料库补缺的策略逐步解析头像；只存远程引用与来源，不复制第三方图片文件。"
if not text.startswith("dev.28："):
    p.write_text(entry + "\n\n" + text, encoding="utf-8")
