package com.riftlab.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

class LolEsportsScheduleDataSource(
    private val client: LolEsportsApiClient = LolEsportsApiClient()
) : ScheduleDataSource {
    override suspend fun fetchLeagueSchedule(): List<ScheduledEsportsMatch> = client.fetchLplSchedule()
}

class LolEsportsLiveDataSource(
    private val client: LolEsportsApiClient = LolEsportsApiClient(),
    private val preferredTeamCodes: Set<String> = emptySet()
) : LiveMatchDataSource {

    private val _status = MutableStateFlow(
        LiveSourceStatus(
            phase = LiveSourcePhase.IDLE,
            message = "实时源尚未启动"
        )
    )
    val status: StateFlow<LiveSourceStatus> = _status.asStateFlow()

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
                    currentEvent = client.findLiveLplEvent(preferredTeamCodes)
                    if (currentEvent == null) {
                        delay(10_000)
                        continue
                    }
                }

                val event = currentEvent
                val game = client.fetchLiveGame(event)
                if (game == null || game.state.lowercase().contains("complete")) {
                    _status.value = LiveSourceStatus(
                        phase = LiveSourcePhase.BETWEEN_GAMES,
                        message = "系列赛已识别，等待下一局 Live Feed",
                        eventId = event.eventId,
                        gameId = currentGameId,
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    delay(5_000)
                    currentEvent = client.findLiveLplEvent(preferredTeamCodes) ?: event
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
                // Re-discover the live event after an error. This also handles a series moving to a new game id.
                currentEvent = null
            }
        }
    }
}
