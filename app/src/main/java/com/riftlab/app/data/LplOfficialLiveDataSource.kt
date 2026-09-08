package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Match-agnostic live provider router.
 *
 * Provider order is a policy, not a match special-case:
 * 1) Tencent/LPL comm-match-app realtime data plane
 * 2) TJStats matchDetail current-game fallback
 * 3) Riot LiveStats generic fallback
 *
 * More community/self-hosted providers can be inserted without changing MatchSessionStore or UI.
 */
internal class LplOfficialLiveDataSource : LiveMatchDataSource {

    private data class Provider(
        val name: String,
        val priority: Int,
        val source: LiveMatchDataSource,
        val status: StateFlow<LiveSourceStatus>
    )

    private val commRealtime = LplCommRealtimeDataSource()
    private val lplMatchDetail = LplCurrentGameLiveDataSource()
    private val riotFallback = LolEsportsLiveDataSource()

    private val providers = listOf(
        Provider("LPL Comm Realtime", 0, commRealtime, commRealtime.status),
        Provider("LPL MatchDetail", 20, lplMatchDetail, lplMatchDetail.status),
        Provider("Riot fallback", 100, riotFallback, riotFallback.status)
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val providerStatuses = providers.associate { it.name to it.status.value }.toMutableMap()

    private val _status = MutableStateFlow(
        LiveSourceStatus(LiveSourcePhase.IDLE, "Live Provider Router 尚未启动")
    )
    val status: StateFlow<LiveSourceStatus> = _status.asStateFlow()

    init {
        providers.forEach { provider ->
            scope.launch {
                provider.status.collect { next ->
                    synchronized(lock) {
                        providerStatuses[provider.name] = next
                        _status.value = chooseRouterStatusLocked()
                    }
                }
            }
        }
    }

    override fun observe(matchId: String): Flow<LiveSnapshot> = channelFlow {
        val snapshots = mutableMapOf<String, LiveSnapshot>()
        var lastEmissionKey = ""

        providers.forEach { provider ->
            launch {
                provider.source.observe(matchId).collect { snapshot ->
                    var chosen: LiveSnapshot? = null
                    var chosenProvider: Provider? = null

                    synchronized(lock) {
                        snapshots[provider.name] = snapshot
                        val now = System.currentTimeMillis()
                        val best = providers
                            .filter { candidate ->
                                val status = providerStatuses[candidate.name]
                                status?.phase == LiveSourcePhase.LIVE &&
                                    status.lastUpdateEpochMs > 0L &&
                                    now - status.lastUpdateEpochMs <= LIVE_STATUS_STALE_MS &&
                                    snapshots[candidate.name] != null
                            }
                            .minByOrNull { it.priority }

                        if (best != null) {
                            val candidate = snapshots[best.name]
                            if (candidate != null) {
                                val key = buildString {
                                    append(best.name).append('|')
                                    append(candidate.gameId).append('|')
                                    append(candidate.elapsedSeconds).append('|')
                                    append(candidate.blueGold).append('|').append(candidate.redGold).append('|')
                                    append(candidate.blueKills).append('|').append(candidate.redKills)
                                }
                                if (key != lastEmissionKey) {
                                    lastEmissionKey = key
                                    chosenProvider = best
                                    chosen = candidate.copy(
                                        source = "${candidate.source} · Router=${best.name}"
                                    )
                                }
                            }
                        }
                    }

                    val output = chosen
                    if (output != null) {
                        trySend(output)
                        val p = chosenProvider
                        if (p != null) {
                            synchronized(lock) {
                                val providerStatus = providerStatuses[p.name]
                                if (providerStatus != null) {
                                    _status.value = providerStatus.copy(
                                        message = "ROUTER → ${p.name} · ${providerStatus.message}"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        awaitClose { }
    }

    private fun chooseRouterStatusLocked(): LiveSourceStatus {
        val now = System.currentTimeMillis()
        val live = providers.firstOrNull { provider ->
            val s = providerStatuses[provider.name]
            s?.phase == LiveSourcePhase.LIVE &&
                s.lastUpdateEpochMs > 0L &&
                now - s.lastUpdateEpochMs <= LIVE_STATUS_STALE_MS
        }
        if (live != null) {
            val s = providerStatuses.getValue(live.name)
            return s.copy(message = "ROUTER → ${live.name} · ${s.message}")
        }

        val phaseOrder = listOf(
            LiveSourcePhase.BETWEEN_GAMES,
            LiveSourcePhase.WAITING_FOR_MATCH,
            LiveSourcePhase.ERROR,
            LiveSourcePhase.IDLE
        )
        for (phase in phaseOrder) {
            val provider = providers.firstOrNull { providerStatuses[it.name]?.phase == phase } ?: continue
            val s = providerStatuses.getValue(provider.name)
            return s.copy(message = "ROUTER · ${provider.name} · ${s.message}")
        }

        return LiveSourceStatus(LiveSourcePhase.IDLE, "Live Provider Router 等待数据源")
    }

    companion object {
        private const val LIVE_STATUS_STALE_MS = 15_000L
    }
}
