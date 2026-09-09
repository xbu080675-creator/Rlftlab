package com.riftlab.app.data

import com.riftlab.app.RiftLabApplication
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

internal data class TeamArchiveIdentity(
    val foundedAt: String = "",
    val lolDivisionFoundedAt: String = "",
    val region: String = "",
    val city: String = "",
    val status: String = ""
)

internal data class TeamOrganizationRef(
    val name: String,
    val role: String,
    val source: String = "",
    val displayRole: String = ""
)

internal data class TeamHonorRef(
    val year: String,
    val event: String,
    val placement: String,
    val tier: String = "",
    val source: String = ""
)

internal data class TeamLineageRef(
    val name: String,
    val from: String = "",
    val to: String = "",
    val relation: String = "",
    val scope: String = "",
    val operator: String = "",
    val note: String = "",
    val source: String = ""
)

internal data class TeamAlumniRef(
    val name: String,
    val role: String,
    val category: String = "",
    val realName: String = "",
    val joinedAt: String = "",
    val leftAt: String = "",
    val source: String = "",
    val note: String = ""
)

internal data class TeamArchiveSupplement(
    val identity: TeamArchiveIdentity = TeamArchiveIdentity(),
    val operators: List<TeamOrganizationRef> = emptyList(),
    val parentOrganizations: List<TeamOrganizationRef> = emptyList(),
    val peopleInCharge: List<TeamOrganizationRef> = emptyList(),
    val honors: List<TeamHonorRef> = emptyList(),
    val lineage: List<TeamLineageRef> = emptyList(),
    val alumni: List<TeamAlumniRef> = emptyList(),
    val updatedAt: String = "",
    val sourceMode: String = ""
)

internal class TeamArchiveProvider {
    companion object {
        private const val CACHE_TTL_MS = 30L * 60L * 1000L
        private val ENDPOINTS = listOf(
            "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/lpl/team_archive.json",
            "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/lpl/team_archive.json"
        )
        private data class Cached(val at: Long, val root: JSONObject)
        private val cache = AtomicReference<Cached?>(null)
    }

    suspend fun fetch(team: EsportsTeamRef, details: EsportsTeamDetails): TeamArchiveSupplement {
        val code = resolveCode(team, details)
        if (code.isBlank()) return TeamArchiveSupplement(sourceMode = "unresolved")
        val root = directory()
        val node = root.optJSONObject("teams")?.optJSONObject(code)
            ?: return TeamArchiveSupplement(updatedAt = root.optString("updatedAt"), sourceMode = "missing")
        return parse(node, root.optString("updatedAt"))
    }

    private fun directory(): JSONObject {
        val now = System.currentTimeMillis()
        cache.get()?.takeIf { now - it.at < CACHE_TTL_MS }?.let { return it.root }

        for (endpoint in ENDPOINTS) {
            runCatching { getJson(endpoint) }.getOrNull()?.let { root ->
                if (root.optInt("schemaVersion", 0) > 0 && root.optJSONObject("teams") != null) {
                    cache.set(Cached(now, root))
                    return root
                }
            }
        }

        val bundled = runCatching {
            RiftLabApplication.appContext.assets.open("team_archive.json")
                .bufferedReader()
                .use { JSONObject(it.readText()) }
        }.getOrNull()
        if (bundled != null && bundled.optJSONObject("teams") != null) {
            cache.set(Cached(now, bundled))
            return bundled
        }
        error("team archive unavailable")
    }

    private fun getJson(endpoint: String): JSONObject {
        val bucket = System.currentTimeMillis() / 600_000L
        val connection = URL("$endpoint?riftlabArchive=$bucket").openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 4_500
            connection.readTimeout = 6_500
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("User-Agent", "RiftLab-TeamArchive/1")
            if (connection.responseCode !in 200..299) error("archive HTTP ${connection.responseCode}")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(node: JSONObject, updatedAt: String): TeamArchiveSupplement {
        val identityNode = node.optJSONObject("identity")
        return TeamArchiveSupplement(
            identity = TeamArchiveIdentity(
                foundedAt = identityNode?.optString("foundedAt").orEmpty(),
                lolDivisionFoundedAt = identityNode?.optString("lolDivisionFoundedAt").orEmpty(),
                region = identityNode?.optString("region").orEmpty(),
                city = identityNode?.optString("city").orEmpty(),
                status = identityNode?.optString("status").orEmpty()
            ),
            operators = parseOrg(node.optJSONArray("operators")),
            parentOrganizations = parseOrg(node.optJSONArray("parentOrganizations")),
            peopleInCharge = parseOrg(node.optJSONArray("peopleInCharge")),
            honors = parseHonors(node.optJSONArray("honors")),
            lineage = parseLineage(node.optJSONArray("lineage")),
            alumni = parseAlumni(node.optJSONArray("alumni")),
            updatedAt = updatedAt,
            sourceMode = "archive"
        )
    }

    private fun parseOrg(rows: JSONArray?): List<TeamOrganizationRef> = buildList {
        if (rows == null) return@buildList
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val name = row.optString("name").trim()
            if (name.isBlank()) continue
            add(TeamOrganizationRef(name, row.optString("role"), row.optString("source"), row.optString("displayRole")))
        }
    }

    private fun parseHonors(rows: JSONArray?): List<TeamHonorRef> = buildList {
        if (rows == null) return@buildList
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val event = row.optString("event").trim()
            if (event.isBlank()) continue
            add(TeamHonorRef(row.optString("year"), event, row.optString("placement"), row.optString("tier"), row.optString("source")))
        }
    }

    private fun parseLineage(rows: JSONArray?): List<TeamLineageRef> = buildList {
        if (rows == null) return@buildList
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val name = row.optString("name").trim()
            if (name.isBlank()) continue
            add(TeamLineageRef(
                name = name,
                from = row.optString("from"),
                to = row.optString("to"),
                relation = row.optString("relation"),
                scope = row.optString("scope"),
                operator = row.optString("operator"),
                note = row.optString("note"),
                source = row.optString("source")
            ))
        }
    }

    private fun parseAlumni(rows: JSONArray?): List<TeamAlumniRef> = buildList {
        if (rows == null) return@buildList
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val name = row.optString("name").trim()
            val role = row.optString("role").trim()
            if (name.isBlank() || role.isBlank()) continue
            add(TeamAlumniRef(
                name = name,
                role = role,
                category = row.optString("category"),
                realName = row.optString("realName"),
                joinedAt = row.optString("joinedAt"),
                leftAt = row.optString("leftAt"),
                source = row.optString("source"),
                note = row.optString("note")
            ))
        }
    }

    private fun resolveCode(team: EsportsTeamRef, details: EsportsTeamDetails): String {
        val candidates = listOf(details.code, team.code, details.name, team.name, details.slug, team.slug).map(::token)
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
        "NINJASINPYJAMASCN" to "NIP",
        "NINJASINPYJAMAS" to "NIP",
        "WEIBOGAMING" to "WBG",
        "XIATEKTEAMWE" to "WE",
        "TEAMWE" to "WE"
    )
}
