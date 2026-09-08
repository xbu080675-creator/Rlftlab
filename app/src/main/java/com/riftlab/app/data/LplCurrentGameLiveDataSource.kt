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
 * Current-game-only LPL provider.
 *
 * Invariant:
 * - LIVE may expose only the game implied by the current series score (scoreA + scoreB + 1).
 * - Completed games are archived and are never reused as the live surface.
 * - A stale status flag on G1/G2 cannot pull an old final snapshot back into the live UI.
 */
internal class LplCurrentGameLiveDataSource : LiveMatchDataSource {

    companion object {
        private const val LPL_BASE = "https://lpl.qq.com"
        private const val LIVE_FEED = "$LPL_BASE/web201612/data/LOL_MATCH2_LIVE_BMATCH_LIST.js"
        private const val TJ_BASE = "https://open.tjstats.com/match-auth-app/open/v1"
        private const val TJ_AUTH = "7935be4c41d8760a28c05581a7b1f570"
        private const val POLL_MS = 2_000L
    }

    private data class MatchRef(
        val bmid: String,
        val teamAId: Int,
        val teamBId: Int,
        val teamAName: String,
        val teamBName: String
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

    private val _status = MutableStateFlow(
        LiveSourceStatus(LiveSourcePhase.IDLE, "LPL 当前小局实时源尚未启动")
    )
    val status: StateFlow<LiveSourceStatus> = _status.asStateFlow()

    override fun observe(matchId: String): Flow<LiveSnapshot> = flow {
        var ref: MatchRef? = null
        var previous: LiveSnapshot? = null
        var lastExpectedBo = 0

        while (currentCoroutineContext().isActive) {
            try {
                if (ref == null) {
                    _status.value = LiveSourceStatus(
                        phase = LiveSourcePhase.WAITING_FOR_MATCH,
                        message = "正在读取 LPL 官方 LIVE feed…"
                    )
                    ref = fetchCurrentMatch()
                    if (ref == null) {
                        delay(3_000L)
                        continue
                    }
                }

                val active = ref
                val root = getJson("$TJ_BASE/compound/matchDetail?matchId=${enc(active.bmid)}")
                if (root.has("success") && !root.optBoolean("success", false)) {
                    throw IOException("matchDetail success=false")
                }
                val data = root.optJSONObject("data") ?: throw IOException("matchDetail missing data")
                val seriesStatus = data.optInt("matchStatus", 0)
                val scoreA = data.optInt("teamAScore", 0)
                val scoreB = data.optInt("teamBScore", 0)
                val expectedBo = (scoreA + scoreB + 1).coerceAtLeast(1)
                val teamAId = data.optInt("teamAId", active.teamAId)
                val teamBId = data.optInt("teamBId", active.teamBId)
                val teamAName = data.optString("teamAName").ifBlank { active.teamAName.ifBlank { "TEAM A" } }
                val teamBName = data.optString("teamBName").ifBlank { active.teamBName.ifBlank { "TEAM B" } }
                val games = parseGames(data.optJSONArray("matchInfos") ?: JSONArray())

                if (expectedBo != lastExpectedBo) {
                    if (previous != null && previous.game < expectedBo) {
                        CompletedGameArchive.publish(previous)
                    }
                    previous = null
                    lastExpectedBo = expectedBo
                }

                if (seriesStatus == 3) {
                    previous?.let(CompletedGameArchive::publish)
                    previous = null
                    _status.value = LiveSourceStatus(
                        phase = LiveSourcePhase.BETWEEN_GAMES,
                        message = "LPL 官方 · 系列赛已结束 · bmid=${active.bmid} · $scoreA:$scoreB",
                        gameId = "TJ:${active.bmid}:G${(scoreA + scoreB).coerceAtLeast(1)}",
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    delay(4_000L)
                    ref = fetchCurrentMatch()
                    continue
                }

                // Critical rule: never pick an arbitrary status=2 record. Only G(expectedBo) may
                // become live. Old G1/G2 records are history even if Tencent leaves stale flags.
                val expectedGame = games.firstOrNull { it.bo == expectedBo }
                val current = expectedGame?.takeIf {
                    it.status != 3 && (it.status == 2 || it.gameTime > 0 || isMeaningful(it.teams))
                }

                if (current == null) {
                    val phase = if (scoreA + scoreB > 0) LiveSourcePhase.BETWEEN_GAMES else LiveSourcePhase.WAITING_FOR_MATCH
                    val diagnostic = games.joinToString(",") {
                        "G${it.bo}:s${it.status}:t${it.gameTime}:teams${it.teams.size}"
                    }
                    _status.value = LiveSourceStatus(
                        phase = phase,
                        message = "LPL 当前局 · bmid=${active.bmid} · 只等待 G$expectedBo · score=$scoreA:$scoreB · games=[$diagnostic]",
                        gameId = "TJ:${active.bmid}:G$expectedBo",
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    delay(POLL_MS)
                    continue
                }

                val blueId = current.blueTeamId.takeIf { it > 0 } ?: teamAId
                val redId = when (blueId) {
                    teamAId -> teamBId
                    teamBId -> teamAId
                    else -> teamBId
                }
                val byId = current.teams.associateBy { it.teamId }
                val blue = byId[blueId]
                val red = byId[redId] ?: current.teams.firstOrNull { it.teamId != blue?.teamId }

                if (blue == null || red == null || !isMeaningful(listOf(blue, red))) {
                    _status.value = LiveSourceStatus(
                        phase = LiveSourcePhase.WAITING_FOR_MATCH,
                        message = "LPL 当前局 · G$expectedBo 已出现但数值尚未生效 · status=${current.status} · time=${current.gameTime} · teams=${current.teams.size}",
                        gameId = "TJ:${active.bmid}:G$expectedBo",
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    delay(POLL_MS)
                    continue
                }

                val blueName = when (blueId) {
                    teamAId -> teamAName
                    teamBId -> teamBName
                    else -> "BLUE"
                }
                val redName = when (redId) {
                    teamAId -> teamAName
                    teamBId -> teamBName
                    else -> "RED"
                }
                val elapsed = current.gameTime.takeIf { it in 1..10_800 }
                    ?: previous?.takeIf { it.game == expectedBo }?.elapsedSeconds?.plus(2)
                    ?: 0

                val snapshot = LiveSnapshot(
                    game = expectedBo,
                    elapsedSeconds = elapsed,
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
                    latestEvent = detectEvent(previous, expectedBo, blueName, redName, blue, red),
                    source = "LPL Official · TJStats current-game",
                    gameId = "TJ:${active.bmid}:G$expectedBo"
                )

                previous = snapshot
                _status.value = LiveSourceStatus(
                    phase = LiveSourcePhase.LIVE,
                    message = "LPL Official · CURRENT G$expectedBo LIVE · bmid=${active.bmid} · status=${current.status} · time=${current.gameTime} · players=${blue.players.size + red.players.size}",
                    gameId = snapshot.gameId,
                    lastUpdateEpochMs = System.currentTimeMillis()
                )
                emit(snapshot)
                delay(POLL_MS)
            } catch (t: Throwable) {
                _status.value = LiveSourceStatus(
                    phase = LiveSourcePhase.ERROR,
                    message = "LPL 当前局 ERROR · ${t.message?.take(170) ?: t::class.java.simpleName}" + (ref?.let { " · bmid=${it.bmid}" } ?: ""),
                    gameId = ref?.let { "TJ:${it.bmid}" }.orEmpty(),
                    lastUpdateEpochMs = System.currentTimeMillis()
                )
                delay(3_000L)
            }
        }
    }

    private fun parseGames(infos: JSONArray): List<ParsedGame> = buildList {
        for (i in 0 until infos.length()) {
            val info = infos.optJSONObject(i) ?: continue
            val teamInfos = info.optJSONArray("teamInfos") ?: JSONArray()
            val teams = buildList {
                for (j in 0 until teamInfos.length()) {
                    val team = teamInfos.optJSONObject(j) ?: continue
                    val id = intAny(team, "teamId", "teamID", "id")
                    if (id <= 0) continue
                    add(
                        TeamState(
                            teamId = id,
                            gold = intAny(team, "golds", "gold", "totalGold", "teamGold"),
                            kills = intAny(team, "kills", "kill", "totalKills"),
                            towers = intAny(team, "turretAmount", "towers", "tower"),
                            dragons = intAny(team, "dragonAmount", "dragons", "dragon"),
                            barons = intAny(team, "baronAmount", "barons", "baron"),
                            players = parsePlayers(team.optJSONArray("playerInfos") ?: JSONArray())
                        )
                    )
                }
            }
            add(
                ParsedGame(
                    bo = parseBo(info, i + 1),
                    status = intAny(info, "matchStatus", "status", "gameStatus"),
                    gameTime = intAny(info, "gameTime", "time", "elapsedSeconds"),
                    blueTeamId = intAny(info, "blueTeam", "blueTeamId", "blueId"),
                    teams = teams
                )
            )
        }
    }

    private fun parseBo(info: JSONObject, fallback: Int): Int {
        val keys = listOf("bo", "gameNo", "gameNum", "gameNumber", "gameIndex", "round")
        for (key in keys) {
            if (!info.has(key)) continue
            when (val raw = info.opt(key)) {
                is Number -> if (raw.toInt() > 0) return raw.toInt()
                is String -> Regex("\\d+").find(raw)?.value?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
            }
        }
        return fallback
    }

    private fun parsePlayers(array: JSONArray): List<LivePlayerSnapshot> = buildList {
        for (i in 0 until array.length()) {
            val p = array.optJSONObject(i) ?: continue
            val battle = p.optJSONObject("battleDetail") ?: JSONObject()
            val other = p.optJSONObject("otherDetail") ?: JSONObject()
            add(
                LivePlayerSnapshot(
                    participantId = p.optInt("playerId", i + 1),
                    role = normalizeRole(p.optString("playerLocation")),
                    summonerName = p.optString("playerName").ifBlank { "P${i + 1}" },
                    championId = p.opt("heroId")?.toString().orEmpty(),
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

    private fun normalizeRole(raw: String): String = when (raw.lowercase()) {
        "top", "1" -> "TOP"
        "jungle", "jug", "2" -> "JUG"
        "mid", "middle", "3" -> "MID"
        "bottom", "bot", "adc", "4" -> "BOT"
        "support", "sup", "5" -> "SUP"
        else -> raw.uppercase().ifBlank { "—" }
    }

    private fun isMeaningful(teams: List<TeamState>): Boolean = teams.any { team ->
        team.gold > 0 || team.kills > 0 || team.towers > 0 || team.dragons > 0 || team.barons > 0 ||
            team.players.any { it.gold > 0 || it.level > 0 || it.creepScore > 0 }
    }

    private fun detectEvent(
        previous: LiveSnapshot?,
        bo: Int,
        blue: String,
        red: String,
        blueNow: TeamState,
        redNow: TeamState
    ): String {
        if (previous == null || previous.game != bo) return "LPL Official · G$bo 当前小局实时数据已接入"
        return when {
            blueNow.barons > previous.blueBarons -> "$blue 获得男爵"
            redNow.barons > previous.redBarons -> "$red 获得男爵"
            blueNow.dragons > previous.blueDragons -> "$blue 获得小龙"
            redNow.dragons > previous.redDragons -> "$red 获得小龙"
            blueNow.towers > previous.blueTowers -> "$blue 摧毁防御塔"
            redNow.towers > previous.redTowers -> "$red 摧毁防御塔"
            blueNow.kills > previous.blueKills -> "$blue 完成击杀"
            redNow.kills > previous.redKills -> "$red 完成击杀"
            else -> "LPL Official · G$bo 当前小局数据已同步"
        }
    }

    private suspend fun fetchCurrentMatch(): MatchRef? {
        val text = getText(LIVE_FEED, auth = false)
        val candidates = Regex("bMatchId", RegexOption.IGNORE_CASE).findAll(text).mapNotNull { hit ->
            val start = (hit.range.first - 2400).coerceAtLeast(0)
            val end = (hit.range.first + 3600).coerceAtMost(text.length)
            parseRef(text.substring(start, end))
        }.distinctBy { it.bmid }.toList()
        return candidates.firstOrNull { it.teamAName.isNotBlank() && it.teamBName.isNotBlank() }
            ?: candidates.firstOrNull()
    }

    private fun parseRef(chunk: String): MatchRef? {
        val bmid = field(chunk, "bMatchId")
        if (bmid.isBlank() || !bmid.all(Char::isDigit)) return null
        val a = field(chunk, "TeamA").toIntOrNull() ?: return null
        val b = field(chunk, "TeamB").toIntOrNull() ?: return null
        return MatchRef(
            bmid = bmid,
            teamAId = a,
            teamBId = b,
            teamAName = field(chunk, "TeamNameA"),
            teamBName = field(chunk, "TeamNameB")
        )
    }

    private fun field(chunk: String, key: String): String =
        Regex("[\\\"']?${Regex.escape(key)}[\\\"']?\\s*:\\s*[\\\"']?([^,\\\"'}\\]\\s]+)", RegexOption.IGNORE_CASE)
            .find(chunk)?.groupValues?.getOrNull(1).orEmpty().trim()

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

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private suspend fun getJson(url: String): JSONObject = JSONObject(getText(url, auth = true))

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
