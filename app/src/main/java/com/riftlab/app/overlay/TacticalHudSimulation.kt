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
    val matchLabel: String = "2026 LPL 第三赛段 · 胜者组决赛 G1 · AL vs BLG",
    val blueTeam: String = "AL",
    val redTeam: String = "BLG",
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
 * 2026-09-07 LPL 第三赛段季后赛胜者组决赛 · AL vs BLG · G1。
 * 事件时间与主要比赛骨架按公开赛后战报复原；闪现、技能冷却、局部血量、装备差等
 * “赛事 X 光层”字段为模拟补充，只用于验证视觉体验，不进入真实比赛数据或归档。
 */
object TacticalHudSimulation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playbackJob: Job? = null

    private val script = listOf(
        TacticalHudState(
            phase = TacticalHudPhase.GLOBAL,
            clock = "06:12",
            headline = "BLG 下路越塔窗口已经形成",
            explanation = "Xun 与 knight 已同时向下靠，AL 下路撤退空间正在缩小。这里不重复直播底板，而是把镜头外的人员联动直接做成态势提示。",
            evidence = listOf(
                TacticalHudEvidence("Xun", "进入下半区", true),
                TacticalHudEvidence("knight", "可快速支援"),
                TacticalHudEvidence("Hope", "防御塔下"),
                TacticalHudEvidence("风险", "4人越塔窗口", true)
            ),
            players = listOf(
                TacticalHudPlayer("BLG", "打野", "Xun", hpPercent = 92, alive = true, note = "向下靠"),
                TacticalHudPlayer("BLG", "中路", "knight", hpPercent = 95, alive = true, ultimateReady = true, note = "支援可用"),
                TacticalHudPlayer("AL", "下路", "Hope", hpPercent = 88, alive = true, flashReady = true, note = "塔下受压")
            )
        ),
        TacticalHudState(
            phase = TacticalHudPhase.FIGHT,
            clock = "06:20",
            headline = "越塔开始：AL 下路成为集火点",
            explanation = "真实比赛在这个时间点由 BLG 四人越塔击杀 Hope。HUD 重点展示谁已经进入有效支援范围，而不是重复击杀数字。",
            evidence = listOf(
                TacticalHudEvidence("Xun", "已进场", true),
                TacticalHudEvidence("knight", "已支援"),
                TacticalHudEvidence("Hope", "撤退路线被压缩", true)
            ),
            blueAlive = 4,
            redAlive = 5,
            players = listOf(
                TacticalHudPlayer("AL", "下路", "Hope", hpPercent = 0, alive = false, note = "被越塔击杀"),
                TacticalHudPlayer("BLG", "打野", "Xun", hpPercent = 71, alive = true, note = "完成进场"),
                TacticalHudPlayer("BLG", "中路", "knight", hpPercent = 83, alive = true, note = "支援到位")
            )
        ),
        TacticalHudState(
            phase = TacticalHudPhase.GLOBAL,
            clock = "06:56",
            headline = "Tarzan 残血撤离，小龙后的安全窗口很短",
            explanation = "公开战报显示 Tarzan 吃完小龙后半血撤退，并在 7:02 被 Xun 抓到。这里模拟把血量与附近追击威胁提前视觉化。",
            evidence = listOf(
                TacticalHudEvidence("Tarzan", "约半血", true),
                TacticalHudEvidence("Xun", "附近可追击"),
                TacticalHudEvidence("撤离风险", "高", true)
            ),
            players = listOf(
                TacticalHudPlayer("AL", "打野", "Tarzan", hpPercent = 48, alive = true, flashReady = true, note = "残血撤离"),
                TacticalHudPlayer("BLG", "打野", "Xun", hpPercent = 79, alive = true, ultimateReady = true, note = "具备追击条件")
            )
        ),
        TacticalHudState(
            phase = TacticalHudPhase.FIGHT,
            clock = "12:48",
            headline = "小龙争夺：Tarzan 抢龙失败后暴露",
            explanation = "真实比赛此处 Tarzan 抢龙失败并被 Viper 艾希命中，BLG 人龙双收。X 光层强调的是打野暴露和后续控制链，而不是再报一次比分。",
            evidence = listOf(
                TacticalHudEvidence("Tarzan", "位置暴露", true),
                TacticalHudEvidence("Viper", "控制命中", true),
                TacticalHudEvidence("后续", "BLG 可接控制链")
            ),
            blueAlive = 4,
            redAlive = 5,
            objective = "小龙",
            objectiveHp = 0,
            objectiveMaxHp = 8_000,
            players = listOf(
                TacticalHudPlayer("AL", "打野", "Tarzan", hpPercent = 0, alive = false, smiteReady = false, note = "争夺失败后阵亡"),
                TacticalHudPlayer("BLG", "下路", "Viper", hpPercent = 86, alive = true, ultimateReady = true, note = "控制命中")
            )
        ),
        TacticalHudState(
            phase = TacticalHudPhase.GLOBAL,
            clock = "14:56",
            headline = "先锋团前，AL 中路站位已经成为薄弱点",
            explanation = "15:05 的真实先锋团由 Xun 先手击杀 Shanks 开始。这里模拟展示团战爆发前几秒的站位风险，让观众提前看懂为什么这波会突然崩。",
            evidence = listOf(
                TacticalHudEvidence("Shanks", "靠近上河三角草", true),
                TacticalHudEvidence("Xun", "具备推墙角度", true),
                TacticalHudEvidence("AL", "即将少一人接团")
            ),
            players = listOf(
                TacticalHudPlayer("AL", "中路", "Shanks", hpPercent = 96, alive = true, flashReady = true, note = "站位危险"),
                TacticalHudPlayer("BLG", "打野", "Xun", hpPercent = 91, alive = true, ultimateReady = true, note = "先手角度形成")
            )
        ),
        TacticalHudState(
            phase = TacticalHudPhase.FIGHT,
            clock = "15:05",
            headline = "先锋团失衡：AL 在少人状态下被迫接战",
            explanation = "Shanks 被先手击杀后，AL 河道实际进入四打五。随后 BLG 连续收割，真实比赛这一波打成 0 换 4。",
            evidence = listOf(
                TacticalHudEvidence("Shanks", "已阵亡", true),
                TacticalHudEvidence("人数", "AL 4 : 5 BLG", true),
                TacticalHudEvidence("Tarzan", "正面承压"),
                TacticalHudEvidence("Viper", "可持续输出")
            ),
            blueAlive = 4,
            redAlive = 5,
            players = listOf(
                TacticalHudPlayer("AL", "中路", "Shanks", hpPercent = 0, alive = false, note = "先手被秒"),
                TacticalHudPlayer("AL", "打野", "Tarzan", hpPercent = 42, alive = true, note = "正面承压"),
                TacticalHudPlayer("BLG", "下路", "Viper", hpPercent = 84, alive = true, note = "持续输出窗口")
            )
        ),
        TacticalHudState(
            phase = TacticalHudPhase.GLOBAL,
            clock = "17:43",
            headline = "第二次小龙团前，AL 进场容错已经很低",
            explanation = "17:51 的真实小龙团里 Tarzan 潘森跳大进场后迅速被融化。这里模拟用进场路径、血量和后排跟进距离提前表示风险。",
            evidence = listOf(
                TacticalHudEvidence("Tarzan", "准备大招进场", true),
                TacticalHudEvidence("AL 后排", "跟进距离偏远"),
                TacticalHudEvidence("BLG", "反打阵型完整", true)
            ),
            players = listOf(
                TacticalHudPlayer("AL", "打野", "Tarzan", hpPercent = 81, alive = true, ultimateReady = true, note = "准备进场"),
                TacticalHudPlayer("BLG", "上路", "Bin", hpPercent = 94, alive = true, ultimateReady = true, note = "反打可用"),
                TacticalHudPlayer("BLG", "下路", "Viper", hpPercent = 97, alive = true, note = "输出位置安全")
            )
        ),
        TacticalHudState(
            phase = TacticalHudPhase.FIGHT,
            clock = "17:51",
            headline = "Tarzan 落地即被集火，AL 开团链断裂",
            explanation = "真实比赛这一波 BLG 再打出 0 换 4。观赛层突出的是：潘森先手没有获得队友同步跟进，导致第一时间变成单点进场。",
            evidence = listOf(
                TacticalHudEvidence("Tarzan", "落地被集火", true),
                TacticalHudEvidence("跟进", "未同步", true),
                TacticalHudEvidence("Bin", "获得收割空间")
            ),
            blueAlive = 1,
            redAlive = 5,
            players = listOf(
                TacticalHudPlayer("AL", "打野", "Tarzan", hpPercent = 0, alive = false, note = "落地被融化"),
                TacticalHudPlayer("BLG", "上路", "Bin", hpPercent = 63, alive = true, note = "进入收割"),
                TacticalHudPlayer("BLG", "下路", "Viper", hpPercent = 72, alive = true, note = "安全输出")
            )
        ),
        TacticalHudState(
            phase = TacticalHudPhase.GLOBAL,
            clock = "19:29",
            headline = "AL 三人集火仍未形成击杀",
            explanation = "真实比赛此处 AL 三人集火 knight 只打到半血，双方随后拉开。这个节点适合展示技能投入与实际收益不匹配，而不是重复经济差。",
            evidence = listOf(
                TacticalHudEvidence("AL", "3人投入", true),
                TacticalHudEvidence("knight", "仍约半血", true),
                TacticalHudEvidence("结果", "未形成击杀"),
                TacticalHudEvidence("代价", "技能窗口被消耗")
            ),
            players = listOf(
                TacticalHudPlayer("BLG", "中路", "knight", hpPercent = 52, alive = true, note = "成功脱离"),
                TacticalHudPlayer("AL", "打野", "Tarzan", hpPercent = 76, alive = true, note = "控制已交")
            )
        ),
        TacticalHudState(
            phase = TacticalHudPhase.GLOBAL,
            clock = "24:18",
            headline = "BLG 已获得几乎无压力的大龙窗口",
            explanation = "真实比赛 24:23 BLG 顺利拿到大龙。此时赛事 X 光层只需要告诉观众：AL 的接近路线、关键技能与视野条件不足以形成有效争夺。",
            evidence = listOf(
                TacticalHudEvidence("AL 接近路线", "受限", true),
                TacticalHudEvidence("争夺能力", "不足", true),
                TacticalHudEvidence("BLG", "可安全转大龙")
            ),
            objective = "纳什男爵",
            objectiveHp = 12_600,
            objectiveMaxHp = 12_600,
            players = listOf(
                TacticalHudPlayer("BLG", "打野", "Xun", hpPercent = 96, alive = true, smiteReady = true, note = "惩戒可用"),
                TacticalHudPlayer("AL", "打野", "Tarzan", hpPercent = 88, alive = true, note = "难以接近龙坑")
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
