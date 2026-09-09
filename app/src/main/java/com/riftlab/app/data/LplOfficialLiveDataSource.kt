package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * High-level lifecycle is deliberately separate from provider health.
 *
 * EVENT_LIVE means the broadcast/series has started; GAME_LIVE is only entered after a real,
 * meaningful game frame exists. This prevents the old failure mode where an API saying "live"
 * left RiftLab stuck forever waiting for a gameId/frame while the event was already on air.
 */
enum class LiveLifecycleStage {
    SCHEDULED,
    EVENT_LIVE,
    PRE_GAME,
    DRAFT,
    GAME_LOADING,
    GAME_LIVE,
    BETWEEN_GAMES,
    SERIES_FINISHED,
    DEGRADED
}

data class LiveLifecycleState(
    val stage: LiveLifecycleStage,
    val message: String,
    val eventId: String = "",
    val gameId: String = "",
    val provider: String = "",
    val sinceEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Global, match-agnostic live provider router.
 *
 * Provider order is a policy, not a match special-case:
 * 1) Tencent/LPL comm-match-app realtime data plane (LPL only)
 * 2) Riot LoL Esports LiveStats window feed (global official continuous frames)
 * 3) Cito API WSS / quota-aware REST fallback (global, only when a key is configured)
 * 4) TJStats matchDetail current/final-frame fallback (LPL only)
 *
 * All eligible providers run in parallel. A provider that stalls cannot block the state machine;
 * after EVENT_LIVE has no meaningful frame for EVENT_TO_FRAME_TIMEOUT_MS, RiftLab enters
 * GAME_LOADING and keeps probing every provider instead of showing an endless "解析中" state.
 */
internal class GlobalOfficialLiveDataSource : LiveMatchDataSource {

    private data class Provider(
        val name: String,
        val priority: Int,
        val source: LiveMatchDataSource,
        val status: StateFlow<LiveSourceStatus>,
        val lplOnly: Boolean = false
    )

    private val commRealtime = LplCommRealtimeDataSource()
    private val riotLiveStats = LolEsportsLiveDataSource()
    private val citoLive = CitoLiveDataSource()
    private val lplMatchDetail = LplCurrentGameLiveDataSource()

