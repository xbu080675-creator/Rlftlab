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

    // Match-specific fallback is intentionally limited to lineups separately verified before the match.
    // The generic schedule center never invents a starting five for other teams.
    private val verifiedLgdRoster = listOf(
        PlayerCard("TOP", "Burdol", "RANK 待接", "赛前已验证缓存"),
        PlayerCard("JUG", "Heng", "RANK 待接", "赛前已验证缓存"),
        PlayerCard("MID", "Tangyuan", "RANK 待接", "赛前已验证缓存"),
        PlayerCard("BOT", "Shaoye", "RANK 待接", "赛前已验证缓存"),
        PlayerCard("SUP", "Crisp", "RANK 待接", "赛前已验证缓存")
    )

    private val verifiedIgRoster = listOf(
        PlayerCard("TOP", "TheShy", "RANK 待接", "赛前已验证缓存"),
        PlayerCard("JUG", "Wei", "RANK 待接", "赛前已验证缓存"),
        PlayerCard("MID", "Rookie", "RANK 待接", "赛前已验证缓存"),
        PlayerCard("BOT", "JiaQi", "RANK 待接", "赛前已验证缓存"),
        PlayerCard("SUP", "Meiko", "RANK 待接", "赛前已验证缓存")
    )

    private val emptyPreMatch = PreMatchInfo(
        league = "LPL",
        stage = "SCHEDULE CENTER",
        blue = "—",
        red = "—",
        startTime = "--:--",
        blueForm = "RIOT SCHEDULE",
        redForm = "RIOT SCHEDULE",
        blueRoster = emptyList(),
        redRoster = emptyList(),
        rosterNote = "正在同步 Riot LPL 赛程；不会用 Mock 首发或 Rank 填空。"
    )

    private val _preMatch = MutableStateFlow(emptyPreMatch)
    val preMatch: PreMatchInfo get() = _preMatch.value
    val preMatchFlow: StateFlow<PreMatchInfo> = _preMatch.asStateFlow()

    val completedGame: StateFlow<LiveSnapshot?> = CompletedGameArchive.latest

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
    private val liveDataSource = LplOfficialLiveDataSource()

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
        source = "LPL Official · current-game only",
        gameId = ""
    )

    private val _live = MutableStateFlow(
        emptyLiveSnapshot("LPL Official · 等待当前正在进行的小局")
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
        _scheduleCenter.value = _scheduleCenter.value.copy(selectedMatch = match)
        scope.launch { refreshPreMatchFromTarget(match) }
    }

    private suspend fun refreshScheduleAndRoster() {
        _scheduleStatus.value = "正在同步 Riot LPL 分页赛程…"
        try {
            val matches = scheduleSource.fetchLeagueSchedule()
            _schedule.value = matches

            val currentBySchedule = matches.firstOrNull(::isLiveState)
            val next = findNextMatch(matches, currentBySchedule?.matchId.orEmpty())
            val oldSelectedId = _scheduleCenter.value.selectedMatch?.matchId
            val selected = matches.firstOrNull { it.matchId == oldSelectedId }
                ?: currentBySchedule
                ?: next
                ?: matches.lastOrNull()

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
            _targetMatch.value = selected
            _scheduleStatus.value = center.statusMessage

            if (selected != null) {
                refreshPreMatchFromTarget(selected)
            } else {
                _preMatch.value = emptyPreMatch
                _rosterStatus.value = "ROSTER · 当前分页没有可选赛事"
            }
        } catch (t: Throwable) {
            val message = "赛程中心 ERROR · ${t.message?.take(150) ?: t::class.java.simpleName}"
            _scheduleStatus.value = message
            _scheduleCenter.value = _scheduleCenter.value.copy(statusMessage = message)
            _rosterStatus.value = "ROSTER · 网络源不可用，保留上次已同步数据"
        }
    }

    private suspend fun syncLiveStatusIntoSchedule(status: LiveSourceStatus) {
        if (status.eventId.isBlank()) return
        if (status.phase != LiveSourcePhase.LIVE && status.phase != LiveSourcePhase.BETWEEN_GAMES) return

        val center = _scheduleCenter.value
        val liveMatch = center.matches.firstOrNull {
            it.eventId == status.eventId || it.matchId == status.eventId
        } ?: return

        val key = scheduleKey(liveMatch)
        val detected = if (key in center.liveDetectedAtEpochMs) {
            center.liveDetectedAtEpochMs
        } else {
            center.liveDetectedAtEpochMs + (key to System.currentTimeMillis())
        }

        val targetChanged = _targetMatch.value?.matchId != liveMatch.matchId
        val next = findNextMatch(center.matches, liveMatch.matchId)

        _scheduleCenter.value = center.copy(
            currentMatch = liveMatch,
            nextMatch = next,
            selectedMatch = liveMatch,
            liveDetectedAtEpochMs = detected,
            statusMessage = buildScheduleStatus(center.matches, liveMatch, next)
        )
        _targetMatch.value = liveMatch
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
        val leftFallback = fallbackRoster(left.code)
        val rightFallback = fallbackRoster(right.code)
        val leftRoster = leftUniqueFive ?: leftFallback
        val rightRoster = rightUniqueFive ?: rightFallback
        val connectedCount = listOf(leftDetails != null, rightDetails != null).count { it }
        val autoStarterCount = listOf(leftUniqueFive != null, rightUniqueFive != null).count { it }

        _preMatch.value = PreMatchInfo(
            league = target.league.ifBlank { "LPL" },
            stage = target.blockName.ifBlank { "LPL" }.uppercase(),
            blue = left.code.ifBlank { left.name },
            red = right.code.ifBlank { right.name },
            startTime = formatLocalStart(target.startTimeIso),
            blueForm = scheduleTeamForm(left),
            redForm = scheduleTeamForm(right),
            blueRoster = leftRoster,
            redRoster = rightRoster,
            rosterNote = when {
                connectedCount == 2 && autoStarterCount == 2 ->
                    "两队 Riot getTeams roster 已连接，五位置均唯一；当前显示 Riot roster 五人。Rank 将接独立 Ranked 数据源。"
                connectedCount == 2 ->
                    "两队 Riot team roster 已连接；存在替补或位置歧义时不会冒充本场首发。只有单独核实过的阵容才允许走缓存。"
                connectedCount == 1 ->
                    "一侧 Riot roster 已连接；另一侧若没有单独核实的首发则保持空缺，不伪造。Rank 暂未接入。"
                else ->
                    "Riot Schedule 已连接，但当前队伍 roster 暂不可用；没有独立核实的数据就保持空缺。Rank 暂未接入。"
            }
        )
        _rosterStatus.value = "ROSTER · RIOT GETTEAMS $connectedCount/2 · AUTO STARTERS $autoStarterCount/2"
        return "$connectedCount/2"
    }

    private fun teamLookupSlug(team: EsportsTeamRef): String? {
        if (team.slug.isNotBlank()) return team.slug

        val verified = when (team.code.uppercase()) {
            "LGD" -> "lgd-gaming"
            "IG" -> "invictus-gaming"
            else -> null
        }
        if (verified != null) return verified

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

    private fun fallbackRoster(code: String): List<PlayerCard> = when (code.uppercase()) {
        "LGD" -> verifiedLgdRoster
        "IG" -> verifiedIgRoster
        else -> emptyList()
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
        val currentText = current?.let { "LIVE ${teamsLabel(it)}" } ?: "NO LIVE"
        val nextText = next?.let { "NEXT ${teamsLabel(it)} ${formatLocalDateTime(it.startTimeIso)}" } ?: "NO NEXT"
        return "Riot Schedule · ${matches.size} 场 · 已结束 $completed · $currentText · $nextText"
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

    fun scheduleTimingNote(match: ScheduledEsportsMatch): String {
        val detectedAt = _scheduleCenter.value.liveDetectedAtEpochMs[scheduleKey(match)] ?: return "计划 ${formatLocalStart(match.startTimeIso)}"
        val planned = plannedStartEpochMs(match) ?: return "LIVE 已检测"
        val deltaMs = planned - detectedAt
        return if (deltaMs >= 60_000L) {
            "计划 ${formatLocalStart(match.startTimeIso)} · 提前约 ${deltaMs / 60_000L} 分钟检测到 LIVE"
        } else {
            "计划 ${formatLocalStart(match.startTimeIso)} · LIVE 已检测"
        }
    }

    private fun scheduleKey(match: ScheduledEsportsMatch): String =
        match.eventId.ifBlank { match.matchId }

    private fun normalizeState(value: String): String =
        value.lowercase().replace("_", "").replace("-", "").replace(" ", "")

    private fun plannedStartEpochMs(match: ScheduledEsportsMatch): Long? =
        runCatching { Instant.parse(match.startTimeIso).toEpochMilli() }.getOrNull()

    private fun formatLocalStart(iso: String): String {
        if (iso.isBlank()) return "--:--"
        return runCatching {
            DateTimeFormatter.ofPattern("HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.parse(iso))
        }.getOrElse { iso }
    }

    private fun formatLocalDateTime(iso: String): String {
        if (iso.isBlank()) return "--"
        return runCatching {
            DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.parse(iso))
        }.getOrElse { iso }
    }

    private fun formatGold(value: Int): String =
        if (value >= 1000) "%.1fK".format(value / 1000f) else value.toString()

    /** Compatibility alias retained for older overlay/UI call sites. */
    fun ensureMockRunning() = ensureDataRunning()

    fun formatTime(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)
}
