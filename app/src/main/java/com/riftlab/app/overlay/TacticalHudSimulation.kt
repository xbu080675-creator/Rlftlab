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
    val player: String = "",
    val hpPercent: Int? = null,
    val alive: Boolean? = null,
    val flashReady: Boolean? = null,
    val ultimateReady: Boolean? = null,
    val smiteReady: Boolean? = null,
    val note: String = ""
)

data class TacticalHudEvidence(
    val label: String,
    val value: String,
    val emphasis: Boolean = false
)

data class TacticalHudState(
    val active: Boolean = false,
    val phase: TacticalHudPhase = TacticalHudPhase.GLOBAL,
    val step: Int = 0,
    val totalSteps: Int = 0,
    val clock: String = "--:--",
    val matchLabel: String = "BLG vs AL · 2026 LPL 第三赛段 · 第一局",
    val headline: String = "",
    val explanation: String = "",
    val evidence: List<TacticalHudEvidence> = emptyList(),
    val blueAlive: Int? = null,
    val redAlive: Int? = null,
    val objective: String = "",
    val objectiveHp: Int? = null,
    val objectiveMaxHp: Int? = null,
    val players: List<TacticalHudPlayer> = emptyList(),
    val autoPlay: Boolean = false
)

/**
 * BLG vs AL · 2026 LPL 第三赛段 · 第一局的完整比赛阶段模拟。
 * 所有数值均为模拟数据，只用于验证“赛事 X 光层”的视觉与流程，不进入真实比赛数据。
 */
