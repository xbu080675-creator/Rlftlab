package com.riftlab.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.time.Instant

/**
 * Keeps the schedule-selected live-watch target inside the data layer.
 * The live feed must not depend on getLive exposing LPL in time: when Schedule already knows
 * an eventId, EventDetails becomes the primary game-start detector and getLive is only fallback.
 */
private object LiveScheduleTargetRegistry {
    @Volatile
    var target: ScheduledEsportsMatch? = null
}

internal class LolEsportsScheduleDataSource(
    private val client: LolEsportsApiClient = LolEsportsApiClient()
) : ScheduleDataSource {
    override suspend fun fetchLeagueSchedule(): List<ScheduledEsportsMatch> {
        val matches = client.fetchLplSchedule().map(::verifySeriesCompletion)
        LiveScheduleTargetRegistry.target = chooseLiveWatchTarget(matches)
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
     * Schedule eventId is the primary discovery path. getLive is fallback only.
     * Once an event is known, poll getEventDetails directly for games[].state=inProgress.
     */
    override fun observe(matchId: String): Flow<LiveSnapshot> = flow {
        var currentEvent: LiveEventRef? = null
        var currentGameId = ""
        var previous: LiveSnapshot? = null
        var lockedFromSchedule = false

        while (currentCoroutineContext().isActive) {
            try {
                if (currentEvent == null) {
                    val scheduled = LiveScheduleTargetRegistry.target
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
                            message = "已锁定 ${teamLabel(scheduled)}，直连 Riot EventDetails 等待 G1",
                            eventId = scheduled.eventId,
                            lastUpdateEpochMs = System.currentTimeMillis()
                        )
                    } else {
                        lockedFromSchedule = false
                        _status.value = LiveSourceStatus(
                            phase = LiveSourcePhase.WAITING_FOR_MATCH,
                            message = "正在等待 LPL 实时比赛…"
                        )
                        currentEvent = client.findLiveLplEvent(preferredMatchId = matchId)
                    }

                    if (currentEvent == null) {
                        delay(5_000)
                        continue
                    }
                }

                val event = currentEvent ?: continue
                val game = client.fetchLiveGame(event)
                if (game == null) {
                    val beforeFirstGame = currentGameId.isBlank()
                    _status.value = LiveSourceStatus(
                        phase = if (beforeFirstGame) LiveSourcePhase.WAITING_FOR_MATCH else LiveSourcePhase.BETWEEN_GAMES,
                        message = if (beforeFirstGame) {
                            "赛事已锁定，正在直连 Riot EventDetails 等待 G1"
                        } else {
                            "上一局已结束，等待下一局 Live Feed"
                        },
                        eventId = event.eventId,
                        gameId = currentGameId,
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    // Known event: game-start detection should be fast and must not wait for getLive.
                    delay(2_000)
                    if (!lockedFromSchedule) {
                        currentEvent = client.findLiveLplEvent(preferredMatchId = matchId) ?: event
                    }
                    continue
                }

                if (game.gameId != currentGameId) {
                    currentGameId = game.gameId
                    previous = null
                }

                val snapshot = client.fetchLiveWindow(event, game, previous)
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
                lockedFromSchedule = false
            }
        }
    }

    private fun teamLabel(match: ScheduledEsportsMatch): String =
        match.teams.take(2).joinToString(" vs ") { it.code.ifBlank { it.name } }
}
