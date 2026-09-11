package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Historical process fallback backed by OP.GG Esports' public GraphQL data plane.
 *
 * OP.GG's gameByMatch payload exposes per-team `frames { gold xp timestamp }` in addition to
 * terminal team totals. These are upstream observations, not values inferred from a final score.
 * RiftLab only uses this resolver when the primary Riot LiveStats history feed is unavailable.
 *
 * No login/cookie bypass is used here: this calls the same unauthenticated GraphQL endpoint that
 * the public esports site uses and backs off on failures.
 */
enum class OpggHistoryPhase {
    IDLE,
    LOADING,
    READY,
    UNAVAILABLE,
    ERROR
}

data class OpggHistoryBackfillState(
    val key: String,
    val game: Int,
    val phase: OpggHistoryPhase,
    val loadedFrames: Int = 0,
    val latestSecond: Int = 0,
    val message: String = "",
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

private data class OpggNormalizedFrame(
    val elapsedSeconds: Int,
    val gold: Int,
    val xp: Int
)

private data class OpggTeamProcess(
    val side: String,
    val code: String,
    val frames: List<OpggNormalizedFrame>
)

object OpggHistoricalFrameResolver {
    private const val GRAPHQL = "https://esports.op.gg/matches/graphql"
    private const val RETRY_COOLDOWN_MS = 10 * 60 * 1000L

    private const val LIST_MATCHES_QUERY = """
        query ListPagedAllMatches(${ '$' }status: String!, ${ '$' }leagueId: ID, ${ '$' }teamId: ID, ${ '$' }page: Int, ${ '$' }year: Int, ${ '$' }month: Int, ${ '$' }limit: Int) {
          pagedAllMatches(status: ${ '$' }status, leagueId: ${ '$' }leagueId, teamId: ${ '$' }teamId, page: ${ '$' }page, year: ${ '$' }year, month: ${ '$' }month, limit: ${ '$' }limit) {
            id name scheduledAt beginAt status homeScore awayScore
            homeTeam { id name acronym }
            awayTeam { id name acronym }
          }
        }
    """

    private const val GAME_QUERY = """
        query GetGameByMatch(${ '$' }matchId: ID!, ${ '$' }set: Int) {
          gameByMatch(matchId: ${ '$' }matchId, set: ${ '$' }set) {
            id beginAt endAt finished length
            teams {
              side
              kills deaths assists
              towerKills inhibitorKills heraldKills dragonKills elderDrakeKills baronKills
              goldEarned
              team { id name acronym }
              frames { gold xp timestamp }
            }
          }
        }
    """

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _states = MutableStateFlow<Map<String, OpggHistoryBackfillState>>(emptyMap())
    val states: StateFlow<Map<String, OpggHistoryBackfillState>> = _states.asStateFlow()

    fun stateKey(match: ScheduledEsportsMatch, game: Int): String =
        "${MatchLifecycleArchive.keyFor(match)}:G$game"

    fun ensure(match: ScheduledEsportsMatch, game: Int) {
        if (game <= 0 || MatchSessionStore.schedulePhase(match) != ScheduleMatchPhase.COMPLETED) return
        val key = stateKey(match, game)
        val archived = MatchLifecycleArchive.find(match)?.framesFor(game).orEmpty()
        if (archived.size >= 2) {
            publish(
                OpggHistoryBackfillState(
                    key = key,
                    game = game,
                    phase = OpggHistoryPhase.READY,
                    loadedFrames = archived.size,
                    latestSecond = archived.lastOrNull()?.snapshot?.elapsedSeconds ?: 0,
                    message = "历史过程已归档"
                )
            )
            return
        }

        val previous = _states.value[key]
        if (previous?.phase == OpggHistoryPhase.LOADING) return
        if (previous != null && previous.phase in setOf(OpggHistoryPhase.UNAVAILABLE, OpggHistoryPhase.ERROR)) {
            if (System.currentTimeMillis() - previous.updatedAtEpochMs < RETRY_COOLDOWN_MS) return
        }
        if (jobs[key]?.isActive == true) return

        jobs[key] = scope.launch {
            try {
                backfill(match, game, key)
            } finally {
                jobs.remove(key)
            }
        }
    }

    private fun backfill(match: ScheduledEsportsMatch, gameNumber: Int, key: String) {
        publish(
            OpggHistoryBackfillState(
                key = key,
                game = gameNumber,
                phase = OpggHistoryPhase.LOADING,
                message = "Riot 历史源不可用，正在查询 OP.GG G$gameNumber 过程帧…"
            )
        )

        try {
            val opggMatch = findMatch(match)
                ?: return unavailable(key, gameNumber, "OP.GG 未匹配到该系列赛")
            val matchId = opggMatch.opt("id")?.toString().orEmpty()
            if (matchId.isBlank()) return unavailable(key, gameNumber, "OP.GG match id 缺失")

            val game = fetchGame(matchId, gameNumber)
                ?: return unavailable(key, gameNumber, "OP.GG 没有 G$gameNumber 数据")
            val opggGameId = game.opt("id")?.toString().orEmpty()
            val begin = parseInstant(game.optString("beginAt"))
            val lengthSeconds = normalizeGameLength(game.optLong("length", 0L))
            val teams = game.optJSONArray("teams") ?: JSONArray()

            val parsed = buildList {
                for (i in 0 until teams.length()) {
                    val row = teams.optJSONObject(i) ?: continue
                    val side = row.optString("side").lowercase()
                    if (side != "blue" && side != "red") continue
                    val team = row.optJSONObject("team") ?: JSONObject()
                    val code = team.optString("acronym").ifBlank { team.optString("name") }.ifBlank { side.uppercase() }
                    val frames = normalizeFrames(row.optJSONArray("frames"), begin, lengthSeconds)
                    if (frames.isNotEmpty()) add(OpggTeamProcess(side, code, frames))
                }
            }

            val blue = parsed.firstOrNull { it.side == "blue" }
                ?: return unavailable(key, gameNumber, "OP.GG G$gameNumber 缺少蓝方过程帧")
            val red = parsed.firstOrNull { it.side == "red" }
                ?: return unavailable(key, gameNumber, "OP.GG G$gameNumber 缺少红方过程帧")

            val blueBySecond = blue.frames.associateBy { it.elapsedSeconds }
            val redBySecond = red.frames.associateBy { it.elapsedSeconds }
            val exactSeconds = blueBySecond.keys.intersect(redBySecond.keys).sorted()
            val seconds = if (exactSeconds.size >= 2) exactSeconds else pairByNearestSecond(blue.frames, red.frames)
            if (seconds.size < 2) {
                return unavailable(key, gameNumber, "OP.GG G$gameNumber 返回了帧，但双方时间戳无法可靠对齐")
            }

            var stored = 0
            for (second in seconds) {
                val bf = nearestFrame(blue.frames, second) ?: continue
                val rf = nearestFrame(red.frames, second) ?: continue
                if (abs(bf.elapsedSeconds - second) > 2 || abs(rf.elapsedSeconds - second) > 2) continue
                if (bf.gold <= 0 && rf.gold <= 0) continue

                MatchLifecycleArchive.observeLive(
                    match,
                    LiveSnapshot(
                        game = gameNumber,
                        elapsedSeconds = second,
                        blue = blue.code,
                        red = red.code,
                        blueGold = bf.gold,
                        redGold = rf.gold,
                        blueKills = 0,
                        redKills = 0,
                        blueTowers = 0,
                        redTowers = 0,
                        blueDragons = 0,
                        redDragons = 0,
                        latestEvent = "OP.GG 历史帧 · ${clock(second)} · GOLD/XP",
                        blueXp = bf.xp,
                        redXp = rf.xp,
                        source = "OP.GG Esports · gameByMatch.team.frames · third-party",
                        gameId = if (opggGameId.isBlank()) "opgg:$matchId:g$gameNumber" else "opgg:$opggGameId",
                        targetKey = LiveMatchTargetRegistry.key(match)
                    )
                )
                stored++
            }

            // Historical backfill must not leave the lifecycle record looking LIVE.
            MatchLifecycleArchive.observeScheduleMatch(match)
            val total = MatchLifecycleArchive.find(match)?.framesFor(gameNumber)?.size ?: stored
            if (total >= 2) {
                publish(
                    OpggHistoryBackfillState(
                        key = key,
                        game = gameNumber,
                        phase = OpggHistoryPhase.READY,
                        loadedFrames = total,
                        latestSecond = seconds.lastOrNull() ?: 0,
                        message = "OP.GG G$gameNumber 历史 GOLD/XP 过程已恢复 · $total 帧"
                    )
                )
            } else {
                unavailable(key, gameNumber, "OP.GG G$gameNumber 可用过程帧不足")
            }
        } catch (t: Throwable) {
            publish(
                OpggHistoryBackfillState(
                    key = key,
                    game = gameNumber,
                    phase = OpggHistoryPhase.ERROR,
                    message = "OP.GG 历史过程恢复失败：${t.message?.take(140) ?: t::class.java.simpleName}"
                )
            )
        }
    }

    private fun findMatch(target: ScheduledEsportsMatch): JSONObject? {
        val targetInstant = parseInstant(target.startTimeIso)
        val china = targetInstant?.atZone(ZoneId.of("Asia/Shanghai"))
        val year = china?.year ?: java.time.Year.now().value
        val month = china?.monthValue ?: 1
        val targetDate = china?.toLocalDate()

        val payload = graphQl(
            operationName = "ListPagedAllMatches",
            query = LIST_MATCHES_QUERY,
            variables = JSONObject()
                .put("status", "finished")
                .put("leagueId", JSONObject.NULL)
                .put("teamId", JSONObject.NULL)
                .put("page", 0)
                .put("year", year)
                .put("month", month)
                .put("limit", 500)
        )
        val rows = payload.optJSONObject("data")?.optJSONArray("pagedAllMatches") ?: return null
        val left = target.teams.getOrNull(0)
        val right = target.teams.getOrNull(1)
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
            if (targetDate != null) {
                val rowDate = parseInstant(row.optString("scheduledAt").ifBlank { row.optString("beginAt") })
                    ?.atZone(ZoneId.of("Asia/Shanghai"))
                    ?.toLocalDate()
                if (rowDate != null) {
                    val days = abs(java.time.temporal.ChronoUnit.DAYS.between(targetDate, rowDate).toInt())
                    score += when (days) {
                        0 -> 50
                        1 -> 8
                        else -> -days.coerceAtMost(30)
                    }
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

    private fun normalizeFrames(array: JSONArray?, begin: Instant?, gameLengthSeconds: Int): List<OpggNormalizedFrame> {
        if (array == null || array.length() == 0) return emptyList()
        data class Raw(val timestamp: Any?, val gold: Int, val xp: Int, val index: Int)
        val raw = buildList {
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                add(Raw(row.opt("timestamp"), row.optInt("gold", 0), row.optInt("xp", 0), i))
            }
        }
        if (raw.isEmpty()) return emptyList()

        val numeric = raw.mapNotNull { numericTimestamp(it.timestamp) }
        val maxNumeric = numeric.maxOrNull() ?: 0.0
        val relativeMode = when {
            maxNumeric >= 1_000_000_000_000.0 -> 3 // epoch millis
            maxNumeric >= 1_000_000_000.0 -> 2     // epoch seconds
            gameLengthSeconds > 0 && maxNumeric > gameLengthSeconds * 10.0 -> 1 // relative millis
            gameLengthSeconds > 600 && maxNumeric in 1.0..120.0 && maxNumeric * 30.0 < gameLengthSeconds -> 4 // minutes
            else -> 0 // relative seconds
        }

        return raw.mapNotNull { item ->
            val second = elapsedSecond(item.timestamp, begin, relativeMode) ?: return@mapNotNull null
            if (second < 0 || second > 4 * 60 * 60) return@mapNotNull null
            OpggNormalizedFrame(second, item.gold, item.xp)
        }.distinctBy { it.elapsedSeconds }.sortedBy { it.elapsedSeconds }
    }

    private fun elapsedSecond(raw: Any?, begin: Instant?, mode: Int): Int? {
        if (raw is String && raw.isNotBlank()) {
            parseInstant(raw)?.let { instant ->
                val start = begin ?: return null
                return ((instant.toEpochMilli() - start.toEpochMilli()) / 1000L).toInt()
            }
        }
        val value = numericTimestamp(raw) ?: return null
        return when (mode) {
            3 -> begin?.let { ((value.toLong() - it.toEpochMilli()) / 1000L).toInt() }
            2 -> begin?.let { (value.toLong() - it.epochSecond).toInt() }
            1 -> (value / 1000.0).toInt()
            4 -> (value * 60.0).toInt()
            else -> value.toInt()
        }
    }

    private fun numericTimestamp(raw: Any?): Double? = when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }

    private fun normalizeGameLength(raw: Long): Int = when {
        raw <= 0L -> 0
        raw > 100_000L -> (raw / 1000L).toInt()
        else -> raw.toInt()
    }

    private fun pairByNearestSecond(blue: List<OpggNormalizedFrame>, red: List<OpggNormalizedFrame>): List<Int> =
        blue.mapNotNull { bf ->
            red.minByOrNull { rf -> abs(rf.elapsedSeconds - bf.elapsedSeconds) }
                ?.takeIf { abs(it.elapsedSeconds - bf.elapsedSeconds) <= 2 }
                ?.let { (it.elapsedSeconds + bf.elapsedSeconds) / 2 }
        }.distinct().sorted()

    private fun nearestFrame(frames: List<OpggNormalizedFrame>, second: Int): OpggNormalizedFrame? =
        frames.minByOrNull { abs(it.elapsedSeconds - second) }

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
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("OP.GG HTTP $code")
            val root = JSONObject(text)
            val errors = root.optJSONArray("errors")
            if ((errors?.length() ?: 0) > 0) error("OP.GG GraphQL: ${errors?.optJSONObject(0)?.optString("message").orEmpty().take(120)}")
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

    private fun token(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
    private fun parseInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
    private fun clock(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)

    private fun unavailable(key: String, game: Int, message: String) {
        publish(OpggHistoryBackfillState(key, game, OpggHistoryPhase.UNAVAILABLE, message = message))
    }

    private fun publish(next: OpggHistoryBackfillState) {
        _states.value = _states.value + (next.key to next.copy(updatedAtEpochMs = System.currentTimeMillis()))
    }
}
