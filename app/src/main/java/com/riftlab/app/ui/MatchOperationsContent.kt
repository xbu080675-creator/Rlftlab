package com.riftlab.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.LivePlayerSnapshot
import com.riftlab.app.data.LiveSnapshot
import com.riftlab.app.data.MatchDetailRepository
import com.riftlab.app.data.MatchLifecycleArchive
import com.riftlab.app.data.MatchLifecycleFrame
import com.riftlab.app.data.MatchLifecycleRecord
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.OpggHistoricalFrameResolver
import com.riftlab.app.data.OpggHistoryBackfillState
import com.riftlab.app.data.OpggHistoryPhase
import com.riftlab.app.data.RiotHistoryBackfillState
import com.riftlab.app.data.RiotHistoryPhase
import com.riftlab.app.data.RiotLiveStatsHistoryResolver
import com.riftlab.app.data.ScheduleMatchPhase
import com.riftlab.app.data.ScheduledEsportsMatch
import kotlin.math.max
import kotlin.math.min

/**
 * Match data plane from an event-operator point of view.
 *
 * This screen intentionally consumes live flows directly instead of waiting for a page refresh.
 * If the upstream moves team gold from 5k -> 6k -> 7k, the current value and the curve move with
 * it and every verified provider frame is retained by MatchLifecycleArchive for later replay/sandbox.
 */
