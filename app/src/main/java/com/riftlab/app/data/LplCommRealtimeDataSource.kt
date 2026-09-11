package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * LPL/Tencent comm-match-app realtime provider.
 *
 * This provider is intentionally match-agnostic. It resolves the current series from the LPL
 * live feed + LiveMatchTargetRegistry, derives the expected small-game number from the series
 * score, then discovers the working realtime query shape on-device. A successful route is cached
 * for the current small game, so discovery is not repeated on every poll.
 */
internal class LplCommRealtimeDataSource : LiveMatchDataSource {

    companion object {
        private const val LPL_BASE = "https://lpl.qq.com"
        private const val LIVE_FEED = "$LPL_BASE/web201612/data/LOL_MATCH2_LIVE_BMATCH_LIST.js"
        private const val MATCH_BASE = "https://open.tjstats.com/match-auth-app/open/v1"
        private const val COMM_BASE = "https://open.tjstats.com/comm-match-app/open/v1"
        private const val TJ_AUTH = "7935be4c41d8760a28c05581a7b1f570"
        private const val POLL_MS = 2_000L
        private const val DISCOVERY_RETRY_MS = 3_000L
    }

    private data class MatchRef(
        val bmid: String,
        val teamAId: Int,
        val teamBId: Int,
        val teamAName: String,
        val teamBName: String
    )

    private data class SeriesState(
        val status: Int,
        val scoreA: Int,
        val scoreB: Int,
        val expectedBo: Int,
        val teamAId: Int,
        val teamBId: Int,
        val teamAName: String,
        val teamBName: String,
        val gameObject: JSONObject?,
        val candidateIds: List<String>
    )

    private data class Route(
        val query: String,
        val label: String
    )

    private data class TeamRealtime(
        val teamId: Int,
        val name: String,
        val side: String,
        val gold: Int,
        val kills: Int,
        val towers: Int,
        val dragons: Int,
        val barons: Int
    )

    private val _status = MutableStateFlow(
        LiveSourceStatus(LiveSourcePhase.IDLE, "LPL Comm Realtime 尚未启动")
    )
    val status: StateFlow<LiveSourceStatus> = _status.asStateFlow()