    private val providers = listOf(
        Provider("LPL Comm Realtime", 0, commRealtime, commRealtime.status, lplOnly = true),
        Provider("Riot LiveStats", 10, riotLiveStats, riotLiveStats.status),
        Provider("Cito API", 20, citoLive, citoLive.status),
        Provider("LPL MatchDetail", 30, lplMatchDetail, lplMatchDetail.status, lplOnly = true)
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val providerStatuses = providers.associate { it.name to it.status.value }.toMutableMap()
    private val providerSnapshots = mutableMapOf<String, LiveSnapshot>()

    private var targetKey = ""
    private var eventLiveSinceEpochMs = 0L
    private var lastMeaningfulFrameEpochMs = 0L
    private var lastChosenProvider = ""

    private val _status = MutableStateFlow(
        LiveSourceStatus(LiveSourcePhase.IDLE, "Live Provider Router 尚未启动")
    )
    val status: StateFlow<LiveSourceStatus> = _status.asStateFlow()

    private val _lifecycle = MutableStateFlow(
        LiveLifecycleState(LiveLifecycleStage.SCHEDULED, "等待赛事")
    )
    val lifecycle: StateFlow<LiveLifecycleState> = _lifecycle.asStateFlow()

    init {
        providers.forEach { provider ->
            scope.launch {
                provider.status.collect { next ->
                    synchronized(lock) {
                        providerStatuses[provider.name] = next
                        refreshLifecycleLocked()
                    }
                }
            }
        }

        // Provider status callbacks are not guaranteed to arrive while a source is wedged.
        // The watchdog keeps lifecycle/failover decisions moving even when an upstream is silent.
        scope.launch {
            while (isActive) {
                synchronized(lock) { refreshLifecycleLocked() }
                delay(WATCHDOG_TICK_MS)
            }
        }
    }

    override fun observe(matchId: String): Flow<LiveSnapshot> = channelFlow {
        var lastEmissionKey = ""

        providers.forEach { provider ->
            launch {
                provider.source.observe(matchId).collect { snapshot ->
                    var chosen: LiveSnapshot? = null
                    var chosenProvider: Provider? = null

                    synchronized(lock) {
                        providerSnapshots[provider.name] = snapshot
                        val now = System.currentTimeMillis()
                        val best = eligibleProvidersLocked()
                            .filter { candidate ->
                                val status = providerStatuses[candidate.name]
                                status?.phase == LiveSourcePhase.LIVE &&
                                    status.lastUpdateEpochMs > 0L &&
                                    now - status.lastUpdateEpochMs <= LIVE_STATUS_STALE_MS &&
                                    providerSnapshots[candidate.name]?.let(::isMeaningful) == true
                            }
                            .minByOrNull { it.priority }

                        if (best != null) {
                            val candidate = providerSnapshots[best.name]
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
                                    lastMeaningfulFrameEpochMs = now
                                    lastChosenProvider = best.name
                                    chosenProvider = best
                                    chosen = candidate.copy(
                                        source = "${candidate.source} · Router=${best.name}"
                                    )
                                }
                            }
                        }

                        refreshLifecycleLocked()
                    }

                    chosen?.let { output ->
                        trySend(output)
                        chosenProvider?.let { p ->
                            synchronized(lock) {
                                val providerStatus = providerStatuses[p.name]
                                if (providerStatus != null) {
                                    _status.value = providerStatus.copy(
                                        message = "GAME LIVE · ROUTER → ${p.name} · ${providerStatus.message}"
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

    private fun refreshLifecycleLocked() {
        val now = System.currentTimeMillis()
        val target = LiveMatchTargetRegistry.snapshot()
        val nextTargetKey = target?.eventId?.ifBlank { target.matchId }.orEmpty()
        if (nextTargetKey != targetKey) {
            targetKey = nextTargetKey
            eventLiveSinceEpochMs = 0L
            lastMeaningfulFrameEpochMs = 0L
            lastChosenProvider = ""
            providerSnapshots.clear()
        }

        if (target == null) {
            setLifecycleLocked(LiveLifecycleStage.SCHEDULED, "等待赛事目标", now = now)
            _status.value = chooseProviderStatusLocked("SCHEDULED")
            return
        }

        if (isCompleted(target.state)) {
            setLifecycleLocked(
                LiveLifecycleStage.SERIES_FINISHED,
                "SERIES FINISHED · ${teamLabel(target)}",
                eventId = target.eventId,
                now = now
            )
            _status.value = LiveSourceStatus(
                LiveSourcePhase.IDLE,
                "SERIES FINISHED · ${teamLabel(target)}",
                eventId = target.eventId,
                lastUpdateEpochMs = now
            )
            return
        }

        val freshLiveProvider = eligibleProvidersLocked().firstOrNull { provider ->
            val s = providerStatuses[provider.name]
            s?.phase == LiveSourcePhase.LIVE &&
                s.lastUpdateEpochMs > 0L &&
                now - s.lastUpdateEpochMs <= LIVE_STATUS_STALE_MS &&
                providerSnapshots[provider.name]?.let(::isMeaningful) == true
        }

        if (freshLiveProvider != null) {
            val snapshot = providerSnapshots.getValue(freshLiveProvider.name)
            lastMeaningfulFrameEpochMs = maxOf(lastMeaningfulFrameEpochMs, now)
            lastChosenProvider = freshLiveProvider.name
            if (eventLiveSinceEpochMs == 0L) eventLiveSinceEpochMs = now
            setLifecycleLocked(
                LiveLifecycleStage.GAME_LIVE,
                "GAME LIVE · G${snapshot.game} · ${freshLiveProvider.name}",
                eventId = target.eventId,
                gameId = snapshot.gameId,
                provider = freshLiveProvider.name,
                now = now
            )
            _status.value = providerStatuses.getValue(freshLiveProvider.name).copy(
                message = "GAME LIVE · ROUTER → ${freshLiveProvider.name} · ${providerStatuses.getValue(freshLiveProvider.name).message}"
            )
            return
        }

        val between = eligibleProvidersLocked().firstOrNull {
            providerStatuses[it.name]?.phase == LiveSourcePhase.BETWEEN_GAMES
        }
        if (between != null) {
            if (eventLiveSinceEpochMs == 0L) eventLiveSinceEpochMs = now
            setLifecycleLocked(
                LiveLifecycleStage.BETWEEN_GAMES,
                "BETWEEN GAMES · ${between.name} · 等待下一小局",
                eventId = target.eventId,
                provider = between.name,
                now = now
            )
            _status.value = providerStatuses.getValue(between.name).copy(
                message = "BETWEEN GAMES · ${providerStatuses.getValue(between.name).message}"
            )
            return
        }

        val statusText = eligibleProvidersLocked()
            .mapNotNull { providerStatuses[it.name]?.message }
            .joinToString(" · ")
            .lowercase()
        val draftSignal = listOf("draft", "ban/pick", "ban pick", "bp", "选人", "禁用").any { it in statusText }
        val startedBySchedule = isLive(target.state) || plannedStartReached(target, now)

        if (startedBySchedule) {
            if (eventLiveSinceEpochMs == 0L) eventLiveSinceEpochMs = now
            val waited = now - eventLiveSinceEpochMs
            when {
                draftSignal -> setLifecycleLocked(
                    LiveLifecycleStage.DRAFT,
                    "DRAFT · 已确认 BP/选人信号 · 并行等待游戏帧",
                    eventId = target.eventId,
                    now = now
                )
                waited < EVENT_TO_FRAME_TIMEOUT_MS -> setLifecycleLocked(
                    LiveLifecycleStage.PRE_GAME,
                    "EVENT LIVE · PRE-GAME · 多源并行等待 gameId/有效帧",
                    eventId = target.eventId,
                    now = now
                )
                allEligibleProvidersUnhealthyLocked(now) -> setLifecycleLocked(
                    LiveLifecycleStage.DEGRADED,
                    "EVENT LIVE · 数据源降级 · 不阻塞赛事状态，持续自动重试",
                    eventId = target.eventId,
                    now = now
                )
                else -> setLifecycleLocked(
                    LiveLifecycleStage.GAME_LOADING,
                    "EVENT LIVE · GAME LOADING · 20s 无有效帧，已升级多源探测",
                    eventId = target.eventId,
                    now = now
                )
            }

            // WAITING is intentional: event is live, but we refuse to call the game LIVE until a
            // meaningful telemetry frame exists. MatchSessionStore reads lifecycle separately.
            _status.value = chooseProviderStatusLocked(_lifecycle.value.message).copy(
                phase = LiveSourcePhase.WAITING_FOR_MATCH,
                eventId = target.eventId.ifBlank { _status.value.eventId },
                message = _lifecycle.value.message,
                lastUpdateEpochMs = now
            )
            return
        }

        eventLiveSinceEpochMs = 0L
        val startLabel = target.startTimeIso.takeIf { it.isNotBlank() } ?: "时间待定"
        setLifecycleLocked(
            LiveLifecycleStage.SCHEDULED,
            "SCHEDULED · $startLabel",
            eventId = target.eventId,
            now = now
        )
        _status.value = chooseProviderStatusLocked("SCHEDULED · 等待赛事开始")
    }

    private fun chooseProviderStatusLocked(prefix: String): LiveSourceStatus {
        val now = System.currentTimeMillis()
        val eligible = eligibleProvidersLocked()
        val useful = eligible.firstOrNull { provider ->
            val s = providerStatuses[provider.name]
            s != null && s.phase != LiveSourcePhase.IDLE &&
                (s.lastUpdateEpochMs == 0L || now - s.lastUpdateEpochMs <= PROVIDER_STATUS_MAX_AGE_MS)
        }
        val s = useful?.let { providerStatuses[it.name] }
        return if (s != null && useful != null) {
            s.copy(message = "$prefix · ${useful.name}: ${s.message}")
        } else {
            LiveSourceStatus(
                LiveSourcePhase.IDLE,
                prefix,
                eventId = LiveMatchTargetRegistry.snapshot()?.eventId.orEmpty(),
                lastUpdateEpochMs = now
            )
        }
    }

    private fun setLifecycleLocked(
        stage: LiveLifecycleStage,
        message: String,
        eventId: String = "",
        gameId: String = "",
        provider: String = "",
        now: Long
    ) {
        val old = _lifecycle.value
        val since = if (old.stage == stage && old.eventId == eventId) old.sinceEpochMs else now
        _lifecycle.value = LiveLifecycleState(
            stage = stage,
            message = message,
            eventId = eventId,
            gameId = gameId,
            provider = provider,
            sinceEpochMs = since,
            updatedAtEpochMs = now
        )
    }

    private fun eligibleProvidersLocked(): List<Provider> = providers.filter {
        !it.lplOnly || currentTargetIsLpl()
    }

    private fun allEligibleProvidersUnhealthyLocked(now: Long): Boolean {
        val eligible = eligibleProvidersLocked()
        if (eligible.isEmpty()) return true
        return eligible.all { provider ->
            val s = providerStatuses[provider.name] ?: return@all true
            s.phase == LiveSourcePhase.ERROR ||
                (s.lastUpdateEpochMs > 0L && now - s.lastUpdateEpochMs > PROVIDER_STATUS_MAX_AGE_MS)
        }
    }

    private fun isMeaningful(snapshot: LiveSnapshot): Boolean =
        snapshot.blueGold > 0 || snapshot.redGold > 0 ||
            snapshot.blueKills > 0 || snapshot.redKills > 0 ||
            snapshot.blueTowers > 0 || snapshot.redTowers > 0 ||
            snapshot.bluePlayers.any { it.gold > 0 || it.creepScore > 0 || it.level > 1 } ||
            snapshot.redPlayers.any { it.gold > 0 || it.creepScore > 0 || it.level > 1 }

    private fun currentTargetIsLpl(): Boolean {
        val target = LiveMatchTargetRegistry.snapshot() ?: return false
        val league = target.league.lowercase()
        return league == "lpl" || league.contains("league of legends pro league")
    }

    private fun isLive(value: String): Boolean {
        val state = normalize(value)
        return state == "live" || state.contains("progress")
    }

    private fun isCompleted(value: String): Boolean {
        val state = normalize(value)
        return state == "finished" || state.contains("complete")
    }

    private fun plannedStartReached(match: ScheduledEsportsMatch, now: Long): Boolean {
        val start = runCatching { Instant.parse(match.startTimeIso).toEpochMilli() }.getOrNull() ?: return false
        return now >= start
    }

    private fun normalize(value: String): String =
        value.lowercase().replace("_", "").replace("-", "").replace(" ", "")

    private fun teamLabel(match: ScheduledEsportsMatch): String =
        match.teams.take(2).joinToString(" vs ") { it.code.ifBlank { it.name } }

    companion object {
        private const val LIVE_STATUS_STALE_MS = 15_000L
        private const val PROVIDER_STATUS_MAX_AGE_MS = 45_000L
        private const val EVENT_TO_FRAME_TIMEOUT_MS = 20_000L
        private const val WATCHDOG_TICK_MS = 1_000L
    }
}
