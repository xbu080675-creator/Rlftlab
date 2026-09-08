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
 * Free LPL-first live provider using the same public sources loaded by lpl.qq.com.
 *
 * Control plane:
 *   lpl.qq.com static LIVE_BMATCH feed -> current Tencent bMatchId
 *   TJStats compound/matchDetail       -> series score/current BO/team ids
 * Data plane:
 *   TJStats realtime/team              -> live team totals
 *
 * The public Authorization value is exposed by the official lpl.qq.com frontend. It is not a
 * client secret; keep it centralized because Tencent may rotate it. CI outside China can be
 * blocked by TJStats, therefore runtime diagnostics are intentionally explicit for phone tests.
 */
internal class LplOfficialLiveDataSource : LiveMatchDataSource {

    companion object {
        private const val LPL_BASE = "https://lpl.qq.com"
        private const val LIVE_FEED = "$LPL_BASE/web201612/data/LOL_MATCH2_LIVE_BMATCH_LIST.js"
        private const val TJ_BASE = "https://open.tjstats.com/match-auth-app/open/v1"
        private const val TJ_AUTH = "7935be4c41d8760a28c05581a7b1f570"
        private const val POLL_MS = 2_000L
    }

    private data class LplMatchRef(
        val bMatchId: String,
        val gameId: String,
        val teamAId: Int,
        val teamBId: Int,
        val teamAName: String,
        val teamBName: String,
        val scoreA: Int,
        val scoreB: Int,
        val matchDate: String
    )

    private data class MatchDetail(
        val matchId: String,
        val matchStatus: Int,
        val teamAId: Int,
        val teamBId: Int,
        val teamAName: String,
        val teamBName: String,
        val scoreA: Int,
        val scoreB: Int,
        val currentBo: Int,
        val blueTeamId: Int,
        val gameTime: Int
    )

    private data class TeamLive(
        val teamId: Int,
        val gold: Int,
        val kills: Int,
        val towers: Int,
        val dragons: Int,
        val barons: Int
    )

    private data class ProbeResult(
        val teams: List<TeamLive>,
        val endpoint: String,
        val diagnostic: String
    )

    private val _status = MutableStateFlow(
        LiveSourceStatus(
            phase = LiveSourcePhase.IDLE,
            message = "LPL 官方实时源尚未启动"
        )
    )
    val status: StateFlow<LiveSourceStatus> = _status.asStateFlow()

