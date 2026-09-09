package com.riftlab.app.data

import android.content.Context
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
import java.io.File
import kotlin.math.abs

/**
 * Event-sourced timeline foundation for RiftLab.
 *
 * Live providers still own truth. This store only records successive verified snapshots, derives
 * conservative events from numeric deltas, and persists them locally so the same game can be
 * scrubbed after it ends. It never invents a killer/victim pairing that the upstream payload did
 * not explicitly provide.
 */
enum class TimelineEventType {
    GAME_START,
    KILL,
    TOWER,
    DRAGON,
    BARON,
    GOLD_SWING,
    GAME_END
}

data class MatchTimelineEvent(
    val seconds: Int,
    val type: TimelineEventType,
    val team: String = "",
    val title: String,
    val detail: String = "",
    val amount: Int = 1
)

data class MatchTimelinePoint(
    val seconds: Int,
    val snapshot: LiveSnapshot
)

data class GameTimeline(
    val gameId: String,
    val game: Int,
    val blue: String,
    val red: String,
    val durationSeconds: Int,
    val points: List<MatchTimelinePoint>,
    val events: List<MatchTimelineEvent>,
    val completed: Boolean = false,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    fun stateAt(seconds: Int): LiveSnapshot? =
        points.lastOrNull { it.seconds <= seconds }?.snapshot ?: points.firstOrNull()?.snapshot
}

object MatchTimelineStore {
    private const val SNAPSHOT_INTERVAL_SECONDS = 10
    private const val MAX_POINTS = 720
    private const val MAX_EVENTS = 600

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val persistJobs = linkedMapOf<String, Job>()
    private var timelineDir: File? = null

