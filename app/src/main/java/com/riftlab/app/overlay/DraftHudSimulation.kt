package com.riftlab.app.overlay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DraftSide { BLUE, RED }
enum class DraftRole { TOP, JUG, MID, BOT, SUP }

data class DraftHudPick(
    val side: DraftSide,
    val role: DraftRole,
    val player: String,
    val champion: String,
    val versionWinRate: Double,
    val sampleGames: Int,
    val playerGames: Int,
    val playerWinRate: Double,
    val roleHint: String = role.name
)

data class DraftHudMatchup(
    val role: DraftRole,
    val blueChampion: String,
    val redChampion: String,
    val verdict: String,
    val csd15: Double,
    val sampleGames: Int,
    val confidence: String
)

data class DraftHudState(
    val active: Boolean = false,
    val autoPlay: Boolean = false,
    val step: Int = 0,
    val totalSteps: Int = 10,
    val bluePicks: List<DraftHudPick> = emptyList(),
    val redPicks: List<DraftHudPick> = emptyList(),
    val latestPick: DraftHudPick? = null,
    val matchup: DraftHudMatchup? = null,
    val message: String = "BP 模拟待机",
    val finished: Boolean = false
)

/**
 * BLG vs AL · 2026 LPL 第三赛段 · 第一局的演示 BP。
 * 所有英雄、胜率、样本与对位数据均为模拟数据，仅用于 UI/交互测试。
 */
