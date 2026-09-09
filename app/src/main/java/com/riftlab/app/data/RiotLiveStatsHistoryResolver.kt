package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Restores real process frames from Riot's LoL Esports LiveStats feed.
 *
 * Important: this is not interpolation. /window/{gameId} is the same public data plane used by
 * lolesports.com. We walk its 10-second cursor grid, parse the returned real frames and append
 * them to MatchLifecycleArchive. If Riot no longer retains a game's feed, RiftLab leaves the gap.
 */
enum class RiotHistoryPhase {
    IDLE,
    LOADING,
    READY,
    UNAVAILABLE,
    ERROR
}

data class RiotHistoryBackfillState(
    val key: String,
    val game: Int,
    val phase: RiotHistoryPhase,
    val loadedFrames: Int = 0,
    val latestSecond: Int = 0,
    val message: String = "",
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

object RiotLiveStatsHistoryResolver {
    private const val WINDOW_SECONDS = 10L
    private const val SAMPLE_SECONDS = 2
    private const val MAX_WINDOWS = 1_080 // hard cap: 3 hours
    private const val RETRY_COOLDOWN_MS = 5 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _states = MutableStateFlow<Map<String, RiotHistoryBackfillState>>(emptyMap())
    val states: StateFlow<Map<String, RiotHistoryBackfillState>> = _states.asStateFlow()

    fun stateKey(match: ScheduledEsportsMatch, game: Int): String =
        "${MatchLifecycleArchive.keyFor(match)}:G$game"

    fun ensure(match: ScheduledEsportsMatch, game: Int) {
        if (game <= 0 || match.eventId.isBlank()) return
        val phase = MatchSessionStore.schedulePhase(match)
        if (phase == ScheduleMatchPhase.UPCOMING) return

        val key = stateKey(match, game)
        val archived = MatchLifecycleArchive.find(match)?.framesFor(game).orEmpty()
        if (archived.size >= 2) {
            publish(
                RiotHistoryBackfillState(
                    key = key,
                    game = game,
                    phase = RiotHistoryPhase.READY,
                    loadedFrames = archived.size,
                    latestSecond = archived.lastOrNull()?.snapshot?.elapsedSeconds ?: 0,
                    message = "Riot LiveStats 历史过程已归档"
                )
            )
            return
        }

        val previous = _states.value[key]
        if (previous?.phase == RiotHistoryPhase.LOADING) return
        if (previous != null && previous.phase in setOf(RiotHistoryPhase.UNAVAILABLE, RiotHistoryPhase.ERROR)) {
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

    private suspend fun backfill(match: ScheduledEsportsMatch, gameNumber: Int, key: String) {
        publish(
            RiotHistoryBackfillState(
                key = key,
                game = gameNumber,
                phase = RiotHistoryPhase.LOADING,
                message = "正在定位 Riot LiveStats G$gameNumber…"
            )
        )

        try {
            val eventRoot = RiotResilientHttp.getJson(
                "${LolEsportsConfig.PERSISTED_BASE}/getEventDetails?hl=en-US&id=${enc(match.eventId)}",
                connectTimeoutMs = 7_000,
                readTimeoutMs = 8_000
            )
            val matchRoot = eventRoot.optJSONObject("data")
                ?.optJSONObject("event")
                ?.optJSONObject("match")
                ?: return unavailable(key, gameNumber, "Riot EventDetails 没有比赛对象")
            val gameObject = findGame(matchRoot.optJSONArray("games"), gameNumber)
                ?: return unavailable(key, gameNumber, "Riot EventDetails 没有 G$gameNumber gameId")
            val gameId = gameObject.optString("id")
            if (gameId.isBlank()) return unavailable(key, gameNumber, "Riot EventDetails 的 G$gameNumber 缺少 gameId")

            val sideIds = parseSideIds(gameObject)
            val kickoff = try {
                RiotResilientHttp.getJson(
                    "${LolEsportsConfig.LIVE_BASE}/window/$gameId",
                    connectTimeoutMs = 7_000,
                    readTimeoutMs = 8_000
                )
            } catch (_: Throwable) {
                return unavailable(key, gameNumber, "Riot LiveStats 没有保留 G$gameNumber 的起始窗口")
            }
            val kickoffFrames = kickoff.optJSONArray("frames") ?: JSONArray()
            if (kickoffFrames.length() == 0) {
                return unavailable(key, gameNumber, "Riot LiveStats 的 G$gameNumber 起始窗口为空")
            }

            var metadata = kickoff.optJSONObject("gameMetadata") ?: JSONObject()
            val firstTs = firstTimestamp(kickoffFrames)
                ?: return unavailable(key, gameNumber, "Riot LiveStats 缺少可用时间戳")
            val t0 = firstTs
            var cursor = align10(t0)
            var emptyWindows = 0
            var requests = 0
            var stored = 0
            var lastStoredSecond = -SAMPLE_SECONDS
            var finished = false

            while (requests < MAX_WINDOWS && !finished) {
                val startingTime = cursor.truncatedTo(ChronoUnit.SECONDS).toString()
                val root = try {
                    RiotResilientHttp.getJson(
                        "${LolEsportsConfig.LIVE_BASE}/window/$gameId?startingTime=${enc(startingTime)}",
                        connectTimeoutMs = 7_000,
                        readTimeoutMs = 8_000
                    )
                } catch (_: Throwable) {
                    emptyWindows++
                    if (emptyWindows >= 3) break
                    cursor = cursor.plusSeconds(WINDOW_SECONDS)
                    requests++
                    delay(60L)
                    continue
                }

                val frames = root.optJSONArray("frames") ?: JSONArray()
                if (frames.length() == 0) {
                    emptyWindows++
                    if (emptyWindows >= 3) break
                } else {
                    emptyWindows = 0
                    root.optJSONObject("gameMetadata")?.takeIf { it.length() > 0 }?.let { metadata = it }
                    for (i in 0 until frames.length()) {
                        val frame = frames.optJSONObject(i) ?: continue
                        val frameTs = parseInstant(frame.optString("rfc460Timestamp")) ?: continue
                        val elapsed = (frameTs.toEpochMilli() - t0.toEpochMilli()).div(1000L).toInt()
                        if (elapsed < 0) continue
                        val gameState = frame.optString("gameState")
                        val isFinish = gameState.equals("finished", ignoreCase = true)
                        if (!isFinish && elapsed - lastStoredSecond < SAMPLE_SECONDS) continue

                        val snapshot = parseSnapshot(
                            match = match,
                            gameNumber = gameNumber,
                            gameId = gameId,
                            frame = frame,
                            metadata = metadata,
                            sideIds = sideIds,
                            elapsedSeconds = elapsed
                        )
                        if (snapshot != null && meaningful(snapshot)) {
                            MatchLifecycleArchive.observeLive(match, snapshot)
                            lastStoredSecond = elapsed
                            stored++
                        }
                        if (isFinish) {
                            finished = true
                            break
                        }
                    }
                }

                if (requests % 6 == 0) {
                    publish(
                        RiotHistoryBackfillState(
                            key = key,
                            game = gameNumber,
                            phase = RiotHistoryPhase.LOADING,
                            loadedFrames = stored,
                            latestSecond = max(0, lastStoredSecond),
                            message = "Riot LiveStats 正在恢复 G$gameNumber · ${clock(max(0, lastStoredSecond))} · $stored 帧"
                        )
                    )
                }

                cursor = cursor.plusSeconds(WINDOW_SECONDS)
                requests++
                delay(60L)
            }

            // observeLive marks the transient record LIVE; restore the schedule-derived phase after backfill.
            MatchLifecycleArchive.observeScheduleMatch(match)
            val total = MatchLifecycleArchive.find(match)?.framesFor(gameNumber)?.size ?: stored
            if (total >= 2) {
                publish(
                    RiotHistoryBackfillState(
                        key = key,
                        game = gameNumber,
                        phase = RiotHistoryPhase.READY,
                        loadedFrames = total,
                        latestSecond = max(0, lastStoredSecond),
                        message = if (finished) {
                            "Riot LiveStats G$gameNumber 历史过程恢复完成 · $total 帧"
                        } else {
                            "Riot LiveStats G$gameNumber 已恢复可用过程 · $total 帧"
                        }
                    )
                )
            } else {
                unavailable(key, gameNumber, "Riot LiveStats 未返回足够的连续状态帧")
            }
        } catch (t: Throwable) {
            publish(
                RiotHistoryBackfillState(
                    key = key,
                    game = gameNumber,
                    phase = RiotHistoryPhase.ERROR,
                    message = "Riot LiveStats 恢复失败：${t.message?.take(140) ?: t::class.java.simpleName}"
                )
            )
        }
    }

    private fun parseSnapshot(
        match: ScheduledEsportsMatch,
        gameNumber: Int,
        gameId: String,
        frame: JSONObject,
        metadata: JSONObject,
        sideIds: Pair<String, String>,
        elapsedSeconds: Int
    ): LiveSnapshot? {
        val blueFrame = frame.optJSONObject("blueTeam") ?: return null
        val redFrame = frame.optJSONObject("redTeam") ?: return null
        val blueMeta = metadata.optJSONObject("blueTeamMetadata") ?: JSONObject()
        val redMeta = metadata.optJSONObject("redTeamMetadata") ?: JSONObject()
        val blueId = blueMeta.optString("esportsTeamId").ifBlank { sideIds.first }
        val redId = redMeta.optString("esportsTeamId").ifBlank { sideIds.second }
        val byId = match.teams.associateBy { it.id }
        val blueCode = byId[blueId]?.code?.ifBlank { null }
            ?: match.teams.getOrNull(0)?.code?.ifBlank { match.teams.getOrNull(0)?.name }
            ?: "BLUE"
        val redCode = byId[redId]?.code?.ifBlank { null }
            ?: match.teams.getOrNull(1)?.code?.ifBlank { match.teams.getOrNull(1)?.name }
            ?: "RED"

        return LiveSnapshot(
            game = gameNumber,
            elapsedSeconds = elapsedSeconds,
            blue = blueCode,
            red = redCode,
            blueGold = blueFrame.optInt("totalGold", 0),
            redGold = redFrame.optInt("totalGold", 0),
            blueKills = blueFrame.optInt("totalKills", 0),
            redKills = redFrame.optInt("totalKills", 0),
            blueTowers = blueFrame.optInt("towers", 0),
            redTowers = redFrame.optInt("towers", 0),
            blueDragons = countDragons(blueFrame),
            redDragons = countDragons(redFrame),
            latestEvent = "Riot LiveStats · ${clock(elapsedSeconds)}",
            blueBarons = blueFrame.optInt("barons", 0),
            redBarons = redFrame.optInt("barons", 0),
            bluePlayers = parsePlayers(blueMeta, blueFrame),
            redPlayers = parsePlayers(redMeta, redFrame),
            source = "Riot LoL Esports LiveStats · verified window",
            gameId = gameId
        )
    }

    private fun parsePlayers(metadata: JSONObject, teamFrame: JSONObject): List<LivePlayerSnapshot> {
        val metaArray = metadata.optJSONArray("participantMetadata") ?: JSONArray()
        val metaById = mutableMapOf<Int, JSONObject>()
        for (i in 0 until metaArray.length()) {
            val item = metaArray.optJSONObject(i) ?: continue
            metaById[item.optInt("participantId", -1)] = item
        }
        val players = teamFrame.optJSONArray("participants") ?: JSONArray()
        return buildList {
            for (i in 0 until players.length()) {
                val row = players.optJSONObject(i) ?: continue
                val id = row.optInt("participantId", -1)
                val meta = metaById[id]
                add(
                    LivePlayerSnapshot(
                        participantId = id,
                        role = meta?.optString("role").orEmpty().uppercase(),
                        summonerName = meta?.optString("summonerName").orEmpty(),
                        championId = meta?.optString("championId").orEmpty(),
                        level = row.optInt("level", 0),
                        kills = row.optInt("kills", 0),
                        deaths = row.optInt("deaths", 0),
                        assists = row.optInt("assists", 0),
                        creepScore = row.optInt("creepScore", 0),
                        gold = row.optInt("totalGold", 0)
                    )
                )
            }
        }
    }

    private fun findGame(games: JSONArray?, gameNumber: Int): JSONObject? {
        if (games == null) return null
        for (i in 0 until games.length()) {
            val row = games.optJSONObject(i) ?: continue
            if (row.optInt("number", i + 1) == gameNumber) return row
        }
        return null
    }

    private fun parseSideIds(game: JSONObject): Pair<String, String> {
        var blue = ""
        var red = ""
        val teams = game.optJSONArray("teams") ?: JSONArray()
        for (i in 0 until teams.length()) {
            val row = teams.optJSONObject(i) ?: continue
            when (row.optString("side").lowercase()) {
                "blue" -> blue = row.optString("id")
                "red" -> red = row.optString("id")
            }
        }
        return blue to red
    }

    private fun firstTimestamp(frames: JSONArray): Instant? {
        for (i in 0 until frames.length()) {
            parseInstant(frames.optJSONObject(i)?.optString("rfc460Timestamp").orEmpty())?.let { return it }
        }
        return null
    }

    private fun align10(value: Instant): Instant {
        val epoch = value.epochSecond
        return Instant.ofEpochSecond(epoch - Math.floorMod(epoch, WINDOW_SECONDS))
    }

    private fun countDragons(team: JSONObject): Int =
        team.optJSONArray("dragons")?.length() ?: team.optInt("dragons", 0)

    private fun meaningful(snapshot: LiveSnapshot): Boolean =
        snapshot.blueGold > 0 || snapshot.redGold > 0 ||
            snapshot.blueKills > 0 || snapshot.redKills > 0 ||
            snapshot.bluePlayers.any { it.gold > 0 || it.level > 1 || it.creepScore > 0 } ||
            snapshot.redPlayers.any { it.gold > 0 || it.level > 1 || it.creepScore > 0 }

    private fun unavailable(key: String, game: Int, message: String) {
        MatchLifecycleArchive.records.value.values
        publish(
            RiotHistoryBackfillState(
                key = key,
                game = game,
                phase = RiotHistoryPhase.UNAVAILABLE,
                message = message
            )
        )
    }

    private fun publish(next: RiotHistoryBackfillState) {
        _states.value = _states.value + (next.key to next.copy(updatedAtEpochMs = System.currentTimeMillis()))
    }

    private fun parseInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun clock(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)
}
