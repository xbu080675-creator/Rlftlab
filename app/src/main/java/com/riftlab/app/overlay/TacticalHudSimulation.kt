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

enum class TacticalHudPhase { GLOBAL, FIGHT }

data class TacticalHudPlayer(
    val team: String,
    val role: String,
    val hpPercent: Int? = null,
    val alive: Boolean? = null,
    val flashReady: Boolean? = null,
    val ultimateReady: Boolean? = null,
    val smiteReady: Boolean? = null
)

data class TacticalHudState(
    val active: Boolean = false,
    val phase: TacticalHudPhase = TacticalHudPhase.GLOBAL,
    val step: Int = 0,
    val totalSteps: Int = 0,
    val clock: String = "--:--",
    val headline: String = "",
    val primary: String = "",
    val secondary: String = "",
    val blueAlive: Int? = null,
    val redAlive: Int? = null,
    val objective: String = "",
    val objectiveHp: Int? = null,
    val objectiveMaxHp: Int? = null,
    val players: List<TacticalHudPlayer> = emptyList(),
    val autoPlay: Boolean = false
)

/**
 * Overlay-only fixture for phone testing of the new Global/Fight HUD.
 *
 * Every value here is synthetic. The fixture never touches MatchSessionStore, the Cito realtime bus,
 * MatchTimelineStore or any archive. Its only job is to exercise layout density and phase changes on
 * top of a real video app before the production HUD is bound to verified realtime events.
 */
object TacticalHudSimulation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playbackJob: Job? = null

    private val script = listOf(
        TacticalHudState(
            phase = TacticalHudPhase.GLOBAL,
            clock = "18:32",
            headline = "DRAGON WINDOW · 0:44",
            primary = "BOT · 3 ITEM SPIKE",
            secondary = "TOP TP · 0:21"
        ),
        TacticalHudState(
            phase = TacticalHudPhase.GLOBAL,
            clock = "19:10",
            headline = "NEXT FIGHT · 0:06",
            primary = "RED ADC · FLASH 0:32",
            secondary = "BOTH JUG · SMITE READY"
        ),
        TacticalHudState(
            phase = TacticalHudPhase.FIGHT,
            clock = "19:18",
            headline = "DRAGON CONTEST",
            blueAlive = 5,
            redAlive = 5,
            objective = "DRAGON",
            objectiveHp = 4_120,
            objectiveMaxHp = 8_000,
            players = listOf(
                TacticalHudPlayer("BLUE", "JUG", hpPercent = 82, alive = true, smiteReady = true),
                TacticalHudPlayer("RED", "JUG", hpPercent = 76, alive = true, smiteReady = true)
            )
        ),
        TacticalHudState(
            phase = TacticalHudPhase.FIGHT,
            clock = "19:22",
            headline = "JUNGLE DOWN",
            blueAlive = 5,
            redAlive = 4,
            objective = "DRAGON",
            objectiveHp = 1_980,
            objectiveMaxHp = 8_000,
            players = listOf(
                TacticalHudPlayer("BLUE", "JUG", hpPercent = 61, alive = true, smiteReady = true),
                TacticalHudPlayer("RED", "JUG", hpPercent = 0, alive = false, smiteReady = false)
            )
        ),
        TacticalHudState(
            phase = TacticalHudPhase.FIGHT,
            clock = "19:26",
            headline = "CARRY WINDOW",
            blueAlive = 4,
            redAlive = 3,
            players = listOf(
                TacticalHudPlayer("BLUE", "ADC", hpPercent = 28, alive = true, flashReady = false, ultimateReady = true),
                TacticalHudPlayer("RED", "ADC", hpPercent = 43, alive = true, flashReady = false, ultimateReady = false)
            )
        ),
        TacticalHudState(
            phase = TacticalHudPhase.GLOBAL,
            clock = "19:34",
            headline = "FIGHT END · BLUE +2",
            primary = "LAST 60s · +1.8K",
            secondary = "BARON WINDOW · 1:08"
        )
    )

    private val _state = MutableStateFlow(TacticalHudState(totalSteps = script.size))
    val state: StateFlow<TacticalHudState> = _state.asStateFlow()

    @Synchronized
    fun startAuto() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            script.forEachIndexed { index, frame ->
                _state.value = frame.copy(
                    active = true,
                    step = index + 1,
                    totalSteps = script.size,
                    autoPlay = true
                )
                delay(if (frame.phase == TacticalHudPhase.FIGHT) 3_200L else 4_200L)
            }
            _state.value = TacticalHudState(totalSteps = script.size)
        }
    }

    @Synchronized
    fun startManual() {
        playbackJob?.cancel()
        _state.value = script.first().copy(active = true, step = 1, totalSteps = script.size)
    }

    @Synchronized
    fun next() {
        playbackJob?.cancel()
        val current = _state.value
        val nextIndex = if (!current.active) 0 else current.step.coerceAtMost(script.lastIndex)
        _state.value = script[nextIndex].copy(
            active = true,
            step = nextIndex + 1,
            totalSteps = script.size,
            autoPlay = false
        )
    }

    @Synchronized
    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        _state.value = TacticalHudState(totalSteps = script.size)
    }
}
