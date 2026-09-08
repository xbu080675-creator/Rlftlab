package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToLong

internal data class OpggMatchSupplement(
    val drafts: List<DraftPickRecord> = emptyList(),
    val gameMvps: List<OfficialMvpRecord> = emptyList(),
    val panels: List<OfficialVoteRecord> = emptyList(),
    val teamImages: Map<String, String> = emptyMap(),
    val status: String = "OP.GG · 未同步"
)

/**
 * Third-party post-match supplement from esports.op.gg.
 *
 * OP.GG is never presented as an official league source. It is only used when the primary
 * tournament source does not expose a field (notably bans and an MVP/POG-style rating panel).
 */
internal class OpggMatchSupplementProvider {
    companion object {
        private const val GRAPHQL = "https://esports.op.gg/matches/graphql"

        private const val LIST_MATCHES_QUERY = """
            query ListPagedAllMatches(${ '$' }status: String!, ${ '$' }leagueId: ID, ${ '$' }teamId: ID, ${ '$' }page: Int, ${ '$' }year: Int, ${ '$' }month: Int, ${ '$' }limit: Int) {
              pagedAllMatches(status: ${ '$' }status, leagueId: ${ '$' }leagueId, teamId: ${ '$' }teamId, page: ${ '$' }page, year: ${ '$' }year, month: ${ '$' }month, limit: ${ '$' }limit) {
                id name scheduledAt beginAt status homeScore awayScore
                homeTeam { id name acronym imageUrl imageUrlDarkMode imageUrlLightMode }
                awayTeam { id name acronym imageUrl imageUrlDarkMode imageUrlLightMode }
              }
            }
        """

        private const val GAME_QUERY = """
            query GetGameByMatch(${ '$' }matchId: ID!, ${ '$' }set: Int) {
              gameByMatch(matchId: ${ '$' }matchId, set: ${ '$' }set) {
                id finished length
                winner { id name acronym imageUrl imageUrlDarkMode imageUrlLightMode }
                teams {
                  side bans
                  team { id name acronym imageUrl imageUrlDarkMode imageUrlLightMode }
                }
                players {
                  side championId position mvpPoint
                  team { id name acronym imageUrl imageUrlDarkMode imageUrlLightMode }
                  player { id nickName position imageUrl }
                }
              }
            }
        """
    }

    suspend fun fetch(match: ScheduledEsportsMatch): OpggMatchSupplement = withContext(Dispatchers.IO) {
        val opggMatch = findMatch(match) ?: return@withContext OpggMatchSupplement(
            status = "OP.GG · 未匹配到该系列赛"
        )
        val matchId = opggMatch.opt("id")?.toString().orEmpty()
        if (matchId.isBlank()) return@withContext OpggMatchSupplement(status = "OP.GG · match id 缺失")

        val images = linkedMapOf<String, String>()
        collectTeamImage(opggMatch.optJSONObject("homeTeam"), images)
        collectTeamImage(opggMatch.optJSONObject("awayTeam"), images)

        val drafts = mutableListOf<DraftPickRecord>()
        val mvps = mutableListOf<OfficialMvpRecord>()
        val panels = mutableListOf<OfficialVoteRecord>()
        val maxSets = match.bestOf.takeIf { it > 0 } ?: 5

        for (set in 1..maxSets) {
            val game = runCatching { fetchGame(matchId, set) }.getOrNull() ?: continue
            if (game.length() == 0) continue

            val teamSideById = linkedMapOf<String, String>()
            val teamCodeById = linkedMapOf<String, String>()
            val blueBans = mutableListOf<String>()
            val redBans = mutableListOf<String>()
            val teams = game.optJSONArray("teams") ?: JSONArray()
            for (i in 0 until teams.length()) {
                val row = teams.optJSONObject(i) ?: continue
                val team = row.optJSONObject("team") ?: JSONObject()
                val teamId = team.opt("id")?.toString().orEmpty()
                val side = row.optString("side").lowercase()
                val code = team.optString("acronym").ifBlank { team.optString("name") }
                if (teamId.isNotBlank()) {
                    teamSideById[teamId] = side
                    teamCodeById[teamId] = code
                }
                collectTeamImage(team, images)
                val bans = jsonScalarList(row.optJSONArray("bans"))
                when (side) {
                    "blue" -> blueBans += bans
                    "red" -> redBans += bans
                }
            }

            val bluePicks = mutableListOf<String>()
            val redPicks = mutableListOf<String>()
            data class Rating(val name: String, val team: String, val role: String, val point: Double)
            val ratings = mutableListOf<Rating>()
            val players = game.optJSONArray("players") ?: JSONArray()
            for (i in 0 until players.length()) {
                val row = players.optJSONObject(i) ?: continue
                val team = row.optJSONObject("team") ?: JSONObject()
                val player = row.optJSONObject("player") ?: JSONObject()
                val teamId = team.opt("id")?.toString().orEmpty()
                val side = row.optString("side").lowercase().ifBlank { teamSideById[teamId].orEmpty() }
                val champion = row.opt("championId")?.toString()?.trim().orEmpty()
                if (champion.isNotBlank() && champion != "0") {
                    when (side) {
                        "blue" -> bluePicks += champion
                        "red" -> redPicks += champion
                    }
                }
                collectTeamImage(team, images)

                val point = number(row.opt("mvpPoint"))
                if (point > 0.0) {
                    ratings += Rating(
                        name = player.optString("nickName").ifBlank { row.optString("nickName") }.ifBlank { "P${i + 1}" },
                        team = team.optString("acronym").ifBlank { team.optString("name") }.ifBlank { teamCodeById[teamId].orEmpty() },
                        role = row.optString("position").ifBlank { player.optString("position") },
                        point = point
                    )
                }
            }

            if (blueBans.isNotEmpty() || redBans.isNotEmpty() || bluePicks.isNotEmpty() || redPicks.isNotEmpty()) {
                drafts += DraftPickRecord(
                    game = set,
                    blueBans = blueBans.distinct(),
                    redBans = redBans.distinct(),
                    bluePicks = bluePicks.distinct(),
                    redPicks = redPicks.distinct(),
                    source = "OP.GG · gameByMatch"
                )
            }

            val sortedRatings = ratings.sortedByDescending { it.point }
            sortedRatings.firstOrNull()?.let { top ->
                mvps += OfficialMvpRecord(
                    game = set,
                    playerName = top.name,
                    team = top.team,
                    role = top.role,
                    source = "OP.GG · MVP Point"
                )
            }
            if (sortedRatings.isNotEmpty()) {
                panels += OfficialVoteRecord(
                    title = "G$set · OP.GG MVP POINT",
                    options = sortedRatings.take(5).map { rating ->
                        VoteOptionRecord(
                            label = "${rating.name}${rating.team.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
                            votes = rating.point.roundToLong()
                        )
                    },
                    totalVotes = null,
                    source = "OP.GG · MVP Point（第三方评分，不是官方投票）"
                )
            }
        }

        OpggMatchSupplement(
            drafts = drafts,
            gameMvps = mvps,
            panels = panels,
            teamImages = images,
            status = "OP.GG · match=$matchId · BP ${drafts.size} 局 · MVP ${mvps.size} 局"
        )
    }

