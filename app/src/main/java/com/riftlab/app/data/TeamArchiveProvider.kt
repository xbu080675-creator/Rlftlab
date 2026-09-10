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

internal data class TeamResultRef(
    val id: String,
    val year: String,
    val event: String,
    val placement: String,
    val placementMin: Int = 0,
    val placementMax: Int = 0,
    val stage: String = "",
    val tier: String = "",
    val source: String = "",
    val sourceUrl: String = "",
    val isTitle: Boolean = false
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
    val organizationId: String = "",
    val gameId: String = "",
    val teamId: String = "",
    val identity: TeamArchiveIdentity = TeamArchiveIdentity(),
    val operators: List<TeamOrganizationRef> = emptyList(),
    val parentOrganizations: List<TeamOrganizationRef> = emptyList(),
    val peopleInCharge: List<TeamOrganizationRef> = emptyList(),
    val honors: List<TeamHonorRef> = emptyList(),
    val results: List<TeamResultRef> = emptyList(),
    val lineage: List<TeamLineageRef> = emptyList(),
    val alumni: List<TeamAlumniRef> = emptyList(),
    val updatedAt: String = "",
    val sourceMode: String = ""
)

/**
 * Team archive adapter over the normalized RiftLab esports graph.
 *
 * Canonical model: Organization -> Game -> Team -> memberships/results/lineage.
 * The legacy LPL team_archive.json remains a fallback while old datasets migrate.
 */
internal class TeamArchiveProvider {
    private val globalProvider = GlobalTeamArchiveProvider()

    companion object {
        private const val CACHE_TTL_MS = 30L * 60L * 1000L
        private val GRAPH_ENDPOINTS = listOf(
            "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/esports/esports_graph.json",
            "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/esports/esports_graph.json"
        )
        private val LEGACY_ENDPOINTS = listOf(
            "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/lpl/team_archive.json",
            "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/lpl/team_archive.json"
        )
        private data class Cached(val at: Long, val root: JSONObject)
        private val cache = AtomicReference<Cached?>(null)
    }

    suspend fun fetch(team: EsportsTeamRef, details: EsportsTeamDetails): TeamArchiveSupplement {
        val code = resolveCode(team, details)
        val local = runCatching {
            if (code.isBlank()) {
                TeamArchiveSupplement(sourceMode = "local-code-unresolved")
            } else {
                val root = directory()
                if (root.optJSONArray("teams") != null) {
                    parseGraph(root, code)
                } else {
                    val node = root.optJSONObject("teams")?.optJSONObject(code)
                    if (node == null) TeamArchiveSupplement(updatedAt = root.optString("updatedAt"), sourceMode = "legacy-missing-team")
                    else parseLegacy(node, root.optString("updatedAt"))
                }
            }
        }.getOrElse { TeamArchiveSupplement(sourceMode = "local-archive-error") }

        if (hasArchiveData(local)) return local

        // dev.72: overseas teams must receive the same archive surface as LPL teams. Riot Teams
        // remains roster authority; Leaguepedia supplies region/organization/result history with
        // explicit provenance. A source failure keeps the local result rather than fabricating data.
        val global = runCatching { globalProvider.fetch(team, details) }.getOrNull()
        return global?.takeIf(::hasArchiveData) ?: local
    }

    private fun hasArchiveData(value: TeamArchiveSupplement): Boolean =
        value.identity.foundedAt.isNotBlank() || value.identity.lolDivisionFoundedAt.isNotBlank() ||
            value.identity.region.isNotBlank() || value.identity.city.isNotBlank() ||
            value.operators.isNotEmpty() || value.parentOrganizations.isNotEmpty() ||
            value.peopleInCharge.isNotEmpty() || value.honors.isNotEmpty() || value.results.isNotEmpty() ||
            value.lineage.isNotEmpty() || value.alumni.isNotEmpty()

