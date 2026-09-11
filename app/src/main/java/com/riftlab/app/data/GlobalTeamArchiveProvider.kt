package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Global non-LPL team archive resolver.
 *
 * LPL uses RiftLab's curated organization graph. For overseas teams we first keep Riot Teams as the
 * current-roster authority, then enrich organization/region and tournament history from Leaguepedia
 * Cargo. Every field remains source-labelled; unavailable historical fields stay empty instead of
 * being guessed from the current roster or league.
 */
internal class GlobalTeamArchiveProvider {
    companion object {
        private const val CARGO_API = "https://lol.fandom.com/api.php"
        private const val POSITIVE_TTL_MS = 6L * 60L * 60L * 1000L
        private const val NEGATIVE_TTL_MS = 20L * 60L * 1000L
        private const val SOURCE_TEAM = "Leaguepedia Teams · live"
        private const val SOURCE_RESULTS = "Leaguepedia TournamentResults · live"

        private data class Cached(val at: Long, val value: TeamArchiveSupplement)
        private val cache = ConcurrentHashMap<String, Cached>()
    }

    suspend fun fetch(team: EsportsTeamRef, details: EsportsTeamDetails): TeamArchiveSupplement = withContext(Dispatchers.IO) {
        val key = identityTokens(team, details).firstOrNull().orEmpty().ifBlank { "UNKNOWN" }
        val now = System.currentTimeMillis()
        cache[key]?.let { cached ->
            val ttl = if (hasUsefulArchive(cached.value)) POSITIVE_TTL_MS else NEGATIVE_TTL_MS
            if (now - cached.at < ttl) return@withContext cached.value
        }

        val resolved = runCatching { fetchLive(team, details) }
            .getOrElse {
                TeamArchiveSupplement(
                    identity = TeamArchiveIdentity(status = "GLOBAL_ARCHIVE_UNAVAILABLE"),
                    updatedAt = Instant.now().toString(),
                    sourceMode = "leaguepedia-global-error"
                )
            }
        cache[key] = Cached(now, resolved)
        resolved
    }

    private fun fetchLive(team: EsportsTeamRef, details: EsportsTeamDetails): TeamArchiveSupplement {
        val candidates = candidateNames(team, details)
        if (candidates.isEmpty()) return TeamArchiveSupplement(sourceMode = "leaguepedia-global-unresolved")

        val teamRow = fetchTeamRow(candidates)
            ?: return TeamArchiveSupplement(
                updatedAt = Instant.now().toString(),
                sourceMode = "leaguepedia-global-missing-team"
            )

        val canonicalName = teamRow.optString("Name").ifBlank {
            details.name.ifBlank { team.name.ifBlank { details.code.ifBlank { team.code } } }
        }
        val short = teamRow.optString("Short").ifBlank { details.code.ifBlank { team.code } }
        val overviewPage = teamRow.optString("OverviewPage").ifBlank { canonicalName }
        val organizationPage = teamRow.optString("OrganizationPage").trim()
        val region = teamRow.optString("Region").trim()
        val location = teamRow.optString("TeamLocation").ifBlank { teamRow.optString("Location") }.trim()
        val isDisbanded = teamRow.optBoolean("IsDisbanded", false) ||
            teamRow.optString("IsDisbanded").equals("1", true) ||
            teamRow.optString("IsDisbanded").equals("yes", true)
        val renamedTo = teamRow.optString("RenamedTo").trim()

        val resultNames = buildList {
            add(canonicalName)
            add(overviewPage)
            add(short)
            candidates.forEach(::add)
        }.filter { it.isNotBlank() }.distinct()

        val results = fetchTournamentResults(resultNames)
        val honors = results.filter { it.isTitle }.map {
            TeamHonorRef(
                year = it.year,
                event = it.event,
                placement = it.placement,
                tier = it.tier,
                source = it.source
            )
        }
        val parents = if (organizationPage.isBlank()) emptyList() else listOf(
            TeamOrganizationRef(
                name = organizationPage,
                role = "PARENT_ORGANIZATION",
                source = SOURCE_TEAM,
                displayRole = "所属组织"
            )
        )
        val lineage = if (renamedTo.isBlank()) emptyList() else listOf(
            TeamLineageRef(
                name = renamedTo,
                from = canonicalName,
                to = renamedTo,
                relation = "RENAMED_TO",
                scope = "League of Legends team identity",
                note = "Leaguepedia Teams 当前标记该队已更名；RiftLab 不把更名前成绩自动并入新品牌。",
                source = SOURCE_TEAM
            )
        )

        return TeamArchiveSupplement(
            organizationId = organizationPage.takeIf { it.isNotBlank() }?.let { "leaguepedia-org:${token(it)}" }.orEmpty(),
            gameId = "game-lol",
            teamId = "leaguepedia-team:${token(overviewPage.ifBlank { canonicalName })}",
            identity = TeamArchiveIdentity(
                region = region,
                city = location,
                status = if (isDisbanded) "DISBANDED" else "ACTIVE"
            ),
            parentOrganizations = parents,
            honors = honors,
            results = results,
            lineage = lineage,
            updatedAt = Instant.now().toString(),
            sourceMode = "leaguepedia-global-live"
        )
    }