object DraftHudSimulation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playbackJob: Job? = null
    private var handoffEnabled = true

    private val script = listOf(
        DraftHudPick(DraftSide.BLUE, DraftRole.TOP, "Bin", "纳尔", 52.8, 74, 18, 61.1, "上路"),
        DraftHudPick(DraftSide.RED, DraftRole.TOP, "Flandre", "凯南", 51.9, 69, 15, 60.0, "上路"),
        DraftHudPick(DraftSide.BLUE, DraftRole.JUG, "Wei", "蔚", 53.4, 81, 16, 62.5, "打野"),
        DraftHudPick(DraftSide.RED, DraftRole.JUG, "Tarzan", "猴子", 52.7, 88, 21, 61.9, "打野"),
        DraftHudPick(DraftSide.BLUE, DraftRole.MID, "knight", "阿狸", 54.1, 96, 24, 66.7, "中路"),
        DraftHudPick(DraftSide.RED, DraftRole.MID, "Shanks", "沙皇", 50.8, 91, 20, 55.0, "中路"),
        DraftHudPick(DraftSide.BLUE, DraftRole.BOT, "Viper", "卡莎", 52.5, 112, 27, 63.0, "下路"),
        DraftHudPick(DraftSide.RED, DraftRole.BOT, "Hope", "伊泽瑞尔", 51.2, 108, 26, 57.7, "下路"),
        DraftHudPick(DraftSide.BLUE, DraftRole.SUP, "ON", "洛", 53.0, 84, 19, 63.2, "辅助"),
        DraftHudPick(DraftSide.RED, DraftRole.SUP, "Kael", "芮尔", 51.6, 86, 22, 59.1, "辅助")
    )

    private val _state = MutableStateFlow(DraftHudState(totalSteps = script.size))
    val state: StateFlow<DraftHudState> = _state.asStateFlow()

    @Synchronized
    fun startAuto(handoffToTactical: Boolean = true) {
        playbackJob?.cancel()
        handoffEnabled = handoffToTactical
        resetInternal(active = true, autoPlay = true)
        playbackJob = scope.launch {
            delay(700)
            while (_state.value.active && _state.value.autoPlay && !_state.value.finished) {
                advanceInternal()
                if (!_state.value.finished) delay(3_000)
            }
        }
    }

    @Synchronized
    fun startManual(handoffToTactical: Boolean = true) {
        playbackJob?.cancel()
        handoffEnabled = handoffToTactical
        resetInternal(active = true, autoPlay = false)
    }

    @Synchronized
    fun next() {
        if (!_state.value.active && !_state.value.finished) {
            handoffEnabled = true
            resetInternal(active = true, autoPlay = false)
        }
        if (_state.value.autoPlay) {
            playbackJob?.cancel()
            _state.value = _state.value.copy(autoPlay = false, message = "BP 模拟已暂停 · 手动步进")
        }
        advanceInternal()
    }

    @Synchronized
    fun toggleAuto() {
        if (!_state.value.active || _state.value.finished) {
            startAuto(handoffToTactical = true)
            return
        }
        if (_state.value.autoPlay) {
            playbackJob?.cancel()
            _state.value = _state.value.copy(autoPlay = false, message = "BP 模拟已暂停")
        } else {
            _state.value = _state.value.copy(autoPlay = true, message = "BP 模拟自动播放")
            playbackJob = scope.launch {
                while (_state.value.active && _state.value.autoPlay && !_state.value.finished) {
                    delay(3_000)
                    advanceInternal()
                }
            }
        }
    }

    @Synchronized
    fun stop(stopTactical: Boolean = true) {
        playbackJob?.cancel()
        playbackJob = null
        handoffEnabled = true
        _state.value = DraftHudState(totalSteps = script.size)
        if (stopTactical) TacticalHudSimulation.stop()
    }

    private fun resetInternal(active: Boolean, autoPlay: Boolean) {
        _state.value = DraftHudState(
            active = active,
            autoPlay = autoPlay,
            totalSteps = script.size,
            message = if (autoPlay) "BLG vs AL · 第一局 BP 自动模拟" else "BLG vs AL · 第一局 BP 手动模拟"
        )
    }

    @Synchronized
    private fun advanceInternal() {
        val current = _state.value
        if (!current.active || current.finished) return
        val nextPick = script.getOrNull(current.step) ?: run {
            finish(current)
            return
        }

        val blue = if (nextPick.side == DraftSide.BLUE) current.bluePicks + nextPick else current.bluePicks
        val red = if (nextPick.side == DraftSide.RED) current.redPicks + nextPick else current.redPicks
        val matchup = buildMatchup(nextPick.role, blue, red)
        val nextStep = current.step + 1
        val finished = nextStep >= script.size
        _state.value = current.copy(
            active = !finished,
            step = nextStep,
            bluePicks = blue,
            redPicks = red,
            latestPick = nextPick,
            matchup = matchup,
            autoPlay = current.autoPlay && !finished,
            finished = finished,
            message = if (finished) "BP 已锁定 · 准备进入比赛态势" else "${teamName(nextPick.side)} ${nextPick.player} 锁定 ${nextPick.champion}"
        )
        if (finished) handoffIfNeeded()
    }

    private fun finish(current: DraftHudState) {
        _state.value = current.copy(active = false, autoPlay = false, finished = true, message = "BP 已锁定 · 准备进入比赛态势")
        handoffIfNeeded()
    }

    private fun handoffIfNeeded() {
        if (!handoffEnabled) return
        handoffEnabled = false
        scope.launch {
            delay(1_200L)
            TacticalHudSimulation.startAuto()
        }
    }

    private fun buildMatchup(role: DraftRole, blue: List<DraftHudPick>, red: List<DraftHudPick>): DraftHudMatchup? {
        val left = blue.lastOrNull { it.role == role } ?: return null
        val right = red.lastOrNull { it.role == role } ?: return null
        val fixture = when (role) {
            DraftRole.TOP -> Triple("BLG 对线小优", 3.2, 46)
            DraftRole.JUG -> Triple("野区节奏接近", 0.6, 55)
            DraftRole.MID -> Triple("BLG 线权小优", 2.4, 63)
            DraftRole.BOT -> Triple("下路对线接近", 1.1, 71)
            DraftRole.SUP -> Triple("开团能力各有侧重", -0.4, 58)
        }
        return DraftHudMatchup(role, left.champion, right.champion, fixture.first, fixture.second, fixture.third, if (fixture.third >= 60) "中高" else "中")
    }

    private fun teamName(side: DraftSide): String = if (side == DraftSide.BLUE) "BLG" else "AL"
}
