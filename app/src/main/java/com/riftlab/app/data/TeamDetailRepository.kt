package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class TeamDetailState(
    val team: EsportsTeamRef? = null,
    val details: EsportsTeamDetails? = null,
    val loading: Boolean = false,
    val status: String = "选择一支战队查看详情",
    val errorMessage: String? = null
)

/** Independent team-detail navigation state for the event center. */
internal object TeamDetailRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val source: TeamDataSource = LolEsportsTeamDataSource()
    private val cache = linkedMapOf<String, EsportsTeamDetails>()
    private var loadJob: Job? = null

    private val _state = MutableStateFlow(TeamDetailState())
    val state: StateFlow<TeamDetailState> = _state.asStateFlow()

    fun open(team: EsportsTeamRef, forceRefresh: Boolean = false) {
        val key = team.slug.ifBlank { team.id }.ifBlank { team.code }
        val cached = cache[key]
        if (!forceRefresh && cached != null) {
            _state.value = TeamDetailState(
                team = team,
                details = cached,
                status = "Riot Teams · 阵容已加载"
            )
            return
        }

        loadJob?.cancel()
        _state.value = TeamDetailState(
            team = team,
            loading = true,
            status = "正在同步 ${team.code.ifBlank { team.name }} 战队资料…"
        )
        loadJob = scope.launch {
            val slug = team.slug.ifBlank { team.id }
            if (slug.isBlank()) {
                _state.value = TeamDetailState(
                    team = team,
                    loading = false,
                    status = "战队资料暂不可用",
                    errorMessage = "缺少 Riot team slug/id"
                )
                return@launch
            }

            val result = runCatching { source.fetchTeam(slug) }
            val details = result.getOrNull()
            if (details != null) cache[key] = details
            _state.value = TeamDetailState(
                team = team,
                details = details,
                loading = false,
                status = when {
                    details != null -> "Riot Teams · ${details.players.size} 名选手"
                    result.isFailure -> "战队资料同步失败"
                    else -> "Riot Teams 暂未返回战队详情"
                },
                errorMessage = result.exceptionOrNull()?.message
            )
        }
    }

    fun refresh() {
        _state.value.team?.let { open(it, forceRefresh = true) }
    }

    fun close() {
        loadJob?.cancel()
        _state.value = TeamDetailState()
    }
}
