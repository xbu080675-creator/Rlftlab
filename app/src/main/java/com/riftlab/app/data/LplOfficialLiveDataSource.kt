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
 * meaningful game frame exists. This prevents one provider error from being mistaken for the
 * event itself being unavailable.
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
 * Truth order:
 * 1) Tencent/LPL comm-match-app realtime data plane (LPL only)
 * 2) Riot LoL Esports LiveStats window feed (global official continuous frames)
 * 3) Cito realtime fabric (global WebSocket primary + REST reconnect/reconcile)
 * 4) TJStats matchDetail current/final-frame fallback (LPL only)
 *
 * Providers may fail independently. A single ERROR never becomes the public router state while
 * another provider is healthy/waiting for the same target. Frames are accepted only after strict
 * current-target identity validation.
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
    private val citoLive = CitoRealtimeLiveDataSource()
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
                provider.source.observe(matchId).collect { rawSnapshot ->
                    var chosen: LiveSnapshot? = null
                    var chosenProvider: Provider? = null

                    synchronized(lock) {
                        val target = LiveMatchTargetRegistry.snapshot()
                        val currentKey = LiveMatchTargetRegistry.key(target)
                        val snapshot = if (rawSnapshot.targetKey.isBlank()) rawSnapshot.copy(targetKey = currentKey) else rawSnapshot
                        if (target != null && MatchIdentityPolicy.snapshotBelongsTo(snapshot, target)) {
                            providerSnapshots[provider.name] = snapshot
                        } else {
                            providerSnapshots.remove(provider.name)
                        }

                        val now = System.currentTimeMillis()
                        val best = eligibleProvidersLocked()
                            .filter { candidate ->
                                val status = providerStatuses[candidate.name]
                                status?.phase == LiveSourcePhase.LIVE &&
                                    status.lastUpdateEpochMs > 0L &&
                                    now - status.lastUpdateEpochMs <= LIVE_STATUS_STALE_MS &&
                                    providerMatchesCurrentTargetLocked(candidate, requireFrame = true) &&
                                    providerSnapshots[candidate.name]?.let(::isMeaningful) == true
                            }
                            .minByOrNull { it.priority }

                        if (best != null) {
                            val candidate = providerSnapshots[best.name]
                            if (candidate != null) {
                                val fused = fuseCitoSupplementLocked(candidate, best.name, now)
                                val key = emissionKey(best.name, fused)
                                if (key != lastEmissionKey) {
                                    lastEmissionKey = key
                                    lastMeaningfulFrameEpochMs = now
                                    lastChosenProvider = best.name
                                    chosenProvider = best
                                    chosen = fused.copy(
                                        source = "${fused.source} · Router=${best.name}",
                                        targetKey = currentKey
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

    private fun fuseCitoSupplementLocked(
        primary: LiveSnapshot,
        primaryProvider: String,
        now: Long
    ): LiveSnapshot {
        if (primaryProvider == CITO_PROVIDER_NAME) return primary
        val citoStatus = providerStatuses[CITO_PROVIDER_NAME] ?: return primary
        if (citoStatus.phase != LiveSourcePhase.LIVE || citoStatus.lastUpdateEpochMs <= 0L) return primary
        if (now - citoStatus.lastUpdateEpochMs > CITO_SUPPLEMENT_STALE_MS) return primary
        val cito = providerSnapshots[CITO_PROVIDER_NAME] ?: return primary
        val target = LiveMatchTargetRegistry.snapshot() ?: return primary
        if (!MatchIdentityPolicy.snapshotBelongsTo(cito, target)) return primary
        return CitoLiveFusion.mergeSupplement(primary, cito)
    }

    private fun emissionKey(provider: String, snapshot: LiveSnapshot): String = buildString {
        append(provider).append('|')
        append(snapshot.targetKey).append('|')
        append(snapshot.gameId).append('|')
        append(snapshot.elapsedSeconds).append('|')
        append(snapshot.blueGold).append('|').append(snapshot.redGold).append('|')
        append(snapshot.blueKills).append('|').append(snapshot.redKills).append('|')
        append(snapshot.supplementUpdatedAtEpochMs)
        (snapshot.bluePlayers + snapshot.redPlayers).forEach { player ->
            append('|').append(player.participantId)
            append(':').append(player.alive)
            append(':').append(player.currentHealth ?: -1)
            append(':').append(player.items.joinToString(","))
        }
    }

    private fun refreshLifecycleLocked() {
        val now = System.currentTimeMillis()
        val target = LiveMatchTargetRegistry.snapshot()
        val nextTargetKey = LiveMatchTargetRegistry.key(target)
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

        val freshLiveProvider = eligibleProvidersLocked()
            .filter { provider ->
                val s = providerStatuses[provider.name]
                s?.phase == LiveSourcePhase.LIVE &&
                    s.lastUpdateEpochMs > 0L &&
                    now - s.lastUpdateEpochMs <= LIVE_STATUS_STALE_MS &&
                    providerMatchesCurrentTargetLocked(provider, requireFrame = true) &&
                    providerSnapshots[provider.name]?.let(::isMeaningful) == true
            }
            .minByOrNull { it.priority }

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

        val between = eligibleProvidersLocked()
            .filter { provider ->
                providerStatuses[provider.name]?.phase == LiveSourcePhase.BETWEEN_GAMES &&
                    providerMatchesCurrentTargetLocked(provider, requireFrame = false)
            }
            .minByOrNull { it.priority }
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
                    "EVENT LIVE · 所有实时源暂不可用 · 自动重绑/重试中",
                    eventId = target.eventId,
                    now = now
                )
                else -> setLifecycleLocked(
                    LiveLifecycleStage.GAME_LOADING,
                    "EVENT LIVE · GAME LOADING · 多源并行探测当前小局",
                    eventId = target.eventId,
                    now = now
                )
            }

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

    /** Prefer healthy/waiting provider diagnostics over a higher-priority provider's ERROR. */
    private fun chooseProviderStatusLocked(prefix: String): LiveSourceStatus {
        val now = System.currentTimeMillis()
        val useful = eligibleProvidersLocked()
            .mapNotNull { provider ->
                val s = providerStatuses[provider.name] ?: return@mapNotNull null
                if (s.phase == LiveSourcePhase.IDLE) return@mapNotNull null
                if (!providerMatchesCurrentTargetLocked(provider, requireFrame = false)) return@mapNotNull null
                if (s.lastUpdateEpochMs > 0L && now - s.lastUpdateEpochMs > PROVIDER_STATUS_MAX_AGE_MS) return@mapNotNull null
                provider to s
            }
            .sortedWith(
                compareBy<Pair<Provider, LiveSourceStatus>> { phaseRank(it.second.phase) }
                    .thenBy { it.first.priority }
            )
            .firstOrNull()

        return if (useful != null) {
            val (provider, status) = useful
            status.copy(message = "$prefix · ${provider.name}: ${status.message}")
        } else {
            LiveSourceStatus(
                LiveSourcePhase.IDLE,
                prefix,
                eventId = LiveMatchTargetRegistry.snapshot()?.eventId.orEmpty(),
                lastUpdateEpochMs = now
            )
        }
    }

    private fun phaseRank(phase: LiveSourcePhase): Int = when (phase) {
        LiveSourcePhase.LIVE -> 0
        LiveSourcePhase.BETWEEN_GAMES -> 1
        LiveSourcePhase.WAITING_FOR_MATCH -> 2
        LiveSourcePhase.ERROR -> 3
        LiveSourcePhase.IDLE -> 4
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

    private fun providerMatchesCurrentTargetLocked(provider: Provider, requireFrame: Boolean): Boolean {
        val target = LiveMatchTargetRegistry.snapshot() ?: return false
        val status = providerStatuses[provider.name] ?: return false
        val targetEventId = target.eventId.trim()
        val providerEventId = status.eventId.trim()
        if (targetEventId.isNotBlank()) {
            if (providerEventId.isNotBlank() && providerEventId != targetEventId) return false
            if (providerEventId.isBlank() && status.phase in setOf(LiveSourcePhase.LIVE, LiveSourcePhase.BETWEEN_GAMES)) return false
        }
        if (!requireFrame) return true
        val snapshot = providerSnapshots[provider.name] ?: return false
        return MatchIdentityPolicy.snapshotBelongsTo(snapshot, target)
    }

    private fun eligibleProvidersLocked(): List<Provider> = providers.filter {
        !it.lplOnly || currentTargetIsLpl()
    }

    private fun allEligibleProvidersUnhealthyLocked(now: Long): Boolean {
        val eligible = eligibleProvidersLocked()
        if (eligible.isEmpty()) return true
        return eligible.all { provider ->
            val s = providerStatuses[provider.name] ?: return@all true
            !providerMatchesCurrentTargetLocked(provider, requireFrame = false) ||
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
        val slug = target.leagueSlug.lowercase()
        return league == "lpl" || slug == "lpl" || league.contains("league of legends pro league")
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
        private const val CITO_PROVIDER_NAME = "Cito API"
        private const val LIVE_STATUS_STALE_MS = 15_000L
        private const val CITO_SUPPLEMENT_STALE_MS = 15_000L
        private const val PROVIDER_STATUS_MAX_AGE_MS = 45_000L
        private const val EVENT_TO_FRAME_TIMEOUT_MS = 20_000L
        private const val WATCHDOG_TICK_MS = 1_000L
    }
}
