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
import androidx.compose.runtime.LaunchedEffect
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
import com.riftlab.app.data.BilibiliVodRepository
import com.riftlab.app.data.MatchDetailRepository
import com.riftlab.app.data.MatchTimelineStore

/** Full match timeline surface with local-live capture plus official-VOD historical fallback. */
@Composable
internal fun MatchTimelineContent() {
    val state by MatchDetailRepository.state.collectAsState()
    val allTimelines by MatchTimelineStore.timelines.collectAsState()
    val vodState by BilibiliVodRepository.state.collectAsState()
    val series = state.series
    val live = state.liveGame
    val match = state.match
    val lplReplay = match?.let(::isLplReplayMatch) == true
    val vodKey = if (lplReplay) match?.let(BilibiliVodRepository::keyFor).orEmpty() else ""

    LaunchedEffect(vodKey, lplReplay) {
        if (lplReplay && match != null && vodKey.isNotBlank()) BilibiliVodRepository.open(match)
    }

    val vod = vodState.vod.takeIf { lplReplay && vodState.matchKey == vodKey }
    val games = remember(series?.games, live?.game, vod?.parts) {
        buildList {
            series?.games.orEmpty().map { it.game }.filter { it > 0 }.distinct().sorted().forEach(::add)
            live?.game?.takeIf { it > 0 && it !in this }?.let(::add)
            vod?.parts.orEmpty().map { it.game }.filter { it > 0 && it !in this }.sorted().forEach(::add)
        }.distinct().sorted()
    }
    var selectedGame by remember(state.key?.stableId, games) {
        mutableIntStateOf(live?.game?.takeIf { it > 0 } ?: games.firstOrNull() ?: 0)
    }
    if (selectedGame !in games && games.isNotEmpty()) selectedGame = games.first()

    val snapshot = series?.games?.firstOrNull { it.game == selectedGame }
        ?: live?.takeIf { it.game == selectedGame }
    val localTimeline = snapshot?.let { MatchTimelineStore.find(it, allTimelines) }
    val vodPart = vod?.parts?.firstOrNull { it.game == selectedGame }
    val hasLocalTimeline = localTimeline != null && localTimeline.points.isNotEmpty()

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
                Text("EVENT TIMELINE", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("比赛进程 / 状态回放", color = RiftText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(
                    if (lplReplay) {
                        "实时阶段优先保存约 10 秒状态快照；LPL 历史比赛没有本机快照时，可使用 B站英雄联盟赛事官方录像的分P与章节锚点补回时间轴。来源会明确标记，不从终局比分伪造中间过程。"
                    } else {
                        "实时阶段优先保存约 10 秒状态快照；海外赛区与国际赛事只使用 RiftLab 本机归档、Riot/官方可核实事件数据，不请求、不解析 Bilibili，也不从终局比分伪造中间过程。"
                    },
                    color = RiftMuted,
                    fontSize = 11.sp
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
                            fontSize = 11.sp,
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

        when {
            snapshot != null && hasLocalTimeline -> {
                item { MatchTimelinePanel(snapshot) }
            }
            vod != null && vodPart != null -> {
                item { BilibiliHistoricalTimelinePanel(vod, vodPart) }
            }
            snapshot != null -> {
                item { MatchTimelinePanel(snapshot) }
                if (lplReplay && vodState.matchKey == vodKey && vodState.loading) {
                    item { TimelineVodStatus("正在查找 B站英雄联盟赛事官方录像，找到后会自动补历史章节时间轴…") }
                } else if (lplReplay && vodState.matchKey == vodKey && !vodState.loading) {
                    item { TimelineVodStatus(vodState.status) }
                }
            }
            lplReplay && vodState.matchKey == vodKey && vodState.loading -> {
                item { TimelineVodStatus("正在解析 B站英雄联盟赛事官方录像与分P…") }
            }
            else -> {
                item {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(RiftPanel, CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
                            .padding(14.dp)
                    ) {
                        Text("TIMELINE 尚未建立", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "本机实时快照和官方录像章节都暂未解析到。RiftLab 会保留数据缺口，不会从最终比分倒推不存在的历史事件。",
                            color = RiftMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TimelineVodStatus(text: String) {
    Column(
        Modifier.fillMaxWidth()
            .background(RiftPanel, CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
            .padding(14.dp)
    ) {
        Text("HISTORICAL VOD SOURCE", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(text, color = RiftMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
