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

object MatchSessionStore {
    const val MATCH_ID = "2026-09-08-lgd-ig"

    // Tonight's test target. Schedule/live values are fetched from Riot LoL Esports at runtime.
    // The lineup is a verified pre-match cache; Rank is deliberately not fabricated.
    val preMatch = PreMatchInfo(
        league = "LPL",
        stage = "PLAYOFFS · LOWER BRACKET",
        blue = "LGD",
        red = "IG",
        startTime = "17:00",
        blueForm = "REAL DATA",
        redForm = "REAL DATA",
        blueRoster = listOf(
            PlayerCard("TOP", "Burdol", "RANK 待接", "首发"),
            PlayerCard("JUG", "Heng", "RANK 待接", "首发"),
            PlayerCard("MID", "Tangyuan", "RANK 待接", "首发"),
            PlayerCard("BOT", "Shaoye", "RANK 待接", "首发"),
            PlayerCard("SUP", "Crisp", "RANK 待接", "首发")
        ),
        redRoster = listOf(
            PlayerCard("TOP", "TheShy", "RANK 待接", "首发"),
            PlayerCard("JUG", "Wei", "RANK 待接", "首发"),
            PlayerCard("MID", "Rookie", "RANK 待接", "首发"),
            PlayerCard("BOT", "JiaQi", "RANK 待接", "首发"),
            PlayerCard("SUP", "Meiko", "RANK 待接", "首发")
        ),
        rosterNote = "9月8日 LGD vs iG 实战测试场。赛程与实时比赛数据走 Riot LoL Esports；Rank 独立数据源下一步接入。"
    )

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
                    _scheduleStatus.value = "正在同步 Riot LPL 赛程…"
                    runCatching { scheduleSource.fetchLeagueSchedule() }
                        .onSuccess { matches ->
                            _schedule.value = matches
                            val target = matches.firstOrNull(::isTonightTarget)
                            _targetMatch.value = target
                            _scheduleStatus.value = if (target != null) {
                                val teams = target.teams.joinToString(" vs ") { it.code }
                                "Riot Schedule · $teams · ${target.state.uppercase()} · EVENT ${target.eventId}"
                            } else {
                                "Riot Schedule 已连接，但未找到 LGD vs IG 目标赛事"
                            }
                        }
                        .onFailure { error ->
                            _scheduleStatus.value = "赛程源 ERROR · ${error.message?.take(150) ?: error::class.java.simpleName}"
                        }
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

    /** Kept temporarily so the current Compose shell does not need a broad rewrite. It no longer starts Mock data. */
    fun ensureMockRunning() = ensureDataRunning()

    private fun isTonightTarget(match: ScheduledEsportsMatch): Boolean {
        val codes = match.teams.map { it.code.uppercase() }.toSet()
        return "LGD" in codes && "IG" in codes
    }

    fun formatTime(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)
}
