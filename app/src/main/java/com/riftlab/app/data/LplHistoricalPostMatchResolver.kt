package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Restores completed LPL series data without depending on the live-match feed.
 *
 * Resolution chain:
 * Riot completed schedule -> LPL historical GameList -> historical BMatch list -> bMatchId
 * -> TJStats matchDetail -> CompletedGameArchive.
 */
internal class LplHistoricalPostMatchResolver {

    companion object {
        private const val LPL_BASE = "https://lpl.qq.com"
        private const val GAME_LIST = "$LPL_BASE/web201612/data/LOL_MATCH2_GAME_LIST_BRIEF.js"
        private const val BMATCH_PREFIX = "$LPL_BASE/web201612/data/LOL_MATCH2_MATCH_HOMEPAGE_BMATCH_LIST_"
        private const val TJ_BASE = "https://open.tjstats.com/match-auth-app/open/v1"
        private const val TJ_AUTH = "7935be4c41d8760a28c05581a7b1f570"
        private const val MAX_GAME_LIST_PROBES = 18
    }

    private data class HistoricalMatchRef(
        val bmid: String,
        val gameId: String,
        val matchName: String,
        val matchDate: String,
        val scoreA: Int,
        val scoreB: Int,
        val matchStatus: Int
    )

    private data class ParsedGame(
        val bo: Int,
        val status: Int,
        val gameTime: Int,
        val blueTeamId: Int,
        val teams: List<TeamState>
    )

    private data class TeamState(
        val teamId: Int,
        val gold: Int,
        val kills: Int,
        val towers: Int,
        val dragons: Int,
        val barons: Int,
        val players: List<LivePlayerSnapshot>
    )

    private val _status = MutableStateFlow("POST · 历史赛事解析器待命")
    val status: StateFlow<String> = _status.asStateFlow()

    private var lastResolvedScheduleKey: String = ""

    suspend fun refresh(match: ScheduledEsportsMatch) {
        try {
            refreshInternal(match)
        } catch (t: Throwable) {
            _status.value = "POST · 历史赛后恢复失败 · ${t.message?.take(180) ?: t::class.java.simpleName}"
        }
    }

    private suspend fun refreshInternal(match: ScheduledEsportsMatch) {
        val scheduleKey = match.eventId.ifBlank { match.matchId }.ifBlank {
            match.teams.take(2).joinToString("|") { team -> team.code.ifBlank { team.name } } + "|" + match.startTimeIso
        }

        val already = CompletedGameArchive.series.value
        if (already != null && already.seriesFinished && lastResolvedScheduleKey == scheduleKey) {
            _status.value = "POST · 已恢复 ${already.teamA} ${already.scoreA}:${already.scoreB} ${already.teamB} · ${already.games.size} 局"
            return
        }

        _status.value = "POST · 正在从 LPL 历史赛事列表定位 ${teamsLabel(match)}…"

        val ref = resolveHistoricalBMatch(match)
        if (ref == null) {
            _status.value = "POST · 未在 LPL 历史 BMatch 列表找到 ${teamsLabel(match)} · ${localDate(match.startTimeIso)}"
            return
        }

        _status.value = "POST · 已定位 bmid=${ref.bmid} · 正在重建终局数据…"
        val root = getJson("$TJ_BASE/compound/matchDetail?matchId=${enc(ref.bmid)}", auth = true)
        if (root.has("success") && !root.optBoolean("success", false)) {
            throw IOException("matchDetail success=false for bmid=${ref.bmid}")
        }
        val data = root.optJSONObject("data") ?: throw IOException("matchDetail missing data for bmid=${ref.bmid}")

        val teamAId = intAny(data, "teamAId", "teamAID")
        val teamBId = intAny(data, "teamBId", "teamBID")
        val targetA = match.teams.getOrNull(0)?.let { team -> team.code.ifBlank { team.name } }.orEmpty()
        val targetB = match.teams.getOrNull(1)?.let { team -> team.code.ifBlank { team.name } }.orEmpty()
        val teamAName = data.optString("teamAName").ifBlank { targetA.ifBlank { ref.matchName.substringBefore(" vs ").trim() } }
        val teamBName = data.optString("teamBName").ifBlank { targetB.ifBlank { ref.matchName.substringAfter(" vs ", "TEAM B").trim() } }
        val scoreA = intAny(data, "teamAScore", "scoreA").takeIf { it > 0 } ?: ref.scoreA
        val scoreB = intAny(data, "teamBScore", "scoreB").takeIf { it > 0 } ?: ref.scoreB
        val seriesStatus = intAny(data, "matchStatus", "status").takeIf { it > 0 } ?: ref.matchStatus
        val gameArray = firstArray(
            data,
            "matchInfos", "gameInfos", "games", "gameList", "singleGames", "gameInfoList"
        ) ?: findLikelyGameArray(data) ?: JSONArray()
        val games = parseGames(gameArray)

        val finals = games
            .filter { game -> (game.status == 3 || seriesStatus == 3) && isMeaningful(game.teams) }
            .mapNotNull { game ->
                finalSnapshotFor(
                    game = game,
                    bmid = ref.bmid,
                    teamAId = teamAId,
                    teamBId = teamBId,
                    teamAName = teamAName,
                    teamBName = teamBName
                )
            }
            .sortedBy { it.game }

        if (finals.isEmpty()) {
            _status.value = "POST · bmid=${ref.bmid} 已定位，但终局解析为空 · games=${games.size} · ${schemaSummary(data)}"
            return
        }

        val snapshot = CompletedSeriesSnapshot(
            matchKey = "TJ:${ref.bmid}",
            teamA = teamAName,
            teamB = teamBName,
            scoreA = scoreA,
            scoreB = scoreB,
            games = finals,
            seriesFinished = seriesStatus == 3 || isSeriesWon(match.bestOf, scoreA, scoreB),
            source = "LPL Historical BMatch → TJStats matchDetail FINAL"
        )
        CompletedGameArchive.publishSeries(snapshot)
        lastResolvedScheduleKey = scheduleKey
        _status.value = "POST · 已恢复 ${snapshot.teamA} ${snapshot.scoreA}:${snapshot.scoreB} ${snapshot.teamB} · ${snapshot.games.size} 局 · bmid=${ref.bmid}"
    }

