package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object MatchSessionStore {
    const val MATCH_ID = "demo-blg-al"

    val preMatch = PreMatchInfo(
        league = "LPL",
        stage = "PLAYOFFS",
        blue = "BLG",
        red = "AL",
        startTime = "19:00",
        blueForm = "4W 1L",
        redForm = "2W 3L",
        blueRoster = listOf(
            PlayerCard("TOP", "Bin", "KR 1420 LP", "Jax · Rumble · K'Sante"),
            PlayerCard("JUG", "Xun", "KR 1288 LP", "Vi · Sejuani · Xin Zhao"),
            PlayerCard("MID", "knight", "KR 1516 LP", "Aurora · Azir · Neeko"),
            PlayerCard("BOT", "Viper", "KR 1472 LP", "Kai'Sa · Ezreal · Varus"),
            PlayerCard("SUP", "ON", "KR 1034 LP", "Rakan · Nautilus · Camille")
        ),
        redRoster = listOf(
            PlayerCard("TOP", "Flandre", "KR 1088 LP", "Renekton · Gnar · Rumble"),
            PlayerCard("JUG", "Tarzan", "KR 1394 LP", "Wukong · Vi · Nocturne"),
            PlayerCard("MID", "Shanks", "KR 1242 LP", "Azir · Taliyah · Orianna"),
            PlayerCard("BOT", "Hope", "KR 1110 LP", "Varus · Jinx · Ezreal"),
            PlayerCard("SUP", "Kael", "KR 1187 LP", "Rell · Alistar · Nautilus")
        ),
        rosterNote = "首发与 Rank 当前为演示数据；真实源接入后自动替换。"
    )

    val postMatch = PostMatchInfo(
        score = "2 : 1",
        winner = "BLG",
        mvp = "Viper",
        mvpRole = "BOT",
        mvpDpm = 842,
        mvpGoldDiff15 = 721,
        positionRank = "ADC DATA RANK  #1",
        keyPoint = "22:14 小龙团 0 换 3，随后拿下第一条男爵。"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mockJob: Job? = null

    private val _live = MutableStateFlow(
        LiveSnapshot(
            game = 2,
            elapsedSeconds = 18 * 60 + 42,
            blue = "BLG",
            red = "AL",
            blueGold = 34700,
            redGold = 33100,
            blueKills = 8,
            redKills = 6,
            blueTowers = 4,
            redTowers = 3,
            blueDragons = 2,
            redDragons = 1,
            latestEvent = "18:37 · BLG 获得小龙"
        )
    )
    val live: StateFlow<LiveSnapshot> = _live.asStateFlow()

    fun ensureMockRunning() {
        if (mockJob?.isActive == true) return
        mockJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(2000)
                tick++
                val old = _live.value
                val blueGain = 115 + (tick % 4) * 17
                val redGain = 92 + (tick % 3) * 13
                val event = when {
                    tick % 15 == 0 -> "${formatTime(old.elapsedSeconds + 2)} · BLG 摧毁防御塔"
                    tick % 10 == 0 -> "${formatTime(old.elapsedSeconds + 2)} · AL 击杀一名选手"
                    tick % 6 == 0 -> "${formatTime(old.elapsedSeconds + 2)} · 经济差继续扩大"
                    else -> old.latestEvent
                }
                _live.value = old.copy(
                    elapsedSeconds = old.elapsedSeconds + 2,
                    blueGold = old.blueGold + blueGain,
                    redGold = old.redGold + redGain,
                    blueKills = old.blueKills + if (tick % 13 == 0) 1 else 0,
                    redKills = old.redKills + if (tick % 10 == 0) 1 else 0,
                    blueTowers = old.blueTowers + if (tick % 15 == 0) 1 else 0,
                    latestEvent = event
                )
            }
        }
    }

    fun formatTime(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)
}