    private val _timelines = MutableStateFlow<Map<String, GameTimeline>>(emptyMap())
    val timelines: StateFlow<Map<String, GameTimeline>> = _timelines.asStateFlow()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (timelineDir != null) return
            timelineDir = File(context.filesDir, "match_timelines").apply { mkdirs() }
        }
        scope.launch { loadFromDisk() }
    }

    fun ingest(snapshot: LiveSnapshot) {
        if (snapshot.game <= 0 || snapshot.elapsedSeconds < 0) return
        val key = timelineKey(snapshot)
        val updated = synchronized(lock) {
            val current = _timelines.value[key]
            val previous = current?.points?.lastOrNull()?.snapshot
            if (previous != null && snapshot.elapsedSeconds + 5 < previous.elapsedSeconds) {
                return
            }

            val derived = if (previous == null) {
                listOf(
                    MatchTimelineEvent(
                        seconds = 0,
                        type = TimelineEventType.GAME_START,
                        title = "GAME START",
                        detail = "G${snapshot.game} · ${snapshot.blue} vs ${snapshot.red}"
                    )
                )
            } else {
                deriveEvents(previous, snapshot)
            }

            val existingPoints = current?.points.orEmpty()
            val shouldStorePoint = existingPoints.isEmpty() ||
                snapshot.elapsedSeconds - existingPoints.last().seconds >= SNAPSHOT_INTERVAL_SECONDS ||
                derived.isNotEmpty()

            val points = if (shouldStorePoint) {
                val withoutSameSecond = existingPoints.dropLastWhile { it.seconds == snapshot.elapsedSeconds }
                (withoutSameSecond + MatchTimelinePoint(snapshot.elapsedSeconds, snapshot)).takeLast(MAX_POINTS)
            } else {
                existingPoints
            }

            val events = (current?.events.orEmpty() + derived)
                .distinctBy { listOf(it.seconds, it.type.name, it.team, it.title, it.detail).joinToString("|") }
                .sortedBy { it.seconds }
                .takeLast(MAX_EVENTS)

            val timeline = GameTimeline(
                gameId = key,
                game = snapshot.game,
                blue = snapshot.blue,
                red = snapshot.red,
                durationSeconds = maxOf(current?.durationSeconds ?: 0, snapshot.elapsedSeconds),
                points = points,
                events = events,
                completed = current?.completed ?: false,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            _timelines.value = _timelines.value + (key to timeline)
            timeline
        }
        schedulePersist(updated)
    }

    fun markCompleted(snapshot: LiveSnapshot) {
        if (snapshot.game <= 0) return
        ingest(snapshot)
        val key = timelineKey(snapshot)
        val updated = synchronized(lock) {
            val current = _timelines.value[key] ?: return
            if (current.completed) return
            val endEvent = MatchTimelineEvent(
                seconds = current.durationSeconds,
                type = TimelineEventType.GAME_END,
                title = "GAME END",
                detail = "本地记录到最终实时帧 · ${formatClock(current.durationSeconds)}"
            )
            val timeline = current.copy(
                events = (current.events + endEvent).distinctBy {
                    listOf(it.seconds, it.type.name, it.team, it.title).joinToString("|")
                },
                completed = true,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            _timelines.value = _timelines.value + (key to timeline)
            timeline
        }
        schedulePersist(updated)
    }

    /** Match-detail final frames normally preserve the TJ gameId; fall back to game+teams. */
    fun find(snapshot: LiveSnapshot, source: Map<String, GameTimeline> = _timelines.value): GameTimeline? {
        val direct = source[timelineKey(snapshot)]
        if (direct != null) return direct
        val blueToken = token(snapshot.blue)
        val redToken = token(snapshot.red)
        return source.values
            .filter { it.game == snapshot.game }
            .maxByOrNull { timeline ->
                val sameTeams = token(timeline.blue) == blueToken && token(timeline.red) == redToken
                if (sameTeams) timeline.updatedAtEpochMs else Long.MIN_VALUE
            }
            ?.takeIf { token(it.blue) == blueToken && token(it.red) == redToken }
    }

    private fun deriveEvents(previous: LiveSnapshot, current: LiveSnapshot): List<MatchTimelineEvent> = buildList {
        val second = current.elapsedSeconds

        appendKillEvents(
            output = this,
            second = second,
            team = current.blue,
            teamDelta = current.blueKills - previous.blueKills,
            previousPlayers = previous.bluePlayers,
            currentPlayers = current.bluePlayers
        )
        appendKillEvents(
            output = this,
            second = second,
            team = current.red,
            teamDelta = current.redKills - previous.redKills,
            previousPlayers = previous.redPlayers,
            currentPlayers = current.redPlayers
        )

        appendObjectiveDelta(this, second, current.blue, "防御塔", TimelineEventType.TOWER, current.blueTowers - previous.blueTowers)
        appendObjectiveDelta(this, second, current.red, "防御塔", TimelineEventType.TOWER, current.redTowers - previous.redTowers)
        appendObjectiveDelta(this, second, current.blue, "小龙", TimelineEventType.DRAGON, current.blueDragons - previous.blueDragons)
        appendObjectiveDelta(this, second, current.red, "小龙", TimelineEventType.DRAGON, current.redDragons - previous.redDragons)
        appendObjectiveDelta(this, second, current.blue, "男爵", TimelineEventType.BARON, current.blueBarons - previous.blueBarons)
        appendObjectiveDelta(this, second, current.red, "男爵", TimelineEventType.BARON, current.redBarons - previous.redBarons)

        val oldDiff = previous.goldDiff
        val newDiff = current.goldDiff
        val leadChangedHands = (oldDiff > 250 && newDiff < -250) || (oldDiff < -250 && newDiff > 250)
        val suddenSwing = abs(newDiff - oldDiff) >= 1800 && current.elapsedSeconds - previous.elapsedSeconds <= 20
        if (leadChangedHands || suddenSwing) {
            val leader = when {
                newDiff > 0 -> current.blue
                newDiff < 0 -> current.red
                else -> "双方"
            }
            add(
                MatchTimelineEvent(
                    seconds = second,
                    type = TimelineEventType.GOLD_SWING,
                    team = if (newDiff > 0) current.blue else if (newDiff < 0) current.red else "",
                    title = if (leadChangedHands) "经济领先易手" else "经济快速摆动",
                    detail = "$leader · ${signedGold(newDiff)} · 本段 ${signedGold(newDiff - oldDiff)}"
                )
            )
        }
    }

    private fun appendKillEvents(
        output: MutableList<MatchTimelineEvent>,
        second: Int,
        team: String,
        teamDelta: Int,
        previousPlayers: List<LivePlayerSnapshot>,
        currentPlayers: List<LivePlayerSnapshot>
    ) {
        if (teamDelta <= 0) return
        val previousById = previousPlayers.associateBy { playerKey(it) }
        val playerDeltas = currentPlayers.mapNotNull { player ->
            val before = previousById[playerKey(player)] ?: return@mapNotNull null
            val delta = player.kills - before.kills
            if (delta > 0) player.summonerName.ifBlank { player.role } to delta else null
        }
        val explained = playerDeltas.sumOf { it.second }
        if (playerDeltas.isNotEmpty() && explained == teamDelta) {
            playerDeltas.forEach { (name, amount) ->
                output += MatchTimelineEvent(
                    seconds = second,
                    type = TimelineEventType.KILL,
                    team = team,
                    title = "$team · $name +$amount 击杀",
                    detail = "由连续实时 KDA 数值差分确认；不推断未提供的受害者配对",
                    amount = amount
                )
            }
        } else {
            output += MatchTimelineEvent(
                seconds = second,
                type = TimelineEventType.KILL,
                team = team,
                title = "$team +$teamDelta 击杀",
                detail = "由队伍击杀总数差分确认",
                amount = teamDelta
            )
        }
    }

    private fun appendObjectiveDelta(
        output: MutableList<MatchTimelineEvent>,
        second: Int,
        team: String,
        label: String,
        type: TimelineEventType,
        delta: Int
    ) {
        if (delta <= 0) return
        output += MatchTimelineEvent(
            seconds = second,
            type = type,
            team = team,
            title = "$team · $label +$delta",
            detail = "由连续实时资源计数差分确认",
            amount = delta
        )
    }

    private fun playerKey(player: LivePlayerSnapshot): String =
        player.participantId.takeIf { it > 0 }?.toString()
            ?: token(player.summonerName).ifBlank { player.role.uppercase() }

    private fun timelineKey(snapshot: LiveSnapshot): String = snapshot.gameId.trim().ifBlank {
        "${token(snapshot.blue)}_${token(snapshot.red)}_G${snapshot.game}"
    }

    private fun token(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

    private fun schedulePersist(timeline: GameTimeline) {
        if (timelineDir == null) return
        persistJobs.remove(timeline.gameId)?.cancel()
        persistJobs[timeline.gameId] = scope.launch {
            delay(450L)
            runCatching { persist(timeline) }
            synchronized(lock) { persistJobs.remove(timeline.gameId) }
        }
    }

    private fun persist(timeline: GameTimeline) {
        val dir = timelineDir ?: return
        val safe = timeline.gameId.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(96).ifBlank { "game" }
        val suffix = timeline.gameId.hashCode().toUInt().toString(16)
        val file = File(dir, "timeline_${safe}_$suffix.json")
        val temp = File(dir, file.name + ".tmp")
        temp.writeText(timelineToJson(timeline).toString())
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun loadFromDisk() {
        val dir = timelineDir ?: return
        val loaded = linkedMapOf<String, GameTimeline>()
        dir.listFiles { file -> file.isFile && file.name.startsWith("timeline_") && file.name.endsWith(".json") }
            .orEmpty()
            .forEach { file ->
                runCatching { timelineFromJson(JSONObject(file.readText())) }.getOrNull()?.let { timeline ->
                    loaded[timeline.gameId] = timeline
                }
            }
        if (loaded.isNotEmpty()) {
            synchronized(lock) {
                _timelines.value = loaded + _timelines.value
            }
        }
    }

    private fun timelineToJson(timeline: GameTimeline): JSONObject = JSONObject()
        .put("gameId", timeline.gameId)
        .put("game", timeline.game)
        .put("blue", timeline.blue)
        .put("red", timeline.red)
        .put("durationSeconds", timeline.durationSeconds)
        .put("completed", timeline.completed)
        .put("updatedAtEpochMs", timeline.updatedAtEpochMs)
        .put("points", JSONArray().apply {
            timeline.points.forEach { point ->
                put(JSONObject().put("seconds", point.seconds).put("snapshot", snapshotToJson(point.snapshot)))
            }
        })
        .put("events", JSONArray().apply {
            timeline.events.forEach { event ->
                put(
                    JSONObject()
                        .put("seconds", event.seconds)
                        .put("type", event.type.name)
                        .put("team", event.team)
                        .put("title", event.title)
                        .put("detail", event.detail)
                        .put("amount", event.amount)
                )
            }
        })

    private fun timelineFromJson(root: JSONObject): GameTimeline {
        val pointsArray = root.optJSONArray("points") ?: JSONArray()
        val points = buildList {
            for (i in 0 until pointsArray.length()) {
                val item = pointsArray.optJSONObject(i) ?: continue
                val snapshot = item.optJSONObject("snapshot")?.let(::snapshotFromJson) ?: continue
                add(MatchTimelinePoint(item.optInt("seconds", snapshot.elapsedSeconds), snapshot))
            }
        }
        val eventsArray = root.optJSONArray("events") ?: JSONArray()
        val events = buildList {
            for (i in 0 until eventsArray.length()) {
                val item = eventsArray.optJSONObject(i) ?: continue
                val type = runCatching { TimelineEventType.valueOf(item.optString("type")) }.getOrNull() ?: continue
                add(
                    MatchTimelineEvent(
                        seconds = item.optInt("seconds"),
                        type = type,
                        team = item.optString("team"),
                        title = item.optString("title"),
                        detail = item.optString("detail"),
                        amount = item.optInt("amount", 1)
                    )
                )
            }
        }
        return GameTimeline(
            gameId = root.getString("gameId"),
            game = root.optInt("game", points.lastOrNull()?.snapshot?.game ?: 0),
            blue = root.optString("blue"),
            red = root.optString("red"),
            durationSeconds = root.optInt("durationSeconds", points.lastOrNull()?.seconds ?: 0),
            points = points,
            events = events,
            completed = root.optBoolean("completed", false),
            updatedAtEpochMs = root.optLong("updatedAtEpochMs", 0L)
        )
    }

    private fun snapshotToJson(snapshot: LiveSnapshot): JSONObject = JSONObject()
        .put("game", snapshot.game)
        .put("elapsedSeconds", snapshot.elapsedSeconds)
        .put("blue", snapshot.blue)
        .put("red", snapshot.red)
        .put("blueGold", snapshot.blueGold)
        .put("redGold", snapshot.redGold)
        .put("blueKills", snapshot.blueKills)
        .put("redKills", snapshot.redKills)
        .put("blueTowers", snapshot.blueTowers)
        .put("redTowers", snapshot.redTowers)
        .put("blueDragons", snapshot.blueDragons)
        .put("redDragons", snapshot.redDragons)
        .put("blueBarons", snapshot.blueBarons)
        .put("redBarons", snapshot.redBarons)
        .put("latestEvent", snapshot.latestEvent)
        .put("source", snapshot.source)
        .put("gameId", snapshot.gameId)
        .put("bluePlayers", playersToJson(snapshot.bluePlayers))
        .put("redPlayers", playersToJson(snapshot.redPlayers))

    private fun snapshotFromJson(root: JSONObject): LiveSnapshot = LiveSnapshot(
        game = root.optInt("game"),
        elapsedSeconds = root.optInt("elapsedSeconds"),
        blue = root.optString("blue"),
        red = root.optString("red"),
        blueGold = root.optInt("blueGold"),
        redGold = root.optInt("redGold"),
        blueKills = root.optInt("blueKills"),
        redKills = root.optInt("redKills"),
        blueTowers = root.optInt("blueTowers"),
        redTowers = root.optInt("redTowers"),
        blueDragons = root.optInt("blueDragons"),
        redDragons = root.optInt("redDragons"),
        blueBarons = root.optInt("blueBarons"),
        redBarons = root.optInt("redBarons"),
        latestEvent = root.optString("latestEvent"),
        bluePlayers = playersFromJson(root.optJSONArray("bluePlayers") ?: JSONArray()),
        redPlayers = playersFromJson(root.optJSONArray("redPlayers") ?: JSONArray()),
        source = root.optString("source"),
        gameId = root.optString("gameId")
    )

    private fun playersToJson(players: List<LivePlayerSnapshot>): JSONArray = JSONArray().apply {
        players.forEach { player ->
            put(
                JSONObject()
                    .put("participantId", player.participantId)
                    .put("role", player.role)
                    .put("summonerName", player.summonerName)
                    .put("championId", player.championId)
                    .put("level", player.level)
                    .put("kills", player.kills)
                    .put("deaths", player.deaths)
                    .put("assists", player.assists)
                    .put("creepScore", player.creepScore)
                    .put("gold", player.gold)
            )
        }
    }

    private fun playersFromJson(array: JSONArray): List<LivePlayerSnapshot> = buildList {
        for (i in 0 until array.length()) {
            val player = array.optJSONObject(i) ?: continue
            add(
                LivePlayerSnapshot(
                    participantId = player.optInt("participantId"),
                    role = player.optString("role"),
                    summonerName = player.optString("summonerName"),
                    championId = player.optString("championId"),
                    level = player.optInt("level"),
                    kills = player.optInt("kills"),
                    deaths = player.optInt("deaths"),
                    assists = player.optInt("assists"),
                    creepScore = player.optInt("creepScore"),
                    gold = player.optInt("gold")
                )
            )
        }
    }

    private fun signedGold(value: Int): String {
        val sign = if (value >= 0) "+" else "-"
        val absolute = abs(value)
        return if (absolute >= 1000) "$sign%.1fK".format(absolute / 1000f) else "$sign$absolute"
    }

    private fun formatClock(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)
}
