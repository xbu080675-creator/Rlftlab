package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stable identity shared by schedule, post-match and MVP/vote/BP providers. */
data class MatchDetailKey(
    val riotEventId: String,
    val riotMatchId: String,
    val startTimeIso: String,
    val teamAId: String,
    val teamBId: String
) {
    val stableId: String
        get() = riotEventId.ifBlank { riotMatchId }.ifBlank {
            listOf(teamAId, teamBId, startTimeIso).joinToString("|")
        }

    companion object {
        fun from(match: ScheduledEsportsMatch): MatchDetailKey = MatchDetailKey(
            riotEventId = match.eventId,
            riotMatchId = match.matchId,
            startTimeIso = match.startTimeIso,
            teamAId = match.teams.getOrNull(0)?.id.orEmpty(),
            teamBId = match.teams.getOrNull(1)?.id.orEmpty()
        )
    }
}

data class OfficialMvpRecord(
    val game: Int?,
    val playerName: String,
    val team: String,
    val role: String = "",
    val source: String
)

data class VoteOptionRecord(
    val label: String,
    val votes: Long,
    val percent: Double? = null
)

data class OfficialVoteRecord(
    val title: String,
    val options: List<VoteOptionRecord>,
    val totalVotes: Long? = null,
    val source: String
)

data class DraftPickRecord(
    val game: Int,
    val blueBans: List<String> = emptyList(),
    val redBans: List<String> = emptyList(),
    val bluePicks: List<String> = emptyList(),
    val redPicks: List<String> = emptyList(),
    val source: String
)

data class MatchDetailState(
    val key: MatchDetailKey? = null,
    val match: ScheduledEsportsMatch? = null,
    val loading: Boolean = false,
    val series: CompletedSeriesSnapshot? = null,
    val liveGame: LiveSnapshot? = null,
    val seriesMvp: OfficialMvpRecord? = null,
    val gameMvps: List<OfficialMvpRecord> = emptyList(),
    val votes: List<OfficialVoteRecord> = emptyList(),
    val drafts: List<DraftPickRecord> = emptyList(),
    val status: String = "选择一场比赛查看详情",
    val errorMessage: String? = null,
    val updatedAtEpochMs: Long = 0L
)

/**
 * Match detail has its own selection state. Opening a historical match must never mutate the main
 * viewing target, which keeps following the current/next series independently.
 */
object MatchDetailRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val resolver = LplHistoricalPostMatchResolver()
    private val awardsProvider = LplOfficialAwardsProvider()
    private val resolverMutex = Mutex()
    private val cache = linkedMapOf<String, MatchDetailState>()
    private var loadJob: Job? = null

    private val _state = MutableStateFlow(MatchDetailState())
    val state: StateFlow<MatchDetailState> = _state.asStateFlow()

    fun open(match: ScheduledEsportsMatch, forceRefresh: Boolean = false) {
        val key = MatchDetailKey.from(match)
        val cached = cache[key.stableId]
        if (!forceRefresh && cached != null) {
            _state.value = cached.copy(match = match, key = key)
            return
        }

        loadJob?.cancel()
        loadJob = scope.launch {
            val phase = MatchSessionStore.schedulePhase(match)
            val base = MatchDetailState(
                key = key,
                match = match,
                loading = phase == ScheduleMatchPhase.COMPLETED,
                liveGame = if (phase == ScheduleMatchPhase.LIVE) currentLiveFor(match) else null,
                status = when (phase) {
                    ScheduleMatchPhase.UPCOMING -> "比赛尚未开始 · 当前展示赛程与赛前元数据"
                    ScheduleMatchPhase.LIVE -> "比赛进行中 · 当前局实时数据由赛中 Provider Router 提供"
                    ScheduleMatchPhase.COMPLETED -> "正在加载该场历史终局数据与官方 MVP / 投票…"
                },
                updatedAtEpochMs = System.currentTimeMillis()
            )
            _state.value = base

            if (phase != ScheduleMatchPhase.COMPLETED) {
                cache[key.stableId] = base
                return@launch
            }

            val result = runCatching {
                resolverMutex.withLock { resolver.resolve(match) }
            }
            val resolved = result.getOrNull()
            val bmid = resolved?.matchKey
                ?.takeIf { it.startsWith("TJ:") }
                ?.removePrefix("TJ:")
                .orEmpty()
            val awards = if (bmid.isNotBlank()) {
                runCatching { awardsProvider.fetch(bmid) }.getOrElse {
                    OfficialAwardsResult(status = "MVP / 投票同步失败 · ${it.message?.take(100) ?: it::class.java.simpleName}")
                }
            } else {
                OfficialAwardsResult(status = "MVP / 投票等待历史 bMatchId")
            }

            val finalState = base.copy(
                loading = false,
                series = resolved,
                seriesMvp = awards.seriesMvp,
                gameMvps = awards.gameMvps,
                votes = awards.votes,
                status = when {
                    resolved != null -> "已加载 ${resolved.games.size} 局终局数据 · ${awards.status}"
                    result.isFailure -> "比赛详情同步失败"
                    else -> resolver.status.value
                },
                errorMessage = result.exceptionOrNull()?.message,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            cache[key.stableId] = finalState
            _state.value = finalState
        }
    }

    fun refresh() {
        _state.value.match?.let { open(it, forceRefresh = true) }
    }

    fun close() {
        loadJob?.cancel()
        _state.value = MatchDetailState()
    }

    private fun currentLiveFor(match: ScheduledEsportsMatch): LiveSnapshot? {
        val current = MatchSessionStore.scheduleCenter.value.currentMatch ?: return null
        val same = current.matchId == match.matchId ||
            (current.eventId.isNotBlank() && current.eventId == match.eventId)
        return MatchSessionStore.live.value.takeIf { same && it.game > 0 }
    }
}
