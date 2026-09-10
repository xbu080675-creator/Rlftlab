package com.riftlab.app.data

import com.riftlab.app.RiftLabApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

internal data class GlobalTeamStaffSnapshot(
    val management: List<EsportsStaffRef> = emptyList(),
    val staff: List<EsportsStaffRef> = emptyList(),
    val status: String = "海外人员资料尚未同步",
    val sourceMode: String = "global",
    val recognized: Boolean = false
)

/**
 * Global coaching / team-management resolver for non-LPL clubs.
 *
 * Riot getTeams is authoritative for players but currently does not expose coaches or managers.
 * RiftLab therefore reads a small GitHub mirror of Leaguepedia's current-roster table first and
 * falls back to one conservative Cargo request when the mirror does not yet know a team.
 *
 * The direct Cargo path is deliberately cached for hours because anonymous Leaguepedia Cargo is
 * heavily rate-limited. No page scraping, login bypass or anti-bot circumvention is used.
 */
internal class GlobalTeamStaffProvider {
    companion object {
        private const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L
        private const val NEGATIVE_TTL_MS = 15L * 60L * 1000L
        private const val CARGO_API = "https://lol.fandom.com/api.php"
        private val MIRRORS = listOf(
            "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/global/team_staff.json",
            "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/global/team_staff.json"
        )

        private data class Cached(val fetchedAt: Long, val value: GlobalTeamStaffSnapshot)
        private data class DirectoryCache(val fetchedAt: Long, val root: JSONObject)

        private val teamCache = ConcurrentHashMap<String, Cached>()
        @Volatile private var directoryCache: DirectoryCache? = null
    }

    suspend fun fetch(team: EsportsTeamRef, details: EsportsTeamDetails): GlobalTeamStaffSnapshot = withContext(Dispatchers.IO) {
        val cacheKey = canonicalKey(team, details)
        val now = System.currentTimeMillis()
        teamCache[cacheKey]?.let { cached ->
            val ttl = if (cached.value.recognized) CACHE_TTL_MS else NEGATIVE_TTL_MS
            if (now - cached.fetchedAt < ttl) return@withContext cached.value
        }

        val mirrored = runCatching { fetchFromMirror(team, details) }.getOrNull()
        if (mirrored?.recognized == true) {
            teamCache[cacheKey] = Cached(now, mirrored)
            return@withContext mirrored
        }

        val live = runCatching { fetchFromCargo(team, details) }.getOrElse { error ->
            GlobalTeamStaffSnapshot(
                status = "Riot Teams 不提供管理层/教练字段 · Leaguepedia 当前不可达：${error.message?.take(72).orEmpty()}",
                sourceMode = "global-unavailable",
                recognized = false
            )
        }
        teamCache[cacheKey] = Cached(now, live)
        live
    }

