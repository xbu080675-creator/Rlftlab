package com.riftlab.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.MatchDetailRepository

/** Full match timeline surface; the timeline store itself is provider-agnostic. */
@Composable
internal fun MatchTimelineContent() {
    val state by MatchDetailRepository.state.collectAsState()
    val series = state.series
    val live = state.liveGame
    val games = remember(series?.games, live?.game) {
        buildList {
            series?.games.orEmpty().map { it.game }.filter { it > 0 }.distinct().sorted().forEach(::add)
            live?.game?.takeIf { it > 0 && it !in this }?.let(::add)
        }.sorted()
    }
    var selectedGame by remember(state.key?.stableId, games) {
        mutableIntStateOf(live?.game?.takeIf { it > 0 } ?: games.firstOrNull() ?: 0)
    }
    if (selectedGame !in games && games.isNotEmpty()) selectedGame = games.first()

    val snapshot = series?.games?.firstOrNull { it.game == selectedGame }
        ?: live?.takeIf { it.game == selectedGame }

    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(RiftPanel, CutCornerShape(topEnd = 14.dp, bottomStart = 10.dp))
                    .padding(14.dp)
            ) {
                Text("EVENT TIMELINE", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("比赛进程 / 状态回放", color = RiftText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(
                    "实时阶段按约 10 秒保存状态快照，并在击杀、防御塔、小龙、男爵、经济领先变化时额外落点。拖动时间轴恢复最近快照，再查看该时间点之前的事件。",
                    color = RiftMuted,
                    fontSize = 9.sp
                )
            }
        }

        if (games.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth()
                        .background(RiftPanelAlt, CutCornerShape(topEnd = 10.dp, bottomStart = 8.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    games.forEach { game ->
                        val selected = game == selectedGame
                        Text(
                            "G$game",
                            color = if (selected) RiftCyan else RiftMuted,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                                .background(
                                    if (selected) RiftPanel else androidx.compose.ui.graphics.Color.Transparent,
                                    CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp)
                                )
                                .clickable { selectedGame = game }
                                .padding(vertical = 9.dp)
                        )
                    }
                }
            }
        }

        if (snapshot != null) {
            item { MatchTimelinePanel(snapshot) }
        } else {
            item {
                Column(
                    Modifier.fillMaxWidth()
                        .background(RiftPanel, CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
                        .padding(14.dp)
                ) {
                    Text("TIMELINE 尚未建立", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "等待当前小局实时帧，或选择已经存在终局数据的小局。旧比赛如果当时没有连续采集，只展示数据缺口，不会伪造中间过程。",
                        color = RiftMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