    override fun observe(matchId: String): Flow<LiveSnapshot> = flow {
        var ref: MatchRef? = null
        var cachedRoute: Route? = null
        var cachedRouteKey = ""
        var previous: LiveSnapshot? = null
        var observedTargetKey = ""

        while (currentCoroutineContext().isActive) {
            try {
                val nextTargetKey = LiveMatchTargetRegistry.key(LiveMatchTargetRegistry.snapshot())
                if (nextTargetKey != observedTargetKey) {
                    observedTargetKey = nextTargetKey
                    ref = null
                    cachedRoute = null
                    cachedRouteKey = ""
                    previous = null
                }
                if (ref == null) {
                    _status.value = LiveSourceStatus(
                        phase = LiveSourcePhase.WAITING_FOR_MATCH,
                        message = "COMM · 正在解析当前 LPL 系列赛…",
                        eventId = targetEventId()
                    )
                    ref = fetchCurrentMatch(matchId)
                    if (ref == null) {
                        delay(DISCOVERY_RETRY_MS)
                        continue
                    }
                }

                val active = ref
                val series = fetchSeriesState(active)
                val gameKey = "${active.bmid}:G${series.expectedBo}"
                if (cachedRouteKey != gameKey) {
                    cachedRoute = null
                    cachedRouteKey = gameKey
                    previous = null
                }

                if (series.status == 3) {
                    previous?.let(CompletedGameArchive::publish)
                    previous = null
                    _status.value = LiveSourceStatus(
                        phase = LiveSourcePhase.BETWEEN_GAMES,
                        message = "COMM · 系列赛已结束 · bmid=${active.bmid} · ${series.scoreA}:${series.scoreB}",
                        eventId = targetEventId(),
                        gameId = "COMM:${active.bmid}:G${(series.scoreA + series.scoreB).coerceAtLeast(1)}",
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    delay(4_000L)
                    ref = fetchCurrentMatch(matchId)
                    continue
                }

                val route = cachedRoute ?: discoverRoute(active, series).also { cachedRoute = it }
                if (route == null) {
                    _status.value = LiveSourceStatus(
                        phase = if (series.scoreA + series.scoreB > 0) LiveSourcePhase.BETWEEN_GAMES else LiveSourcePhase.WAITING_FOR_MATCH,
                        message = buildString {
                            append("COMM · bmid=${active.bmid} · 等待 G${series.expectedBo} realtime")
                            append(" · ids=")
                            append(series.candidateIds.take(5).joinToString("/").ifBlank { "none" })
                            append(" · route=undiscovered")
                        },
                        eventId = targetEventId(),
                        gameId = "COMM:${active.bmid}:G${series.expectedBo}",
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    delay(DISCOVERY_RETRY_MS)
                    continue
                }

                val topRoot = getJsonOrNull("$COMM_BASE/realtime/topData?${route.query}")
                val topData = unwrapData(topRoot)
                val teams = topData?.let(::parseTeams).orEmpty()
                if (teams.size < 2 || !teams.any { it.gold > 0 || it.kills > 0 || it.towers > 0 || it.dragons > 0 || it.barons > 0 }) {
                    // Route may have become stale at a game transition. Rediscover on next loop.
                    cachedRoute = null
                    _status.value = LiveSourceStatus(
                        phase = LiveSourcePhase.WAITING_FOR_MATCH,
                        message = "COMM · G${series.expectedBo} 路由已命中但实时数值尚未生效 · ${route.label}",
                        eventId = targetEventId(),
                        gameId = "COMM:${active.bmid}:G${series.expectedBo}",
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    delay(POLL_MS)
                    continue
                }

                val ordered = orderTeams(teams, series)
                val blue = ordered.first
                val red = ordered.second
                val playerRoot = getJsonOrNull("$COMM_BASE/realtime/playerInfo?${route.query}")
                val players = unwrapData(playerRoot)?.let(::parsePlayers).orEmpty()
                val bluePlayers = players.filter { playerTeamId(it) == blue.teamId || playerSide(it) == "BLUE" }
                val redPlayers = players.filter { playerTeamId(it) == red.teamId || playerSide(it) == "RED" }
                val elapsed = intAnyDeep(topData, "gameTime", "gameDuration", "elapsedSeconds", "time")
                    .takeIf { it in 1..10_800 }
                    ?: previous?.elapsedSeconds?.plus(2)
                    ?: 0

                val snapshot = LiveSnapshot(
                    game = series.expectedBo,
                    elapsedSeconds = elapsed,
                    blue = blue.name.ifBlank { teamNameById(blue.teamId, series).ifBlank { "BLUE" } },
                    red = red.name.ifBlank { teamNameById(red.teamId, series).ifBlank { "RED" } },
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
                    bluePlayers = bluePlayers,
                    redPlayers = redPlayers,
                    latestEvent = detectEvent(previous, blue, red),
                    source = "LPL Comm Realtime · ${route.label}",
                    gameId = "COMM:${active.bmid}:G${series.expectedBo}"
                )

                previous = snapshot
                _status.value = LiveSourceStatus(
                    phase = LiveSourcePhase.LIVE,
                    message = "COMM LIVE · G${series.expectedBo} · bmid=${active.bmid} · ${route.label} · players=${players.size}",
                    eventId = targetEventId(),
                    gameId = snapshot.gameId,
                    lastUpdateEpochMs = System.currentTimeMillis()
                )
                emit(snapshot)
                delay(POLL_MS)
            } catch (t: Throwable) {
                cachedRoute = null
                _status.value = LiveSourceStatus(
                    phase = LiveSourcePhase.ERROR,
                    message = "COMM ERROR · ${t.message?.take(170) ?: t::class.java.simpleName}" + (ref?.let { " · bmid=${it.bmid}" } ?: ""),
                    eventId = targetEventId(),
                    gameId = ref?.let { "COMM:${it.bmid}" }.orEmpty(),
                    lastUpdateEpochMs = System.currentTimeMillis()
                )
                delay(DISCOVERY_RETRY_MS)
            }
        }
    }

    private suspend fun fetchSeriesState(ref: MatchRef): SeriesState {
        val root = getJson("$MATCH_BASE/compound/matchDetail?matchId=${enc(ref.bmid)}")
        if (root.has("success") && !root.optBoolean("success", false)) {
            throw IOException("matchDetail success=false")
        }
        val data = root.optJSONObject("data") ?: throw IOException("matchDetail missing data")
        val scoreA = intAny(data, "teamAScore", "scoreA")
        val scoreB = intAny(data, "teamBScore", "scoreB")
        val expectedBo = (scoreA + scoreB + 1).coerceAtLeast(1)
        val infos = data.optJSONArray("matchInfos") ?: JSONArray()
        val game = findExpectedGame(infos, expectedBo)
        val ids = collectCandidateIds(game).distinct().filter { it.isNotBlank() }
        return SeriesState(
            status = intAny(data, "matchStatus", "status"),
            scoreA = scoreA,
            scoreB = scoreB,
            expectedBo = expectedBo,
            teamAId = intAny(data, "teamAId").takeIf { it > 0 } ?: ref.teamAId,
            teamBId = intAny(data, "teamBId").takeIf { it > 0 } ?: ref.teamBId,
            teamAName = data.optString("teamAName").ifBlank { ref.teamAName },
            teamBName = data.optString("teamBName").ifBlank { ref.teamBName },
            gameObject = game,
            candidateIds = ids
        )
    }

    private fun findExpectedGame(infos: JSONArray, expectedBo: Int): JSONObject? {
        for (i in 0 until infos.length()) {
            val obj = infos.optJSONObject(i) ?: continue
            if (parseBo(obj, i + 1) == expectedBo) return obj
        }
        return null
    }

    private fun collectCandidateIds(game: JSONObject?): List<String> {
        if (game == null) return emptyList()
        val keys = listOf(
            "gameId", "gameID", "sMatchId", "battleId", "battleID", "roomGameId",
            "matchGameId", "singleGameId", "id"
        )
        val out = mutableListOf<String>()
        for (key in keys) {
            if (!game.has(key)) continue
            val value = game.opt(key)?.toString()?.trim().orEmpty()
            if (value.isNotBlank() && value != "0" && value != "null") out += value
        }
        return out
    }

    private suspend fun discoverRoute(ref: MatchRef, series: SeriesState): Route? {
        val routes = buildList {
            for (id in series.candidateIds) {
                add(Route("gameId=${enc(id)}", "gameId"))
                add(Route("matchId=${enc(id)}", "matchId(game)"))
                add(Route("sMatchId=${enc(id)}", "sMatchId"))
                add(Route("battleId=${enc(id)}", "battleId"))
            }
            add(Route("matchId=${enc(ref.bmid)}&bo=${series.expectedBo}", "matchId+bo"))
            add(Route("bMatchId=${enc(ref.bmid)}&bo=${series.expectedBo}", "bMatchId+bo"))
            add(Route("matchId=${enc(ref.bmid)}&gameNo=${series.expectedBo}", "matchId+gameNo"))
            add(Route("bMatchId=${enc(ref.bmid)}&gameNo=${series.expectedBo}", "bMatchId+gameNo"))
        }.distinctBy { it.query }

        for (route in routes) {
            val root = getJsonOrNull("$COMM_BASE/realtime/topData?${route.query}") ?: continue
            if (root.has("success") && !root.optBoolean("success", false)) continue
            val data = unwrapData(root) ?: continue
            val teams = parseTeams(data)
            if (teams.size >= 2) return route
        }
        return null
    }

    private fun unwrapData(root: JSONObject?): JSONObject? {
        root ?: return null
        val data = root.opt("data")
        return when (data) {
            is JSONObject -> data
            is JSONArray -> JSONObject().put("items", data)
            else -> if (root.length() > 0) root else null
        }
    }

    private fun parseTeams(data: JSONObject): List<TeamRealtime> {
        val candidates = mutableListOf<JSONObject>()
        val arrays = listOf("teams", "teamInfos", "teamData", "teamList", "items", "list")
        for (key in arrays) {
            val arr = data.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(candidates::add)
        }
        val objectPairs = listOf("blueTeam" to "BLUE", "redTeam" to "RED", "teamA" to "", "teamB" to "")
        for ((key, side) in objectPairs) {
            val obj = data.optJSONObject(key) ?: continue
            if (side.isNotBlank() && obj.optString("side").isBlank()) obj.put("side", side)
            candidates += obj
        }
        if (candidates.size < 2) {
            collectTeamLikeObjects(data, candidates, 0)
        }
        return candidates.distinctBy { obj ->
            val id = intAny(obj, "teamId", "teamID", "id")
            val side = stringAny(obj, "side", "camp", "teamSide", "color")
            "$id|$side|${stringAny(obj, "teamName", "name")}"
        }.map { obj ->
            TeamRealtime(
                teamId = intAny(obj, "teamId", "teamID", "id"),
                name = stringAny(obj, "teamName", "name", "shortName"),
                side = normalizeSide(stringAny(obj, "side", "camp", "teamSide", "color")),
                gold = intAnyDeep(obj, "golds", "gold", "totalGold", "teamGold"),
                kills = intAnyDeep(obj, "kills", "kill", "totalKills"),
                towers = intAnyDeep(obj, "turretAmount", "towerAmount", "towers", "tower"),
                dragons = intAnyDeep(obj, "dragonAmount", "dragons", "dragon"),
                barons = intAnyDeep(obj, "baronAmount", "barons", "baron", "nashorAmount")
            )
        }.filter { it.teamId > 0 || it.name.isNotBlank() || it.side.isNotBlank() }
    }

    private fun collectTeamLikeObjects(obj: JSONObject, out: MutableList<JSONObject>, depth: Int) {
        if (depth > 2) return
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = obj.opt(key)) {
                is JSONObject -> {
                    val looksTeam = value.has("teamId") || value.has("teamID") ||
                        value.has("teamGold") || value.has("totalGold") || value.has("kills")
                    if (looksTeam) out += value else collectTeamLikeObjects(value, out, depth + 1)
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val child = value.optJSONObject(i) ?: continue
                        val looksTeam = child.has("teamId") || child.has("teamID") ||
                            child.has("teamGold") || child.has("totalGold") || child.has("kills")
                        if (looksTeam) out += child else collectTeamLikeObjects(child, out, depth + 1)
                    }
                }
            }
        }
    }

