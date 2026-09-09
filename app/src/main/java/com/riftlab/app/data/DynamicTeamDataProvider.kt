package com.riftlab.app.data

import com.riftlab.app.RiftLabApplication
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

internal data class TeamHistoryRef(
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

internal data class TeamDynamicSupplement(
    val profile: TeamProfileSupplement,
    val staff: TeamStaffSupplement,
    val organizationSummary: String = "",
    val history: List<TeamHistoryRef> = emptyList(),
    val verifiedAt: String = "",
    val sourceMode: String = "remote"
)

/**
 * Remote-first LPL organisation/staff directory.
 *
 * Normal operation reads a tiny JSON dataset from the repository/CDN so team personnel can change
 * without shipping a new APK. The old Kotlin snapshots remain strictly as an offline fallback.
 */
internal class DynamicTeamDataProvider(
    private val fallbackProfile: LeaguepediaProfileProvider = LeaguepediaProfileProvider(),
    private val fallbackStaff: LplStaffSnapshotProvider = LplStaffSnapshotProvider(),
    private val globalStaff: GlobalTeamStaffProvider = GlobalTeamStaffProvider()
) {
    companion object {
        private const val CACHE_TTL_MS = 30L * 60L * 1000L
        private val ENDPOINTS = listOf(
            "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/lpl/team_profiles.json",
            "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/lpl/team_profiles.json"
        )
        private val PEOPLE_ENDPOINTS = listOf(
            "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/lpl/people.json",
            "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/lpl/people.json"
        )

        private data class CachedDirectory(val fetchedAt: Long, val root: JSONObject)
        private val cache = AtomicReference<CachedDirectory?>(null)
        private val peopleCache = AtomicReference<CachedDirectory?>(null)
    }

    suspend fun fetch(team: EsportsTeamRef, details: EsportsTeamDetails): TeamDynamicSupplement {
        val code = resolveCode(team, details)
        val remote = if (code.isNotBlank()) runCatching { fetchRemote(code) }.getOrNull() else null
        if (remote != null) return remote

        // Non-LPL teams were previously sent into LPL-only snapshots, which guaranteed an empty
        // management/coaching section. Use the global current-roster mirror / Leaguepedia resolver
        // instead. Riot getTeams remains the player-roster authority.
        if (code.isBlank()) {
            val global = runCatching { globalStaff.fetch(team, details) }
                .getOrElse { error ->
                    GlobalTeamStaffSnapshot(
                        status = "海外人员资料同步失败 · ${error.message?.take(80).orEmpty()}",
                        sourceMode = "global-error"
                    )
                }
            return TeamDynamicSupplement(
                profile = TeamProfileSupplement(
                    management = global.management,
                    status = global.status
                ),
                staff = TeamStaffSupplement(
                    staff = global.staff,
                    status = global.status
                ),
                sourceMode = global.sourceMode
            )
        }

        val profile = runCatching { fallbackProfile.fetch(team, details) }
            .getOrElse { TeamProfileSupplement(status = "管理层离线快照读取失败") }
        val staff = runCatching { fallbackStaff.fetch(team, details) }
            .getOrElse { TeamStaffSupplement(status = "教练组离线快照读取失败") }
        return TeamDynamicSupplement(
            profile = profile.copy(status = "RiftLab Dynamic Data 暂不可达 · ${profile.status}"),
            staff = staff.copy(status = "RiftLab Dynamic Data 暂不可达 · ${staff.status}"),
            sourceMode = "fallback"
        )
    }

    private fun fetchRemote(code: String): TeamDynamicSupplement? {
        if (code.isBlank()) return null
        val root = directory()
        val teams = root.optJSONObject("teams") ?: return null
        val node = teams.optJSONObject(code) ?: return null
        val verifiedAt = node.optString("verifiedAt")
        val datasetUpdatedAt = root.optString("updatedAt")
        val sourceCount = node.optJSONArray("sources")?.length() ?: 0
        val sourceLabel = buildString {
            append("RiftLab Dynamic Data")
            if (verifiedAt.isNotBlank()) append(" · verified ").append(verifiedAt)
        }

        val peopleRoot = runCatching { peopleDirectory() }.getOrNull()
        val management = parseStaff(node.optJSONArray("management"), sourceLabel, code, peopleRoot)
        val staff = parseStaff(node.optJSONArray("staff"), sourceLabel, code, peopleRoot)
        val operators = parseOperators(node.optJSONArray("operators"))
        val history = parseHistory(node.optJSONArray("history"), sourceLabel, code, peopleRoot)

        val statusBits = mutableListOf<String>()
        statusBits += sourceLabel
        if (datasetUpdatedAt.isNotBlank()) statusBits += "dataset $datasetUpdatedAt"
        if (sourceCount > 0) statusBits += "来源 $sourceCount"
        if (operators.isNotBlank()) statusBits += "运营：$operators"

        return TeamDynamicSupplement(
            profile = TeamProfileSupplement(
                teamLinks = emptyList(),
                playerLinks = emptyMap(),
                management = management,
                status = statusBits.joinToString(" · ")
            ),
            staff = TeamStaffSupplement(
                staff = staff,
                status = if (staff.isEmpty()) "$sourceLabel · 暂无远程教练组记录" else "$sourceLabel · 教练组 ${staff.size} 人"
            ),
            organizationSummary = operators,
            history = history,
            verifiedAt = verifiedAt,
            sourceMode = "remote"
        )
    }

    private fun directory(): JSONObject {
        val now = System.currentTimeMillis()
        cache.get()?.takeIf { now - it.fetchedAt < CACHE_TTL_MS }?.let { return it.root }

        var lastError: Throwable? = null
        for (endpoint in ENDPOINTS) {
            val result = runCatching { getJson(endpoint) }
            result.onSuccess { root ->
                if (root.optInt("schemaVersion", 0) <= 0 || root.optJSONObject("teams") == null) {
                    lastError = IllegalStateException("team directory schema invalid")
                } else {
                    cache.set(CachedDirectory(now, root))
                    return root
                }
            }.onFailure { lastError = it }
        }
        loadBundled("team_profiles.json")?.let { root ->
            if (root.optInt("schemaVersion", 0) > 0 && root.optJSONObject("teams") != null) {
                cache.set(CachedDirectory(now, root))
                return root
            }
        }
        throw lastError ?: IllegalStateException("team directory unavailable")
    }

    private fun peopleDirectory(): JSONObject {
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
        loadBundled("people.json")?.let { root ->
            if (root.optInt("schemaVersion", 0) > 0 && root.optJSONObject("people") != null) {
                peopleCache.set(CachedDirectory(now, root))
                return root
            }
        }
        throw lastError ?: IllegalStateException("people directory unavailable")
    }

    private fun loadBundled(name: String): JSONObject? = runCatching {
        RiftLabApplication.appContext.assets.open(name).bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()

    private fun getJson(endpoint: String): JSONObject {
        val hourBucket = System.currentTimeMillis() / 3_600_000L
        val connection = URL("$endpoint?riftlab=$hourBucket").openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 5_000
            connection.readTimeout = 7_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("User-Agent", "RiftLab-TeamDirectory/1")
            val code = connection.responseCode
            if (code !in 200..299) error("team directory HTTP $code")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseStaff(
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
        if (rows == null) return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                if (item.optBoolean("current", false)) continue
                val name = item.optString("name").trim()
                val formerRole = item.optString("formerRole").ifBlank { item.optString("role") }.trim()
                if (name.isBlank() || formerRole.isBlank()) continue
                val realName = item.optString("realName")
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
            }
        }
    }

    private fun findPerson(
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
        if (rows == null) return ""
        val names = buildList {
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                item.optString("name").trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        return names.distinct().joinToString(" × ")
    }

    private fun resolveCode(team: EsportsTeamRef, details: EsportsTeamDetails): String {
        val candidates = listOf(details.code, team.code, details.name, team.name, details.slug, team.slug)
            .map(::token)
        return candidates.firstNotNullOfOrNull { aliases[it] }
            ?: candidates.firstOrNull { it in knownCodes }
            .orEmpty()
    }

    private fun token(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

    private val knownCodes = setOf("AL", "BLG", "TES", "JDG", "LGD", "EDG", "TT", "IG", "LNG", "NIP", "WBG", "WE")
    private val aliases = mapOf(
        "ANYONESLEGEND" to "AL",
        "BILIBILIGAMING" to "BLG",
        "TOPESPORTS" to "TES",
        "BEIJINGJDGESPORTS" to "JDG",
        "JDGAMING" to "JDG",
        "LGDGAMING" to "LGD",
        "EDWARDGAMING" to "EDG",
        "THUNDERTALKGAMING" to "TT",
        "INVICTUSGAMING" to "IG",
        "SUZHOULNGESPORTS" to "LNG",
        "LNGESPORTS" to "LNG",
        "SHENZHENNINJASINPYJAMAS" to "NIP",
        "NINJASINPYJAMASCN" to "NIP",
        "NINJASINPYJAMAS" to "NIP",
        "WEIBOGAMING" to "WBG",
        "XIATEKTEAMWE" to "WE",
        "TEAMWE" to "WE"
    )
}
