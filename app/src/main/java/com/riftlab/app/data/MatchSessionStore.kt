package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object MatchSessionStore {
    private val coreRoles = listOf("TOP", "JUG", "MID", "BOT", "SUP")
    @Volatile private var subscribedLeagueKeys: Set<String> = setOf("LPL")

    private val emptyPreMatch = PreMatchInfo(
        league = "LoL Esports",
        stage = "SCHEDULE CENTER",
        blue = "—",
        red = "—",
        startTime = "--:--",
        blueForm = "RIOT SCHEDULE",
        redForm = "RIOT SCHEDULE",
        blueRoster = emptyList(),
        redRoster = emptyList(),
        rosterNote = "正在同步 Riot 全球 LoL Esports 赛程；不会用 Mock 首发或 Rank 填空。"
    )

    private val _preMatch = MutableStateFlow(emptyPreMatch)
    val preMatch: PreMatchInfo get() = _preMatch.value
    val preMatchFlow: StateFlow<PreMatchInfo> = _preMatch.asStateFlow()

    val completedGame: StateFlow<LiveSnapshot?> = CompletedGameArchive.latest
    val completedSeries: StateFlow<CompletedSeriesSnapshot?> = CompletedGameArchive.series

    /**
     * Post tab is fed by the last completed small-game snapshot only.
     * It never reads the current live surface.
     */
    val postMatch: PostMatchInfo
        get() {
            val game = completedGame.value ?: return PostMatchInfo(
                score = "—",
                winner = "等待赛果",
                mvp = "—",
                mvpRole = "—",
                mvpDpm = 0,
                mvpGoldDiff15 = 0,
                positionRank = "POST MATCH DATA PENDING",
                keyPoint = "比赛结束后，最后一帧真实数据会从赛中迁移到这里；当前不显示 Mock 结论。"
            )
            return PostMatchInfo(
                score = "G${game.game}",
                winner = "G${game.game} 已结束 · ${game.blue} vs ${game.red}",
                mvp = "—",
                mvpRole = "—",
                mvpDpm = 0,
                mvpGoldDiff15 = game.goldDiff,
                positionRank = "FINAL SNAPSHOT · ${formatTime(game.elapsedSeconds)} · GOLD ${formatGold(game.blueGold)} : ${formatGold(game.redGold)}",
                keyPoint = "K ${game.blueKills}:${game.redKills} · T ${game.blueTowers}:${game.redTowers} · D ${game.blueDragons}:${game.redDragons} · B ${game.blueBarons}:${game.redBarons} · SOURCE ${game.source}"
            )
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scheduleSource = LolEsportsScheduleDataSource()
    private val teamSource = LolEsportsTeamDataSource()
    private val liveDataSource = GlobalOfficialLiveDataSource()
    private val postMatchResolver = LplHistoricalPostMatchResolver()

    private var liveJob: Job? = null
    private var scheduleJob: Job? = null
    private var statusJob: Job? = null

    private val _schedule = MutableStateFlow<List<ScheduledEsportsMatch>>(emptyList())
    val schedule: StateFlow<List<ScheduledEsportsMatch>> = _schedule.asStateFlow()

    private val _targetMatch = MutableStateFlow<ScheduledEsportsMatch?>(null)
    val targetMatch: StateFlow<ScheduledEsportsMatch?> = _targetMatch.asStateFlow()

    private val _scheduleStatus = MutableStateFlow("正在连接 Riot LoL Esports 赛程中心…")
    val scheduleStatus: StateFlow<String> = _scheduleStatus.asStateFlow()

    private val _rosterStatus = MutableStateFlow("ROSTER · 等待选中赛事")
    val rosterStatus: StateFlow<String> = _rosterStatus.asStateFlow()

    private val _scheduleCenter = MutableStateFlow(ScheduleCenterState())
    val scheduleCenter: StateFlow<ScheduleCenterState> = _scheduleCenter.asStateFlow()

    val liveSourceStatus: StateFlow<LiveSourceStatus> = liveDataSource.status
    val liveLifecycle: StateFlow<LiveLifecycleState> = liveDataSource.lifecycle
    val postSourceStatus: StateFlow<String> = postMatchResolver.status

    private fun emptyLiveSnapshot(message: String): LiveSnapshot = LiveSnapshot(
        game = 0,
        elapsedSeconds = 0,
        blue = "—",
        red = "—",
        blueGold = 0,
        redGold = 0,
        blueKills = 0,
        redKills = 0,
        blueTowers = 0,
        redTowers = 0,
        blueDragons = 0,
        redDragons = 0,
        latestEvent = message,
        source = "Live Provider Router",
        gameId = ""
    )

    private val _live = MutableStateFlow(
        emptyLiveSnapshot("LoL Esports · 等待当前正在进行的小局")
    )
    val live: StateFlow<LiveSnapshot> = _live.asStateFlow()

    fun ensureDataRunning() {
        if (scheduleJob?.isActive != true) {
            scheduleJob = scope.launch {
                while (isActive) {
                    refreshScheduleAndRoster()
                    delay(5 * 60 * 1000L)
                }
            }
        }

        if (liveJob?.isActive != true) {
            liveJob = scope.launch {
                // Live surface receives current-game snapshots only.
                liveDataSource.observe("").collect { snapshot ->
                    _live.value = snapshot
                }
            }
        }

        if (statusJob?.isActive != true) {
            statusJob = scope.launch {
                liveDataSource.status.collect { status ->
                    // Strong boundary: the moment the provider is not LIVE, the live cache is
                    // cleared. Finished-game values are available only from CompletedGameArchive.
                    if (status.phase != LiveSourcePhase.LIVE) {
                        _live.value = emptyLiveSnapshot(status.message)
                    }
                    syncLiveStatusIntoSchedule(status)
                }
            }
        }
    }

    fun selectScheduleMatch(matchId: String) {
        val match = _scheduleCenter.value.matches.firstOrNull {
            it.matchId == matchId || it.eventId == matchId
        } ?: return

        _targetMatch.value = match
        LiveMatchTargetRegistry.update(match)
        _scheduleCenter.value = _scheduleCenter.value.copy(selectedMatch = match)
        scope.launch { refreshPreMatchFromTarget(match) }
    }

    fun updateLeagueSubscriptions(keys: Set<String>) {
        val normalized = keys.map(::subscriptionLeagueToken).filter { it.isNotBlank() }.toSet().ifEmpty { setOf("LPL") }
        if (normalized == subscribedLeagueKeys) return
        subscribedLeagueKeys = normalized
        scope.launch { applySubscribedHomepageTarget() }
    }

    private suspend fun applySubscribedHomepageTarget() {
        val all = _schedule.value
        if (all.isEmpty()) return
        val home = all.filter(::matchesHomepageSubscription)
        val current = home.firstOrNull(::isLiveState)
        val next = findNextMatch(home, current?.matchId.orEmpty())
        val target = current ?: next ?: home.maxByOrNull { plannedStartEpochMs(it) ?: Long.MIN_VALUE }
        _targetMatch.value = target
        LiveMatchTargetRegistry.update(target)
        _scheduleCenter.value = _scheduleCenter.value.copy(
            currentMatch = current,
            nextMatch = next,
            statusMessage = if (target == null) {
                "订阅赛区 ${subscribedLeagueKeys.joinToString(" / ")} · 当前 Riot 分页暂无赛事"
            } else {
                buildScheduleStatus(all, current, next)
            }
        )
        _scheduleStatus.value = _scheduleCenter.value.statusMessage
        if (target != null) refreshPreMatchFromTarget(target) else {
            _preMatch.value = emptyPreMatch
            _rosterStatus.value = "ROSTER · 订阅赛区当前无可选赛事"
        }
    }

    private suspend fun refreshScheduleAndRoster() {
        _scheduleStatus.value = "正在同步 Riot 全球赛事分页赛程…"
        try {
            val matches = scheduleSource.fetchLeagueSchedule()
            _schedule.value = matches

            val homepageMatches = matches.filter(::matchesHomepageSubscription)
            val currentBySchedule = homepageMatches.firstOrNull(::isLiveState)
            val next = findNextMatch(homepageMatches, currentBySchedule?.matchId.orEmpty())
            val oldSelectedId = _scheduleCenter.value.selectedMatch?.matchId
            val selected = matches.firstOrNull { it.matchId == oldSelectedId }
                ?: currentBySchedule
                ?: next
                ?: matches.lastOrNull()
            val homepageTarget = currentBySchedule
                ?: next
                ?: homepageMatches.maxByOrNull { plannedStartEpochMs(it) ?: Long.MIN_VALUE }

            val center = _scheduleCenter.value.copy(
                matches = matches,
                currentMatch = currentBySchedule ?: _scheduleCenter.value.currentMatch?.let { old ->
                    matches.firstOrNull { it.matchId == old.matchId }
                },
                nextMatch = next,
                selectedMatch = selected,
                lastRefreshEpochMs = System.currentTimeMillis(),
                statusMessage = buildScheduleStatus(matches, currentBySchedule, next)
            )
            _scheduleCenter.value = center
            _targetMatch.value = homepageTarget
            LiveMatchTargetRegistry.update(homepageTarget)
            _scheduleStatus.value = if (homepageTarget == null) {
                "订阅赛区 ${subscribedLeagueKeys.joinToString(" / ")} · 当前 Riot 分页暂无赛事"
            } else center.statusMessage

            // Post-match recovery is independent from the live target. Always resolve the most
            // recent completed series from the schedule. LPL currently has an additional TJStats final resolver;
            // can still rebuild the complete final archive.
            val latestCompleted = matches
                .filter(::isCompletedState)
                .maxByOrNull { plannedStartEpochMs(it) ?: Long.MIN_VALUE }
            if (latestCompleted != null && latestCompleted.league.contains("LPL", ignoreCase = true)) {
                scope.launch {
                    runCatching { postMatchResolver.refresh(latestCompleted) }
                }
            }

            if (homepageTarget != null) {
                refreshPreMatchFromTarget(homepageTarget)
            } else {
                _preMatch.value = emptyPreMatch
                _rosterStatus.value = "ROSTER · 订阅赛区当前没有可选赛事"
            }
        } catch (t: Throwable) {
            val message = "赛程中心 ERROR · ${t.message?.take(150) ?: t::class.java.simpleName}"
            _scheduleStatus.value = message
            _scheduleCenter.value = _scheduleCenter.value.copy(statusMessage = message)
            _rosterStatus.value = "ROSTER · 网络源不可用，保留上次已同步数据"
        }
    }

    private suspend fun syncLiveStatusIntoSchedule(status: LiveSourceStatus) {
        val lifecycle = liveDataSource.lifecycle.value
        val resolvedEventId = lifecycle.eventId.ifBlank {
            status.eventId.ifBlank { LiveMatchTargetRegistry.snapshot()?.eventId.orEmpty() }
        }
        if (resolvedEventId.isBlank()) return

        // EVENT_LIVE/PRE_GAME/DRAFT/GAME_LOADING are real event states even without a game frame.
        // This is the key fix for the old IG-day deadlock: currentMatch no longer depends on one
        // provider successfully resolving a gameId first.
        val eventActive = lifecycle.stage in setOf(
            LiveLifecycleStage.EVENT_LIVE,
            LiveLifecycleStage.PRE_GAME,
            LiveLifecycleStage.DRAFT,
            LiveLifecycleStage.GAME_LOADING,
            LiveLifecycleStage.GAME_LIVE,
            LiveLifecycleStage.BETWEEN_GAMES,
            LiveLifecycleStage.DEGRADED
        )
        if (!eventActive && status.phase != LiveSourcePhase.LIVE && status.phase != LiveSourcePhase.BETWEEN_GAMES) return

        val center = _scheduleCenter.value
        val liveMatch = center.matches.firstOrNull {
            it.eventId == resolvedEventId || it.matchId == resolvedEventId
        } ?: return
        if (!matchesHomepageSubscription(liveMatch)) return

        val key = scheduleKey(liveMatch)
        val detected = if (
            lifecycle.stage == LiveLifecycleStage.GAME_LIVE &&
            key !in center.liveDetectedAtEpochMs
        ) {
            center.liveDetectedAtEpochMs + (key to System.currentTimeMillis())
        } else {
            center.liveDetectedAtEpochMs
        }

        val targetChanged = _targetMatch.value?.matchId != liveMatch.matchId
        val next = findNextMatch(center.matches.filter(::matchesHomepageSubscription), liveMatch.matchId)

        _scheduleCenter.value = center.copy(
            currentMatch = liveMatch,
            nextMatch = next,
            selectedMatch = liveMatch,
            liveDetectedAtEpochMs = detected,
            statusMessage = buildScheduleStatus(center.matches, liveMatch, next)
        )
        _targetMatch.value = liveMatch
        LiveMatchTargetRegistry.update(liveMatch)
        _scheduleStatus.value = _scheduleCenter.value.statusMessage

        if (targetChanged) refreshPreMatchFromTarget(liveMatch)
    }

    private suspend fun refreshPreMatchFromTarget(target: ScheduledEsportsMatch): String {
        val left = target.teams.getOrNull(0) ?: return "0/2"
        val right = target.teams.getOrNull(1) ?: return "0/2"
        _rosterStatus.value = "ROSTER · 正在同步 Riot getTeams…"

        val leftDetails = runCatching {
            teamLookupSlug(left)?.let { teamSource.fetchTeam(it) }
        }.getOrNull()
        val rightDetails = runCatching {
            teamLookupSlug(right)?.let { teamSource.fetchTeam(it) }
        }.getOrNull()

        val leftRiotRoster = leftDetails?.players.orEmpty().toPlayerCards()
        val rightRiotRoster = rightDetails?.players.orEmpty().toPlayerCards()
        val leftUniqueFive = leftRiotRoster.uniqueStartingFiveOrNull()
        val rightUniqueFive = rightRiotRoster.uniqueStartingFiveOrNull()
        val leftRoster = leftUniqueFive ?: emptyList()
        val rightRoster = rightUniqueFive ?: emptyList()
        val leftStaff = staffForPre(leftDetails)
        val rightStaff = staffForPre(rightDetails)
        val leftRecent = recentCompletedSeries(left, target, limit = 5)
        val rightRecent = recentCompletedSeries(right, target, limit = 5)
        val recentH2h = recentHeadToHead(left, right, target, limit = 5)
        val connectedCount = listOf(leftDetails != null, rightDetails != null).count { it }
        val autoStarterCount = listOf(leftUniqueFive != null, rightUniqueFive != null).count { it }

        _preMatch.value = PreMatchInfo(
            league = target.league.ifBlank { "LoL Esports" },
            stage = target.blockName.ifBlank { target.league.ifBlank { "LoL Esports" } }.uppercase(),
            blue = left.code.ifBlank { left.name },
            red = right.code.ifBlank { right.name },
            startTime = formatLocalStart(target.startTimeIso),
            blueForm = scheduleTeamForm(left),
            redForm = scheduleTeamForm(right),
            blueRoster = leftRoster,
            redRoster = rightRoster,
            rosterNote = when {
                connectedCount == 2 && autoStarterCount == 2 ->
                    "两队 roster 已连接且五位置均唯一；可作为当前 roster 五人展示，但仍不把 roster pool 额外成员擅自标成替补。Rank 继续等待独立 Ranked 数据源。"
                connectedCount == 2 ->
                    "两队 roster pool 已连接；存在同位置多人或位置歧义时，首发保持未确认，不从名单顺序猜首发/替补。"
                connectedCount == 1 ->
                    "一侧 roster pool 已连接；另一侧保持空缺。未取得公开确认前，不填首发变化、伤病/缺席或转会结论。"
                else ->
                    "Schedule 已连接，但当前 roster 暂不可用；没有独立核实的数据就保持空缺。Rank 暂未接入。"
            },
            blueRosterPool = leftRiotRoster,
            redRosterPool = rightRiotRoster,
            blueStaff = leftStaff,
            redStaff = rightStaff,
            blueRecentSeries = leftRecent,
            redRecentSeries = rightRecent,
            recentHeadToHead = recentH2h
        )
        _rosterStatus.value = "ROSTER · RIOT GETTEAMS $connectedCount/2 · AUTO STARTERS $autoStarterCount/2"
        return "$connectedCount/2"
    }

    private fun staffForPre(details: EsportsTeamDetails?): List<EsportsStaffRef> =
        (details?.staff.orEmpty() + details?.management.orEmpty())
            .filter { it.name.isNotBlank() }
            .distinctBy { "${teamIdentityToken(it.name)}|${teamIdentityToken(it.role)}" }

    private fun recentCompletedSeries(
        team: EsportsTeamRef,
        exclude: ScheduledEsportsMatch,
        limit: Int
    ): List<PreRecentSeries> = _schedule.value
        .asSequence()
        .filter(::isCompletedState)
        .filter { candidate -> candidate.matchId != exclude.matchId && candidate.eventId != exclude.eventId }
        .filter { candidate -> candidate.teams.any { matchesTeamIdentity(it, team) } }
        .sortedByDescending { plannedStartEpochMs(it) ?: Long.MIN_VALUE }
        .mapNotNull { toPreRecentSeries(it, team) }
        .take(limit)
        .toList()

    private fun recentHeadToHead(
        left: EsportsTeamRef,
        right: EsportsTeamRef,
        exclude: ScheduledEsportsMatch,
        limit: Int
    ): List<PreRecentSeries> = _schedule.value
        .asSequence()
        .filter(::isCompletedState)
        .filter { candidate -> candidate.matchId != exclude.matchId && candidate.eventId != exclude.eventId }
        .filter { candidate ->
            candidate.teams.any { matchesTeamIdentity(it, left) } &&
                candidate.teams.any { matchesTeamIdentity(it, right) }
        }
        .sortedByDescending { plannedStartEpochMs(it) ?: Long.MIN_VALUE }
        .mapNotNull { toPreRecentSeries(it, left) }
        .take(limit)
        .toList()

    private fun toPreRecentSeries(
        match: ScheduledEsportsMatch,
        perspective: EsportsTeamRef
    ): PreRecentSeries? {
        val index = match.teams.indexOfFirst { matchesTeamIdentity(it, perspective) }
        if (index < 0) return null
        val self = match.teams[index]
        val opponent = match.teams.firstOrNull { !matchesTeamIdentity(it, perspective) } ?: return null
        val scoreFor = self.gameWins
        val scoreAgainst = opponent.gameWins
        val outcome = when {
            scoreFor > scoreAgainst -> "W"
            scoreFor < scoreAgainst -> "L"
            else -> "—"
        }
        return PreRecentSeries(
            eventId = match.eventId.ifBlank { match.matchId },
            opponentCode = opponent.code.ifBlank { opponent.name },
            scoreFor = scoreFor,
            scoreAgainst = scoreAgainst,
            outcome = outcome,
            startTimeIso = match.startTimeIso,
            source = "Unified Schedule · Riot/Cito"
        )
    }

    private fun matchesTeamIdentity(candidate: EsportsTeamRef, target: EsportsTeamRef): Boolean {
        val a = listOf(candidate.id, candidate.code, candidate.name, candidate.slug)
            .map(::teamIdentityToken)
            .filter { it.isNotBlank() }
            .toSet()
        val b = listOf(target.id, target.code, target.name, target.slug)
            .map(::teamIdentityToken)
            .filter { it.isNotBlank() }
            .toSet()
        return a.any { token -> token in b }
    }

    private fun teamIdentityToken(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

    private fun teamLookupSlug(team: EsportsTeamRef): String? {
        if (team.id.isNotBlank()) return team.id
        if (team.slug.isNotBlank()) return team.slug

        return team.name
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .takeIf { it.isNotBlank() }
    }

    private fun List<EsportsPlayerRef>.toPlayerCards(): List<PlayerCard> =
        sortedBy { roleOrder(it.role) }
            .map { player ->
                PlayerCard(
                    role = player.role,
                    id = player.summonerName,
                    rank = "RANK 待接",
                    recent = "Riot roster"
                )
            }

    private fun List<PlayerCard>.uniqueStartingFiveOrNull(): List<PlayerCard>? {
        val byRole = groupBy { it.role.uppercase() }
        if (coreRoles.any { role -> byRole[role]?.size != 1 }) return null
        return coreRoles.map { role -> byRole.getValue(role).single() }
    }


    private fun roleOrder(role: String): Int = when (role.uppercase()) {
        "TOP" -> 0
        "JUG", "JUNGLE" -> 1
        "MID" -> 2
        "BOT", "ADC", "BOTTOM" -> 3
        "SUP", "SUPPORT" -> 4
        else -> 99
    }

    private fun scheduleTeamForm(team: EsportsTeamRef): String =
        if (team.recordWins > 0 || team.recordLosses > 0) {
            "${team.recordWins}W-${team.recordLosses}L"
        } else {
            "RIOT SCHEDULE"
        }

    private fun matchesHomepageSubscription(match: ScheduledEsportsMatch): Boolean {
        val key = canonicalLeagueKey(match)
        return key.isNotBlank() && key in subscribedLeagueKeys
    }

    private fun canonicalLeagueKey(match: ScheduledEsportsMatch): String {
        val raw = subscriptionLeagueToken(match.leagueSlug.ifBlank { match.league })
        return when {
            raw.contains("LCPWILDCARD") -> "LCPWILDCARD"
            raw.contains("LCKCL") || raw.contains("LCKCHALLENGERS") -> "LCKCL"
            raw.contains("LPLDEVELOPMENT") || raw == "LDL" -> "LDL"
            raw.contains("LTA") || raw.contains("LCS") -> "LCS"
            raw.contains("CBLOL") -> "CBLOL"
            raw.contains("LPL") -> "LPL"
            raw.contains("LCK") -> "LCK"
            raw.contains("LEC") -> "LEC"
            raw.contains("LCP") -> "LCP"
            raw.contains("PCS") -> "PCS"
            raw.contains("VCS") -> "VCS"
            raw.contains("LJL") -> "LJL"
            raw.contains("FLS") -> "FLS"
            raw.contains("NLC") -> "NLC"
            raw == "LIT" || raw.contains("LEAGUEITALIA") -> "LIT"
            raw.contains("TCL") -> "TCL"
            else -> raw
        }
    }

    private fun subscriptionLeagueToken(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

    private fun findNextMatch(
        matches: List<ScheduledEsportsMatch>,
        excludeMatchId: String = ""
    ): ScheduledEsportsMatch? {
        val candidates = matches.filter { match ->
            match.matchId != excludeMatchId && !isCompletedState(match) && !isLiveState(match)
        }
        if (candidates.isEmpty()) return null

        // Riot can occasionally leave an old event marked "unstarted". Keep a generous
        // 12-hour grace window for delayed series, but do not let stale records become NEXT.
        val staleCutoff = System.currentTimeMillis() - 12 * 60 * 60 * 1000L
        return candidates.firstOrNull { match ->
            plannedStartEpochMs(match)?.let { it >= staleCutoff } ?: true
        } ?: candidates.maxByOrNull { plannedStartEpochMs(it) ?: Long.MIN_VALUE }
    }

    private fun buildScheduleStatus(
        matches: List<ScheduledEsportsMatch>,
        current: ScheduledEsportsMatch?,
        next: ScheduledEsportsMatch?
    ): String {
        val completed = matches.count(::isCompletedState)
        val currentText = current?.let { "${scheduleActivityLabel(it)} ${teamsLabel(it)}" } ?: "NO ACTIVE EVENT"
        val nextText = next?.let { "NEXT ${teamsLabel(it)} ${formatLocalDateTime(it.startTimeIso)}" } ?: "NO NEXT"
        return "${RiotResilientHttp.sourceLabel()} · Schedule · ${matches.size} 场 · 已结束 $completed · $currentText · $nextText"
    }

    private fun teamsLabel(match: ScheduledEsportsMatch): String =
        match.teams.take(2).joinToString(" vs ") { it.code.ifBlank { it.name } }

    private fun isLiveState(match: ScheduledEsportsMatch): Boolean {
        val state = normalizeState(match.state)
        return state.contains("progress") || state == "live"
    }

    private fun isCompletedState(match: ScheduledEsportsMatch): Boolean {
        val state = normalizeState(match.state)
        return state.contains("complete") || state == "finished"
    }

    fun schedulePhase(match: ScheduledEsportsMatch): ScheduleMatchPhase = when {
        _scheduleCenter.value.currentMatch?.matchId == match.matchId -> ScheduleMatchPhase.LIVE
        isLiveState(match) -> ScheduleMatchPhase.LIVE
        isCompletedState(match) -> ScheduleMatchPhase.COMPLETED
        else -> ScheduleMatchPhase.UPCOMING
    }

    fun scheduleActivity(match: ScheduledEsportsMatch): ScheduleActivityState {
        if (isCompletedState(match)) return ScheduleActivityState.COMPLETED

        val current = _scheduleCenter.value.currentMatch
        val isCurrent = current?.let { candidate ->
            candidate.matchId == match.matchId ||
                (match.eventId.isNotBlank() && candidate.eventId == match.eventId)
        } == true
        val status = liveDataSource.status.value
        val statusTargetsMatch = isCurrent && (
            status.eventId.isBlank() ||
                status.eventId == match.eventId ||
                status.eventId == match.matchId
            )

        if (statusTargetsMatch) {
            when (liveDataSource.lifecycle.value.stage) {
                LiveLifecycleStage.GAME_LIVE -> return ScheduleActivityState.GAME_LIVE
                LiveLifecycleStage.BETWEEN_GAMES -> return ScheduleActivityState.BETWEEN_GAMES
                LiveLifecycleStage.EVENT_LIVE,
                LiveLifecycleStage.PRE_GAME,
                LiveLifecycleStage.DRAFT,
                LiveLifecycleStage.GAME_LOADING,
                LiveLifecycleStage.DEGRADED -> return ScheduleActivityState.EVENT_LIVE
                else -> Unit
            }
            when (status.phase) {
                LiveSourcePhase.LIVE -> return ScheduleActivityState.GAME_LIVE
                LiveSourcePhase.BETWEEN_GAMES -> return ScheduleActivityState.BETWEEN_GAMES
                else -> Unit
            }
        }

        if (isLiveState(match)) return ScheduleActivityState.EVENT_LIVE
        return ScheduleActivityState.UPCOMING
    }

    fun scheduleActivityLabel(match: ScheduledEsportsMatch): String = when (scheduleActivity(match)) {
        ScheduleActivityState.GAME_LIVE -> "小局进行中"
        ScheduleActivityState.EVENT_LIVE -> "赛事进行中"
        ScheduleActivityState.BETWEEN_GAMES -> "局间"
        ScheduleActivityState.UPCOMING -> "待开"
        ScheduleActivityState.COMPLETED -> "已结束"
    }

    fun scheduleScore(match: ScheduledEsportsMatch): String {
        val left = match.teams.getOrNull(0)?.gameWins ?: 0
        val right = match.teams.getOrNull(1)?.gameWins ?: 0
        return if (left > 0 || right > 0 || isCompletedState(match)) "$left : $right" else "—"
    }

    fun scheduleDateKey(match: ScheduledEsportsMatch): String = runCatching {
        DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(match.startTimeIso))
    }.getOrElse { "日期未知" }

    fun scheduleTimingNote(match: ScheduledEsportsMatch): String = when (scheduleActivity(match)) {
        ScheduleActivityState.UPCOMING -> "计划 ${formatLocalStart(match.startTimeIso)}"
        ScheduleActivityState.EVENT_LIVE -> liveDataSource.lifecycle.value.message.ifBlank { "赛事已开始 · 等待小局数据" }
        ScheduleActivityState.BETWEEN_GAMES -> "局间 · 等待下一小局"
        ScheduleActivityState.COMPLETED -> "已结束"
        ScheduleActivityState.GAME_LIVE -> {
            val plannedLabel = formatLocalStart(match.startTimeIso)
            val detectedAt = _scheduleCenter.value.liveDetectedAtEpochMs[scheduleKey(match)]
            val planned = plannedStartEpochMs(match)
            if (detectedAt == null || planned == null) {
                "赛事计划 $plannedLabel · 小局进行中"
            } else {
                val deltaMinutes = (detectedAt - planned) / 60_000L
                when {
                    deltaMinutes >= 1L -> "赛事计划 $plannedLabel · 首次检测小局 LIVE +${deltaMinutes} 分钟"
                    deltaMinutes <= -1L -> "赛事计划 $plannedLabel · 首次检测小局 LIVE ${deltaMinutes} 分钟"
                    else -> "赛事计划 $plannedLabel · 小局进行中"
                }
            }
        }
    }

    private fun scheduleKey(match: ScheduledEsportsMatch): String =
        match.eventId.ifBlank { match.matchId }

    private fun normalizeState(value: String): String =
        value.lowercase().replace("_", "").replace("-", "").replace(" ", "")

    private fun plannedStartEpochMs(match: ScheduledEsportsMatch): Long? =
        runCatching { Instant.parse(match.startTimeIso).toEpochMilli() }.getOrNull()

    fun scheduleDateTimeLabel(match: ScheduledEsportsMatch): String = formatLocalDateTime(match.startTimeIso)

    private fun formatLocalStart(iso: String): String {
        if (iso.isBlank()) return "--:--"
        return runCatching {
            val instant = Instant.parse(iso)
            val zone = ZoneId.systemDefault()
            val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(instant)
            "$clock · ${zoneOffsetLabel(zone, instant)}"
        }.getOrElse { iso }
    }

    private fun formatLocalDateTime(iso: String): String {
        if (iso.isBlank()) return "--"
        return runCatching {
            val instant = Instant.parse(iso)
            val zone = ZoneId.systemDefault()
            val clock = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone).format(instant)
            "$clock · ${zoneOffsetLabel(zone, instant)}"
        }.getOrElse { iso }
    }

    private fun zoneOffsetLabel(zone: ZoneId, instant: Instant): String {
        val totalMinutes = zone.rules.getOffset(instant).totalSeconds / 60
        val sign = if (totalMinutes >= 0) "+" else "-"
        val absolute = kotlin.math.abs(totalMinutes)
        val hours = absolute / 60
        val minutes = absolute % 60
        return if (minutes == 0) "UTC$sign$hours" else "UTC$sign%02d:%02d".format(hours, minutes)
    }

    private fun formatGold(value: Int): String =
        if (value >= 1000) "%.1fK".format(value / 1000f) else value.toString()

    /** Compatibility alias retained for older overlay/UI call sites. */
    fun ensureMockRunning() = ensureDataRunning()

    fun formatTime(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)
}
