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
 * 2026-09-07 LPL 第三赛段季后赛胜者组决赛 · AL vs BLG · G1。
 * 英雄与选手对应真实赛果；胜率、样本、CSD@15 等分析值仍为模拟值，仅用于 UI/交互测试。
 * 蓝色方 AL：加里奥 / 潘森 / 辛德拉 / 伊泽瑞尔 / 卡尔玛。
 * 红色方 BLG：兰博 / 奇亚娜 / 瑞兹 / 艾希 / 萨勒芬妮。
 */
object DraftHudSimulation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playbackJob: Job? = null
    private var handoffEnabled = true

    private val script = listOf(
        DraftHudPick(DraftSide.BLUE, DraftRole.TOP, "Breathe", "加里奥", 50.8, 52, 7, 57.1, "上路"),
        DraftHudPick(DraftSide.RED, DraftRole.TOP, "Bin", "兰博", 53.6, 71, 17, 64.7, "上路"),
        DraftHudPick(DraftSide.BLUE, DraftRole.JUG, "Tarzan", "潘森", 51.9, 66, 12, 58.3, "打野"),
        DraftHudPick(DraftSide.RED, DraftRole.JUG, "Xun", "奇亚娜", 52.7, 43, 9, 66.7, "打野"),
        DraftHudPick(DraftSide.BLUE, DraftRole.MID, "Shanks", "辛德拉", 51.4, 89, 21, 57.1, "中路"),
        DraftHudPick(DraftSide.RED, DraftRole.MID, "knight", "瑞兹", 54.0, 78, 19, 68.4, "中路"),
        DraftHudPick(DraftSide.BLUE, DraftRole.BOT, "Hope", "伊泽瑞尔", 51.6, 103, 24, 58.3, "下路"),
        DraftHudPick(DraftSide.RED, DraftRole.BOT, "Viper", "艾希", 53.2, 97, 23, 65.2, "下路"),
        DraftHudPick(DraftSide.BLUE, DraftRole.SUP, "Kael", "卡尔玛", 52.1, 82, 18, 61.1, "辅助"),
        DraftHudPick(DraftSide.RED, DraftRole.SUP, "ON", "萨勒芬妮", 51.7, 48, 10, 60.0, "辅助")
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
            message = if (autoPlay) "胜者组决赛 G1 · BP 自动模拟" else "胜者组决赛 G1 · BP 手动模拟"
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
            DraftRole.TOP -> Triple("BLG 推线与团战伤害更主动", -3.4, 46)
            DraftRole.JUG -> Triple("BLG 野区爆发更高", -1.2, 55)
            DraftRole.MID -> Triple("BLG 支援节奏更强", -2.6, 63)
            DraftRole.BOT -> Triple("BLG 下路先手更强", -1.8, 71)
            DraftRole.SUP -> Triple("AL 保护更稳 · BLG 团战范围更大", 0.2, 58)
        }
        return DraftHudMatchup(role, left.champion, right.champion, fixture.first, fixture.second, fixture.third, if (fixture.third >= 60) "中高" else "中")
    }

    private fun teamName(side: DraftSide): String = if (side == DraftSide.BLUE) "AL" else "BLG"
}