    private fun fetchFromMirror(team: EsportsTeamRef, details: EsportsTeamDetails): GlobalTeamStaffSnapshot? {
        val root = directory()
        val teams = root.optJSONObject("teams") ?: return null
        val candidates = identityTokens(team, details)

        var selected: JSONObject? = null
        var selectedKey = ""
        val keys = teams.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val node = teams.optJSONObject(key) ?: continue
            val aliases = buildSet {
                add(token(key))
                add(token(node.optString("name")))
                add(token(node.optString("short")))
                add(token(node.optString("page")))
                val rawAliases = node.optJSONArray("aliases") ?: JSONArray()
                for (i in 0 until rawAliases.length()) add(token(rawAliases.optString(i)))
            }.filter { it.isNotBlank() }.toSet()
            if (candidates.any { it in aliases }) {
                selected = node
                selectedKey = key
                break
            }
        }
        val node = selected ?: return null
        val source = node.optString("source").ifBlank { "Leaguepedia Current Roster · RiftLab mirror" }
        val management = parseRows(node.optJSONArray("management"), source)
        val staff = parseRows(node.optJSONArray("staff"), source)
        val updated = root.optString("updatedAt")
        val label = node.optString("name").ifBlank { selectedKey }
        return GlobalTeamStaffSnapshot(
            management = management,
            staff = staff,
            status = buildString {
                append("海外人员镜像 · ").append(label)
                if (updated.isNotBlank()) append(" · synced ").append(updated)
                append(" · 管理层 ").append(management.size).append(" · 教练组 ").append(staff.size)
            },
            sourceMode = "global-mirror",
            recognized = true
        )
    }

    private fun directory(): JSONObject {
        val now = System.currentTimeMillis()
        directoryCache?.takeIf { now - it.fetchedAt < CACHE_TTL_MS }?.let { return it.root }
        var last: Throwable? = null
        for (endpoint in MIRRORS) {
            runCatching { getJson(endpoint, 5_000, 7_000) }
                .onSuccess { root ->
                    if (root.optInt("schemaVersion", 0) > 0 && root.optJSONObject("teams") != null) {
                        directoryCache = DirectoryCache(now, root)
                        return root
                    }
                    last = IllegalStateException("global staff mirror schema invalid")
                }
                .onFailure { last = it }
        }
        loadBundled("team_staff.json")?.let { root ->
            if (root.optInt("schemaVersion", 0) > 0 && root.optJSONObject("teams") != null) {
                directoryCache = DirectoryCache(now, root)
                return root
            }
        }
        throw last ?: IllegalStateException("global staff mirror unavailable")
    }

    private fun fetchFromCargo(team: EsportsTeamRef, details: EsportsTeamDetails): GlobalTeamStaffSnapshot {
        val candidates = listOf(details.name, team.name, details.code, team.code, details.slug, team.slug)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(6)
        if (candidates.isEmpty()) return GlobalTeamStaffSnapshot(status = "海外人员源缺少战队标识")

        val where = candidates.joinToString(" OR ", prefix = "(", postfix = ")") { value ->
            "LPC.Team=\"${cargoEscape(value)}\""
        }
        val url = buildString {
            append(CARGO_API)
            append("?action=cargoquery&format=json&limit=80")
            append("&tables=").append(enc("ListplayerCurrent=LPC"))
            append("&fields=").append(enc("LPC.ID=ID,LPC.Name=Name,LPC.Role=Role,LPC.Team=Team"))
            append("&where=").append(enc(where))
        }
        val root = getJson(url, 5_000, 8_000)
        root.optJSONObject("error")?.let { error ->
            throw IOException("Leaguepedia ${error.optString("code")} · ${error.optString("info").take(90)}")
        }
        val rows = root.optJSONArray("cargoquery") ?: JSONArray()
        val management = mutableListOf<EsportsStaffRef>()
        val staff = mutableListOf<EsportsStaffRef>()
        var recognizedRows = 0
        for (i in 0 until rows.length()) {
            val wrapper = rows.optJSONObject(i) ?: continue
            val row = wrapper.optJSONObject("title") ?: wrapper
            recognizedRows += 1
            val normalizedRole = normalizeStaffRole(row.optString("Role")) ?: continue
            val id = row.optString("ID").trim()
            val realName = row.optString("Name").trim()
            val displayName = id.ifBlank { realName }
            if (displayName.isBlank()) continue
            val ref = EsportsStaffRef(
                name = displayName,
                role = normalizedRole,
                source = "Leaguepedia Current Roster · live",
                realName = realName.takeIf { it.isNotBlank() && !it.equals(displayName, ignoreCase = true) }.orEmpty()
            )
            if (isManagementRole(normalizedRole)) management += ref else staff += ref
        }
        val recognized = recognizedRows > 0
        return GlobalTeamStaffSnapshot(
            management = management.distinctBy { token(it.name) to token(it.role) },
            staff = staff.distinctBy { token(it.name) to token(it.role) },
            status = when {
                !recognized -> "Leaguepedia 当前名单未识别该战队 · 不猜管理层/教练"
                management.isEmpty() && staff.isEmpty() -> "Leaguepedia 已识别该战队，但当前名单未公开可核实管理层/教练"
                else -> "Leaguepedia Current Roster · live · 管理层 ${management.size} · 教练组 ${staff.size}"
            },
            sourceMode = "leaguepedia-live",
            recognized = recognized
        )
    }

    private fun parseRows(rows: JSONArray?, fallbackSource: String): List<EsportsStaffRef> {
        if (rows == null) return emptyList()
        return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val name = row.optString("name").trim()
                val role = normalizeStaffRole(row.optString("role")) ?: row.optString("role").trim()
                if (name.isBlank() || role.isBlank()) continue
                add(
                    EsportsStaffRef(
                        name = name,
                        role = role,
                        source = row.optString("source").ifBlank { fallbackSource },
                        realName = row.optString("realName")
                    )
                )
            }
        }.distinctBy { token(it.name) to token(it.role) }
    }

    private fun normalizeStaffRole(raw: String): String? {
        val key = token(raw)
        if (key.isBlank()) return null
        return when {
            key.contains("HEADCOACH") -> "HEAD_COACH"
            key.contains("ASSISTANTCOACH") || key.contains("ASSISTCOACH") -> "ASSISTANT_COACH"
            key.contains("STRATEGICCOACH") || key.contains("STRATEGYCOACH") -> "STRATEGIC_COACH"
            key.contains("POSITIONALCOACH") -> "POSITIONAL_COACH"
            key.contains("COACH") -> "COACH"
            key.contains("ANALYST") -> "ANALYST"
            key.contains("GENERALMANAGER") -> "GENERAL_MANAGER"
            key.contains("ASSISTANTMANAGER") -> "ASSISTANT_MANAGER"
            key.contains("MANAGER") -> "MANAGER"
            key == "LEADER" || key.contains("TEAMLEADER") -> "LEADER"
            key.contains("SUPERVISOR") -> "SUPERVISOR"
            key.contains("ESPORTSDIRECTOR") -> "ESPORTS_DIRECTOR"
            key == "DIRECTOR" || key.endsWith("DIRECTOR") -> "DIRECTOR"
            key.contains("MANAGINGDIRECTOR") -> "MANAGING_DIRECTOR"
            key == "CEO" || key.contains("CHIEFEXECUTIVEOFFICER") -> "CEO"
            key == "COO" || key.contains("CHIEFOPERATINGOFFICER") -> "COO"
            key.contains("COOWNER") -> "CO_OWNER"
            key == "OWNER" -> "OWNER"
            key.contains("FOUNDER") && key.contains("CEO") -> "FOUNDER_AND_CEO"
            key.contains("FOUNDER") -> "FOUNDER"
            key.contains("HEADOFESPORTS") -> "HEAD_OF_ESPORTS"
            key.contains("HEADOFLOL") || key.contains("HEADOFLEAGUEOFLEGENDS") -> "HEAD_OF_LOL"
            else -> null
        }
    }

    private fun isManagementRole(role: String): Boolean {
        val key = token(role)
        return key.contains("MANAGER") || key in setOf(
            "LEADER", "SUPERVISOR", "DIRECTOR", "ESPORTSDIRECTOR", "MANAGINGDIRECTOR",
            "CEO", "COO", "OWNER", "COOWNER", "FOUNDER", "FOUNDERANDCEO", "HEADOFESPORTS", "HEADOFLOL"
        )
    }

    private fun canonicalKey(team: EsportsTeamRef, details: EsportsTeamDetails): String =
        identityTokens(team, details).firstOrNull().orEmpty().ifBlank { "UNKNOWN" }

    private fun identityTokens(team: EsportsTeamRef, details: EsportsTeamDetails): Set<String> =
        listOf(details.id, team.id, details.code, team.code, details.name, team.name, details.slug, team.slug)
            .map(::token)
            .filter { it.isNotBlank() }
            .toSet()

    private fun cargoEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun token(value: String): String = value.uppercase().filter { it.isLetterOrDigit() }
    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun loadBundled(name: String): JSONObject? = runCatching {
        RiftLabApplication.appContext.assets.open(name).bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()

    private fun getJson(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("User-Agent", "RiftLab-GlobalStaff/1.0")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code · ${text.take(100)}")
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
