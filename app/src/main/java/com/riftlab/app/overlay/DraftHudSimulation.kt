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

/**
 * Local-only BP fixture used to validate RiftScreen layout before provider draft events are mapped
 * into the shared Cito realtime bus. Synthetic percentages/edges must never enter match archives.
 * The fixture now follows production HUD lifecycle: the BP surface deactivates on the final lock so
 * the small global live HUD can immediately reclaim the screen.
 */
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

object DraftHudSimulation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playbackJob: Job? = null

    private val script = listOf(
        DraftHudPick(DraftSide.BLUE, DraftRole.TOP, "Burdol", "Karma", 53.8, 41, 4, 75.0, "TOP / MID"),
        DraftHudPick(DraftSide.RED, DraftRole.TOP, "TheShy", "Ambessa", 51.4, 67, 9, 55.6),
        DraftHudPick(DraftSide.BLUE, DraftRole.JUG, "Heng", "Xin Zhao", 50.6, 88, 12, 58.3),
        DraftHudPick(DraftSide.RED, DraftRole.JUG, "Wei", "Wukong", 52.1, 73, 15, 60.0),
        DraftHudPick(DraftSide.BLUE, DraftRole.MID, "Tangyuan", "Azir", 48.9, 94, 18, 50.0),
        DraftHudPick(DraftSide.RED, DraftRole.MID, "Rookie", "Orianna", 51.7, 86, 21, 61.9),
        DraftHudPick(DraftSide.BLUE, DraftRole.BOT, "Shaoye", "Kai'Sa", 50.2, 109, 23, 56.5),
        DraftHudPick(DraftSide.RED, DraftRole.BOT, "JiaQi", "Ezreal", 49.6, 102, 17, 52.9),
        DraftHudPick(DraftSide.BLUE, DraftRole.SUP, "Crisp", "Rakan", 52.4, 79, 16, 62.5),
        DraftHudPick(DraftSide.RED, DraftRole.SUP, "Meiko", "Nautilus", 50.8, 91, 20, 55.0)
    )

    private val _state = MutableStateFlow(DraftHudState(totalSteps = script.size))
    val state: StateFlow<DraftHudState> = _state.asStateFlow()

    @Synchronized
    fun startAuto() {
        playbackJob?.cancel()
        resetInternal(active = true, autoPlay = true)
        playbackJob = scope.launch {
            delay(700)
            while (_state.value.active && _state.value.autoPlay && !_state.value.finished) {
                advanceInternal()
                if (!_state.value.finished) delay(3_800)
            }
        }
    }

    @Synchronized
    fun startManual() {
        playbackJob?.cancel()
        resetInternal(active = true, autoPlay = false)
    }

    @Synchronized
    fun next() {
        if (!_state.value.active && !_state.value.finished) resetInternal(active = true, autoPlay = false)
        if (_state.value.autoPlay) {
            playbackJob?.cancel()
            _state.value = _state.value.copy(autoPlay = false, message = "BP 模拟已暂停 · 手动步进")
        }
        advanceInternal()
    }

    @Synchronized
    fun toggleAuto() {
        if (!_state.value.active || _state.value.finished) {
            startAuto()
            return
        }
        if (_state.value.autoPlay) {
            playbackJob?.cancel()
            _state.value = _state.value.copy(autoPlay = false, message = "BP 模拟已暂停")
        } else {
            _state.value = _state.value.copy(autoPlay = true, message = "BP 模拟自动播放")
            playbackJob = scope.launch {
                while (_state.value.active && _state.value.autoPlay && !_state.value.finished) {
                    delay(3_800)
                    advanceInternal()
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        _state.value = DraftHudState(totalSteps = script.size)
    }

    private fun resetInternal(active: Boolean, autoPlay: Boolean) {
        _state.value = DraftHudState(
            active = active,
            autoPlay = autoPlay,
            totalSteps = script.size,
            message = if (autoPlay) "BP 模拟自动播放" else "BP 模拟手动模式"
        )
    }

    @Synchronized
    private fun advanceInternal() {
        val current = _state.value
        if (!current.active || current.finished) return
        val nextPick = script.getOrNull(current.step) ?: run {
            _state.value = current.copy(
                active = false,
                autoPlay = false,
                finished = true,
                message = "BP 已锁定 · HUD 已撤出"
            )
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
            message = if (finished) "BP 已锁定 · HUD 已撤出" else "${nextPick.side.name} ${nextPick.player} 锁定 ${nextPick.champion}"
        )
    }

    private fun buildMatchup(
        role: DraftRole,
        blue: List<DraftHudPick>,
        red: List<DraftHudPick>
    ): DraftHudMatchup? {
        val left = blue.lastOrNull { it.role == role } ?: return null
        val right = red.lastOrNull { it.role == role } ?: return null
        val fixture = when (role) {
            DraftRole.TOP -> Triple("BLUE LANE EDGE", 3.8, 38)
            DraftRole.JUG -> Triple("RED SOFT COUNTER", -2.1, 44)
            DraftRole.MID -> Triple("EVEN MATCHUP", 0.7, 52)
            DraftRole.BOT -> Triple("BLUE SOFT EDGE", 2.6, 61)
            DraftRole.SUP -> Triple("ENGAGE TRADE-OFF", -0.9, 47)
        }
        return DraftHudMatchup(
            role = role,
            blueChampion = left.champion,
            redChampion = right.champion,
            verdict = fixture.first,
            csd15 = fixture.second,
            sampleGames = fixture.third,
            confidence = if (fixture.third >= 50) "中高" else "中"
        )
    }
}
