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
    val imageUrl: String = "",
    val loading: Boolean = false,
    val status: String = "选择一支战队查看详情",
    val errorMessage: String? = null
)

/** Independent team-detail navigation state for the event center. */
internal object TeamDetailRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val source: TeamDataSource = LolEsportsTeamDataSource()
    private val assetProvider = RiotTeamAssetProvider()
    private val cache = linkedMapOf<String, EsportsTeamDetails>()
    private val imageCache = linkedMapOf<String, String>()
    private var loadJob: Job? = null

    private val _state = MutableStateFlow(TeamDetailState())
    val state: StateFlow<TeamDetailState> = _state.asStateFlow()

    fun open(team: EsportsTeamRef, forceRefresh: Boolean = false) {
        val key = team.slug.ifBlank { team.id }.ifBlank { team.code }
        val cached = cache[key]
        val cachedImage = team.imageUrl.ifBlank { imageCache[key].orEmpty() }
        if (!forceRefresh && cached != null && cachedImage.isNotBlank()) {
            _state.value = TeamDetailState(
                team = team,
                details = cached,
                imageUrl = cachedImage,
                status = "Riot Teams · 阵容与队标已加载"
            )
            return
        }

        loadJob?.cancel()
        _state.value = TeamDetailState(
            team = team,
            imageUrl = cachedImage,
            loading = true,
            status = "正在同步 ${team.code.ifBlank { team.name }} 战队资料…"
        )
        loadJob = scope.launch {
            val slug = team.slug.ifBlank { team.id }
            if (slug.isBlank()) {
                _state.value = TeamDetailState(
                    team = team,
                    imageUrl = runCatching { assetProvider.resolve(team) }.getOrDefault(cachedImage),
                    loading = false,
                    status = "战队资料暂不可用",
                    errorMessage = "缺少 Riot team slug/id"
                )
                return@launch
            }

            val detailResult = runCatching { source.fetchTeam(slug) }
            val details = detailResult.getOrNull()
            val image = team.imageUrl.ifBlank {
                runCatching { assetProvider.resolve(team) }.getOrDefault("")
            }
            if (details != null) cache[key] = details
            if (image.isNotBlank()) imageCache[key] = image
            _state.value = TeamDetailState(
                team = team,
                details = details,
                imageUrl = image,
                loading = false,
                status = when {
                    details != null && image.isNotBlank() -> "Riot Teams · ${details.players.size} 名选手 · 队标已连接"
                    details != null -> "Riot Teams · ${details.players.size} 名选手 · 队标暂未返回"
                    detailResult.isFailure -> "战队资料同步失败"
                    else -> "Riot Teams 暂未返回战队详情"
                },
                errorMessage = detailResult.exceptionOrNull()?.message
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