    private fun fetchTeamRow(candidates: List<String>): JSONObject? {
        val safe = candidates.take(8)
        val where = safe.joinToString(" OR ", prefix = "(", postfix = ")") { raw ->
            val q = cargoEscape(raw)
            "T.Name=\"$q\" OR T.Short=\"$q\" OR T.OverviewPage=\"$q\""
        }
        val fields = listOf(
            "T.Name=Name",
            "T.Short=Short",
            "T.Location=Location",
            "T.TeamLocation=TeamLocation",
            "T.Region=Region",
            "T.OrganizationPage=OrganizationPage",
            "T.OverviewPage=OverviewPage",
            "T.IsDisbanded=IsDisbanded",
            "T.RenamedTo=RenamedTo"
        ).joinToString(",")
        val root = cargoQuery(
            tables = "Teams=T",
            fields = fields,
            where = where,
            limit = 20
        )
        val rows = root.optJSONArray("cargoquery") ?: JSONArray()
        val wanted = candidates.map(::token).filter { it.isNotBlank() }.toSet()
        return (0 until rows.length())
            .mapNotNull { index -> rows.optJSONObject(index)?.optJSONObject("title") ?: rows.optJSONObject(index) }
            .maxByOrNull { row ->
                val values = listOf(row.optString("Name"), row.optString("Short"), row.optString("OverviewPage"))
                    .map(::token)
                values.count { it in wanted } * 10 + values.count { it.isNotBlank() }
            }
    }

    private fun fetchTournamentResults(names: List<String>): List<TeamResultRef> {
        if (names.isEmpty()) return emptyList()
        val where = names.take(8).joinToString(" OR ", prefix = "(", postfix = ")") { raw ->
            "TR.Team=\"${cargoEscape(raw)}\""
        }
        val root = cargoQuery(
            tables = "TournamentResults=TR",
            fields = listOf(
                "TR.Team=Team",
                "TR.Event=Event",
                "TR.Date=Date",
                "TR.Place=Place",
                "TR.Place_Number=Place_Number",
                "TR.Phase=Phase",
                "TR.Tier=Tier"
            ).joinToString(","),
            where = "$where AND TR.Showmatch IS FALSE",
            orderBy = "TR.Date DESC",
            limit = 250
        )
        val rows = root.optJSONArray("cargoquery") ?: JSONArray()
        return buildList {
            for (index in 0 until rows.length()) {
                val wrapper = rows.optJSONObject(index) ?: continue
                val row = wrapper.optJSONObject("title") ?: wrapper
                val event = row.optString("Event").trim()
                val date = row.optString("Date").trim()
                val place = row.optString("Place").trim()
                if (event.isBlank()) continue
                val placeNumber = row.optInt("Place_Number", parsePlacement(place))
                val year = Regex("(?:19|20)\\d{2}").find(date)?.value
                    ?: Regex("(?:19|20)\\d{2}").find(event)?.value.orEmpty()
                add(
                    TeamResultRef(
                        id = "global-${token(row.optString("Team"))}-${token(event)}-${date}-${placeNumber}",
                        year = year,
                        event = event,
                        placement = place.ifBlank { placeNumber.takeIf { it > 0 }?.toString().orEmpty() },
                        placementMin = placeNumber,
                        placementMax = placementMax(place, placeNumber),
                        stage = row.optString("Phase"),
                        tier = row.optString("Tier"),
                        source = SOURCE_RESULTS,
                        isTitle = placeNumber == 1
                    )
                )
            }
        }.distinctBy { it.id }
    }

    private fun cargoQuery(
        tables: String,
        fields: String,
        where: String,
        orderBy: String = "",
        limit: Int
    ): JSONObject {
        val url = buildString {
            append(CARGO_API)
            append("?action=cargoquery&format=json")
            append("&tables=").append(enc(tables))
            append("&fields=").append(enc(fields))
            append("&where=").append(enc(where))
            if (orderBy.isNotBlank()) append("&order_by=").append(enc(orderBy))
            append("&limit=").append(limit)
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "RiftLab-GlobalTeamArchive/1.0")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Leaguepedia Cargo HTTP $code · ${body.take(120)}")
            val root = JSONObject(body)
            root.optJSONObject("error")?.let { error ->
                throw IOException("Leaguepedia Cargo ${error.optString("code")} · ${error.optString("info").take(120)}")
            }
            root
        } finally {
            connection.disconnect()
        }
    }

    private fun candidateNames(team: EsportsTeamRef, details: EsportsTeamDetails): List<String> =
        listOf(details.name, team.name, details.code, team.code, details.slug, team.slug)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    private fun identityTokens(team: EsportsTeamRef, details: EsportsTeamDetails): Set<String> =
        candidateNames(team, details).map(::token).filter { it.isNotBlank() }.toSet()

    private fun parsePlacement(raw: String): Int {
        return Regex("\\d+").find(raw)?.value?.toIntOrNull() ?: 0
    }

    private fun placementMax(raw: String, min: Int): Int {
        val values = Regex("\\d+").findAll(raw).mapNotNull { it.value.toIntOrNull() }.toList()
        return values.maxOrNull() ?: min
    }

    private fun hasUsefulArchive(value: TeamArchiveSupplement): Boolean =
        value.identity.region.isNotBlank() || value.identity.city.isNotBlank() ||
            value.parentOrganizations.isNotEmpty() || value.results.isNotEmpty() || value.lineage.isNotEmpty()

    private fun cargoEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun token(value: String): String = value.uppercase().filter { it.isLetterOrDigit() }
    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