    private fun findMatch(target: ScheduledEsportsMatch): JSONObject? {
        val instant = runCatching { Instant.parse(target.startTimeIso) }.getOrNull()
        val china = instant?.atZone(ZoneId.of("Asia/Shanghai")) ?: ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
        val payload = graphQl(
            operationName = "ListPagedAllMatches",
            query = LIST_MATCHES_QUERY,
            variables = JSONObject()
                .put("status", "finished")
                .put("leagueId", JSONObject.NULL)
                .put("teamId", JSONObject.NULL)
                .put("page", 0)
                .put("year", china.year)
                .put("month", china.monthValue)
                .put("limit", 500)
        )
        val rows = payload.optJSONObject("data")?.optJSONArray("pagedAllMatches") ?: return null
        val left = target.teams.getOrNull(0)
        val right = target.teams.getOrNull(1)
        val targetDate = china.toLocalDate()

        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val home = row.optJSONObject("homeTeam") ?: continue
            val away = row.optJSONObject("awayTeam") ?: continue
            val direct = left != null && right != null && teamMatches(home, left) && teamMatches(away, right)
            val swapped = left != null && right != null && teamMatches(home, right) && teamMatches(away, left)
            if (!direct && !swapped) continue

            var score = if (direct) 100 else 96
            val date = opggLocalDate(row.optString("scheduledAt").ifBlank { row.optString("beginAt") })
            if (date != null) {
                val days = kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(targetDate, date).toInt())
                score += when (days) {
                    0 -> 40
                    1 -> 10
                    else -> -days.coerceAtMost(20)
                }
            }
            if (row.optInt("homeScore", 0) > 0 || row.optInt("awayScore", 0) > 0) score += 5
            if (score > bestScore) {
                best = row
                bestScore = score
            }
        }
        return best?.takeIf { bestScore >= 100 }
    }

    private fun fetchGame(matchId: String, set: Int): JSONObject? {
        val payload = graphQl(
            operationName = "GetGameByMatch",
            query = GAME_QUERY,
            variables = JSONObject().put("matchId", matchId).put("set", set)
        )
        return payload.optJSONObject("data")?.optJSONObject("gameByMatch")
    }

    private fun graphQl(operationName: String, query: String, variables: JSONObject): JSONObject {
        val connection = URL(GRAPHQL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Origin", "https://esports.op.gg")
            connection.setRequestProperty("Referer", "https://esports.op.gg/")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) RiftLab/1.0")
            val body = JSONObject()
                .put("operationName", operationName)
                .put("query", query)
                .put("variables", variables)
                .toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("OP.GG HTTP $code")
            val root = JSONObject(text)
            if ((root.optJSONArray("errors")?.length() ?: 0) > 0) error("OP.GG GraphQL errors")
            root
        } finally {
            connection.disconnect()
        }
    }

    private fun teamMatches(opgg: JSONObject, team: EsportsTeamRef): Boolean {
        val target = listOf(team.code, team.name, team.slug).map(::token).filter { it.isNotBlank() }
        val candidate = listOf(opgg.optString("acronym"), opgg.optString("name")).map(::token).filter { it.isNotBlank() }
        return candidate.any { a -> target.any { b -> a == b || (a.length >= 3 && b.contains(a)) || (b.length >= 3 && a.contains(b)) } }
    }

    private fun collectTeamImage(team: JSONObject?, out: MutableMap<String, String>) {
        if (team == null) return
        val image = team.optString("imageUrlDarkMode")
            .ifBlank { team.optString("imageUrl") }
            .ifBlank { team.optString("imageUrlLightMode") }
        if (image.isBlank()) return
        for (value in listOf(team.optString("id"), team.optString("acronym"), team.optString("name"))) {
            val key = token(value)
            if (key.isNotBlank()) out[key] = image
        }
    }

    private fun jsonScalarList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val value = array.opt(i)?.toString()?.trim().orEmpty()
                if (value.isNotBlank() && value != "0" && value != "null") add(value)
            }
        }
    }

    private fun opggLocalDate(value: String): java.time.LocalDate? = runCatching {
        Instant.parse(value).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
    }.getOrNull()

    private fun token(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

    private fun number(raw: Any?): Double = when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
}
