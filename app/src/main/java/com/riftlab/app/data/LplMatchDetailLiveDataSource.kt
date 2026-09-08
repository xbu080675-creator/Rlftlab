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
 * LPL live provider that treats TJStats compound/matchDetail as the first data plane.
 *
 * The public match detail schema already carries per-game teamInfos/playerInfos. This provider
 * deliberately consumes that payload before touching the stricter realtime endpoints. It gives
 * us a zero-cost path when Tencent updates matchDetail during a live game, while exposing precise
 * diagnostics when the live-only fields are withheld.
 */
internal class LplMatchDetailLiveDataSource : LiveMatchDataSource {

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
        LiveSourceStatus(LiveSourcePhase.IDLE, "LPL MatchDetail 实时源尚未启动")
    )
    val status: StateFlow<LiveSourceStatus> = _status.asStateFlow()

    override fun observe(matchId: String): Flow<LiveSnapshot> = flow {
        var ref: MatchRef? = null
        var previous: LiveSnapshot? = null
        var lastBo = 0

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
                val teamAId = data.optInt("teamAId", active.teamAId)
                val teamBId = data.optInt("teamBId", active.teamBId)
                val teamAName = data.optString("teamAName").ifBlank { active.teamAName.ifBlank { "TEAM A" } }
                val teamBName = data.optString("teamBName").ifBlank { active.teamBName.ifBlank { "TEAM B" } }
                val infos = data.optJSONArray("matchInfos") ?: JSONArray()
                val games = parseGames(infos)

                val explicitLive = games.firstOrNull { it.status == 2 }
                val expectedBo = (scoreA + scoreB + 1).coerceAtLeast(1)
                val current = explicitLive
                    ?: games.firstOrNull { it.bo == expectedBo }
                    ?: ParsedGame(expectedBo, 0, 0, 0, emptyList())
                val bo = current.bo.coerceAtLeast(1)

                if (bo != lastBo) {
                    previous = null
                    lastBo = bo
                }

                if (seriesStatus == 3) {
                    _status.value = LiveSourceStatus(
                        phase = LiveSourcePhase.BETWEEN_GAMES,
                        message = "LPL 官方 · 系列赛已结束 · bmid=${active.bmid} · $scoreA:$scoreB",
                        gameId = "TJ:${active.bmid}:G$bo",
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    delay(4_000L)
                    ref = fetchCurrentMatch()
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
                val meaningful = blue != null && red != null && isMeaningful(blue, red)

                if (!meaningful) {
                    val phase = if (scoreA + scoreB > 0) LiveSourcePhase.BETWEEN_GAMES else LiveSourcePhase.WAITING_FOR_MATCH
                    _status.value = LiveSourceStatus(
                        phase = phase,
                        message = "LPL matchDetail · bmid=${active.bmid} · G$bo · seriesStatus=$seriesStatus · gameStatus=${current.status} · gameTime=${current.gameTime} · teamInfos=${current.teams.size} · score=$scoreA:$scoreB",
                        gameId = "TJ:${active.bmid}:G$bo",
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    delay(POLL_MS)
                    continue
                }

                val blueState = blue!!
                val redState = red!!
                val blueName = if (blueId == teamAId) teamAName else if (blueId == teamBId) teamBName else "BLUE"
                val redName = if (redId == teamAId) teamAName else if (redId == teamBId) teamBName else "RED"
                val elapsed = current.gameTime.takeIf { it in 1..10_800 }
                    ?: previous?.takeIf { it.game == bo }?.elapsedSeconds?.plus(2)
                    ?: 0

                val snapshot = LiveSnapshot(
                    game = bo,
                    elapsedSeconds = elapsed,
                    blue = blueName,
                    red = redName,
                    blueGold = blueState.gold,
                    redGold = redState.gold,
                    blueKills = blueState.kills,
                    redKills = redState.kills,
                    blueTowers = blueState.towers,
                    redTowers = redState.towers,
                    blueDragons = blueState.dragons,
                    redDragons = redState.dragons,
                    blueBarons = blueState.barons,
                    redBarons = redState.barons,
                    bluePlayers = blueState.players,
                    redPlayers = redState.players,
                    latestEvent = detectEvent(previous, bo, blueName, redName, blueState, redState),
                    source = "LPL Official · TJStats matchDetail",
                    gameId = "TJ:${active.bmid}:G$bo"
                )

                previous = snapshot
                _status.value = LiveSourceStatus(
                    phase = LiveSourcePhase.LIVE,
                    message = "LPL Official · matchDetail LIVE · bmid=${active.bmid} · G$bo · teamInfos=${current.teams.size} · players=${blueState.players.size + redState.players.size}",
                    gameId = snapshot.gameId,
                    lastUpdateEpochMs = System.currentTimeMillis()
                )
                emit(snapshot)
                delay(POLL_MS)
            } catch (t: Throwable) {
                _status.value = LiveSourceStatus(
                    phase = LiveSourcePhase.ERROR,
                    message = "LPL matchDetail ERROR · ${t.message?.take(170) ?: t::class.java.simpleName}" + (ref?.let { " · bmid=${it.bmid}" } ?: ""),
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
                    bo = info.optInt("bo", i + 1),
                    status = info.optInt("matchStatus", 0),
                    gameTime = info.optInt("gameTime", 0),
                    blueTeamId = info.optInt("blueTeam", 0),
                    teams = teams
                )
            )
        }
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

    private fun isMeaningful(a: TeamState, b: TeamState): Boolean =
        a.gold > 0 || b.gold > 0 || a.kills > 0 || b.kills > 0 ||
            a.towers > 0 || b.towers > 0 || a.dragons > 0 || b.dragons > 0 ||
            a.barons > 0 || b.barons > 0 || a.players.any { it.gold > 0 || it.level > 0 } ||
            b.players.any { it.gold > 0 || it.level > 0 }

    private fun detectEvent(
        previous: LiveSnapshot?,
        bo: Int,
        blue: String,
        red: String,
        blueNow: TeamState,
        redNow: TeamState
    ): String {
        if (previous == null || previous.game != bo) return "LPL Official · matchDetail · G$bo 实时数据已接入"
        return when {
            blueNow.barons > previous.blueBarons -> "$blue 获得男爵"
            redNow.barons > previous.redBarons -> "$red 获得男爵"
            blueNow.dragons > previous.blueDragons -> "$blue 获得小龙"
            redNow.dragons > previous.redDragons -> "$red 获得小龙"
            blueNow.towers > previous.blueTowers -> "$blue 摧毁防御塔"
            redNow.towers > previous.redTowers -> "$red 摧毁防御塔"
            blueNow.kills > previous.blueKills -> "$blue 完成击杀"
            redNow.kills > previous.redKills -> "$red 完成击杀"
            else -> "LPL Official · matchDetail · 实时数据已同步"
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
