package com.riftlab.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

internal data class TeamDynamicSupplement(
    val profile: TeamProfileSupplement,
    val staff: TeamStaffSupplement,
    val organizationSummary: String = "",
    val legalSummary: String = "",
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
    private val fallbackStaff: LplStaffSnapshotProvider = LplStaffSnapshotProvider()
) {
    companion object {
        private const val CACHE_TTL_MS = 30L * 60L * 1000L
        private val ENDPOINTS = listOf(
            "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/lpl/team_profiles.json",
            "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/lpl/team_profiles.json"
        )

        private data class CachedDirectory(val fetchedAt: Long, val root: JSONObject)
        private val cache = AtomicReference<CachedDirectory?>(null)
    }

    suspend fun fetch(team: EsportsTeamRef, details: EsportsTeamDetails): TeamDynamicSupplement {
        val code = resolveCode(team, details)
        val remote = runCatching { fetchRemote(code) }.getOrNull()
        if (remote != null) return remote

        val profile = runCatching { fallbackProfile.fetch(team, details) }
            .getOrElse { TeamProfileSupplement(status = "管理层离线快照读取失败") }
        val staff = runCatching { fallbackStaff.fetch(team, details) }
            .getOrElse { TeamStaffSupplement(status = "教练组离线快照读取失败") }
        return TeamDynamicSupplement(
            profile = profile,
            staff = staff,
            organizationSummary = "动态目录暂不可达 · 已回退 APK 离线快照",
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
        val sourceLabel = buildString {
            append("RiftLab Dynamic Data")
            if (verifiedAt.isNotBlank()) append(" · verified ").append(verifiedAt)
        }

        val management = parseStaff(node.optJSONArray("management"), sourceLabel)
        val staff = parseStaff(node.optJSONArray("staff"), sourceLabel)
        val teamLinks = parseSourcesAsLinks(node.optJSONArray("sources"), sourceLabel)
        val operators = parseOperators(node.optJSONArray("operators"))
        val corporate = node.optJSONObject("corporate")
        val legalEntity = corporate?.optString("legalEntity").orEmpty()
        val legalRepresentative = corporate?.optString("legalRepresentative").orEmpty()
        val corporateNote = corporate?.optString("note").orEmpty()

        val statusBits = mutableListOf<String>()
        statusBits += sourceLabel
        if (datasetUpdatedAt.isNotBlank()) statusBits += "dataset $datasetUpdatedAt"
        if (operators.isNotBlank()) statusBits += "运营：$operators"

        val legalSummary = when {
            legalEntity.isNotBlank() && legalRepresentative.isNotBlank() ->
                "$legalEntity · 法定代表人：$legalRepresentative"
            corporateNote.isNotBlank() -> corporateNote
            else -> ""
        }

        return TeamDynamicSupplement(
            profile = TeamProfileSupplement(
                teamLinks = teamLinks,
                playerLinks = emptyMap(),
                management = management,
                status = statusBits.joinToString(" · ")
            ),
            staff = TeamStaffSupplement(
                staff = staff,
                status = if (staff.isEmpty()) "$sourceLabel · 暂无远程教练组记录" else "$sourceLabel · 教练组 ${staff.size} 人"
            ),
            organizationSummary = operators,
            legalSummary = legalSummary,
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
        throw lastError ?: IllegalStateException("team directory unavailable")
    }

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

    private fun parseStaff(rows: JSONArray?, source: String): List<EsportsStaffRef> {
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
                        realName = item.optString("realName")
                    )
                )
            }
        }
    }

    private fun parseSourcesAsLinks(rows: JSONArray?, source: String): List<EsportsSocialLink> {
        if (rows == null) return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                val url = item.optString("url").trim()
                if (url.isBlank()) continue
                add(
                    EsportsSocialLink(
                        platform = item.optString("type").ifBlank { "SOURCE" },
                        url = url,
                        label = item.optString("label").ifBlank { "来源" },
                        source = source
                    )
                )
            }
        }
    }

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
