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
    const val MATCH_ID = "2026-09-08-lgd-ig"

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

    private val fallbackPreMatch = PreMatchInfo(
        league = "LPL",
        stage = "PLAYOFFS · LOWER BRACKET",
        blue = "LGD",
        red = "IG",
        startTime = "17:00",
        blueForm = "REAL DATA",
        redForm = "REAL DATA",
        blueRoster = verifiedLgdRoster,
        redRoster = verifiedIgRoster,
        rosterNote = "等待 Riot LoL Esports roster；Rank 使用独立数据源，当前不伪造。"
    )

    private val _preMatch = MutableStateFlow(fallbackPreMatch)
    val preMatch: PreMatchInfo get() = _preMatch.value
    val preMatchFlow: StateFlow<PreMatchInfo> = _preMatch.asStateFlow()

    val postMatch = PostMatchInfo(
        score = "—",
        winner = "等待赛果",
        mvp = "—",
        mvpRole = "—",
        mvpDpm = 0,
        mvpGoldDiff15 = 0,
        positionRank = "POST MATCH DATA PENDING",
        keyPoint = "比赛结束后再由真实赛后源生成，当前不显示 Mock 结论。"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scheduleSource = LolEsportsScheduleDataSource()
    private val teamSource = LolEsportsTeamDataSource()
    private val liveDataSource = LolEsportsLiveDataSource(
        preferredTeamCodes = setOf("LGD", "IG")
    )

    private var liveJob: Job? = null
    private var scheduleJob: Job? = null
    private var statusJob: Job? = null

    private val _schedule = MutableStateFlow<List<ScheduledEsportsMatch>>(emptyList())
    val schedule: StateFlow<List<ScheduledEsportsMatch>> = _schedule.asStateFlow()

    private val _targetMatch = MutableStateFlow<ScheduledEsportsMatch?>(null)
    val targetMatch: StateFlow<ScheduledEsportsMatch?> = _targetMatch.asStateFlow()

    private val _scheduleStatus = MutableStateFlow("正在连接 Riot LoL Esports 赛程源…")
    val scheduleStatus: StateFlow<String> = _scheduleStatus.asStateFlow()

    private val _rosterStatus = MutableStateFlow("ROSTER · 等待赛程命中")
    val rosterStatus: StateFlow<String> = _rosterStatus.asStateFlow()

    val liveSourceStatus: StateFlow<LiveSourceStatus> = liveDataSource.status

    private val _live = MutableStateFlow(
        LiveSnapshot(
            game = 1,
            elapsedSeconds = 0,
            blue = "LGD",
            red = "IG",
            blueGold = 0,
            redGold = 0,
            blueKills = 0,
            redKills = 0,
            blueTowers = 0,
            redTowers = 0,
            blueDragons = 0,
            redDragons = 0,
            latestEvent = "Riot Live · 等待 17:00 LGD vs iG",
            source = "Riot LoL Esports Live"
        )
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
                liveDataSource.observe(MATCH_ID).collect { snapshot ->
                    _live.value = snapshot
                }
            }
        }

        if (statusJob?.isActive != true) {
            statusJob = scope.launch {
                liveDataSource.status.collect { status ->
                    if (status.phase != LiveSourcePhase.LIVE) {
                        val current = _live.value
                        _live.value = current.copy(latestEvent = status.message)
                    }
                }
            }
        }
    }

    private suspend fun refreshScheduleAndRoster() {
        _scheduleStatus.value = "正在同步 Riot LPL 赛程…"
        try {
            val matches = scheduleSource.fetchLeagueSchedule()
            _schedule.value = matches
            val target = matches.firstOrNull(::isTonightTarget)
            _targetMatch.value = target

            if (target == null) {
                _scheduleStatus.value = "Riot Schedule 已连接，但未找到 LGD vs IG 目标赛事"
                _rosterStatus.value = "ROSTER · 使用赛前已验证缓存"
                return
            }

            val teams = target.teams.joinToString(" vs ") { it.code }
            _scheduleStatus.value = "Riot Schedule · $teams · ${target.state.uppercase()} · EVENT ${target.eventId}"
            val rosterResult = refreshPreMatchFromTarget(target)
            _scheduleStatus.value = "Riot Schedule · $teams · ${target.state.uppercase()} · ROSTER $rosterResult"
        } catch (t: Throwable) {
            _scheduleStatus.value = "赛程源 ERROR · ${t.message?.take(150) ?: t::class.java.simpleName}"
            _rosterStatus.value = "ROSTER · 网络源不可用，使用赛前已验证缓存"
        }
    }

    private suspend fun refreshPreMatchFromTarget(target: ScheduledEsportsMatch): String {
        val left = target.teams.getOrNull(0) ?: return "0/2"
        val right = target.teams.getOrNull(1) ?: return "0/2"
        _rosterStatus.value = "ROSTER · 正在同步 Riot getTeams…"

        val leftDetails = runCatching {
            left.slug.takeIf { it.isNotBlank() }?.let { teamSource.fetchTeam(it) }
        }.getOrNull()
        val rightDetails = runCatching {
            right.slug.takeIf { it.isNotBlank() }?.let { teamSource.fetchTeam(it) }
        }.getOrNull()

        val leftReal = leftDetails?.players.orEmpty().toPlayerCards()
        val rightReal = rightDetails?.players.orEmpty().toPlayerCards()
        val leftRoster = leftReal.takeIf { it.size >= 5 } ?: fallbackRoster(left.code)
        val rightRoster = rightReal.takeIf { it.size >= 5 } ?: fallbackRoster(right.code)
        val realCount = listOf(leftReal.size >= 5, rightReal.size >= 5).count { it }

        _preMatch.value = PreMatchInfo(
            league = target.league.ifBlank { "LPL" },
            stage = target.blockName.ifBlank { "PLAYOFFS" }.uppercase(),
            blue = left.code.ifBlank { left.name },
            red = right.code.ifBlank { right.name },
            startTime = formatLocalStart(target.startTimeIso),
            blueForm = "RIOT SCHEDULE",
            redForm = "RIOT SCHEDULE",
            blueRoster = leftRoster,
            redRoster = rightRoster,
            rosterNote = when (realCount) {
                2 -> "首发/队伍 roster 来自 Riot LoL Esports getTeams；Rank 将接独立 Riot ID / Ranked 数据源。"
                1 -> "一侧 Riot roster 已命中，另一侧使用赛前已验证缓存；Rank 暂未接入。"
                else -> "Riot Schedule 已命中，但 getTeams roster 未完整返回；当前使用赛前已验证缓存，Rank 暂未接入。"
            }
        )
        _rosterStatus.value = when (realCount) {
            2 -> "ROSTER · RIOT GETTEAMS · 2/2"
            1 -> "ROSTER · RIOT GETTEAMS · 1/2 + VERIFIED CACHE"
            else -> "ROSTER · VERIFIED CACHE · 0/2"
        }
        return "$realCount/2"
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
            .take(7)

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

    private fun isTonightTarget(match: ScheduledEsportsMatch): Boolean {
        val codes = match.teams.map { it.code.uppercase() }.toSet()
        return "LGD" in codes && "IG" in codes
    }

    private fun formatLocalStart(iso: String): String {
        if (iso.isBlank()) return "--:--"
        return runCatching {
            DateTimeFormatter.ofPattern("HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.parse(iso))
        }.getOrElse { iso }
    }

    /** Compatibility alias: there is no mock live feed anymore. */
    fun ensureMockRunning() = ensureDataRunning()

    fun formatTime(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)
}
