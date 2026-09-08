package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Stable identity shared by schedule, post-match and future MVP/vote/BP providers. */
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

data class MatchDetailState(
    val key: MatchDetailKey? = null,
    val match: ScheduledEsportsMatch? = null,
    val loading: Boolean = false,
    val series: CompletedSeriesSnapshot? = null,
    val liveGame: LiveSnapshot? = null,
    val seriesMvp: OfficialMvpRecord? = null,
    val gameMvps: List<OfficialMvpRecord> = emptyList(),
    val votes: List<OfficialVoteRecord> = emptyList(),
    val status: String = "选择一场比赛查看详情",
    val updatedAtEpochMs: Long = 0L
)

/**
 * Match detail is keyed by the schedule entry, not by "the latest match".
 *
 * Lightweight schedule data is always available immediately. Heavy post-match data is fetched
 * on demand and cached per match. MVP/vote/BP providers can be attached here later without
 * changing schedule navigation or the detail UI contract.
 */
object MatchDetailRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val resolver = LplHistoricalPostMatchResolver()
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
                    ScheduleMatchPhase.COMPLETED -> "正在加载该场历史终局数据…"
                },
                updatedAtEpochMs = System.currentTimeMillis()
            )
            _state.value = base

            if (phase != ScheduleMatchPhase.COMPLETED) {
                cache[key.stableId] = base
                return@launch
            }

            runCatching { resolver.refresh(match) }
            val resolved = CompletedGameArchive.series.value?.takeIf { seriesMatches(it, match) }
            val finalState = base.copy(
                loading = false,
                series = resolved,
                status = if (resolved != null) {
                    "已加载 ${resolved.games.size} 局终局数据 · ${resolved.source}"
                } else {
                    resolver.status.value
                },
                updatedAtEpochMs = System.currentTimeMillis()
            )
            cache[key.stableId] = finalState
            _state.value = finalState
        }
    }

    fun refresh() {
        _state.value.match?.let { open(it, forceRefresh = true) }
    }

    private fun currentLiveFor(match: ScheduledEsportsMatch): LiveSnapshot? {
        val current = MatchSessionStore.scheduleCenter.value.currentMatch ?: return null
        val same = current.matchId == match.matchId || current.eventId == match.eventId
        return MatchSessionStore.live.value.takeIf { same && it.game > 0 }
    }

    private fun seriesMatches(series: CompletedSeriesSnapshot, match: ScheduledEsportsMatch): Boolean {
        val wanted = match.teams.take(2)
            .map { teamKey(it.code.ifBlank { it.name }) }
            .filter { it.isNotBlank() }
            .toSet()
        if (wanted.size < 2) return false
        val actual = setOf(teamKey(series.teamA), teamKey(series.teamB))
        return actual == wanted || wanted.all { target -> actual.any { actualKey -> actualKey.contains(target) || target.contains(actualKey) } }
    }

    private fun teamKey(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
}