    override fun observe(matchId: String): Flow<LiveSnapshot> = flow {
        var active: LplMatchRef? = null
        var previous: LiveSnapshot? = null
        var lastBo = 0

        while (currentCoroutineContext().isActive) {
            try {
                if (active == null) {
                    _status.value = LiveSourceStatus(
                        phase = LiveSourcePhase.WAITING_FOR_MATCH,
                        message = "正在读取 LPL 官方直播赛事索引…"
                    )
                    active = fetchCurrentLplMatch()
                    if (active == null) {
                        delay(4_000)
                        continue
                    }
                }

                val ref = active
                val detail = fetchMatchDetail(ref.bMatchId)
                val bo = detail.currentBo.coerceAtLeast(1)
                if (bo != lastBo) {
                    previous = null
                    lastBo = bo
                }

                if (detail.matchStatus == 3) {
                    _status.value = LiveSourceStatus(
                        phase = LiveSourcePhase.BETWEEN_GAMES,
                        message = "LPL 官方源 · 系列赛状态已结束，等待赛程刷新 · bmid=${ref.bMatchId}",
                        gameId = "TJ:${ref.bMatchId}:G$bo",
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    delay(5_000)
                    active = fetchCurrentLplMatch()
                    continue
                }

                val probe = probeRealtimeTeam(ref, detail)
                if (probe.teams.size < 2) {
                    val playedGames = detail.scoreA + detail.scoreB
                    val phase = if (playedGames > 0) LiveSourcePhase.BETWEEN_GAMES else LiveSourcePhase.WAITING_FOR_MATCH
                    _status.value = LiveSourceStatus(
                        phase = phase,
                        message = "LPL TJStats · bmid=${ref.bMatchId} · G$bo · ${probe.diagnostic}",
                        gameId = "TJ:${ref.bMatchId}:G$bo",
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    delay(POLL_MS)
                    continue
                }

                val byId = probe.teams.associateBy { it.teamId }
                val blueId = detail.blueTeamId.takeIf { it > 0 } ?: detail.teamAId
                val redId = when (blueId) {
                    detail.teamAId -> detail.teamBId
                    detail.teamBId -> detail.teamAId
                    else -> detail.teamBId
                }
                val blueTeam = byId[blueId]
                val redTeam = byId[redId]
                    ?: probe.teams.firstOrNull { it.teamId != blueTeam?.teamId }

                if (blueTeam == null || redTeam == null || !isMeaningful(blueTeam, redTeam)) {
                    _status.value = LiveSourceStatus(
                        phase = if (detail.scoreA + detail.scoreB > 0) LiveSourcePhase.BETWEEN_GAMES else LiveSourcePhase.WAITING_FOR_MATCH,
                        message = "LPL TJStats · G$bo 已响应但尚无有效实时数值 · ${probe.diagnostic}",
                        gameId = "TJ:${ref.bMatchId}:G$bo",
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    delay(POLL_MS)
                    continue
                }

                val blueName = teamName(blueId, detail)
                val redName = teamName(redId, detail)
                val elapsed = detail.gameTime.takeIf { it in 1..10_800 }
                    ?: previous?.takeIf { it.game == bo }?.elapsedSeconds?.plus(2)
                    ?: 0

                val snapshot = LiveSnapshot(
                    game = bo,
                    elapsedSeconds = elapsed,
                    blue = blueName,
                    red = redName,
                    blueGold = blueTeam.gold,
                    redGold = redTeam.gold,
                    blueKills = blueTeam.kills,
                    redKills = redTeam.kills,
                    blueTowers = blueTeam.towers,
                    redTowers = redTeam.towers,
                    blueDragons = blueTeam.dragons,
                    redDragons = redTeam.dragons,
                    blueBarons = blueTeam.barons,
                    redBarons = redTeam.barons,
                    latestEvent = detectEvent(previous, bo, blueName, redName, blueTeam, redTeam),
                    source = "LPL Official · TJStats",
                    gameId = "TJ:${ref.bMatchId}:G$bo"
                )

                previous = snapshot
                _status.value = LiveSourceStatus(
                    phase = LiveSourcePhase.LIVE,
                    message = "LPL Official · TJStats · G$bo · ${probe.endpoint}",
                    gameId = snapshot.gameId,
                    lastUpdateEpochMs = System.currentTimeMillis()
                )
                emit(snapshot)
                delay(POLL_MS)
            } catch (t: Throwable) {
                val ref = active
                _status.value = LiveSourceStatus(
                    phase = LiveSourcePhase.ERROR,
                    message = "LPL 官方实时源 ERROR · ${t.message?.take(170) ?: t::class.java.simpleName}" +
                        (ref?.let { " · bmid=${it.bMatchId}" } ?: ""),
                    gameId = ref?.let { "TJ:${it.bMatchId}" }.orEmpty(),
                    lastUpdateEpochMs = System.currentTimeMillis()
                )
                delay(3_000)
                // Keep the resolved match on transient TJStats failures; only refresh the official
                // LPL feed periodically instead of bouncing to a different scheduled series.
                if (ref == null) active = null
            }
        }
    }

    private suspend fun fetchCurrentLplMatch(): LplMatchRef? {
        val text = getText(LIVE_FEED, authorization = false)
        val matches = Regex("bMatchId", RegexOption.IGNORE_CASE).findAll(text).mapNotNull { hit ->
            val start = (hit.range.first - 2400).coerceAtLeast(0)
            val end = (hit.range.first + 3600).coerceAtMost(text.length)
            parseMatchChunk(text.substring(start, end))
        }.distinctBy { it.bMatchId }.toList()

        // The LPL main broadcast has one active series in normal operation. Prefer a dated entry
        // with recognizable team names, otherwise keep the first valid live-feed record.
        return matches.firstOrNull { it.teamAName.isNotBlank() && it.teamBName.isNotBlank() }
            ?: matches.firstOrNull()
    }

    private fun parseMatchChunk(chunk: String): LplMatchRef? {
        val bmid = field(chunk, "bMatchId")
        if (bmid.isBlank() || !bmid.all(Char::isDigit)) return null
        val teamA = field(chunk, "TeamA").toIntOrNull() ?: return null
        val teamB = field(chunk, "TeamB").toIntOrNull() ?: return null
        return LplMatchRef(
            bMatchId = bmid,
            gameId = field(chunk, "GameId"),
            teamAId = teamA,
            teamBId = teamB,
            teamAName = field(chunk, "TeamNameA"),
            teamBName = field(chunk, "TeamNameB"),
            scoreA = field(chunk, "ScoreA").toIntOrNull() ?: 0,
            scoreB = field(chunk, "ScoreB").toIntOrNull() ?: 0,
            matchDate = field(chunk, "MatchDate")
        )
    }

    private suspend fun fetchMatchDetail(bMatchId: String): MatchDetail {
        val root = getJson("$TJ_BASE/compound/matchDetail?matchId=${enc(bMatchId)}")
        if (root.has("success") && !root.optBoolean("success", false)) {
            throw IOException("TJStats matchDetail success=false: ${root.toString().take(120)}")
        }
        val data = root.optJSONObject("data") ?: throw IOException("TJStats matchDetail missing data")
        val infos = data.optJSONArray("matchInfos") ?: JSONArray()

        var currentBo = 0
        var blueTeam = 0
        var gameTime = 0
        var maxFinishedBo = 0
        for (i in 0 until infos.length()) {
            val info = infos.optJSONObject(i) ?: continue
            val bo = info.optInt("bo", i + 1)
            when (info.optInt("matchStatus", 0)) {
                2 -> {
                    currentBo = bo
                    blueTeam = info.optInt("blueTeam", 0)
                    gameTime = info.optInt("gameTime", 0)
                }
                3 -> maxFinishedBo = maxOf(maxFinishedBo, bo)
            }
        }
        if (currentBo == 0) {
            currentBo = maxOf(maxFinishedBo + 1, data.optInt("teamAScore", 0) + data.optInt("teamBScore", 0) + 1)
            val candidate = (0 until infos.length())
                .mapNotNull { infos.optJSONObject(it) }
                .firstOrNull { it.optInt("bo", 0) == currentBo }
            blueTeam = candidate?.optInt("blueTeam", 0) ?: 0
            gameTime = candidate?.optInt("gameTime", 0) ?: 0
        }

        return MatchDetail(
            matchId = data.optString("matchId", bMatchId),
            matchStatus = data.optInt("matchStatus", 0),
            teamAId = data.optInt("teamAId", 0),
            teamBId = data.optInt("teamBId", 0),
            teamAName = data.optString("teamAName"),
            teamBName = data.optString("teamBName"),
            scoreA = data.optInt("teamAScore", 0),
            scoreB = data.optInt("teamBScore", 0),
            currentBo = currentBo.coerceAtLeast(1),
            blueTeamId = blueTeam,
            gameTime = gameTime
        )
    }

    /**
     * Tencent exposes Swagger but the public frontend token only has access to a subset of routes,
     * and query contracts have changed over time. Probe a very small set of documented parameter
     * shapes on-device, then remember nothing server-side. The first response containing two team
     * objects wins. Diagnostics intentionally include only response shape/body prefix, never auth.
     */
    private suspend fun probeRealtimeTeam(ref: LplMatchRef, detail: MatchDetail): ProbeResult {
        val bo = detail.currentBo
        val parameterSets = listOf(
            "matchId=${enc(ref.bMatchId)}&bo=$bo",
            "matchId=${enc(ref.bMatchId)}&gameId=${enc(ref.gameId)}&bo=$bo",
            "bMatchId=${enc(ref.bMatchId)}&bo=$bo",
            "gameId=${enc(ref.gameId)}&bo=$bo"
        )
        val routes = listOf("/realtime/team", "/realtime/teamGold", "/pro/realtime/game")
        var last = "no response"

        for (route in routes) {
            for (params in parameterSets) {
                try {
                    val root = getJson("$TJ_BASE$route?$params")
                    val teams = collectTeamLive(root)
                    val success = !root.has("success") || root.optBoolean("success", false)
                    val dataType = when (val d = root.opt("data")) {
                        is JSONArray -> "array:${d.length()}"
                        is JSONObject -> "object:${d.keys().asSequence().take(8).joinToString(",")}"
                        null -> "none"
                        else -> d::class.java.simpleName
                    }
                    last = "$route?$params success=$success data=$dataType"
                    if (teams.size >= 2) return ProbeResult(teams, route, last)
                } catch (t: Throwable) {
                    last = "$route?$params -> ${t.message?.take(85) ?: t::class.java.simpleName}"
                }
            }
        }
        return ProbeResult(emptyList(), "TJ probe", last)
    }

    private fun collectTeamLive(root: Any?): List<TeamLive> {
        val objects = mutableListOf<JSONObject>()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val hasTeamId = listOf("teamId", "teamID", "id").any { key ->
                        node.has(key) && node.optInt(key, 0) > 0
                    }
                    val metricKeys = listOf(
                        "golds", "gold", "totalGold", "teamGold", "kills", "kill",
                        "turretAmount", "towers", "tower", "dragonAmount", "baronAmount"
                    )
                    if (hasTeamId && metricKeys.any(node::has)) objects += node
                    val keys = node.keys()
                    while (keys.hasNext()) walk(node.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
            }
        }
        walk(root)

        return objects.mapNotNull { obj ->
            val teamId = intAny(obj, "teamId", "teamID", "id")
            if (teamId <= 0) return@mapNotNull null
            TeamLive(
                teamId = teamId,
                gold = intAny(obj, "golds", "gold", "totalGold", "teamGold"),
                kills = intAny(obj, "kills", "kill", "totalKills"),
                towers = intAny(obj, "turretAmount", "towers", "tower"),
                dragons = intAny(obj, "dragonAmount", "dragons", "dragon"),
                barons = intAny(obj, "baronAmount", "barons", "baron")
            )
        }.groupBy { it.teamId }.map { (_, values) ->
            TeamLive(
                teamId = values.first().teamId,
                gold = values.maxOf { it.gold },
                kills = values.maxOf { it.kills },
                towers = values.maxOf { it.towers },
                dragons = values.maxOf { it.dragons },
                barons = values.maxOf { it.barons }
            )
        }
    }

    private fun intAny(obj: JSONObject, vararg keys: String): Int {
        for (key in keys) {
            if (!obj.has(key)) continue
            val raw = obj.opt(key)
            val value = when (raw) {
                is Number -> raw.toInt()
                is String -> raw.toDoubleOrNull()?.toInt()
                else -> null
            }
            if (value != null) return value
        }
        return 0
    }

    private fun isMeaningful(a: TeamLive, b: TeamLive): Boolean =
        a.gold > 0 || b.gold > 0 || a.kills > 0 || b.kills > 0 ||
            a.towers > 0 || b.towers > 0 || a.dragons > 0 || b.dragons > 0 ||
            a.barons > 0 || b.barons > 0

    private fun teamName(id: Int, detail: MatchDetail): String = when (id) {
        detail.teamAId -> detail.teamAName.ifBlank { "TEAM A" }
        detail.teamBId -> detail.teamBName.ifBlank { "TEAM B" }
        else -> "TEAM"
    }

    private fun detectEvent(
        previous: LiveSnapshot?,
        bo: Int,
        blue: String,
        red: String,
        blueNow: TeamLive,
        redNow: TeamLive
    ): String {
        if (previous == null || previous.game != bo) return "LPL Official · TJStats · G$bo 实时数据已接入"
        return when {
            blueNow.barons > previous.blueBarons -> "$blue 获得男爵"
            redNow.barons > previous.redBarons -> "$red 获得男爵"
            blueNow.dragons > previous.blueDragons -> "$blue 获得小龙"
            redNow.dragons > previous.redDragons -> "$red 获得小龙"
            blueNow.towers > previous.blueTowers -> "$blue 摧毁防御塔"
            redNow.towers > previous.redTowers -> "$red 摧毁防御塔"
            blueNow.kills > previous.blueKills -> "$blue 完成击杀"
            redNow.kills > previous.redKills -> "$red 完成击杀"
            else -> "LPL Official · TJStats · 实时数据已同步"
        }
    }

    private fun field(chunk: String, key: String): String {
        val quoted = Regex("[\\\"']?${Regex.escape(key)}[\\\"']?\\s*:\\s*[\\\"']?([^,\\\"'}\\]\\s]+)", RegexOption.IGNORE_CASE)
            .find(chunk)?.groupValues?.getOrNull(1)
        return quoted.orEmpty().trim()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private suspend fun getText(url: String, authorization: Boolean = true): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 6_000
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) RiftLab/1.0")
            setRequestProperty("Referer", "$LPL_BASE/")
            setRequestProperty("Origin", LPL_BASE)
            if (authorization) setRequestProperty("Authorization", TJ_AUTH)
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

    private suspend fun getJson(url: String): JSONObject {
        val body = getText(url, authorization = true)
        return try {
            JSONObject(body)
        } catch (t: Throwable) {
            throw IOException("non-JSON ${URL(url).path}: ${body.take(120)}", t)
        }
    }
}
