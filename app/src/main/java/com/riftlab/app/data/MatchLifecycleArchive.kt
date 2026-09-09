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

/**
 * Persistent lifecycle archive for every schedule match RiftLab can observe.
 *
 * The archive is phase-agnostic:
 * - UPCOMING keeps schedule revisions as the upstream changes.
 * - LIVE appends verified telemetry frames as the provider router emits them.
 * - COMPLETED keeps final frames while preserving every live frame already captured.
 *
 * This is intentionally a storage layer, not a UI layer. Future sandbox/replay features can rebuild
 * a historical state from the same frames instead of inventing intermediate values from a final.
 */
data class MatchScheduleRevision(
    val capturedAtEpochMs: Long,
    val state: String,
    val startTimeIso: String,
    val blockName: String,
    val bestOf: Int,
    val teamAGameWins: Int,
    val teamBGameWins: Int,
    val teamAOutcome: String,
    val teamBOutcome: String
)

data class MatchLifecycleFrame(
    val capturedAtEpochMs: Long,
    val snapshot: LiveSnapshot
)

data class MatchLifecycleRecord(
    val key: String,
    val match: ScheduledEsportsMatch,
    val phase: ScheduleMatchPhase,
    val scheduleRevisions: List<MatchScheduleRevision> = emptyList(),
    val games: Map<Int, List<MatchLifecycleFrame>> = emptyMap(),
    val finalGames: Map<Int, LiveSnapshot> = emptyMap(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    fun framesFor(game: Int): List<MatchLifecycleFrame> = games[game].orEmpty()

    fun latestGame(game: Int): LiveSnapshot? =
        games[game]?.lastOrNull()?.snapshot ?: finalGames[game]
}

object MatchLifecycleArchive {
    private const val MAX_SCHEDULE_REVISIONS = 160
    private const val MAX_FRAMES_PER_GAME = 2_700
    private const val PERSIST_DEBOUNCE_MS = 650L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val persistJobs = linkedMapOf<String, Job>()
    private var rootDir: File? = null

    private val _records = MutableStateFlow<Map<String, MatchLifecycleRecord>>(emptyMap())
    val records: StateFlow<Map<String, MatchLifecycleRecord>> = _records.asStateFlow()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (rootDir != null) return
            rootDir = File(context.filesDir, "match_lifecycle").apply { mkdirs() }
        }
        scope.launch { loadFromDisk() }
    }

    fun observeSchedule(matches: List<ScheduledEsportsMatch>) {
        if (matches.isEmpty()) return
        matches.forEach(::observeScheduleMatch)
    }

    fun observeScheduleMatch(match: ScheduledEsportsMatch) {
        val key = keyFor(match)
        val now = System.currentTimeMillis()
        val phase = phaseFor(match)
        val revision = revisionFor(match, now)
        val updated = synchronized(lock) {
            val current = _records.value[key]
            val revisions = current?.scheduleRevisions.orEmpty()
            val appendRevision = revisions.lastOrNull()?.samePayload(revision) != true
            val next = MatchLifecycleRecord(
                key = key,
                match = match,
                phase = phase,
                scheduleRevisions = if (appendRevision) {
                    (revisions + revision).takeLast(MAX_SCHEDULE_REVISIONS)
                } else {
                    revisions
                },
                games = current?.games.orEmpty(),
                finalGames = current?.finalGames.orEmpty(),
                updatedAtEpochMs = now
            )
            _records.value = _records.value + (key to next)
            next
        }
        schedulePersist(updated)
    }

    /** Append a real provider frame. We never synthesize an intermediate state. */
    fun observeLive(match: ScheduledEsportsMatch, snapshot: LiveSnapshot) {
        if (snapshot.game <= 0 || snapshot.elapsedSeconds < 0) return
        val key = keyFor(match)
        val now = System.currentTimeMillis()
        val updated = synchronized(lock) {
            val current = _records.value[key]
            val existing = current?.games?.get(snapshot.game).orEmpty()
            val sameSecondIndex = existing.indexOfLast { it.snapshot.elapsedSeconds == snapshot.elapsedSeconds }
            val nextFrames = when {
                sameSecondIndex >= 0 -> {
                    val mutable = existing.toMutableList()
                    mutable[sameSecondIndex] = MatchLifecycleFrame(now, snapshot)
                    mutable.takeLast(MAX_FRAMES_PER_GAME)
                }
                else -> (existing + MatchLifecycleFrame(now, snapshot)).takeLast(MAX_FRAMES_PER_GAME)
            }
            val nextGames = current?.games.orEmpty() + (snapshot.game to nextFrames)
            val next = MatchLifecycleRecord(
                key = key,
                match = match,
                phase = ScheduleMatchPhase.LIVE,
                scheduleRevisions = current?.scheduleRevisions.orEmpty().ifEmpty {
                    listOf(revisionFor(match, now))
                },
                games = nextGames,
                finalGames = current?.finalGames.orEmpty(),
                updatedAtEpochMs = now
            )
            _records.value = _records.value + (key to next)
            next
        }
        schedulePersist(updated)
    }

    /** Merge terminal truth without discarding previously captured process frames. */
    fun observeCompletedSeries(match: ScheduledEsportsMatch, series: CompletedSeriesSnapshot) {
        val key = keyFor(match)
        val now = System.currentTimeMillis()
        val finals = series.games.filter { it.game > 0 }.associateBy { it.game }
        val updated = synchronized(lock) {
            val current = _records.value[key]
            val next = MatchLifecycleRecord(
                key = key,
                match = match,
                phase = ScheduleMatchPhase.COMPLETED,
                scheduleRevisions = current?.scheduleRevisions.orEmpty().ifEmpty {
                    listOf(revisionFor(match, now))
                },
                games = current?.games.orEmpty(),
                finalGames = current?.finalGames.orEmpty() + finals,
                updatedAtEpochMs = now
            )
            _records.value = _records.value + (key to next)
            next
        }
        schedulePersist(updated)
    }

    fun find(
        match: ScheduledEsportsMatch,
        source: Map<String, MatchLifecycleRecord> = _records.value
    ): MatchLifecycleRecord? {
        source[keyFor(match)]?.let { return it }
        val wantedTeams = match.teams.take(2).map { teamToken(it.code.ifBlank { it.name }) }.toSet()
        if (wantedTeams.size < 2) return null
        return source.values
            .filter { record ->
                record.match.teams.take(2)
                    .map { teamToken(it.code.ifBlank { it.name }) }
                    .toSet() == wantedTeams
            }
            .maxByOrNull { it.updatedAtEpochMs }
    }

    fun keyFor(match: ScheduledEsportsMatch): String =
        match.eventId.trim().ifBlank { match.matchId.trim() }.ifBlank {
            val teams = match.teams.take(2).joinToString("_") { teamToken(it.code.ifBlank { it.name }) }
            "$teams@${match.startTimeIso}"
        }

    private fun phaseFor(match: ScheduledEsportsMatch): ScheduleMatchPhase {
        val state = match.state.trim().lowercase()
        return when {
            state.contains("completed") || state.contains("finished") -> ScheduleMatchPhase.COMPLETED
            state.contains("inprogress") || state.contains("in_progress") || state.contains("live") -> ScheduleMatchPhase.LIVE
            else -> ScheduleMatchPhase.UPCOMING
        }
    }

    private fun revisionFor(match: ScheduledEsportsMatch, now: Long): MatchScheduleRevision {
        val a = match.teams.getOrNull(0)
        val b = match.teams.getOrNull(1)
        return MatchScheduleRevision(
            capturedAtEpochMs = now,
            state = match.state,
            startTimeIso = match.startTimeIso,
            blockName = match.blockName,
            bestOf = match.bestOf,
            teamAGameWins = a?.gameWins ?: 0,
            teamBGameWins = b?.gameWins ?: 0,
            teamAOutcome = a?.outcome.orEmpty(),
            teamBOutcome = b?.outcome.orEmpty()
        )
    }

    private fun MatchScheduleRevision.samePayload(other: MatchScheduleRevision): Boolean =
        state == other.state &&
            startTimeIso == other.startTimeIso &&
            blockName == other.blockName &&
            bestOf == other.bestOf &&
            teamAGameWins == other.teamAGameWins &&
            teamBGameWins == other.teamBGameWins &&
            teamAOutcome == other.teamAOutcome &&
            teamBOutcome == other.teamBOutcome

    private fun schedulePersist(record: MatchLifecycleRecord) {
        if (rootDir == null) return
        persistJobs.remove(record.key)?.cancel()
        persistJobs[record.key] = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            runCatching { persist(record) }
            synchronized(lock) { persistJobs.remove(record.key) }
        }
    }

    private fun persist(record: MatchLifecycleRecord) {
        val dir = rootDir ?: return
        val safe = record.key.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(96).ifBlank { "match" }
        val suffix = record.key.hashCode().toUInt().toString(16)
        val file = File(dir, "match_${safe}_$suffix.json")
        val temp = File(dir, file.name + ".tmp")
        temp.writeText(recordToJson(record).toString())
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun loadFromDisk() {
        val dir = rootDir ?: return
        val loaded = linkedMapOf<String, MatchLifecycleRecord>()
        dir.listFiles { file -> file.isFile && file.name.startsWith("match_") && file.name.endsWith(".json") }
            .orEmpty()
            .forEach { file ->
                runCatching { recordFromJson(JSONObject(file.readText())) }.getOrNull()?.let { record ->
                    loaded[record.key] = record
                }
            }
        if (loaded.isNotEmpty()) {
            synchronized(lock) {
                _records.value = loaded + _records.value
            }
        }
    }

    private fun recordToJson(record: MatchLifecycleRecord): JSONObject = JSONObject()
        .put("key", record.key)
        .put("match", matchToJson(record.match))
        .put("phase", record.phase.name)
        .put("updatedAtEpochMs", record.updatedAtEpochMs)
        .put("scheduleRevisions", JSONArray().apply {
            record.scheduleRevisions.forEach { revision -> put(revisionToJson(revision)) }
        })
        .put("games", JSONObject().apply {
            record.games.forEach { (game, frames) ->
                put(game.toString(), JSONArray().apply {
                    frames.forEach { frame ->
                        put(
                            JSONObject()
                                .put("capturedAtEpochMs", frame.capturedAtEpochMs)
                                .put("snapshot", snapshotToJson(frame.snapshot))
                        )
                    }
                })
            }
        })
        .put("finalGames", JSONObject().apply {
            record.finalGames.forEach { (game, snapshot) ->
                put(game.toString(), snapshotToJson(snapshot))
            }
        })

    private fun recordFromJson(root: JSONObject): MatchLifecycleRecord {
        val gamesRoot = root.optJSONObject("games") ?: JSONObject()
        val games = linkedMapOf<Int, List<MatchLifecycleFrame>>()
        val gameKeys = gamesRoot.keys()
        while (gameKeys.hasNext()) {
            val gameKey = gameKeys.next()
            val game = gameKey.toIntOrNull() ?: continue
            val array = gamesRoot.optJSONArray(gameKey) ?: continue
            val frames = buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val snapshot = item.optJSONObject("snapshot")?.let(::snapshotFromJson) ?: continue
                    add(MatchLifecycleFrame(item.optLong("capturedAtEpochMs"), snapshot))
                }
            }
            games[game] = frames
        }

        val finalsRoot = root.optJSONObject("finalGames") ?: JSONObject()
        val finals = linkedMapOf<Int, LiveSnapshot>()
        val finalKeys = finalsRoot.keys()
        while (finalKeys.hasNext()) {
            val gameKey = finalKeys.next()
            val game = gameKey.toIntOrNull() ?: continue
            finalsRoot.optJSONObject(gameKey)?.let(::snapshotFromJson)?.let { finals[game] = it }
        }

        val revisionsArray = root.optJSONArray("scheduleRevisions") ?: JSONArray()
        val revisions = buildList {
            for (i in 0 until revisionsArray.length()) {
                revisionsArray.optJSONObject(i)?.let(::revisionFromJson)?.let(::add)
            }
        }

        return MatchLifecycleRecord(
            key = root.optString("key"),
            match = matchFromJson(root.getJSONObject("match")),
            phase = runCatching { ScheduleMatchPhase.valueOf(root.optString("phase")) }
                .getOrDefault(ScheduleMatchPhase.UPCOMING),
            scheduleRevisions = revisions,
            games = games,
            finalGames = finals,
            updatedAtEpochMs = root.optLong("updatedAtEpochMs")
        )
    }

    private fun matchToJson(match: ScheduledEsportsMatch): JSONObject = JSONObject()
        .put("eventId", match.eventId)
        .put("matchId", match.matchId)
        .put("league", match.league)
        .put("blockName", match.blockName)
        .put("startTimeIso", match.startTimeIso)
        .put("state", match.state)
        .put("bestOf", match.bestOf)
        .put("teams", JSONArray().apply { match.teams.forEach { put(teamToJson(it)) } })

    private fun matchFromJson(root: JSONObject): ScheduledEsportsMatch {
        val teamsArray = root.optJSONArray("teams") ?: JSONArray()
        val teams = buildList {
            for (i in 0 until teamsArray.length()) teamsArray.optJSONObject(i)?.let(::teamFromJson)?.let(::add)
        }
        return ScheduledEsportsMatch(
            eventId = root.optString("eventId"),
            matchId = root.optString("matchId"),
            league = root.optString("league"),
            blockName = root.optString("blockName"),
            startTimeIso = root.optString("startTimeIso"),
            state = root.optString("state"),
            bestOf = root.optInt("bestOf"),
            teams = teams
        )
    }

    private fun teamToJson(team: EsportsTeamRef): JSONObject = JSONObject()
        .put("id", team.id)
        .put("code", team.code)
        .put("name", team.name)
        .put("slug", team.slug)
        .put("imageUrl", team.imageUrl)
        .put("gameWins", team.gameWins)
        .put("outcome", team.outcome)
        .put("recordWins", team.recordWins)
        .put("recordLosses", team.recordLosses)

    private fun teamFromJson(root: JSONObject): EsportsTeamRef = EsportsTeamRef(
        id = root.optString("id"),
        code = root.optString("code"),
        name = root.optString("name"),
        slug = root.optString("slug"),
        imageUrl = root.optString("imageUrl"),
        gameWins = root.optInt("gameWins"),
        outcome = root.optString("outcome"),
        recordWins = root.optInt("recordWins"),
        recordLosses = root.optInt("recordLosses")
    )

    private fun revisionToJson(revision: MatchScheduleRevision): JSONObject = JSONObject()
        .put("capturedAtEpochMs", revision.capturedAtEpochMs)
        .put("state", revision.state)
        .put("startTimeIso", revision.startTimeIso)
        .put("blockName", revision.blockName)
        .put("bestOf", revision.bestOf)
        .put("teamAGameWins", revision.teamAGameWins)
        .put("teamBGameWins", revision.teamBGameWins)
        .put("teamAOutcome", revision.teamAOutcome)
        .put("teamBOutcome", revision.teamBOutcome)

    private fun revisionFromJson(root: JSONObject): MatchScheduleRevision = MatchScheduleRevision(
        capturedAtEpochMs = root.optLong("capturedAtEpochMs"),
        state = root.optString("state"),
        startTimeIso = root.optString("startTimeIso"),
        blockName = root.optString("blockName"),
        bestOf = root.optInt("bestOf"),
        teamAGameWins = root.optInt("teamAGameWins"),
        teamBGameWins = root.optInt("teamBGameWins"),
        teamAOutcome = root.optString("teamAOutcome"),
        teamBOutcome = root.optString("teamBOutcome")
    )

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
        bluePlayers = playersFromJson(root.optJSONArray("bluePlayers") ?: JSONArray()),
        redPlayers = playersFromJson(root.optJSONArray("redPlayers") ?: JSONArray()),
        latestEvent = root.optString("latestEvent"),
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
            val root = array.optJSONObject(i) ?: continue
            add(
                LivePlayerSnapshot(
                    participantId = root.optInt("participantId"),
                    role = root.optString("role"),
                    summonerName = root.optString("summonerName"),
                    championId = root.optString("championId"),
                    level = root.optInt("level"),
                    kills = root.optInt("kills"),
                    deaths = root.optInt("deaths"),
                    assists = root.optInt("assists"),
                    creepScore = root.optInt("creepScore"),
                    gold = root.optInt("gold")
                )
            )
        }
    }

    private fun teamToken(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
}