    private suspend fun resolveHistoricalBMatch(match: ScheduledEsportsMatch): HistoricalMatchRef? {
        val gameListRoot = parseLooseJson(getText(GAME_LIST, auth = false))
        val sGameList = gameListRoot.optJSONObject("msg")?.optJSONObject("sGameList") ?: return null
        val gameIds = buildList {
            val keys = sGameList.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = sGameList.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.opt("GameId")?.toString().orEmpty()
                    if (id.isNotBlank()) add(id)
                }
            }
        }.distinct().sortedByDescending { it.toLongOrNull() ?: Long.MIN_VALUE }

        val targetDate = localDate(match.startTimeIso)
        var best: Pair<HistoricalMatchRef, Int>? = null

        for (gameId in gameIds.take(MAX_GAME_LIST_PROBES)) {
            val url = "$BMATCH_PREFIX${enc(gameId)}.js"
            val root = runCatching { parseLooseJson(getText(url, auth = false)) }.getOrNull() ?: continue
            val arr = root.optJSONArray("msg") ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val bmid = stringAny(obj, "bMatchId", "bMatchID", "BMatchId")
                if (bmid.isBlank()) continue
                val name = stringAny(obj, "bMatchName", "BMatchName", "matchName")
                val date = stringAny(obj, "MatchDate", "matchDate", "startTime")
                val candidate = HistoricalMatchRef(
                    bmid = bmid,
                    gameId = gameId,
                    matchName = name,
                    matchDate = date,
                    scoreA = intAny(obj, "ScoreA", "scoreA"),
                    scoreB = intAny(obj, "ScoreB", "scoreB"),
                    matchStatus = intAny(obj, "MatchStatus", "matchStatus", "status")
                )
                val score = historicalMatchScore(candidate, match, targetDate)
                if (score > (best?.second ?: Int.MIN_VALUE)) best = candidate to score
            }
            if ((best?.second ?: 0) >= 150) break
        }

        return best?.takeIf { it.second >= 90 }?.first
    }

    private fun historicalMatchScore(candidate: HistoricalMatchRef, target: ScheduledEsportsMatch, targetDate: String): Int {
        val left = target.teams.getOrNull(0) ?: return 0
        val right = target.teams.getOrNull(1) ?: return 0
        val parts = candidate.matchName.split(Regex("\\s+vs\\s+", RegexOption.IGNORE_CASE), limit = 2)
        val a = parts.getOrNull(0).orEmpty()
        val b = parts.getOrNull(1).orEmpty()

        val direct = teamMatches(a, left) && teamMatches(b, right)
        val swapped = teamMatches(a, right) && teamMatches(b, left)
        if (!direct && !swapped) return 0

        var score = if (direct) 120 else 115
        if (targetDate.isNotBlank() && candidate.matchDate.startsWith(targetDate)) score += 35
        if (candidate.matchStatus == 3) score += 10
        val targetLeftWins = left.gameWins
        val targetRightWins = right.gameWins
        if (targetLeftWins > 0 || targetRightWins > 0) {
            val scoreMatches = if (direct) {
                candidate.scoreA == targetLeftWins && candidate.scoreB == targetRightWins
            } else {
                candidate.scoreA == targetRightWins && candidate.scoreB == targetLeftWins
            }
            if (scoreMatches) score += 20
        }
        return score
    }

    private fun parseGames(infos: JSONArray): List<ParsedGame> = buildList {
        for (i in 0 until infos.length()) {
            val info = infos.optJSONObject(i) ?: continue
            val teamInfos = firstArray(
                info,
                "teamInfos", "teams", "teamInfoList", "gameTeamInfos", "teamList", "teamData"
            ) ?: findLikelyTeamArray(info) ?: JSONArray()

            val teams = buildList {
                for (j in 0 until teamInfos.length()) {
                    val team = teamInfos.optJSONObject(j) ?: continue
                    val id = intAny(team, "teamId", "teamID", "team_id", "id")
                    if (id <= 0) continue
                    val players = firstArray(
                        team,
                        "playerInfos", "players", "playerInfoList", "gamePlayerInfos", "playerList"
                    ) ?: JSONArray()
                    add(
                        TeamState(
                            teamId = id,
                            gold = intAny(team, "golds", "gold", "totalGold", "teamGold", "goldAmount"),
                            kills = intAny(team, "kills", "kill", "totalKills", "killAmount"),
                            towers = intAny(team, "turretAmount", "towers", "tower", "towerAmount", "turretCount"),
                            dragons = intAny(team, "dragonAmount", "dragons", "dragon", "dragonCount"),
                            barons = intAny(team, "baronAmount", "barons", "baron", "baronCount"),
                            players = parsePlayers(players)
                        )
                    )
                }
            }

            // Some TJStats payloads put all players at game level rather than under each team.
            // If the team rows have no player list, attach players by teamId when possible.
            val gamePlayers = firstArray(
                info,
                "playerInfos", "players", "playerInfoList", "gamePlayerInfos", "playerList"
            ) ?: JSONArray()
            val playersByTeam = parsePlayersByTeam(gamePlayers)
            val mergedTeams = teams.map { team ->
                if (team.players.isNotEmpty()) team
                else team.copy(players = playersByTeam[team.teamId].orEmpty())
            }

            add(
                ParsedGame(
                    bo = parseBo(info, i + 1),
                    status = intAny(info, "matchStatus", "status", "gameStatus", "state"),
                    gameTime = intAny(info, "gameTime", "time", "elapsedSeconds", "gameDuration", "duration"),
                    blueTeamId = intAny(info, "blueTeam", "blueTeamId", "blueId", "blueTeamID"),
                    teams = mergedTeams
                )
            )
        }
    }

    private fun firstArray(obj: JSONObject, vararg keys: String): JSONArray? {
        for (key in keys) {
            val arr = obj.optJSONArray(key)
            if (arr != null && arr.length() > 0) return arr
        }
        return null
    }

    private fun findLikelyGameArray(root: JSONObject): JSONArray? {
        val preferred = listOf("matchInfos", "gameInfos", "games", "gameList", "singleGames", "gameInfoList")
        firstArray(root, *preferred.toTypedArray())?.let { return it }
        return findArrayRecursive(root, maxDepth = 3) { arr ->
            if (arr.length() == 0) false
            else {
                val sample = arr.optJSONObject(0) ?: return@findArrayRecursive false
                val keys = sample.keys().asSequence().toSet()
                keys.any { it in setOf("bo", "gameNo", "gameNum", "gameNumber", "gameIndex", "round", "gameStatus", "matchStatus") } ||
                    firstArray(sample, "teamInfos", "teams", "teamInfoList", "gameTeamInfos", "teamList") != null
            }
        }
    }

    private fun findLikelyTeamArray(root: JSONObject): JSONArray? =
        findArrayRecursive(root, maxDepth = 2) { arr ->
            if (arr.length() !in 2..4) false
            else {
                var teamLike = 0
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (intAny(obj, "teamId", "teamID", "team_id") > 0) teamLike++
                }
                teamLike >= 2
            }
        }

    private fun findArrayRecursive(
        root: JSONObject,
        maxDepth: Int,
        predicate: (JSONArray) -> Boolean
    ): JSONArray? {
        fun walkObject(obj: JSONObject, depth: Int): JSONArray? {
            if (depth > maxDepth) return null
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when (val value = obj.opt(key)) {
                    is JSONArray -> {
                        if (predicate(value)) return value
                        for (i in 0 until value.length()) {
                            val child = value.optJSONObject(i) ?: continue
                            walkObject(child, depth + 1)?.let { return it }
                        }
                    }
                    is JSONObject -> walkObject(value, depth + 1)?.let { return it }
                }
            }
            return null
        }
        return walkObject(root, 0)
    }

    private fun parsePlayersByTeam(array: JSONArray): Map<Int, List<LivePlayerSnapshot>> {
        val grouped = linkedMapOf<Int, MutableList<LivePlayerSnapshot>>()
        for (i in 0 until array.length()) {
            val p = array.optJSONObject(i) ?: continue
            val teamId = intAny(p, "teamId", "teamID", "team_id")
            if (teamId <= 0) continue
            val one = JSONArray().put(p)
            val parsed = parsePlayers(one).firstOrNull() ?: continue
            grouped.getOrPut(teamId) { mutableListOf() }.add(parsed)
        }
        return grouped
    }

    private fun schemaSummary(data: JSONObject): String {
        val keys = data.keys().asSequence().take(12).joinToString(",")
        val arrays = mutableListOf<String>()
        val it = data.keys()
        while (it.hasNext() && arrays.size < 8) {
            val key = it.next()
            val arr = data.optJSONArray(key) ?: continue
            arrays += "$key:${arr.length()}"
        }
        return "keys=[$keys]" + if (arrays.isNotEmpty()) " arrays=[${arrays.joinToString(",")}]" else ""
    }

    private fun finalSnapshotFor(
        game: ParsedGame,
        bmid: String,
        teamAId: Int,
        teamBId: Int,
        teamAName: String,
        teamBName: String
    ): LiveSnapshot? {
        val blueId = game.blueTeamId.takeIf { it > 0 } ?: teamAId
        val redId = when (blueId) {
            teamAId -> teamBId
            teamBId -> teamAId
            else -> teamBId
        }
        val byId = game.teams.associateBy { it.teamId }
        val blue = byId[blueId] ?: game.teams.firstOrNull() ?: return null
        val red = byId[redId] ?: game.teams.firstOrNull { it.teamId != blue.teamId } ?: return null
        if (!isMeaningful(listOf(blue, red))) return null

        val blueName = when (blue.teamId) {
            teamAId -> teamAName
            teamBId -> teamBName
            else -> "BLUE"
        }
        val redName = when (red.teamId) {
            teamAId -> teamAName
            teamBId -> teamBName
            else -> "RED"
        }
        return LiveSnapshot(
            game = game.bo,
            elapsedSeconds = game.gameTime.coerceAtLeast(0),
            blue = blueName,
            red = redName,
            blueGold = blue.gold,
            redGold = red.gold,
            blueKills = blue.kills,
            redKills = red.kills,
            blueTowers = blue.towers,
            redTowers = red.towers,
            blueDragons = blue.dragons,
            redDragons = red.dragons,
            blueBarons = blue.barons,
            redBarons = red.barons,
            bluePlayers = blue.players,
            redPlayers = red.players,
            latestEvent = "FINAL · G${game.bo}",
            source = "LPL Historical BMatch → TJStats FINAL",
            gameId = "TJ:$bmid:G${game.bo}"
        )
    }

    private fun parsePlayers(array: JSONArray): List<LivePlayerSnapshot> = buildList {
        for (i in 0 until array.length()) {
            val p = array.optJSONObject(i) ?: continue
            val battle = p.optJSONObject("battleDetail") ?: JSONObject()
            val other = p.optJSONObject("otherDetail") ?: JSONObject()
            add(
                LivePlayerSnapshot(
                    participantId = intAny(p, "playerId", "playerID", "id").takeIf { it > 0 } ?: i + 1,
                    role = normalizeRole(stringAny(p, "playerLocation", "position", "role")),
                    summonerName = stringAny(p, "playerName", "summonerName", "name").ifBlank { "P${i + 1}" },
                    championId = stringAny(p, "heroId", "championId", "championID"),
                    level = other.optInt("level", 0),
                    kills = battle.optInt("kills", 0),
                    deaths = battle.optInt("death", 0),
                    assists = battle.optInt("assist", 0),
                    creepScore = p.optInt("minionKilled", other.optInt("creepsKilled", 0)),
                    gold = other.optInt("golds", 0)
                )
            )
        }
    }

    private fun parseBo(info: JSONObject, fallback: Int): Int {
        for (key in listOf("bo", "gameNo", "gameNum", "gameNumber", "gameIndex", "round")) {
            if (!info.has(key)) continue
            when (val raw = info.opt(key)) {
                is Number -> if (raw.toInt() > 0) return raw.toInt()
                is String -> Regex("\\d+").find(raw)?.value?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
            }
        }
        return fallback
    }

    private fun isMeaningful(teams: List<TeamState>): Boolean = teams.any { team ->
        team.gold > 0 || team.kills > 0 || team.towers > 0 || team.dragons > 0 || team.barons > 0 ||
            team.players.any { it.gold > 0 || it.level > 0 || it.creepScore > 0 }
    }

    private fun isSeriesWon(bestOf: Int, a: Int, b: Int): Boolean {
        val need = if (bestOf > 0) bestOf / 2 + 1 else 1
        return a >= need || b >= need
    }

    private fun teamsLabel(match: ScheduledEsportsMatch): String =
        match.teams.take(2).joinToString(" vs ") { team -> team.code.ifBlank { team.name } }

    private fun teamMatches(upstreamName: String, team: EsportsTeamRef): Boolean {
        val upstream = teamKey(upstreamName)
        if (upstream.isBlank()) return false
        val candidates = listOf(team.code, team.name, team.slug).map(::teamKey).filter { it.isNotBlank() }
        return candidates.any { token ->
            upstream == token ||
                (token.length >= 3 && upstream.contains(token)) ||
                (upstream.length >= 3 && token.contains(upstream))
        }
    }

    private fun teamKey(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

    private fun localDate(iso: String): String {
        if (iso.isBlank()) return ""
        return runCatching {
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneId.of("Asia/Shanghai"))
                .format(Instant.parse(iso))
        }.getOrElse { iso.take(10) }
    }

    private fun normalizeRole(raw: String): String = when (raw.lowercase()) {
        "top", "1" -> "TOP"
        "jungle", "jug", "2" -> "JUG"
        "mid", "middle", "3" -> "MID"
        "bottom", "bot", "adc", "4" -> "BOT"
        "support", "sup", "5" -> "SUP"
        else -> raw.uppercase().ifBlank { "—" }
    }

    private fun parseLooseJson(text: String): JSONObject {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw IOException("invalid JSON/JS payload")
        return JSONObject(text.substring(start, end + 1))
    }

    private fun intAny(obj: JSONObject, vararg keys: String): Int {
        for (key in keys) {
            if (!obj.has(key)) continue
            val value = when (val raw = obj.opt(key)) {
                is Number -> raw.toInt()
                is String -> raw.toDoubleOrNull()?.toInt()
                else -> null
            }
            if (value != null) return value
        }
        return 0
    }

    private fun stringAny(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.opt(key)?.toString()?.trim().orEmpty()
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private suspend fun getJson(url: String, auth: Boolean): JSONObject =
        parseLooseJson(getText(url, auth))

    private suspend fun getText(url: String, auth: Boolean): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7_000
            readTimeout = 7_000
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) RiftLab/1.0")
            setRequestProperty("Referer", "$LPL_BASE/")
            setRequestProperty("Origin", LPL_BASE)
            if (auth) setRequestProperty("Authorization", TJ_AUTH)
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code ${URL(url).path}: ${body.take(120)}")
            if (body.isBlank()) throw IOException("empty response ${URL(url).path}")
            body
        } finally {
            connection.disconnect()
        }
    }
}