@Composable
internal fun MatchOperationsContent() {
    val detail by MatchDetailRepository.state.collectAsState()
    val center by MatchSessionStore.scheduleCenter.collectAsState()
    val sessionLive by MatchSessionStore.live.collectAsState()
    val liveStatus by MatchSessionStore.liveSourceStatus.collectAsState()
    val completedSeries by MatchSessionStore.completedSeries.collectAsState()
    val records by MatchLifecycleArchive.records.collectAsState()
    val riotHistory by RiotLiveStatsHistoryResolver.states.collectAsState()
    val opggHistory by OpggHistoricalFrameResolver.states.collectAsState()

    val baseMatch = detail.match
    if (baseMatch == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("未选择比赛", color = RiftMuted)
        }
        return
    }

    val match = center.matches.firstOrNull { sameMatch(it, baseMatch) } ?: baseMatch
    val phase = MatchSessionStore.schedulePhase(match)
    val record = MatchLifecycleArchive.find(match, records)
    val live = sessionLive.takeIf {
        phase == ScheduleMatchPhase.LIVE && it.game > 0 && currentMatchMatches(center.currentMatch, match)
    }

    val finalSeries = completedSeries?.takeIf { series -> seriesMatches(series.teamA, series.teamB, match) }
    val games = remember(record?.games, record?.finalGames, finalSeries?.games, live?.game) {
        buildList {
            record?.games?.keys.orEmpty().filter { it > 0 }.forEach(::add)
            record?.finalGames?.keys.orEmpty().filter { it > 0 && it !in this }.forEach(::add)
            finalSeries?.games.orEmpty().map { it.game }.filter { it > 0 && it !in this }.forEach(::add)
            live?.game?.takeIf { it > 0 && it !in this }?.let(::add)
        }.distinct().sorted()
    }
    var selectedGame by remember(MatchLifecycleArchive.keyFor(match)) {
        mutableIntStateOf(live?.game ?: games.lastOrNull() ?: 0)
    }
    if (selectedGame !in games && games.isNotEmpty()) selectedGame = live?.game?.takeIf { it in games } ?: games.last()

    val frames = record?.framesFor(selectedGame).orEmpty()
    val finalSnapshot = record?.finalGames?.get(selectedGame)
        ?: finalSeries?.games?.firstOrNull { it.game == selectedGame }
    val archivedLatest = frames.lastOrNull()?.snapshot
    val currentSnapshot = live?.takeIf { it.game == selectedGame }
        ?: if (phase == ScheduleMatchPhase.COMPLETED && finalSnapshot != null) {
            finalSnapshot.copy(
                blueXp = archivedLatest?.blueXp?.takeIf { it > 0 } ?: finalSnapshot.blueXp,
                redXp = archivedLatest?.redXp?.takeIf { it > 0 } ?: finalSnapshot.redXp
            )
        } else {
            archivedLatest ?: finalSnapshot
        }
    val backfill = riotHistory[RiotLiveStatsHistoryResolver.stateKey(match, selectedGame)]
    val opggBackfill = opggHistory[OpggHistoricalFrameResolver.stateKey(match, selectedGame)]

    LaunchedEffect(match.eventId, match.matchId, selectedGame, phase, frames.size, backfill?.phase) {
        if (selectedGame > 0 && phase != ScheduleMatchPhase.UPCOMING && frames.size < 2) {
            RiotLiveStatsHistoryResolver.ensure(match, selectedGame)
            if (phase == ScheduleMatchPhase.COMPLETED &&
                (backfill?.phase == RiotHistoryPhase.UNAVAILABLE || backfill?.phase == RiotHistoryPhase.ERROR)
            ) {
                OpggHistoricalFrameResolver.ensure(match, selectedGame)
            }
        }
    }

    val visibleStatus = when (phase) {
        ScheduleMatchPhase.UPCOMING -> "赛前赛程持续同步 · 等待开赛"
        ScheduleMatchPhase.LIVE -> liveStatus.message
        ScheduleMatchPhase.COMPLETED -> when {
            frames.size >= 2 -> {
                val provider = frames.lastOrNull()?.snapshot?.source.orEmpty()
                if (provider.contains("OP.GG", ignoreCase = true)) {
                    "历史过程已归档 · OP.GG GOLD/XP · G$selectedGame ${frames.size} 帧"
                } else {
                    "历史过程已归档 · G$selectedGame ${frames.size} 个状态帧"
                }
            }
            opggBackfill?.phase == OpggHistoryPhase.LOADING -> opggBackfill.message
            opggBackfill?.phase == OpggHistoryPhase.READY -> opggBackfill.message
            opggBackfill?.phase == OpggHistoryPhase.UNAVAILABLE || opggBackfill?.phase == OpggHistoryPhase.ERROR -> opggBackfill?.message.orEmpty()
            backfill?.phase == RiotHistoryPhase.LOADING -> backfill.message
            backfill?.phase == RiotHistoryPhase.READY -> backfill.message
            backfill?.phase == RiotHistoryPhase.UNAVAILABLE || backfill?.phase == RiotHistoryPhase.ERROR -> "${backfill?.message.orEmpty()} · 正在切换 OP.GG 历史帧"
            finalSnapshot != null -> "历史终局已归档 · 正在从 Riot LiveStats 恢复 G$selectedGame 过程帧…"
            finalSeries != null -> "历史系列赛终局已归档 · 正在等待所选小局终局数据"
            else -> "正在恢复历史终局数据…"
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            OperatorHeader(
                match = match,
                phase = phase,
                status = visibleStatus,
                record = record
            )
        }

        if (games.isNotEmpty()) {
            item { OperatorGameTabs(games, selectedGame) { selectedGame = it } }
        }

        when {
            phase == ScheduleMatchPhase.UPCOMING -> {
                item { UpcomingOperatorPanel(match, record) }
            }
            currentSnapshot != null -> {
                item { LiveStatePanel(currentSnapshot, phase, frames.size) }
                item { GoldHistoryPanel(currentSnapshot, frames, phase, backfill, opggBackfill) }
                item { PlayerOperatorTable(currentSnapshot, phase) }
            }
            else -> {
                item {
                    OperatorPanel {
                        Text("等待可核实比赛状态", color = RiftText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "当前上游没有返回这一局的状态帧。RiftLab 保留数据缺口，不用终局数值伪造过程。",
                            color = RiftMuted,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }

        if (record != null) {
            item { LifecycleCoveragePanel(record, selectedGame) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun OperatorHeader(
    match: ScheduledEsportsMatch,
    phase: ScheduleMatchPhase,
    status: String,
    record: MatchLifecycleRecord?
) {
    OperatorPanel(accent = phase == ScheduleMatchPhase.LIVE) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("EVENT OPERATOR DATA PLANE", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text("赛事运营数据", color = RiftText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                when (phase) {
                    ScheduleMatchPhase.UPCOMING -> "UPCOMING"
                    ScheduleMatchPhase.LIVE -> "LIVE · DYNAMIC"
                    ScheduleMatchPhase.COMPLETED -> "FINAL ARCHIVE"
                },
                color = if (phase == ScheduleMatchPhase.LIVE) RiftCyan else RiftMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "${match.league} · ${match.blockName} · BO${match.bestOf} · event=${match.eventId.ifBlank { "—" }}",
            color = RiftMuted,
            fontSize = 8.sp
        )
        Text(
            "schedule=${match.state} · lifecycle revisions=${record?.scheduleRevisions?.size ?: 0} · $status",
            color = RiftMuted,
            fontSize = 8.sp,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

@Composable
private fun OperatorGameTabs(games: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(RiftPanelAlt, CutCornerShape(topEnd = 10.dp, bottomStart = 8.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        games.forEach { game ->
            val active = game == selected
            Text(
                "G$game",
                color = if (active) RiftCyan else RiftMuted,
                fontSize = 10.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
                    .background(if (active) RiftPanel else Color.Transparent, CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp))
                    .clickable { onSelect(game) }
                    .padding(vertical = 9.dp)
            )
        }
    }
}

@Composable
private fun UpcomingOperatorPanel(match: ScheduledEsportsMatch, record: MatchLifecycleRecord?) {
    OperatorPanel {
        Text("PRE-MATCH / 动态赛前状态", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        val a = match.teams.getOrNull(0)
        val b = match.teams.getOrNull(1)
        OperatorValueRow("开赛时间", match.startTimeIso.ifBlank { "—" })
        OperatorValueRow("赛程状态", match.state.ifBlank { "—" })
        OperatorValueRow("对阵", "${a?.code?.ifBlank { a.name } ?: "—"} vs ${b?.code?.ifBlank { b.name } ?: "—"}")
        OperatorValueRow("当前积分记录", "${a?.recordWins ?: 0}W-${a?.recordLosses ?: 0}L / ${b?.recordWins ?: 0}W-${b?.recordLosses ?: 0}L")
        OperatorValueRow("上游变更版本", "${record?.scheduleRevisions?.size ?: 0}")
        Spacer(Modifier.height(7.dp))
        Text(
            "赛前字段不是静态文案：Riot Schedule 每次改变开赛时间、状态、比分/结果字段都会追加一个 revision；开局后自动进入实时帧采集。",
            color = RiftMuted,
            fontSize = 8.sp
        )
    }
}

@Composable
private fun LiveStatePanel(snapshot: LiveSnapshot, phase: ScheduleMatchPhase, frameCount: Int) {
    OperatorPanel(accent = phase == ScheduleMatchPhase.LIVE) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("G${snapshot.game} · ${formatOperatorClock(snapshot.elapsedSeconds)}", color = RiftCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                if (phase == ScheduleMatchPhase.COMPLETED && frameCount == 0) "FINAL ONLY" else "$frameCount FRAMES",
                color = RiftMuted,
                fontSize = 8.sp
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TeamMetricColumn(
                team = snapshot.blue,
                gold = snapshot.blueGold,
                kills = snapshot.blueKills,
                towers = snapshot.blueTowers,
                dragons = snapshot.blueDragons,
                barons = snapshot.blueBarons,
                alignEnd = false
            )
            Column(Modifier.width(86.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("GOLD DIFF", color = RiftMuted, fontSize = 7.sp)
                Text(signedGold(snapshot.goldDiff), color = if (snapshot.goldDiff >= 0) RiftCyan else RiftRed, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("K ${snapshot.blueKills}:${snapshot.redKills}", color = RiftText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                if (snapshot.blueXp > 0 || snapshot.redXp > 0) {
                    Text("XP Δ ${signedGold(snapshot.blueXp - snapshot.redXp)}", color = RiftMuted, fontSize = 7.sp)
                }
            }
            TeamMetricColumn(
                team = snapshot.red,
                gold = snapshot.redGold,
                kills = snapshot.redKills,
                towers = snapshot.redTowers,
                dragons = snapshot.redDragons,
                barons = snapshot.redBarons,
                alignEnd = true
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            snapshot.latestEvent.ifBlank {
                if (phase == ScheduleMatchPhase.COMPLETED) "终局快照" else "等待下一帧"
            },
            color = RiftMuted,
            fontSize = 8.sp
        )
        Text(snapshot.source, color = RiftMuted, fontSize = 7.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TeamMetricColumn(
    team: String,
    gold: Int,
    kills: Int,
    towers: Int,
    dragons: Int,
    barons: Int,
    alignEnd: Boolean
) {
    Column(
        Modifier.weight(1f),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        Text(team, color = RiftText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(formatGold(gold), color = if (alignEnd) RiftRed else RiftCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("K $kills · T $towers · D $dragons · B $barons", color = RiftMuted, fontSize = 8.sp)
    }
}

@Composable
private fun GoldHistoryPanel(
    current: LiveSnapshot,
    frames: List<MatchLifecycleFrame>,
    phase: ScheduleMatchPhase,
    backfill: RiotHistoryBackfillState?,
    opggBackfill: OpggHistoryBackfillState?
) {
    val snapshots = remember(frames, current) {
        val archived = frames.map { it.snapshot }.filter { it.game == current.game }
        if (archived.lastOrNull()?.elapsedSeconds == current.elapsedSeconds) archived else archived + current
    }
    OperatorPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ECONOMY OVER TIME", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (phase == ScheduleMatchPhase.COMPLETED) "历史经济过程" else "经济随比赛时间动态变化",
                    color = RiftText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text("${snapshots.size} points", color = RiftMuted, fontSize = 8.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (snapshots.size < 2) {
            Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                Text(
                    when (phase) {
                        ScheduleMatchPhase.LIVE -> "正在积累实时经济帧…"
                        ScheduleMatchPhase.COMPLETED -> when {
                            opggBackfill?.phase == OpggHistoryPhase.LOADING -> opggBackfill.message
                            opggBackfill?.phase == OpggHistoryPhase.UNAVAILABLE || opggBackfill?.phase == OpggHistoryPhase.ERROR -> "${opggBackfill.message}；当前保留终局快照，不伪造过程曲线。"
                            backfill?.phase == RiotHistoryPhase.LOADING -> backfill.message
                            backfill?.phase == RiotHistoryPhase.UNAVAILABLE || backfill?.phase == RiotHistoryPhase.ERROR -> "Riot 历史帧不可用，正在尝试 OP.GG GOLD/XP 过程帧…"
                            else -> "正在从 Riot LiveStats 恢复历史经济帧…"
                        }
                        ScheduleMatchPhase.UPCOMING -> "比赛尚未开始"
                    },
                    color = RiftMuted,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        } else {
            GoldHistoryChart(snapshots)
            Spacer(Modifier.height(7.dp))
            val first = snapshots.first()
            val last = snapshots.last()
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "${first.blue} ${formatGold(first.blueGold)} → ${formatGold(last.blueGold)}",
                    color = RiftCyan,
                    fontSize = 8.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${first.red} ${formatGold(first.redGold)} → ${formatGold(last.redGold)}",
                    color = RiftRed,
                    fontSize = 8.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun GoldHistoryChart(points: List<LiveSnapshot>) {
    val cyan = RiftCyan
    val red = RiftRed
    val line = RiftLine.copy(alpha = 0.55f)
    val minSecond = points.firstOrNull()?.elapsedSeconds ?: 0
    val maxSecond = max(points.maxOfOrNull { it.elapsedSeconds } ?: 1, minSecond + 1)
    val minGold = min(
        points.minOfOrNull { it.blueGold } ?: 0,
        points.minOfOrNull { it.redGold } ?: 0
    )
    val maxGold = max(
        points.maxOfOrNull { it.blueGold } ?: 1,
        points.maxOfOrNull { it.redGold } ?: 1
    ).coerceAtLeast(minGold + 1)

    Canvas(
        Modifier.fillMaxWidth()
            .height(118.dp)
            .background(Color.Black.copy(alpha = 0.18f), CutCornerShape(topEnd = 8.dp, bottomStart = 6.dp))
            .border(1.dp, line, CutCornerShape(topEnd = 8.dp, bottomStart = 6.dp))
            .padding(8.dp)
    ) {
        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        fun xOf(second: Int): Float =
            ((second - minSecond).toFloat() / (maxSecond - minSecond).toFloat()).coerceIn(0f, 1f) * size.width

        fun yOf(gold: Int): Float =
            size.height - ((gold - minGold).toFloat() / (maxGold - minGold).toFloat()).coerceIn(0f, 1f) * size.height

        val bluePath = Path()
        val redPath = Path()
        points.forEachIndexed { index, point ->
            val x = xOf(point.elapsedSeconds)
            val by = yOf(point.blueGold)
            val ry = yOf(point.redGold)
            if (index == 0) {
                bluePath.moveTo(x, by)
                redPath.moveTo(x, ry)
            } else {
                bluePath.lineTo(x, by)
                redPath.lineTo(x, ry)
            }
        }
        drawPath(bluePath, cyan, style = Stroke(width = 2.4f))
        drawPath(redPath, red, style = Stroke(width = 2.4f))
    }
}

@Composable
private fun PlayerOperatorTable(snapshot: LiveSnapshot, phase: ScheduleMatchPhase) {
    val blue = snapshot.bluePlayers.sortedBy { roleOrder(it.role) }
    val red = snapshot.redPlayers.sortedBy { roleOrder(it.role) }
    if (blue.isEmpty() && red.isEmpty()) return

    OperatorPanel {
        Text(
            if (phase == ScheduleMatchPhase.COMPLETED) "PLAYER STATE / 选手终局状态" else "PLAYER STATE / 选手实时状态",
            color = RiftCyan,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        val count = max(blue.size, red.size)
        repeat(count) { index ->
            val left = blue.getOrNull(index)
            val right = red.getOrNull(index)
            OperatorPlayerRow(left, right)
        }
    }
}

@Composable
private fun OperatorPlayerRow(left: LivePlayerSnapshot?, right: LivePlayerSnapshot?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(left?.summonerName ?: "—", color = RiftText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(playerOperatorStats(left), color = RiftMuted, fontSize = 7.sp)
        }
        Text(
            left?.role?.ifBlank { right?.role.orEmpty() }.orEmpty(),
            color = RiftMuted,
            fontSize = 7.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(34.dp)
        )
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(right?.summonerName ?: "—", color = RiftText, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
            Text(playerOperatorStats(right), color = RiftMuted, fontSize = 7.sp, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun LifecycleCoveragePanel(record: MatchLifecycleRecord, selectedGame: Int) {
    OperatorPanel {
        Text("LIFECYCLE ARCHIVE / 生命周期档案", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        OperatorValueRow("当前阶段", record.phase.name)
        OperatorValueRow("赛程 revision", record.scheduleRevisions.size.toString())
        OperatorValueRow("G$selectedGame 状态帧", record.framesFor(selectedGame).size.toString())
        OperatorValueRow("终局快照", record.finalGames.keys.sorted().joinToString(prefix = "G", separator = ", G").ifBlank { "暂无" })
        OperatorValueRow("最近写入", record.updatedAtEpochMs.toString())
        Spacer(Modifier.height(7.dp))
        Text(
            "生命周期档案不会因页面切换而重置。赛前变更、赛中连续状态、赛后终局共用同一个 Match key，为后续沙盘恢复历史状态保留真实输入。",
            color = RiftMuted,
            fontSize = 8.sp
        )
    }
}

@Composable
private fun OperatorValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = RiftMuted, fontSize = 8.sp, modifier = Modifier.width(92.dp))
        Text(value, color = RiftText, fontSize = 8.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

@Composable
private fun OperatorPanel(accent: Boolean = false, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = CutCornerShape(topEnd = 14.dp, bottomStart = 9.dp)
    Column(
        Modifier.fillMaxWidth()
            .background(RiftPanel, shape)
            .border(1.dp, if (accent) RiftCyan.copy(alpha = 0.58f) else RiftLine, shape)
            .padding(14.dp),
        content = content
    )
}

private fun currentMatchMatches(current: ScheduledEsportsMatch?, wanted: ScheduledEsportsMatch): Boolean =
    current != null && sameMatch(current, wanted)

private fun sameMatch(a: ScheduledEsportsMatch, b: ScheduledEsportsMatch): Boolean {
    if (a.eventId.isNotBlank() && b.eventId.isNotBlank() && a.eventId == b.eventId) return true
    if (a.matchId.isNotBlank() && b.matchId.isNotBlank() && a.matchId == b.matchId) return true
    return a.teams.take(2).map { teamToken(it.code.ifBlank { it.name }) }.toSet() ==
        b.teams.take(2).map { teamToken(it.code.ifBlank { it.name }) }.toSet()
}

private fun seriesMatches(teamA: String, teamB: String, match: ScheduledEsportsMatch): Boolean {
    val series = setOf(teamToken(teamA), teamToken(teamB)).filter { it.isNotBlank() }.toSet()
    val scheduled = match.teams.take(2).map { teamToken(it.code.ifBlank { it.name }) }.filter { it.isNotBlank() }.toSet()
    return series.size == 2 && series == scheduled
}

private fun playerOperatorStats(player: LivePlayerSnapshot?): String = player?.let {
    "Lv${it.level} · ${it.kills}/${it.deaths}/${it.assists} · CS ${it.creepScore} · ${formatGold(it.gold)} · ${it.championId}"
} ?: "数据缺失"

private fun roleOrder(role: String): Int = when (role.uppercase()) {
    "TOP" -> 0
    "JUG", "JGL", "JUNGLE" -> 1
    "MID" -> 2
    "BOT", "ADC" -> 3
    "SUP", "SUPPORT" -> 4
    else -> 99
}

private fun teamToken(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

private fun signedGold(value: Int): String = when {
    value > 0 -> "+${formatGold(value)}"
    value < 0 -> "-${formatGold(-value)}"
    else -> "0"
}

private fun formatGold(value: Int): String = when {
    value >= 1000 -> "%.1fk".format(value / 1000.0)
    else -> value.toString()
}

private fun formatOperatorClock(seconds: Int): String =
    "%02d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)