    private fun directory(): JSONObject {
        val now = System.currentTimeMillis()
        cache.get()?.takeIf { now - it.at < CACHE_TTL_MS }?.let { return it.root }

        for (endpoint in GRAPH_ENDPOINTS) {
            runCatching { getJson(endpoint, "graph") }.getOrNull()?.let { root ->
                if (root.optInt("schemaVersion", 0) > 0 && root.optJSONArray("teams") != null) {
                    cache.set(Cached(now, root))
                    return root
                }
            }
        }
        bundled("esports_graph.json")?.let { root ->
            if (root.optJSONArray("teams") != null) {
                cache.set(Cached(now, root))
                return root
            }
        }

        for (endpoint in LEGACY_ENDPOINTS) {
            runCatching { getJson(endpoint, "legacy") }.getOrNull()?.let { root ->
                if (root.optJSONObject("teams") != null) {
                    cache.set(Cached(now, root))
                    return root
                }
            }
        }
        bundled("team_archive.json")?.let { root ->
            if (root.optJSONObject("teams") != null) {
                cache.set(Cached(now, root))
                return root
            }
        }
        error("team archive unavailable")
    }

    private fun bundled(name: String): JSONObject? = runCatching {
        RiftLabApplication.appContext.assets.open(name).bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()

    private fun getJson(endpoint: String, kind: String): JSONObject {
        val bucket = System.currentTimeMillis() / 600_000L
        val connection = URL("$endpoint?riftlabArchive=$kind-$bucket").openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 4_500
            connection.readTimeout = 6_500
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("User-Agent", "RiftLab-EsportsGraph/1")
            if (connection.responseCode !in 200..299) error("archive HTTP ${connection.responseCode}")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun parseGraph(root: JSONObject, code: String): TeamArchiveSupplement {
        val teams = root.optJSONArray("teams") ?: return TeamArchiveSupplement(sourceMode = "graph-missing")
        val teamNode = (0 until teams.length())
            .mapNotNull { teams.optJSONObject(it) }
            .firstOrNull { token(it.optString("code")) == code }
            ?: return TeamArchiveSupplement(updatedAt = root.optString("updatedAt"), sourceMode = "graph-missing-team")

        val teamId = teamNode.optString("team_id")
        val organizationId = teamNode.optString("organization_id")
        val gameId = teamNode.optString("game_id")
        val organizations = indexBy(root.optJSONArray("organizations"), "organization_id")

        val organizationRows = buildList {
            val links = root.optJSONArray("team_organization_links")
            if (links != null) {
                for (i in 0 until links.length()) {
                    val row = links.optJSONObject(i) ?: continue
                    if (row.optString("team_id") != teamId) continue
                    val orgId = row.optString("organization_id")
                    val org = organizations[orgId]
                    val name = org?.optString("name").orEmpty().ifBlank { orgId }
                    if (name.isBlank()) continue
                    add(TeamOrganizationRef(
                        name = name,
                        role = row.optString("role"),
                        source = row.optString("source"),
                        displayRole = row.optString("display_role")
                    ))
                }
            }
        }
        val operatorRoles = setOf("OPERATOR", "OWNER_OR_OPERATOR", "OPERATOR_OR_PARENT", "STRATEGIC_PARTNER")
        val operators = organizationRows.filter { it.role.uppercase() in operatorRoles }
        val parents = organizationRows.filterNot { it.role.uppercase() in operatorRoles }

        val responsibilities = buildList {
            val rows = root.optJSONArray("team_responsibilities")
            if (rows != null) for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (row.optString("team_id") != teamId) continue
                val name = row.optString("name").trim()
                if (name.isBlank()) continue
                add(TeamOrganizationRef(name, row.optString("role"), row.optString("source"), row.optString("display_role")))
            }
        }

        val results = buildList {
            val rows = root.optJSONArray("team_results")
            if (rows != null) for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (row.optString("team_id") != teamId) continue
                add(TeamResultRef(
                    id = row.optString("result_id").ifBlank { "$teamId-$i" },
                    year = row.optString("year"),
                    event = row.optString("event_name"),
                    placement = row.optString("placement_label"),
                    placementMin = row.optInt("placement_min", 0),
                    placementMax = row.optInt("placement_max", 0),
                    stage = row.optString("stage"),
                    tier = row.optString("tier"),
                    source = row.optString("source"),
                    sourceUrl = row.optString("source_url"),
                    isTitle = row.optBoolean("is_title", false)
                ))
            }
        }.sortedWith(compareByDescending<TeamResultRef> { it.year.toIntOrNull() ?: 0 }.thenBy { it.placementMin })

        val honors = results.filter { it.isTitle }.map {
            TeamHonorRef(it.year, it.event, it.placement, it.tier, it.source)
        }

        val lineage = buildList {
            val rows = root.optJSONArray("team_lineage")
            if (rows != null) for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (row.optString("team_id") != teamId) continue
                add(TeamLineageRef(
                    name = row.optString("name"),
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

        return TeamArchiveSupplement(
            organizationId = organizationId,
            gameId = gameId,
            teamId = teamId,
            identity = TeamArchiveIdentity(
                foundedAt = teamNode.optString("founded_at"),
                lolDivisionFoundedAt = teamNode.optString("game_lineage_founded_at"),
                region = teamNode.optString("region"),
                city = teamNode.optString("city"),
                status = teamNode.optString("status")
            ),
            operators = operators,
            parentOrganizations = parents,
            peopleInCharge = responsibilities,
            honors = honors,
            results = results,
            lineage = lineage,
            updatedAt = root.optString("updatedAt"),
            sourceMode = "organization-graph"
        )
    }

    private fun indexBy(rows: JSONArray?, key: String): Map<String, JSONObject> = buildMap {
        if (rows == null) return@buildMap
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString(key)
            if (id.isNotBlank()) put(id, row)
        }
    }

    private fun parseLegacy(node: JSONObject, updatedAt: String): TeamArchiveSupplement {
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
            sourceMode = "legacy-archive"
        )
    }

    private fun parseOrg(rows: JSONArray?): List<TeamOrganizationRef> = buildList {
        if (rows == null) return@buildList
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val name = row.optString("name").trim()
            if (name.isNotBlank()) add(TeamOrganizationRef(name, row.optString("role"), row.optString("source"), row.optString("displayRole")))
        }
    }