object TacticalHudSimulation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playbackJob: Job? = null

    private val script = listOf(
        TacticalHudState(
            phase = TacticalHudPhase.GLOBAL,
            clock = "06:18",
            headline = "AL 上路出现关键技能异常",
            explanation = "Flandre 的凯南已经 6 级，但大招尚未学习。先锋前这段时间，AL 上路的正面团战威胁低于正常 6 级凯南。",
            evidence = listOf(
                TacticalHudEvidence("等级", "Lv.6"),
                TacticalHudEvidence("大招", "未学习", true),
                TacticalHudEvidence("先锋刷新", "1分37秒")
            ),
            players = listOf(
                TacticalHudPlayer("AL", "上路", "Flandre", hpPercent = 91, alive = true, flashReady = true, ultimateReady = false, note = "R 未学习")
            )
        ),
        TacticalHudState(
            phase = TacticalHudPhase.GLOBAL,
            clock = "07:26",
            headline = "凯南大招已学习",
            explanation = "AL 上路的关键团战能力恢复。下一次河道碰撞需要重新计算凯南进场威胁。",
            evidence = listOf(
                TacticalHudEvidence("大招", "已学习", true),
                TacticalHudEvidence("闪现", "可用"),
                TacticalHudEvidence("先锋刷新", "29秒")
            ),
            players = listOf(
                TacticalHudPlayer("AL", "上路", "Flandre", hpPercent = 88, alive = true, flashReady = true, ultimateReady = true, note = "可进场")
            )
        ),
        TacticalHudState(
            phase = TacticalHudPhase.GLOBAL,
            clock = "18:32",
            headline = "BLG 下半区进入资源窗口",
            explanation = "Viper 已先完成关键装备，Wei 惩戒可用；AL 下路回城节奏更晚。下一条小龙前，BLG 更容易先占河道位置。",
            evidence = listOf(
                TacticalHudEvidence("Viper 装备节点", "已完成", true),
                TacticalHudEvidence("Wei 惩戒", "可用"),
                TacticalHudEvidence("小龙刷新", "44秒"),
                TacticalHudEvidence("AL 下路回城", "晚约12秒")
            ),
            players = listOf(
                TacticalHudPlayer("BLG", "下路", "Viper", hpPercent = 100, alive = true, flashReady = true, ultimateReady = true, note = "关键装备完成"),
                TacticalHudPlayer("BLG", "打野", "Wei", hpPercent = 94, alive = true, smiteReady = true, note = "惩戒可用")
            )
        ),
        TacticalHudState(
            phase = TacticalHudPhase.FIGHT,
            clock = "19:18",
            headline = "小龙区接战",
            explanation = "双方打野惩戒都在，先看血量与关键位存活，不重复直播底板已有的总击杀与总经济。",
            evidence = listOf(
                TacticalHudEvidence("Wei 惩戒", "可用", true),
                TacticalHudEvidence("Tarzan 惩戒", "可用"),
                TacticalHudEvidence("小龙血量", "4120 / 8000")
            ),
            blueAlive = 5,
            redAlive = 5,
            objective = "小龙",
            objectiveHp = 4_120,
            objectiveMaxHp = 8_000,
            players = listOf(
                TacticalHudPlayer("BLG", "打野", "Wei", hpPercent = 82, alive = true, smiteReady = true, note = "可惩戒"),
                TacticalHudPlayer("AL", "打野", "Tarzan", hpPercent = 76, alive = true, smiteReady = true, note = "可惩戒")
            )
        ),
        TacticalHudState(
            phase = TacticalHudPhase.FIGHT,
            clock = "19:22",
            headline = "AL 打野阵亡，资源判断改变",
            explanation = "Tarzan 已阵亡且惩戒退出争夺。BLG 对小龙的控制权显著提高，这是直播底板通常不会完整解释的状态变化。",
            evidence = listOf(
                TacticalHudEvidence("Tarzan", "阵亡", true),
                TacticalHudEvidence("AL 惩戒", "不可用", true),
                TacticalHudEvidence("小龙血量", "1980 / 8000")
            ),
            blueAlive = 5,
            redAlive = 4,
            objective = "小龙",
            objectiveHp = 1_980,
            objectiveMaxHp = 8_000,
            players = listOf(
                TacticalHudPlayer("BLG", "打野", "Wei", hpPercent = 61, alive = true, smiteReady = true, note = "唯一惩戒"),
                TacticalHudPlayer("AL", "打野", "Tarzan", hpPercent = 0, alive = false, smiteReady = false, note = "阵亡")
            )
        ),
        TacticalHudState(
            phase = TacticalHudPhase.FIGHT,
            clock = "19:26",
            headline = "双方主输出仍在场",
            explanation = "人数已经变化，但真正决定后续追击的是主输出状态：Viper 血量偏低但大招可用，Hope 大招已交。",
            evidence = listOf(
                TacticalHudEvidence("Viper", "28%血量 · R可用", true),
                TacticalHudEvidence("Hope", "43%血量 · R已用"),
                TacticalHudEvidence("存活", "BLG 4 : 3 AL")
            ),
            blueAlive = 4,
            redAlive = 3,
            players = listOf(
                TacticalHudPlayer("BLG", "下路", "Viper", hpPercent = 28, alive = true, flashReady = false, ultimateReady = true, note = "R 可用"),
                TacticalHudPlayer("AL", "下路", "Hope", hpPercent = 43, alive = true, flashReady = false, ultimateReady = false, note = "R 已用")
            )
        ),
        TacticalHudState(
            phase = TacticalHudPhase.GLOBAL,
            clock = "19:34",
            headline = "团战结束，重新回到全局态势",
            explanation = "BLG 获得下一段地图主动权。HUD 恢复全局信息层，只保留直播没有持续展示的技能、装备与资源准备信息。",
            evidence = listOf(
                TacticalHudEvidence("Wei 惩戒", "31秒后转好"),
                TacticalHudEvidence("Viper 闪现", "仍未转好"),
                TacticalHudEvidence("大龙刷新", "1分08秒", true)
            )
        )
    )

    private val _state = MutableStateFlow(TacticalHudState(totalSteps = script.size))
    val state: StateFlow<TacticalHudState> = _state.asStateFlow()

    @Synchronized
    fun startAuto() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            script.forEachIndexed { index, frame ->
                _state.value = frame.copy(active = true, step = index + 1, totalSteps = script.size, autoPlay = true)
                delay(if (frame.phase == TacticalHudPhase.FIGHT) 4_000L else 5_000L)
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
        _state.value = script[nextIndex].copy(active = true, step = nextIndex + 1, totalSteps = script.size, autoPlay = false)
    }

    @Synchronized
    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        _state.value = TacticalHudState(totalSteps = script.size)
    }
}