    private fun orderTeams(teams: List<TeamRealtime>, series: SeriesState): Pair<TeamRealtime, TeamRealtime> {
        val blue = teams.firstOrNull { it.side == "BLUE" }
        val red = teams.firstOrNull { it.side == "RED" }
        if (blue != null && red != null && blue !== red) return blue to red

        val a = teams.firstOrNull { it.teamId == series.teamAId }
        val b = teams.firstOrNull { it.teamId == series.teamBId }
        if (a != null && b != null && a !== b) return a to b

        return teams[0] to teams[1]
    }

    private fun parsePlayers(data: JSONObject): List<LivePlayerSnapshot> {
        val objects = mutableListOf<JSONObject>()
        collectPlayerLikeObjects(data, objects, 0)
        return objects.distinctBy {
            val id = intAny(it, "playerId", "participantId", "id")
            "$id|${stringAny(it, "playerName", "summonerName", "name")}"
        }.mapIndexed { index, p ->
            LivePlayerSnapshot(
                participantId = intAny(p, "playerId", "participantId", "id").takeIf { it > 0 } ?: index + 1,
                role = normalizeRole(stringAny(p, "playerLocation", "position", "role")),
                summonerName = stringAny(p, "playerName", "summonerName", "name").ifBlank { "P${index + 1}" },
                championId = stringAny(p, "heroId", "championId", "heroID"),
                level = intAnyDeep(p, "level", "heroLevel"),
                kills = intAnyDeep(p, "kills", "kill"),
                deaths = intAnyDeep(p, "deaths", "death"),
                assists = intAnyDeep(p, "assists", "assist"),
                creepScore = intAnyDeep(p, "minionKilled", "creepScore", "cs", "creepsKilled"),
                gold = intAnyDeep(p, "golds", "gold", "totalGold")
            )
        }
    }

