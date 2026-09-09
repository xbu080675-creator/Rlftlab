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
import java.time.Instant

internal class LolEsportsScheduleDataSource(
    private val client: LolEsportsApiClient = LolEsportsApiClient()
) : ScheduleDataSource {
    override suspend fun fetchLeagueSchedule(): List<ScheduledEsportsMatch> {
        val matches = TeamAssetCatalog.enrichMatches(client.fetchGlobalSchedule().map(::verifySeriesCompletion))
        LiveMatchTargetRegistry.update(chooseLiveWatchTarget(matches))
        return matches
    }

    /**
     * Riot's schedule endpoint can transiently mark a not-yet-played series completed.
     * Treat the series score as the completion proof: BO5 requires 3 wins, BO3 requires 2.
     * Outcome flags are intentionally ignored because they can appear before play begins.
     */
    private fun verifySeriesCompletion(match: ScheduledEsportsMatch): ScheduledEsportsMatch {
        val normalized = normalizeState(match.state)
        val claimsCompleted = normalized.contains("complete") || normalized == "finished"
        if (!claimsCompleted) return match

        val requiredWins = if (match.bestOf > 0) match.bestOf / 2 + 1 else 1
        val maxGameWins = match.teams.maxOfOrNull { it.gameWins } ?: 0

        return if (maxGameWins >= requiredWins) {
            match
        } else {
            match.copy(state = "unstarted")
        }
    }

    private fun chooseLiveWatchTarget(matches: List<ScheduledEsportsMatch>): ScheduledEsportsMatch? {
        matches.firstOrNull { isLiveState(it.state) }?.let { return it }

        // Keep following a delayed/started series for up to 12 hours after its planned start.
        // This prevents a later TBD match from replacing today's event just because Schedule
        // has not advanced its state correctly.
        val staleCutoff = System.currentTimeMillis() - 12 * 60 * 60 * 1000L
        return matches.firstOrNull { match ->
            !isCompletedState(match.state) &&
                parseStart(match.startTimeIso)?.toEpochMilli()?.let { it >= staleCutoff } != false
        }
    }

    private fun normalizeState(value: String): String =
        value.lowercase().replace("_", "").replace("-", "").replace(" ", "")

    private fun isLiveState(value: String): Boolean {
        val state = normalizeState(value)
        return state.contains("progress") || state == "live"
    }

    private fun isCompletedState(match: ScheduledEsportsMatch): Boolean = isCompletedState(match.state)

    private fun isCompletedState(value: String): Boolean {
        val state = normalizeState(value)
        return state.contains("complete") || state == "finished"
    }

    private fun parseStart(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
}

internal class LolEsportsStandingsDataSource(
    private val client: LolEsportsStandingsClient = LolEsportsStandingsClient()
) : StandingsDataSource {
    override suspend fun fetchLeagueTournaments(): List<EsportsTournamentRef> = client.fetchLplTournaments()
    override suspend fun fetchStandings(tournamentId: String): TournamentStandings? =
        client.fetchTournamentStandings(tournamentId)
}

internal class LolEsportsTeamDataSource(
    private val client: LolEsportsApiClient = LolEsportsApiClient()
) : TeamDataSource {
    override suspend fun fetchTeam(slug: String): EsportsTeamDetails? = client.fetchTeamDetails(slug)
}

internal class LolEsportsLiveDataSource(
    private val client: LolEsportsApiClient = LolEsportsApiClient()
) : LiveMatchDataSource {

    private val _status = MutableStateFlow(
        LiveSourceStatus(
            phase = LiveSourcePhase.IDLE,
            message = "实时源尚未启动"
        )
    )
    val status: StateFlow<LiveSourceStatus> = _status.asStateFlow()

    /**
     * Riot's LPL EventDetails can keep every game as "unstarted" even while G1 is already live.
     * Therefore EventDetails supplies game ids/sides, while a non-empty LiveStats window proves
     * that the game has actually started. getLive and game.state remain hints/fallbacks only.
     */
    override fun observe(matchId: String): Flow<LiveSnapshot> = flow {
        var currentEvent: LiveEventRef? = null
        var knownGames: List<LiveGameRef> = emptyList()
        var currentGame: LiveGameRef? = null
        var currentGameId = ""
        var previous: LiveSnapshot? = null
        var lockedFromSchedule = false

        while (currentCoroutineContext().isActive) {
            try {
                if (currentEvent == null) {
                    val scheduled = LiveMatchTargetRegistry.snapshot()
                    if (scheduled != null && scheduled.eventId.isNotBlank()) {
                        currentEvent = LiveEventRef(
                            eventId = scheduled.eventId,
                            matchId = scheduled.matchId,
                            startTimeIso = scheduled.startTimeIso,
                            teams = scheduled.teams
                        )
                        lockedFromSchedule = true
                        _status.value = LiveSourceStatus(
                            phase = LiveSourcePhase.WAITING_FOR_MATCH,
                            message = "已锁定 ${teamLabel(scheduled)}，正在探测 Riot LiveStats",
                            eventId = scheduled.eventId,
                            lastUpdateEpochMs = System.currentTimeMillis()
                        )
                    } else {
                        lockedFromSchedule = false
                        _status.value = LiveSourceStatus(
                            phase = LiveSourcePhase.WAITING_FOR_MATCH,
                            message = "正在等待全球 LoL Esports 实时比赛…"
                        )
                        currentEvent = client.findLiveEvent(preferredMatchId = matchId)
                    }

                    knownGames = emptyList()
                    currentGame = null
                    currentGameId = ""
                    previous = null

                    if (currentEvent == null) {
                        delay(5_000)
                        continue
                    }
                }

                val event = currentEvent ?: continue
                if (knownGames.isEmpty()) {
                    knownGames = fetchEventGames(event)
                }

                // State is only a hint. Prefer it if Riot happens to update it correctly.
                val stateGame = knownGames.firstOrNull { isInProgress(it.state) }

                // Once a game is active, only probe the next numbered game for a newer window.
                val newerWindowGame = currentGame?.let { active ->
                    knownGames
                        .filter { it.gameNumber > active.gameNumber }
                        .minByOrNull { it.gameNumber }
                        ?.takeIf { hasAnyLiveFrames(it.gameId) }
                }

                val discovered = stateGame
                    ?: newerWindowGame
                    ?: currentGame
                    ?: findHighestStartedGame(knownGames)

                if (discovered == null) {
                    _status.value = LiveSourceStatus(
                        phase = LiveSourcePhase.WAITING_FOR_MATCH,
                        message = "赛事已锁定，等待 Riot LiveStats 出现有效游戏帧",
                        eventId = event.eventId,
                        gameId = "",
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    delay(2_000)
                    if (!lockedFromSchedule) {
                        currentEvent = client.findLiveEvent(preferredMatchId = matchId) ?: event
                    }
                    continue
                }

                if (currentGame == null || discovered.gameId != currentGameId) {
                    currentGame = discovered
                    currentGameId = discovered.gameId
                    previous = null
                }

                val game = currentGame ?: continue
                val snapshot = fetchLatestSnapshot(event, game, previous)
                previous = snapshot

                _status.value = LiveSourceStatus(
                    phase = LiveSourcePhase.LIVE,
                    message = "Riot LoL Esports Live · G${snapshot.game}",
                    eventId = event.eventId,
                    gameId = snapshot.gameId,
                    lastUpdateEpochMs = System.currentTimeMillis()
                )
                emit(snapshot)
                delay(3_000)
            } catch (t: Throwable) {
                _status.value = LiveSourceStatus(
                    phase = LiveSourcePhase.ERROR,
                    message = "实时源暂时不可用：${t.message?.take(120) ?: t::class.java.simpleName}",
                    eventId = currentEvent?.eventId.orEmpty(),
                    gameId = currentGameId,
                    lastUpdateEpochMs = System.currentTimeMillis()
                )
                delay(3_000)
                currentEvent = null
                knownGames = emptyList()
                currentGame = null
                currentGameId = ""
                previous = null
                lockedFromSchedule = false
            }
        }
    }

    private suspend fun fetchEventGames(event: LiveEventRef): List<LiveGameRef> {
        val root = getJson(
            "${LolEsportsConfig.PERSISTED_BASE}/getEventDetails?hl=en-US&id=${event.eventId}"
        )
        val match = root.optJSONObject("data")
            ?.optJSONObject("event")
            ?.optJSONObject("match") ?: return emptyList()
        val games = match.optJSONArray("games") ?: JSONArray()

        return buildList {
            for (i in 0 until games.length()) {
                val game = games.optJSONObject(i) ?: continue
                val gameId = game.optString("id")
                if (gameId.isBlank()) continue
                var blueId = ""
                var redId = ""
                val sides = game.optJSONArray("teams") ?: JSONArray()
                for (j in 0 until sides.length()) {
                    val side = sides.optJSONObject(j) ?: continue
                    when (side.optString("side").lowercase()) {
                        "blue" -> blueId = side.optString("id")
                        "red" -> redId = side.optString("id")
                    }
                }
                add(
                    LiveGameRef(
                        gameId = gameId,
                        gameNumber = game.optInt("number", i + 1),
                        state = game.optString("state"),
                        blueTeamId = blueId,
                        redTeamId = redId,
                        teams = event.teams
                    )
                )
            }
        }.sortedBy { it.gameNumber }
    }

    /** Find the highest-numbered game whose LiveStats endpoint has started returning frames. */
    private suspend fun findHighestStartedGame(games: List<LiveGameRef>): LiveGameRef? {
        for (game in games.sortedByDescending { it.gameNumber }) {
            if (hasAnyLiveFrames(game.gameId)) return game
        }
        return null
    }

    private suspend fun hasAnyLiveFrames(gameId: String): Boolean = try {
        val root = getJson("${LolEsportsConfig.LIVE_BASE}/window/$gameId")
        (root.optJSONArray("frames")?.length() ?: 0) > 0
    } catch (_: Throwable) {
        false
    }

    /**
     * The window endpoint is cursor based. Using Schedule.startTime is wrong for delayed starts
     * and currently returns no JSON for this LPL series. Query near wall-clock "now" instead,
     * with progressively wider safety lags, and accept the first meaningful current snapshot.
     */
    private suspend fun fetchLatestSnapshot(
        event: LiveEventRef,
        game: LiveGameRef,
        previous: LiveSnapshot?
    ): LiveSnapshot {
        var lastError: Throwable? = null
        val now = Instant.now()
        val lagsSeconds = longArrayOf(15, 30, 60, 120, 300, 600)

        for (lag in lagsSeconds) {
            val cursorEvent = event.copy(startTimeIso = now.minusSeconds(lag).toString())
            try {
                val snapshot = client.fetchLiveWindow(cursorEvent, game, previous)
                if (isMeaningful(snapshot)) return snapshot
            } catch (t: Throwable) {
                lastError = t
            }
        }

        // No-cursor response is useful as a final proof/debug fallback, but Riot can return
        // only initialization frames (all-zero stats), so never promote those to live data.
        try {
            val fallback = client.fetchLiveWindow(event.copy(startTimeIso = ""), game, previous)
            if (isMeaningful(fallback)) return fallback
        } catch (t: Throwable) {
            lastError = t
        }

        throw IOException("LiveStats game ${game.gameNumber} exists but no current meaningful frame yet", lastError)
    }

    private fun isMeaningful(snapshot: LiveSnapshot): Boolean =
        snapshot.blueGold > 0 ||
            snapshot.redGold > 0 ||
            snapshot.blueKills > 0 ||
            snapshot.redKills > 0 ||
            snapshot.bluePlayers.any { it.gold > 0 || it.creepScore > 0 || it.level > 1 } ||
            snapshot.redPlayers.any { it.gold > 0 || it.creepScore > 0 || it.level > 1 }

    private fun isInProgress(value: String): Boolean = value
        .lowercase()
        .replace("_", "")
        .replace("-", "")
        .contains("progress")

    private suspend fun getJson(url: String): JSONObject =
        RiotResilientHttp.getJson(url, connectTimeoutMs = 5_000, readTimeoutMs = 5_000)

    private fun teamLabel(match: ScheduledEsportsMatch): String =
        match.teams.take(2).joinToString(" vs ") { it.code.ifBlank { it.name } }
}
