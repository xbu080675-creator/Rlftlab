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

        val seriesFinished = seriesStatus == 3 || ref.matchStatus == 3 || isSeriesWon(match.bestOf, scoreA, scoreB)
        val finals = games
            .filter { game -> isMeaningful(game.teams) && (game.status == 3 || seriesFinished) }
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
            _status.value = "POST · bmid=${ref.bmid} 已定位，但终局解析为空 · games=${games.size} · ${parsedGameSummary(gameArray, games)} · ${schemaSummary(data)}"
            return
        }

        val snapshot = CompletedSeriesSnapshot(
            matchKey = "TJ:${ref.bmid}",
            teamA = teamAName,
            teamB = teamBName,
            scoreA = scoreA,
            scoreB = scoreB,
            games = finals,
            seriesFinished = seriesFinished,
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
            val directArray = firstArray(
                info,
                "teamInfos", "teams", "teamInfoList", "gameTeamInfos", "teamList", "teamData",
                "battleTeams", "teamStats", "teamDetails"
            ) ?: findLikelyTeamArray(info)

            val parsed = mutableListOf<TeamState>()
            if (directArray != null) {
                for (j in 0 until directArray.length()) {
                    val row = directArray.optJSONObject(j) ?: continue
                    parseTeamState(row)?.let(parsed::add)
                }
            }

            // Terminal matchDetail may wrap each team in separate blue/red or A/B objects instead
            // of a 2-row teamInfos array. Recursively collect those objects when needed.
            if (parsed.map { it.teamId }.distinct().size < 2) {
                findLikelyTeamObjects(info).mapNotNull(::parseTeamState).forEach(parsed::add)
            }

            val gamePlayers = firstArray(
                info,
                "playerInfos", "players", "playerInfoList", "gamePlayerInfos", "playerList",
                "battlePlayers", "playerStats", "playerDetails"
            ) ?: findLikelyPlayerArray(info) ?: JSONArray()
            val playersByTeam = parsePlayersByTeam(gamePlayers)

            val mergedTeams = parsed
                .groupBy { it.teamId }
                .mapNotNull { (_, rows) -> rows.maxByOrNull(::teamRichness) }
                .map { team ->
                    if (team.players.isNotEmpty()) team
                    else team.copy(players = playersByTeam[team.teamId].orEmpty())
                }
                .map { team ->
                    // If terminal team totals are absent but player finals are present, derive safe
                    // additive metrics. Do not derive towers/dragons/barons from players.
                    if (team.players.isEmpty()) team else team.copy(
                        gold = team.gold.takeIf { it > 0 } ?: team.players.sumOf { it.gold },
                        kills = team.kills.takeIf { it > 0 } ?: team.players.sumOf { it.kills }
                    )
                }

            add(
                ParsedGame(
                    bo = parseBo(info, i + 1),
                    status = intAny(info, "matchStatus", "status", "gameStatus", "state", "gameState"),
                    gameTime = secondsAny(info, "gameTime", "time", "elapsedSeconds", "gameDuration", "duration"),
                    blueTeamId = intAny(info, "blueTeam", "blueTeamId", "blueId", "blueTeamID", "blueIdNum"),
                    teams = mergedTeams
                )
            )
        }
    }

    private fun parseTeamState(raw: JSONObject): TeamState? {
        val id = intAny(raw, "teamId", "teamID", "team_id", "id").takeIf { it > 0 }
            ?: deepTeamId(raw)
        if (id <= 0) return null

        val playerArray = firstArray(
            raw,
            "playerInfos", "players", "playerInfoList", "gamePlayerInfos", "playerList",
            "battlePlayers", "playerStats", "playerDetails"
        ) ?: findLikelyPlayerArray(raw) ?: JSONArray()
        val players = parsePlayers(playerArray)

        val gold = teamMetric(raw, "golds", "gold", "totalGold", "teamGold", "goldAmount", "total_gold")
        val kills = teamMetric(raw, "kills", "kill", "totalKills", "killAmount", "killCount")
        val towers = teamMetric(raw, "turretAmount", "towers", "tower", "towerAmount", "turretCount", "towerCount")
        val dragons = teamMetric(raw, "dragonAmount", "dragons", "dragon", "dragonCount")
        val barons = teamMetric(raw, "baronAmount", "barons", "baron", "baronCount", "nashorCount")

        return TeamState(
            teamId = id,
            gold = gold.takeIf { it > 0 } ?: players.sumOf { it.gold },
            kills = kills.takeIf { it > 0 } ?: players.sumOf { it.kills },
            towers = towers,
            dragons = dragons,
            barons = barons,
            players = players
        )
    }

    private fun teamMetric(raw: JSONObject, vararg keys: String): Int {
        intAny(raw, *keys).takeIf { it != 0 }?.let { return it }
        for (container in listOf("battleDetail", "stats", "teamStats", "detail", "otherDetail", "gameData", "teamData", "battleData")) {
            val obj = raw.optJSONObject(container) ?: continue
            intAny(obj, *keys).takeIf { it != 0 }?.let { return it }
        }
        return 0
    }

    private fun teamRichness(team: TeamState): Int =
        (if (team.gold > 0) 4 else 0) +
            (if (team.kills > 0) 2 else 0) +
            (if (team.towers > 0 || team.dragons > 0 || team.barons > 0) 2 else 0) +
            team.players.size

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
                keys.any { it in setOf("bo", "gameNo", "gameNum", "gameNumber", "gameIndex", "round", "gameStatus", "matchStatus", "gameState") } ||
                    firstArray(sample, "teamInfos", "teams", "teamInfoList", "gameTeamInfos", "teamList") != null
            }
        }
    }

    private fun findLikelyTeamArray(root: JSONObject): JSONArray? =
        findArrayRecursive(root, maxDepth = 3) { arr ->
            if (arr.length() !in 2..6) false
            else {
                var teamLike = 0
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (deepTeamId(obj) > 0) teamLike++
                }
                teamLike >= 2
            }
        }

    private fun findLikelyPlayerArray(root: JSONObject): JSONArray? =
        findArrayRecursive(root, maxDepth = 3) { arr ->
            if (arr.length() < 5) false
            else {
                var playerLike = 0
                for (i in 0 until minOf(arr.length(), 12)) {
                    val p = arr.optJSONObject(i) ?: continue
                    if (
                        intAny(p, "playerId", "playerID") > 0 ||
                        stringAny(p, "playerName", "summonerName", "name").isNotBlank() ||
                        stringAny(p, "heroId", "championId").isNotBlank()
                    ) playerLike++
                }
                playerLike >= minOf(5, arr.length())
            }
        }

    private fun findLikelyTeamObjects(root: JSONObject): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        fun walk(obj: JSONObject, depth: Int) {
            if (depth > 4) return
            val directId = intAny(obj, "teamId", "teamID", "team_id")
            val hasTeamSignal = directId > 0 && (
                teamMetric(obj, "gold", "golds", "totalGold", "kills", "totalKills", "towers", "turretAmount") != 0 ||
                    findLikelyPlayerArray(obj) != null ||
                    obj.has("playerInfos") || obj.has("players")
                )
            if (hasTeamSignal) out += obj

            val keys = obj.keys()
            while (keys.hasNext()) {
                when (val value = obj.opt(keys.next())) {
                    is JSONObject -> walk(value, depth + 1)
                    is JSONArray -> for (i in 0 until value.length()) value.optJSONObject(i)?.let { walk(it, depth + 1) }
                }
            }
        }
        walk(root, 0)
        return out
            .groupBy { deepTeamId(it) }
            .filterKeys { it > 0 }
            .values
            .mapNotNull { rows -> rows.maxByOrNull { row -> objectRichness(row) } }
    }

    private fun objectRichness(obj: JSONObject): Int {
        var score = 0
        if (intAny(obj, "teamId", "teamID", "team_id") > 0) score += 4
        if (teamMetric(obj, "gold", "golds", "totalGold") > 0) score += 4
        if (teamMetric(obj, "kills", "totalKills") > 0) score += 2
        score += (findLikelyPlayerArray(obj)?.length() ?: 0).coerceAtMost(10)
        return score
    }

    private fun deepTeamId(root: JSONObject): Int {
        intAny(root, "teamId", "teamID", "team_id").takeIf { it > 0 }?.let { return it }
        for (container in listOf("teamInfo", "team", "basic", "teamBasicInfo", "gameTeamInfo", "detail")) {
            val obj = root.optJSONObject(container) ?: continue
            intAny(obj, "teamId", "teamID", "team_id", "id").takeIf { it > 0 }?.let { return it }
        }
        return 0
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
            val teamId = intAny(p, "teamId", "teamID", "team_id").takeIf { it > 0 }
                ?: deepTeamId(p)
            if (teamId <= 0) continue
            val one = JSONArray().put(p)
            val parsed = parsePlayers(one).firstOrNull() ?: continue
            grouped.getOrPut(teamId) { mutableListOf() }.add(parsed)
        }
        return grouped
    }

    private fun parsedGameSummary(raw: JSONArray, games: List<ParsedGame>): String {
        val parsed = games.take(5).joinToString(",") { g ->
            "G${g.bo}:s${g.status}:t${g.gameTime}:teams${g.teams.size}:m${g.teams.sumOf { it.gold }}"
        }
        val shapes = mutableListOf<String>()
        for (i in 0 until minOf(raw.length(), 3)) {
            val obj = raw.optJSONObject(i) ?: continue
            val keys = obj.keys().asSequence().take(10).joinToString("/")
            val arrays = mutableListOf<String>()
            val objects = mutableListOf<String>()
            val ids = mutableListOf<String>()
            val it = obj.keys()
            while (it.hasNext()) {
                val key = it.next()
                when (val value = obj.opt(key)) {
                    is JSONArray -> if (arrays.size < 5) arrays += "$key:${value.length()}"
                    is JSONObject -> if (objects.size < 5) objects += key
                    is Number, is String -> if (
                        key.contains("id", true) || key.contains("game", true) || key.contains("match", true)
                    ) {
                        if (ids.size < 6) ids += "$key=${value.toString().take(18)}"
                    }
                }
            }
            shapes += "R${i + 1}{k=$keys;a=${arrays.joinToString("/")};o=${objects.joinToString("/")};id=${ids.joinToString("/")}}"
        }
        return "parsed=[$parsed] raw=[${shapes.joinToString(";")}]"
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

    private fun secondsAny(obj: JSONObject, vararg keys: String): Int {
        for (key in keys) {
            if (!obj.has(key)) continue
            when (val raw = obj.opt(key)) {
                is Number -> return raw.toInt()
                is String -> {
                    raw.toDoubleOrNull()?.toInt()?.let { return it }
                    val parts = raw.trim().split(":")
                    if (parts.size == 2) {
                        val m = parts[0].toIntOrNull()
                        val sec = parts[1].toIntOrNull()
                        if (m != null && sec != null) return m * 60 + sec
                    }
                }
            }
        }
        return 0
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
            val battle = p.optJSONObject("battleDetail") ?: p.optJSONObject("stats") ?: JSONObject()
            val other = p.optJSONObject("otherDetail") ?: p.optJSONObject("detail") ?: JSONObject()
            val kills = intAny(p, "kills", "kill", "killCount").takeIf { it != 0 }
                ?: intAny(battle, "kills", "kill", "killCount")
            val deaths = intAny(p, "deaths", "death", "deathCount").takeIf { it != 0 }
                ?: intAny(battle, "deaths", "death", "deathCount")
            val assists = intAny(p, "assists", "assist", "assistCount").takeIf { it != 0 }
                ?: intAny(battle, "assists", "assist", "assistCount")
            val gold = intAny(p, "golds", "gold", "totalGold").takeIf { it > 0 }
                ?: intAny(other, "golds", "gold", "totalGold")
            val cs = intAny(p, "minionKilled", "creepScore", "cs", "creepsKilled").takeIf { it > 0 }
                ?: intAny(other, "minionKilled", "creepScore", "cs", "creepsKilled")
            val level = intAny(p, "level", "heroLevel").takeIf { it > 0 }
                ?: intAny(other, "level", "heroLevel")
            add(
                LivePlayerSnapshot(
                    participantId = intAny(p, "playerId", "playerID", "id").takeIf { it > 0 } ?: i + 1,
                    role = normalizeRole(stringAny(p, "playerLocation", "position", "role")),
                    summonerName = stringAny(p, "playerName", "summonerName", "name").ifBlank { "P${i + 1}" },
                    championId = stringAny(p, "heroId", "championId", "championID"),
                    level = level,
                    kills = kills,
                    deaths = deaths,
                    assists = assists,
                    creepScore = cs,
                    gold = gold
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
