package com.riftlab.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.GameTimeline
import com.riftlab.app.data.LivePlayerSnapshot
import com.riftlab.app.data.LiveSnapshot
import com.riftlab.app.data.MatchTimelineEvent
import com.riftlab.app.data.MatchTimelineStore
import com.riftlab.app.data.TimelineEventType
import kotlin.math.abs

private enum class TimelineFilter(val label: String) {
    ALL("全部"),
    KILL("击杀"),
    OBJECTIVE("资源"),
    TOWER("防御塔"),
    GOLD("经济")
}

private data class TeamfightWindow(
    val start: Int,
    val end: Int,
    val blueKills: Int,
    val redKills: Int
)

/**
 * Scrubbable local timeline. Historical games without captured snapshots deliberately show an
 * explicit gap instead of reconstructing fake intermediate states from a final frame.
 */
@Composable
internal fun MatchTimelinePanel(snapshot: LiveSnapshot) {
    val all by MatchTimelineStore.timelines.collectAsState()
    val timeline = MatchTimelineStore.find(snapshot, all)
    val shape = CutCornerShape(topEnd = 14.dp, bottomStart = 10.dp)

    if (timeline == null || timeline.points.isEmpty()) {
        Column(
            Modifier.fillMaxWidth()
                .background(RiftPanel, shape)
                .border(1.dp, RiftLine, shape)
                .padding(14.dp)
        ) {
            Text("TIMELINE · 暂无过程记录", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "这局只有终局/当前帧，没有本设备采集到的连续实时快照。RiftLab 不会从最终比分倒推不存在的历史事件。",
                color = RiftMuted,
                fontSize = 10.sp
            )
        }
        return
    }

    var scrub by remember(timeline.gameId) { mutableStateOf(timeline.durationSeconds.toFloat()) }
    var followLatest by remember(timeline.gameId) { mutableStateOf(!timeline.completed) }
    var filter by remember(timeline.gameId) { mutableStateOf(TimelineFilter.ALL) }

    LaunchedEffect(timeline.durationSeconds, followLatest) {
        if (followLatest) scrub = timeline.durationSeconds.toFloat()
        if (scrub > timeline.durationSeconds) scrub = timeline.durationSeconds.toFloat()
    }

    val selectedSecond = scrub.toInt().coerceIn(0, timeline.durationSeconds.coerceAtLeast(0))
    val state = timeline.stateAt(selectedSecond)
    val visibleEvents = timeline.events
        .filter { it.seconds <= selectedSecond + 1 }
        .filter { matchesFilter(it, filter) }
        .takeLast(7)
        .reversed()
    val fights = teamfightWindows(timeline)
        .filter { it.end <= selectedSecond + 1 }
        .takeLast(2)
        .reversed()

    Column(
        Modifier.fillMaxWidth()
            .background(RiftPanel, shape)
            .border(1.dp, RiftLine, shape)
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("MATCH TIMELINE", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    "G${timeline.game} · ${timeline.blue} vs ${timeline.red}",
                    color = RiftText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                if (timeline.completed) "RECORDED" else if (followLatest) "LIVE FOLLOW" else "PAUSED",
                color = if (timeline.completed) RiftMuted else RiftCyan,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("00:00", color = RiftMuted, fontSize = 9.sp)
            Spacer(Modifier.weight(1f))
            Text(formatClock(selectedSecond), color = RiftText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(formatClock(timeline.durationSeconds), color = RiftMuted, fontSize = 9.sp)
        }
        Slider(
            value = scrub.coerceIn(0f, timeline.durationSeconds.coerceAtLeast(1).toFloat()),
            onValueChange = {
                scrub = it
                followLatest = false
            },
            onValueChangeFinished = {
                if (timeline.durationSeconds - scrub.toInt() <= 2) followLatest = !timeline.completed
            },
            valueRange = 0f..timeline.durationSeconds.coerceAtLeast(1).toFloat()
        )

        if (!timeline.completed && !followLatest) {
            Text(
                "回到最新 ${formatClock(timeline.durationSeconds)} ›",
                color = RiftCyan,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().clickable {
                    followLatest = true
                    scrub = timeline.durationSeconds.toFloat()
                },
                textAlign = TextAlign.End
            )
        }

        state?.let {
            Spacer(Modifier.height(8.dp))
            TimelineStateCard(it, selectedSecond)
        }

        Spacer(Modifier.height(10.dp))
        TimelineFilters(filter) { filter = it }

        if (fights.isNotEmpty() && (filter == TimelineFilter.ALL || filter == TimelineFilter.KILL)) {
            Spacer(Modifier.height(10.dp))
            Text("KEY FIGHTS / 团战窗口", color = RiftMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            fights.forEach { fight ->
                val summary = buildString {
                    append(formatClock(fight.start))
                    if (fight.end > fight.start) append("–${formatClock(fight.end)}")
                    append(" · ${timeline.blue} ${fight.blueKills}:${fight.redKills} ${timeline.red}")
                }
                Text(summary, color = RiftText, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("EVENTS / 事件", color = RiftMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        if (visibleEvents.isEmpty()) {
            Text("当前时间点之前没有该筛选类型的事件", color = RiftMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        } else {
            visibleEvents.forEach { event -> TimelineEventRow(event) }
        }
    }
}

@Composable
private fun TimelineStateCard(snapshot: LiveSnapshot, selectedSecond: Int) {
    val shape = CutCornerShape(topEnd = 9.dp, bottomStart = 7.dp)
    Column(
        Modifier.fillMaxWidth()
            .background(RiftPanelAlt, shape)
            .border(1.dp, RiftLine.copy(alpha = 0.75f), shape)
            .padding(10.dp)
    ) {
        Text("STATE @ ${formatClock(selectedSecond)}", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth()) {
            TeamStateColumn(
                name = snapshot.blue,
                gold = snapshot.blueGold,
                kills = snapshot.blueKills,
                towers = snapshot.blueTowers,
                dragons = snapshot.blueDragons,
                barons = snapshot.blueBarons,
                players = snapshot.bluePlayers,
                modifier = Modifier.weight(1f)
            )
            Column(Modifier.padding(horizontal = 8.dp)) {
                Text(formatGoldDiff(snapshot.goldDiff), color = if (snapshot.goldDiff >= 0) RiftCyan else RiftRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("GOLD", color = RiftMuted, fontSize = 8.sp, textAlign = TextAlign.Center)
            }
            TeamStateColumn(
                name = snapshot.red,
                gold = snapshot.redGold,
                kills = snapshot.redKills,
                towers = snapshot.redTowers,
                dragons = snapshot.redDragons,
                barons = snapshot.redBarons,
                players = snapshot.redPlayers,
                modifier = Modifier.weight(1f),
                alignEnd = true
            )
        }
    }
}

@Composable
private fun TeamStateColumn(
    name: String,
    gold: Int,
    kills: Int,
    towers: Int,
    dragons: Int,
    barons: Int,
    players: List<LivePlayerSnapshot>,
    modifier: Modifier,
    alignEnd: Boolean = false
) {
    val align = if (alignEnd) TextAlign.End else TextAlign.Start
    Column(modifier) {
        Text(name, color = RiftText, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = align)
        Text(
            "${formatGold(gold)} · K$kills T$towers D$dragons B$barons",
            color = RiftMuted,
            fontSize = 9.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = align
        )
        if (players.isNotEmpty()) {
            Spacer(Modifier.height(5.dp))
            players.sortedBy { roleOrder(it.role) }.take(5).forEach { player ->
                val label = "${player.summonerName.ifBlank { player.role }} ${player.kills}/${player.deaths}/${player.assists} · L${player.level} · ${player.creepScore}CS"
                Text(label, color = RiftMuted, fontSize = 8.sp, modifier = Modifier.fillMaxWidth(), textAlign = align)
            }
        }
    }
}

@Composable
private fun TimelineFilters(selected: TimelineFilter, onSelect: (TimelineFilter) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TimelineFilter.entries.forEach { item ->
            val active = item == selected
            Text(
                item.label,
                color = if (active) RiftCyan else RiftMuted,
                fontSize = 9.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
                    .background(if (active) RiftPanelAlt else androidx.compose.ui.graphics.Color.Transparent, CutCornerShape(topEnd = 6.dp, bottomStart = 4.dp))
                    .clickable { onSelect(item) }
                    .padding(vertical = 7.dp)
            )
        }
    }
}

@Composable
private fun TimelineEventRow(event: MatchTimelineEvent) {
    Row(Modifier.fillMaxWidth().padding(top = 7.dp)) {
        Text(formatClock(event.seconds), color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                event.title,
                color = when (event.type) {
                    TimelineEventType.KILL -> RiftRed
                    TimelineEventType.DRAGON, TimelineEventType.BARON, TimelineEventType.TOWER -> RiftCyan
                    else -> RiftText
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            if (event.detail.isNotBlank()) {
                Text(event.detail, color = RiftMuted, fontSize = 8.sp)
            }
        }
    }
}

private fun matchesFilter(event: MatchTimelineEvent, filter: TimelineFilter): Boolean = when (filter) {
    TimelineFilter.ALL -> true
    TimelineFilter.KILL -> event.type == TimelineEventType.KILL
    TimelineFilter.OBJECTIVE -> event.type == TimelineEventType.DRAGON || event.type == TimelineEventType.BARON
    TimelineFilter.TOWER -> event.type == TimelineEventType.TOWER
    TimelineFilter.GOLD -> event.type == TimelineEventType.GOLD_SWING
}

private fun teamfightWindows(timeline: GameTimeline): List<TeamfightWindow> {
    val kills = timeline.events.filter { it.type == TimelineEventType.KILL }.sortedBy { it.seconds }
    if (kills.isEmpty()) return emptyList()
    val clusters = mutableListOf<MutableList<MatchTimelineEvent>>()
    kills.forEach { event ->
        val current = clusters.lastOrNull()
        if (current == null || event.seconds - current.last().seconds > 20) {
            clusters += mutableListOf(event)
        } else {
            current += event
        }
    }
    return clusters.mapNotNull { cluster ->
        val total = cluster.sumOf { it.amount.coerceAtLeast(1) }
        if (total < 3) return@mapNotNull null
        val blue = cluster.filter { sameTeam(it.team, timeline.blue) }.sumOf { it.amount.coerceAtLeast(1) }
        val red = cluster.filter { sameTeam(it.team, timeline.red) }.sumOf { it.amount.coerceAtLeast(1) }
        TeamfightWindow(cluster.first().seconds, cluster.last().seconds, blue, red)
    }
}

private fun sameTeam(a: String, b: String): Boolean {
    val ta = a.uppercase().replace(Regex("[^A-Z0-9]+"), "")
    val tb = b.uppercase().replace(Regex("[^A-Z0-9]+"), "")
    return ta.isNotBlank() && tb.isNotBlank() && (ta == tb || ta.contains(tb) || tb.contains(ta))
}

private fun roleOrder(role: String): Int = when (role.uppercase()) {
    "TOP" -> 0
    "JUG", "JUNGLE" -> 1
    "MID" -> 2
    "BOT", "ADC", "BOTTOM" -> 3
    "SUP", "SUPPORT" -> 4
    else -> 99
}

private fun formatClock(seconds: Int): String = "%02d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)
private fun formatGold(value: Int): String = if (abs(value) >= 1000) "%.1fK".format(value / 1000f) else value.toString()
private fun formatGoldDiff(value: Int): String {
    val sign = if (value >= 0) "+" else "-"
    val absolute = abs(value)
    return if (absolute >= 1000) "$sign%.1fK".format(absolute / 1000f) else "$sign$absolute"
}
