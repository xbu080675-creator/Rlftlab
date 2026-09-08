package com.riftlab.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

internal class LolEsportsScheduleDataSource(
    private val client: LolEsportsApiClient = LolEsportsApiClient()
) : ScheduleDataSource {
    override suspend fun fetchLeagueSchedule(): List<ScheduledEsportsMatch> = client.fetchLplSchedule()
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
     * matchId is an optional preference, not a clock gate. If blank, follow whichever LPL
     * series Riot currently exposes through getLive. This keeps early starts discoverable.
     */
    override fun observe(matchId: String): Flow<LiveSnapshot> = flow {
        var currentEvent: LiveEventRef? = null
        var currentGameId = ""
        var previous: LiveSnapshot? = null

        while (currentCoroutineContext().isActive) {
            try {
                if (currentEvent == null) {
                    _status.value = LiveSourceStatus(
                        phase = LiveSourcePhase.WAITING_FOR_MATCH,
                        message = "正在等待 LPL 实时比赛…"
                    )
                    currentEvent = client.findLiveLplEvent(preferredMatchId = matchId)
                    if (currentEvent == null) {
                        delay(10_000)
                        continue
                    }
                }

                val event = currentEvent ?: continue
                val game = client.fetchLiveGame(event)
                if (game == null) {
                    _status.value = LiveSourceStatus(
                        phase = LiveSourcePhase.BETWEEN_GAMES,
                        message = "系列赛已识别，等待下一局 Live Feed",
                        eventId = event.eventId,
                        gameId = currentGameId,
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    delay(5_000)
                    currentEvent = client.findLiveLplEvent(preferredMatchId = matchId) ?: event
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
                delay(5_000)
                currentEvent = null
            }
        }
    }
}
