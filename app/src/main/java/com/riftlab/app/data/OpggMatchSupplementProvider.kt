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
    val series: CompletedSeriesSnapshot? = null,
    val seriesMvp: OfficialMvpRecord? = null,
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
                  kills deaths assists towerKills inhibitorKills heraldKills dragonKills elderDrakeKills baronKills goldEarned
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
        val finals = mutableListOf<LiveSnapshot>()
        val seriesMvpPoints = linkedMapOf<String, Double>()
        val seriesMvpMeta = linkedMapOf<String, OfficialMvpRecord>()
        val maxSets = match.bestOf.takeIf { it > 0 } ?: 5

        for (set in 1..maxSets) {
            val game = runCatching { fetchGame(matchId, set) }.getOrNull() ?: continue
            if (game.length() == 0) continue

            val teamSideById = linkedMapOf<String, String>()
            val teamCodeById = linkedMapOf<String, String>()
            val blueBans = mutableListOf<String>()
            val redBans = mutableListOf<String>()
            var blueCode = ""
            var redCode = ""
            var blueGold = 0
            var redGold = 0
            var blueKills = 0
            var redKills = 0
            var blueTowers = 0
            var redTowers = 0
            var blueDragons = 0
            var redDragons = 0
            var blueBarons = 0
            var redBarons = 0
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
                    "blue" -> {
                        blueBans += bans
                        blueCode = code
                        blueGold = row.optInt("goldEarned", 0)
                        blueKills = row.optInt("kills", 0)
                        blueTowers = row.optInt("towerKills", 0)
                        blueDragons = row.optInt("dragonKills", 0) + row.optInt("elderDrakeKills", 0)
                        blueBarons = row.optInt("baronKills", 0)
                    }
                    "red" -> {
                        redBans += bans
                        redCode = code
                        redGold = row.optInt("goldEarned", 0)
                        redKills = row.optInt("kills", 0)
                        redTowers = row.optInt("towerKills", 0)
                        redDragons = row.optInt("dragonKills", 0) + row.optInt("elderDrakeKills", 0)
                        redBarons = row.optInt("baronKills", 0)
                    }
                }
            }

            val bluePicks = mutableListOf<String>()
            val redPicks = mutableListOf<String>()
            // OP.GG exposes player/champion/role identity even when its public game payload does not
            // expose a trustworthy numeric stat line. Keep that identity so the UI can show the
            // actual five champions instead of ten "data missing" rows; zero numeric fields remain
            // explicitly non-measured and are excluded from comprehensive stat coverage.
            val bluePlayerSnapshots = mutableListOf<LivePlayerSnapshot>()
            val redPlayerSnapshots = mutableListOf<LivePlayerSnapshot>()
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

                val playerName = player.optString("nickName")
                    .ifBlank { row.optString("nickName") }
                    .ifBlank { "P${i + 1}" }
                val teamCode = team.optString("acronym")
                    .ifBlank { team.optString("name") }
                    .ifBlank { teamCodeById[teamId].orEmpty() }
                val role = normalizeLineupRole(row.optString("position").ifBlank { player.optString("position") })
                val playerImage = normalizeAssetUrl(player.optString("imageUrl"))
                if (playerImage.isNotBlank()) {
                    EsportsAssetCache.putPlayer(playerName, teamCode, playerImage)
                }
                val identitySnapshot = LivePlayerSnapshot(
                    participantId = i + 1,
                    role = role,
                    summonerName = playerName,
                    championId = champion,
                    level = 0,
                    kills = 0,
                    deaths = 0,
                    assists = 0,
                    creepScore = 0,
                    gold = 0,
                    teamId = teamId,
                    side = side.uppercase()
                )
                when (side) {
                    "blue" -> bluePlayerSnapshots += identitySnapshot
                    "red" -> redPlayerSnapshots += identitySnapshot
                }

                val point = number(row.opt("mvpPoint"))
                if (point > 0.0) {
                    ratings += Rating(
                        name = playerName,
                        team = teamCode,
                        role = role,
                        point = point
                    )
                    val ratingKey = "${token(playerName)}|${token(teamCode)}"
                    seriesMvpPoints[ratingKey] = (seriesMvpPoints[ratingKey] ?: 0.0) + point
                    seriesMvpMeta[ratingKey] = OfficialMvpRecord(
                        game = null,
                        playerName = playerName,
                        team = teamCode,
                        role = role,
                        source = "OP.GG · 系列赛 MVP Point 累计（第三方评分，非官方奖项）"
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

            val elapsed = normalizeGameLengthSeconds(game.optLong("length", 0L))
            val terminalMeaningful = blueGold > 0 || redGold > 0 || blueKills > 0 || redKills > 0 ||
                blueTowers > 0 || redTowers > 0 || blueDragons > 0 || redDragons > 0 || blueBarons > 0 || redBarons > 0
            if (game.optBoolean("finished", false) && blueCode.isNotBlank() && redCode.isNotBlank() && terminalMeaningful) {
                finals += LiveSnapshot(
                    game = set,
                    elapsedSeconds = elapsed,
                    blue = blueCode,
                    red = redCode,
                    blueGold = blueGold,
                    redGold = redGold,
                    blueKills = blueKills,
                    redKills = redKills,
                    blueTowers = blueTowers,
                    redTowers = redTowers,
                    blueDragons = blueDragons,
                    redDragons = redDragons,
                    latestEvent = "OP.GG 终局快照 · G$set",
                    blueBarons = blueBarons,
                    redBarons = redBarons,
                    bluePlayers = bluePlayerSnapshots.sortedBy { lineupRoleOrder(it.role) },
                    redPlayers = redPlayerSnapshots.sortedBy { lineupRoleOrder(it.role) },
                    source = "OP.GG Esports · gameByMatch FINAL · third-party",
                    gameId = "opgg:${game.opt("id")?.toString().orEmpty()}"
                )
            }
        }

        val left = match.teams.getOrNull(0)
        val right = match.teams.getOrNull(1)
        val scoreA = left?.let { scoreForTeam(opggMatch, it) } ?: 0
        val scoreB = right?.let { scoreForTeam(opggMatch, it) } ?: 0
        val series = if (left != null && right != null && finals.isNotEmpty()) {
            CompletedSeriesSnapshot(
                matchKey = "OPGG:$matchId",
                teamA = left.code.ifBlank { left.name },
                teamB = right.code.ifBlank { right.name },
                scoreA = scoreA.takeIf { it > 0 || scoreB > 0 } ?: left.gameWins,
                scoreB = scoreB.takeIf { it > 0 || scoreA > 0 } ?: right.gameWins,
                games = finals.sortedBy { it.game },
                seriesFinished = opggMatch.optString("status").contains("finish", ignoreCase = true) ||
                    scoreA > 0 || scoreB > 0,
                source = "OP.GG Esports · gameByMatch FINAL · third-party"
            )
        } else null
        val seriesMvp = seriesMvpPoints.maxByOrNull { it.value }?.key?.let(seriesMvpMeta::get)

        OpggMatchSupplement(
            series = series,
            seriesMvp = seriesMvp,
            drafts = drafts,
            gameMvps = mvps,
            panels = panels,
            teamImages = images,
            status = "OP.GG · match=$matchId · FINAL ${finals.size} 局 · BP ${drafts.size} 局 · MVP Point ${mvps.size} 局"
        )
    }

    private fun scoreForTeam(opggMatch: JSONObject, team: EsportsTeamRef): Int {
        val home = opggMatch.optJSONObject("homeTeam")
        val away = opggMatch.optJSONObject("awayTeam")
        return when {
            home != null && teamMatches(home, team) -> opggMatch.optInt("homeScore", team.gameWins)
            away != null && teamMatches(away, team) -> opggMatch.optInt("awayScore", team.gameWins)
            else -> team.gameWins
        }
    }

    private fun normalizeGameLengthSeconds(raw: Long): Int = when {
        raw <= 0L -> 0
        raw > 100_000L -> (raw / 1000L).toInt()
        else -> raw.toInt()
    }

    /** Latest actually-played five for one team, with role/name/image for Riot-roster gaps. */
    suspend fun fetchLatestLineupPlayers(match: ScheduledEsportsMatch, team: EsportsTeamRef): List<EsportsPlayerRef> =
        withContext(Dispatchers.IO) {
            val opggMatch = findMatch(match) ?: return@withContext emptyList()
            val matchId = opggMatch.opt("id")?.toString().orEmpty()
            if (matchId.isBlank()) return@withContext emptyList()
            val maxSets = match.bestOf.takeIf { it > 0 } ?: 5
            for (set in maxSets downTo 1) {
                val game = runCatching { fetchGame(matchId, set) }.getOrNull() ?: continue
                if (game.length() == 0) continue
                val players = game.optJSONArray("players") ?: continue
                val found = linkedMapOf<String, EsportsPlayerRef>()
                for (i in 0 until players.length()) {
                    val row = players.optJSONObject(i) ?: continue
                    val rowTeam = row.optJSONObject("team") ?: JSONObject()
                    if (!teamMatches(rowTeam, team)) continue
                    val player = row.optJSONObject("player") ?: JSONObject()
                    val name = player.optString("nickName").ifBlank { row.optString("nickName") }
                    if (name.isBlank()) continue
                    val role = normalizeLineupRole(row.optString("position").ifBlank { player.optString("position") })
                    val image = normalizeAssetUrl(player.optString("imageUrl"))
                    if (image.isNotBlank()) EsportsAssetCache.putPlayer(name, team.code, image)
                    found[token(name)] = EsportsPlayerRef(
                        id = player.opt("id")?.toString().orEmpty(),
                        summonerName = name,
                        role = role,
                        imageUrl = image
                    )
                }
                if (found.size >= 5) {
                    return@withContext found.values.sortedBy { lineupRoleOrder(it.role) }.take(7)
                }
            }
            emptyList()
        }

    suspend fun fetchLatestLineup(match: ScheduledEsportsMatch, team: EsportsTeamRef): Set<String> =
        fetchLatestLineupPlayers(match, team).map { it.summonerName }.filter { it.isNotBlank() }.toSet()

    private fun normalizeLineupRole(value: String): String = when (value.lowercase()) {
        "top" -> "TOP"
        "jungle", "jun" -> "JUG"
        "mid", "middle" -> "MID"
        "bottom", "bot", "adc" -> "BOT"
        "support", "sup" -> "SUP"
        else -> value.uppercase().ifBlank { "—" }
    }

    private fun lineupRoleOrder(role: String): Int = when (role.uppercase()) {
        "TOP" -> 0
        "JUG", "JUNGLE" -> 1
        "MID" -> 2
        "BOT", "ADC", "BOTTOM" -> 3
        "SUP", "SUPPORT" -> 4
        else -> 99
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
        val image = normalizeAssetUrl(
            team.optString("imageUrlDarkMode")
                .ifBlank { team.optString("imageUrl") }
                .ifBlank { team.optString("imageUrlLightMode") }
        )
        if (image.isBlank()) return
        val aliases = listOf(team.optString("id"), team.optString("acronym"), team.optString("name"))
        EsportsAssetCache.putTeam(image, *aliases.toTypedArray())
        for (value in aliases) {
            val key = token(value)
            if (key.isNotBlank()) out[key] = image
        }
    }

    private fun normalizeAssetUrl(raw: String): String = EsportsAssetCache.normalize(raw)

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