    private fun parseHonors(rows: JSONArray?): List<TeamHonorRef> = buildList {
        if (rows == null) return@buildList
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val event = row.optString("event").trim()
            if (event.isNotBlank()) add(TeamHonorRef(row.optString("year"), event, row.optString("placement"), row.optString("tier"), row.optString("source")))
        }
    }

    private fun parseLineage(rows: JSONArray?): List<TeamLineageRef> = buildList {
        if (rows == null) return@buildList
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val name = row.optString("name").trim()
            if (name.isBlank()) continue
            add(TeamLineageRef(name, row.optString("from"), row.optString("to"), row.optString("relation"), row.optString("scope"), row.optString("operator"), row.optString("note"), row.optString("source")))
        }
    }

    private fun parseAlumni(rows: JSONArray?): List<TeamAlumniRef> = buildList {
        if (rows == null) return@buildList
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val name = row.optString("name").trim()
            val role = row.optString("role").trim()
            if (name.isBlank() || role.isBlank()) continue
            add(TeamAlumniRef(name, role, row.optString("category"), row.optString("realName"), row.optString("joinedAt"), row.optString("leftAt"), row.optString("source"), row.optString("note")))
        }
    }

    private fun resolveCode(team: EsportsTeamRef, details: EsportsTeamDetails): String {
        val explicitCodes = listOf(details.code, team.code).map(::token).filter { it.isNotBlank() }
        val candidates = listOf(details.code, team.code, details.name, team.name, details.slug, team.slug).map(::token)
        return candidates.firstNotNullOfOrNull { aliases[it] }
            ?: explicitCodes.firstOrNull { it in knownCodes }
            ?: explicitCodes.firstOrNull()
            .orEmpty()
    }

    private fun token(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

    private val knownCodes = setOf("AL", "BLG", "TES", "JDG", "LGD", "EDG", "TT", "IG", "LNG", "NIP", "WBG", "WE")
    private val aliases = mapOf(
        "ANYONESLEGEND" to "AL", "BILIBILIGAMING" to "BLG", "TOPESPORTS" to "TES",
        "BEIJINGJDGESPORTS" to "JDG", "JDGAMING" to "JDG", "LGDGAMING" to "LGD",
        "EDWARDGAMING" to "EDG", "THUNDERTALKGAMING" to "TT", "INVICTUSGAMING" to "IG",
        "SUZHOULNGESPORTS" to "LNG", "LNGESPORTS" to "LNG", "NINJASINPYJAMASCN" to "NIP",
        "NINJASINPYJAMAS" to "NIP", "WEIBOGAMING" to "WBG", "XIATEKTEAMWE" to "WE", "TEAMWE" to "WE"
    )
}