    private fun collectPlayerLikeObjects(obj: JSONObject, out: MutableList<JSONObject>, depth: Int) {
        if (depth > 3) return
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = obj.opt(key)) {
                is JSONObject -> {
                    val looksPlayer = value.has("playerId") || value.has("playerName") ||
                        value.has("summonerName") || value.has("heroId")
                    if (looksPlayer) out += value else collectPlayerLikeObjects(value, out, depth + 1)
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val child = value.optJSONObject(i) ?: continue
                        val looksPlayer = child.has("playerId") || child.has("playerName") ||
                            child.has("summonerName") || child.has("heroId")
                        if (looksPlayer) out += child else collectPlayerLikeObjects(child, out, depth + 1)
                    }
                }
            }
        }
    }

    // Player-team metadata is not part of LivePlayerSnapshot yet. These helpers intentionally
    // return neutral values; once a player model carries team/side, the router can split exactly.
    // Until then, playerInfo is optional and team-level realtime is still authoritative.
    private fun playerTeamId(player: LivePlayerSnapshot): Int = 0
    private fun playerSide(player: LivePlayerSnapshot): String = ""

    private fun detectEvent(previous: LiveSnapshot?, blue: TeamRealtime, red: TeamRealtime): String {
        if (previous == null) return "LPL Comm Realtime 已接入当前小局"
        return when {
            blue.barons > previous.blueBarons -> "${previous.blue} 获得男爵"
            red.barons > previous.redBarons -> "${previous.red} 获得男爵"
            blue.dragons > previous.blueDragons -> "${previous.blue} 获得小龙"
            red.dragons > previous.redDragons -> "${previous.red} 获得小龙"
            blue.towers > previous.blueTowers -> "${previous.blue} 摧毁防御塔"
            red.towers > previous.redTowers -> "${previous.red} 摧毁防御塔"
            blue.kills > previous.blueKills -> "${previous.blue} 完成击杀"
            red.kills > previous.redKills -> "${previous.red} 完成击杀"
            else -> "LPL Comm Realtime 数据已同步"
        }
    }

    private suspend fun fetchCurrentMatch(requestedMatchId: String): MatchRef? {
        val text = getText(LIVE_FEED, auth = false)
        val candidates = Regex("bMatchId", RegexOption.IGNORE_CASE).findAll(text).mapNotNull { hit ->
            val start = (hit.range.first - 2400).coerceAtLeast(0)
            val end = (hit.range.first + 3600).coerceAtMost(text.length)
            parseRef(text.substring(start, end))
        }.distinctBy { it.bmid }.toList()
        if (candidates.isEmpty()) return null

        requestedMatchId.trim().takeIf { it.isNotBlank() }?.let { requested ->
            candidates.firstOrNull { it.bmid == requested }?.let { return it }
        }
        val target = LiveMatchTargetRegistry.snapshot()
        if (target != null && target.teams.size >= 2) {
            val ranked = candidates.map { it to matchScore(it, target) }.sortedByDescending { it.second }
            val best = ranked.firstOrNull()
            if (best != null && best.second > 0) return best.first
        }
        return candidates.singleOrNull()
    }

    private fun matchScore(ref: MatchRef, target: ScheduledEsportsMatch): Int {
        val left = target.teams.getOrNull(0) ?: return 0
        val right = target.teams.getOrNull(1) ?: return 0
        val direct = teamMatches(ref.teamAName, left) && teamMatches(ref.teamBName, right)
        val swapped = teamMatches(ref.teamAName, right) && teamMatches(ref.teamBName, left)
        return when {
            direct -> 100
            swapped -> 95
            teamMatches(ref.teamAName, left) || teamMatches(ref.teamBName, right) -> 30
            teamMatches(ref.teamAName, right) || teamMatches(ref.teamBName, left) -> 25
            else -> 0
        }
    }

    private fun teamMatches(upstreamName: String, team: EsportsTeamRef): Boolean {
        val upstream = teamKey(upstreamName)
        if (upstream.isBlank()) return false
        val candidates = listOf(team.code, team.name, team.slug).map(::teamKey).filter { it.isNotBlank() }
        return candidates.any { token ->
            upstream == token || (token.length >= 4 && upstream.contains(token)) ||
                (upstream.length >= 4 && token.contains(upstream))
        }
    }

    private fun parseRef(chunk: String): MatchRef? {
        val bmid = field(chunk, "bMatchId")
        if (bmid.isBlank() || !bmid.all(Char::isDigit)) return null
        val a = field(chunk, "TeamA").toIntOrNull() ?: return null
        val b = field(chunk, "TeamB").toIntOrNull() ?: return null
        return MatchRef(bmid, a, b, field(chunk, "TeamNameA"), field(chunk, "TeamNameB"))
    }

    private fun parseBo(info: JSONObject, fallback: Int): Int {
        for (key in listOf("bo", "gameNo", "gameNum", "gameNumber", "gameIndex", "round")) {
            when (val raw = info.opt(key)) {
                is Number -> if (raw.toInt() > 0) return raw.toInt()
                is String -> Regex("\\d+").find(raw)?.value?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
            }
        }
        return fallback
    }

    private fun teamNameById(id: Int, series: SeriesState): String = when (id) {
        series.teamAId -> series.teamAName
        series.teamBId -> series.teamBName
        else -> ""
    }

    private fun normalizeSide(raw: String): String = when (raw.trim().lowercase()) {
        "blue", "b", "100", "1", "teama", "a" -> "BLUE"
        "red", "r", "200", "2", "teamb", "b2" -> "RED"
        else -> ""
    }

    private fun normalizeRole(raw: String): String = when (raw.trim().lowercase()) {
        "top", "1" -> "TOP"
        "jungle", "jug", "2" -> "JUG"
        "mid", "middle", "3" -> "MID"
        "bottom", "bot", "adc", "4" -> "BOT"
        "support", "sup", "5" -> "SUP"
        else -> raw.uppercase().ifBlank { "—" }
    }

    private fun field(chunk: String, key: String): String =
        Regex("[\\\"']?${Regex.escape(key)}[\\\"']?\\s*:\\s*[\\\"']?([^,\\\"'}\\]\\s]+)", RegexOption.IGNORE_CASE)
            .find(chunk)?.groupValues?.getOrNull(1).orEmpty().trim()

    private fun teamKey(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

    private fun intAny(obj: JSONObject, vararg keys: String): Int {
        for (key in keys) {
            if (!obj.has(key)) continue
            when (val raw = obj.opt(key)) {
                is Number -> return raw.toInt()
                is String -> raw.toDoubleOrNull()?.toInt()?.let { return it }
            }
        }
        return 0
    }

    private fun intAnyDeep(obj: JSONObject?, vararg keys: String): Int {
        obj ?: return 0
        val direct = intAny(obj, *keys)
        if (direct != 0) return direct
        val iterator = obj.keys()
        while (iterator.hasNext()) {
            when (val child = obj.opt(iterator.next())) {
                is JSONObject -> {
                    val v = intAny(child, *keys)
                    if (v != 0) return v
                }
            }
        }
        return 0
    }

    private fun stringAny(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.optString(key).trim()
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun targetEventId(): String = LiveMatchTargetRegistry.snapshot()?.eventId.orEmpty()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private suspend fun getJson(url: String): JSONObject = JSONObject(getText(url, auth = true))

    private suspend fun getJsonOrNull(url: String): JSONObject? = runCatching { getJson(url) }.getOrNull()

    private suspend fun getText(url: String, auth: Boolean): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 6_000
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
