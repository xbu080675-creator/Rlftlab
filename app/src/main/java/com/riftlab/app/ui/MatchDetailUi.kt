package com.riftlab.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.LivePlayerSnapshot
import com.riftlab.app.data.MatchDetailRepository
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.ScheduleMatchPhase

@Composable
internal fun MatchDetailContent() {
    val state by MatchDetailRepository.state.collectAsState()
    val match = state.match

    if (match == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("未选择比赛", color = RiftMuted)
        }
        return
    }

    val left = match.teams.getOrNull(0)
    val right = match.teams.getOrNull(1)
    val phase = MatchSessionStore.schedulePhase(match)
    val series = state.series

    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            DetailPanel(accent = phase == ScheduleMatchPhase.LIVE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (phase) {
                            ScheduleMatchPhase.LIVE -> "LIVE MATCH"
                            ScheduleMatchPhase.UPCOMING -> "UPCOMING MATCH"
                            ScheduleMatchPhase.COMPLETED -> "MATCH FINAL"
                        },
                        color = if (phase == ScheduleMatchPhase.LIVE) RiftCyan else RiftMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    Text("BO${match.bestOf}", color = RiftMuted, fontSize = 10.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(left?.code?.ifBlank { left.name } ?: "—", modifier = Modifier.weight(1f), fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text(
                        series?.let { "${it.scoreA} : ${it.scoreB}" }
                            ?: if (phase == ScheduleMatchPhase.COMPLETED || match.teams.any { it.gameWins > 0 }) MatchSessionStore.scheduleScore(match) else "VS",
                        color = RiftCyan,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        right?.code?.ifBlank { right.name } ?: "—",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(match.blockName.ifBlank { match.league }, color = RiftMuted, fontSize = 10.sp)
                Text(MatchSessionStore.scheduleTimingNote(match), color = RiftMuted, fontSize = 10.sp)
                Text("MATCH KEY · ${state.key?.stableId ?: "—"}", color = RiftMuted, fontSize = 8.sp)
            }
        }

        item { DetailSectionTitle("DATA STATUS / 数据状态") }
        item {
            DetailPanel(accent = series != null || phase == ScheduleMatchPhase.LIVE) {
                Text(
                    when {
                        state.loading -> "正在加载单场详情…"
                        series != null -> "终局数据已连接"
                        phase == ScheduleMatchPhase.UPCOMING -> "等待比赛开始"
                        phase == ScheduleMatchPhase.LIVE -> "赛中数据由 Live Provider Router 提供"
                        else -> "终局数据暂不可用"
                    },
                    color = if (series != null || phase == ScheduleMatchPhase.LIVE) RiftCyan else RiftMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(state.status, color = RiftMuted, fontSize = 10.sp, lineHeight = 16.sp)
                if (phase == ScheduleMatchPhase.COMPLETED) {
                    Spacer(Modifier.height(9.dp))
                    Button(
                        onClick = MatchDetailRepository::refresh,
                        enabled = !state.loading,
                        colors = ButtonDefaults.buttonColors(containerColor = RiftPanelAlt, contentColor = RiftText),
                        shape = CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp)
                    ) {
                        Text(if (state.loading) "同步中" else "重新同步", fontSize = 10.sp)
                    }
                }
            }
        }

        val live = state.liveGame
        if (phase == ScheduleMatchPhase.LIVE && live != null) {
            item { DetailSectionTitle("CURRENT GAME / 当前小局") }
            item { GameDetailCard(live) }
        }

        if (series != null) {
            item { DetailSectionTitle("GAME DATA / 小局数据") }
            items(series.games, key = { it.game }) { game ->
                GameDetailCard(game)
            }
        }

        if (phase == ScheduleMatchPhase.COMPLETED || state.seriesMvp != null || state.gameMvps.isNotEmpty()) {
            item { DetailSectionTitle("MVP / 官方评选") }
            item {
                DetailPanel(accent = state.seriesMvp != null || state.gameMvps.isNotEmpty()) {
                    val seriesMvp = state.seriesMvp
                    if (seriesMvp == null && state.gameMvps.isEmpty()) {
                        Text("暂无可核实官方 MVP 数据", color = RiftMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Spacer(Modifier.height(5.dp))
                        Text("不会用伤害最高、KDA 最高等规则自行冒充官方 MVP。上游接入后直接挂到当前 Match Detail。", color = RiftMuted, fontSize = 10.sp, lineHeight = 16.sp)
                    } else {
                        seriesMvp?.let {
                            Text("SERIES MVP · ${it.playerName}", color = RiftCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("${it.team} · ${it.role} · ${it.source}", color = RiftMuted, fontSize = 9.sp)
                        }
                        state.gameMvps.forEach { mvp ->
                            Spacer(Modifier.height(6.dp))
                            Text("G${mvp.game ?: 0} MVP · ${mvp.playerName} · ${mvp.team}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        if (phase == ScheduleMatchPhase.COMPLETED || state.votes.isNotEmpty()) {
            item { DetailSectionTitle("POG VOTES / 官方数据面板") }
            if (state.votes.isEmpty()) {
                item {
                    DetailPanel {
                        Text("暂无可核实官方 POG / MVP 投票面板数据", color = RiftMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "这里对应官方赛后数据面板展示的 POG/MVP 投票结果（例如 6/8），不是 RiftLab 用户投票，也不是赛季 MVP 积分榜。",
                            color = RiftMuted,
                            fontSize = 10.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            } else {
                items(state.votes, key = { it.title }) { vote ->
                    DetailPanel(accent = true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(vote.title, modifier = Modifier.weight(1f), color = RiftCyan, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            vote.totalVotes?.let { total ->
                                Text("TOTAL $total", color = RiftMuted, fontSize = 9.sp)
                            }
                        }
                        vote.options.forEach { option ->
                            Spacer(Modifier.height(5.dp))
                            Row {
                                Text(option.label, modifier = Modifier.weight(1f), fontSize = 10.sp)
                                val percent = option.percent ?: vote.totalVotes
                                    ?.takeIf { it > 0L }
                                    ?.let { total -> option.votes * 100.0 / total }
                                Text(
                                    percent?.let { "${option.votes} · %.1f%%".format(it) } ?: option.votes.toString(),
                                    color = RiftMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Text(vote.source, color = RiftMuted, fontSize = 8.sp)
                    }
                }
            }
        }

        item { DetailSectionTitle("BP / DRAFT") }
        item {
            DetailPanel {
                Text("BP 数据模型入口已预留", color = RiftMuted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Spacer(Modifier.height(5.dp))
                Text("后续每小局 Ban/Pick、蓝红方、最终阵容会直接挂在同一个 Match Detail，不再另做独立历史页面。", color = RiftMuted, fontSize = 10.sp, lineHeight = 16.sp)
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun GameDetailCard(game: com.riftlab.app.data.LiveSnapshot) {
    DetailPanel(accent = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GAME ${game.game}", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(MatchSessionStore.formatTime(game.elapsedSeconds), color = RiftMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(game.blue, modifier = Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("${game.blueKills} : ${game.redKills}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(game.red, modifier = Modifier.weight(1f), textAlign = TextAlign.End, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "GOLD ${gold(game.blueGold)} : ${gold(game.redGold)} · T ${game.blueTowers}:${game.redTowers} · D ${game.blueDragons}:${game.redDragons} · B ${game.blueBarons}:${game.redBarons}",
            color = RiftMuted,
            fontSize = 9.sp,
            lineHeight = 14.sp
        )
        if (game.bluePlayers.isNotEmpty() || game.redPlayers.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            val max = maxOf(game.bluePlayers.size, game.redPlayers.size)
            for (index in 0 until max) {
                CompactPlayerRow(game.bluePlayers.getOrNull(index), game.redPlayers.getOrNull(index))
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(game.source, color = RiftMuted, fontSize = 8.sp)
    }
}

@Composable
private fun CompactPlayerRow(left: LivePlayerSnapshot?, right: LivePlayerSnapshot?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(left?.summonerName ?: "—", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Text(left?.let { "${it.kills}/${it.deaths}/${it.assists} · CS ${it.creepScore}" } ?: "—", color = RiftMuted, fontSize = 8.sp)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(right?.summonerName ?: "—", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Text(right?.let { "${it.kills}/${it.deaths}/${it.assists} · CS ${it.creepScore}" } ?: "—", color = RiftMuted, fontSize = 8.sp)
        }
    }
}

@Composable
private fun DetailSectionTitle(text: String) {
    Text(text, color = RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun DetailPanel(accent: Boolean = false, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(RiftPanel, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
            .border(1.dp, if (accent) RiftCyan.copy(alpha = 0.55f) else RiftLine, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
            .padding(13.dp)
    ) {
        content()
    }
}

private fun gold(value: Int): String = if (value > 0) "%.1fK".format(value / 1000f) else "—"
