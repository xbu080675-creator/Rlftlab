package com.riftlab.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.BuildConfig
import com.riftlab.app.data.LivePlayerSnapshot
import com.riftlab.app.data.LiveSourcePhase
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.MockAiInsightEngine
import com.riftlab.app.data.EsportsStaffRef
import com.riftlab.app.data.PlayerCard
import com.riftlab.app.data.PreRecentSeries
import com.riftlab.app.data.ScheduleActivityState
import com.riftlab.app.data.ScheduledEsportsMatch
import com.riftlab.app.stream.StreamLauncher
import com.riftlab.app.stream.StreamPlatform
import com.riftlab.app.update.AppUpdateManager
import kotlin.math.abs

private enum class Phase(val label: String) { PRE("赛前"), LIVE("赛中"), POST("赛后") }

@Composable
fun RiftLabApp() {
    RiftTheme {
        MatchSessionStore.ensureDataRunning()
        var phase by remember { mutableIntStateOf(1) }
        val context = LocalContext.current
        var updateCenterOpen by remember { androidx.compose.runtime.mutableStateOf(false) }
        var sourceSettingsOpen by remember { androidx.compose.runtime.mutableStateOf(false) }
        LaunchedEffect(context) { AppUpdateManager.initialize(context) }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        Scaffold(containerColor = RiftBg) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Header(
                    onVersionClick = { updateCenterOpen = true },
                    onSourceSettingsClick = { sourceSettingsOpen = true }
                )
                if (updateCenterOpen) UpdateCenterDialog(onClose = { updateCenterOpen = false })
                if (sourceSettingsOpen) RealtimeSourceSettingsDialog(onClose = { sourceSettingsOpen = false })
                PhaseTabs(phase) { phase = it }
                LeagueSubscriptionBar()
                AnimatedContent(
                    targetState = Phase.entries[phase],
                    transitionSpec = {
                        androidx.compose.animation.fadeIn(tween(180)) togetherWith
                            androidx.compose.animation.fadeOut(tween(120))
                    },
                    label = "phase"
                ) { current ->
                    when (current) {
                        Phase.PRE -> PreScreen()
                        Phase.LIVE -> LiveScreen(
                            startOverlay = { StreamLauncher.startOverlay(context) },
                            watchBili = { StreamLauncher.watch(context, StreamPlatform.BILIBILI) },
                            watchHuya = { StreamLauncher.watch(context, StreamPlatform.HUYA) }
                        )
                        Phase.POST -> PostScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(onVersionClick: () -> Unit, onSourceSettingsClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).background(RiftCyan, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.SportsEsports, null, tint = RiftBg)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("RIFTLAB", fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 1.4.sp)
            Text("LEAGUE ESPORTS COMPANION", color = RiftMuted, fontSize = 9.sp, letterSpacing = 1.1.sp)
        }
        Box(
            Modifier.size(34.dp).clickable(onClick = onSourceSettingsClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "实时数据源设置",
                tint = RiftMuted,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            BuildConfig.VERSION_NAME.replace("1.0.0-", "1.0 ").uppercase(),
            color = RiftCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onVersionClick).padding(horizontal = 6.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun PhaseTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Phase.entries.forEachIndexed { index, phase ->
            Column(
                Modifier.weight(1f).clickable { onSelect(index) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    phase.label,
                    color = if (index == selected) RiftText else RiftMuted,
                    fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.height(2.dp).fillMaxWidth(0.55f)
                        .background(if (index == selected) RiftCyan else Color.Transparent)
                )
            }
        }
    }
}

@Composable
private fun PreScreen() {
    val data by MatchSessionStore.preMatchFlow.collectAsState()
    val scheduleStatus by MatchSessionStore.scheduleStatus.collectAsState()
    val target by MatchSessionStore.targetMatch.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { MatchHero(data.blue, data.red, data.startTime, "${data.league} · ${data.stage}", target) }

        item { SectionTitle("REAL DATA SOURCE / 赛程源") }
        item {
            Panel(accent = target != null) {
                Text("RIOT LOL ESPORTS · SCHEDULE", color = if (target != null) RiftCyan else RiftMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Text(scheduleStatus, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                target?.let { match ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${match.teams.joinToString(" VS ") { it.code }} · BO${match.bestOf} · ${match.state.uppercase()}",
                        color = RiftText,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    )
                    Text("EVENT ${match.eventId}", color = RiftMuted, fontSize = 10.sp)
                    Text(MatchSessionStore.scheduleDateTimeLabel(match), color = RiftMuted, fontSize = 10.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("NO MOCK FALLBACK", color = RiftRed.copy(alpha = 0.85f), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        item { SideSelectionPrePanel() }

        item { SectionTitle("DATA COVERAGE / 全面数据") }
        item { ComprehensiveDataCoveragePanel() }

        item { SectionTitle("STARTING ROSTER / 首发") }
        val starterRows = maxOf(data.blueRoster.size, data.redRoster.size)
        if (starterRows > 0) {
            items((0 until starterRows).toList()) { index ->
                RosterRow(data.blueRoster.getOrNull(index), data.redRoster.getOrNull(index))
            }
        } else {
            item {
                Panel {
                    Text("STARTERS NOT CONFIRMED", color = RiftMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Text("Roster pool 可以已连接，但存在同位置多人时不会把名单顺序当作官方首发。", color = RiftMuted, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
        }
        item {
            Panel {
                Text("ROSTER / RANK STATUS", color = RiftCyan, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Text(data.rosterNote, color = RiftMuted, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }

        if (data.blueRosterPool.isNotEmpty() || data.redRosterPool.isNotEmpty()) {
            item { SectionTitle("ROSTER POOL / 名单池（不等于首发）") }
            item {
                Panel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(rosterPoolLabel(data.blue, data.blueRosterPool), modifier = Modifier.weight(1f), color = RiftText, fontSize = 9.sp, lineHeight = 14.sp)
                        Text(rosterPoolLabel(data.red, data.redRosterPool), modifier = Modifier.weight(1f), color = RiftText, fontSize = 9.sp, lineHeight = 14.sp)
                    }
                }
            }
        }

        if (data.blueStaff.isNotEmpty() || data.redStaff.isNotEmpty()) {
            item { SectionTitle("TEAM STAFF / 教练组与工作人员") }
            item {
                Panel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(staffLabel(data.blue, data.blueStaff), modifier = Modifier.weight(1f), color = RiftText, fontSize = 9.sp, lineHeight = 14.sp)
                        Text(staffLabel(data.red, data.redStaff), modifier = Modifier.weight(1f), color = RiftText, fontSize = 9.sp, lineHeight = 14.sp)
                    }
                }
            }
        }

        item { SectionTitle("RECENT FORM / 近期正式系列赛") }
        item {
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(recentSeriesLabel(data.blue, data.blueRecentSeries), modifier = Modifier.weight(1f), color = RiftText, fontSize = 9.sp, lineHeight = 14.sp)
                    Text(recentSeriesLabel(data.red, data.redRecentSeries), modifier = Modifier.weight(1f), color = RiftText, fontSize = 9.sp, lineHeight = 14.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("仅统计当前 Unified Schedule 历史窗口中已验证结束的 Series；不是全历史数据库。", color = RiftMuted, fontSize = 8.sp)
            }
        }

        item { SectionTitle("RECENT H2H / 近期交手") }
        item {
            Panel {
                Text(
                    if (data.recentHeadToHead.isEmpty()) "当前历史窗口没有可核实的近期直接交手。" else recentSeriesLabel(data.blue, data.recentHeadToHead),
                    color = RiftText,
                    fontSize = 9.sp,
                    lineHeight = 14.sp
                )
                Text("SOURCE  Unified Schedule · Riot/Cito", color = RiftMuted, fontSize = 8.sp)
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun LiveScreen(startOverlay: () -> Unit, watchBili: () -> Unit, watchHuya: () -> Unit) {
    val snapshot by MatchSessionStore.live.collectAsState()
    val status by MatchSessionStore.liveSourceStatus.collectAsState()
    val scheduled by MatchSessionStore.preMatchFlow.collectAsState()
    val target by MatchSessionStore.targetMatch.collectAsState()
    val isLive = status.phase == LiveSourcePhase.LIVE
    val activity = target?.let(MatchSessionStore::scheduleActivity)
    val eventActive = isLive ||
        activity == ScheduleActivityState.EVENT_LIVE ||
        activity == ScheduleActivityState.BETWEEN_GAMES
    val phaseLabel = when {
        isLive -> "小局直播 · GAME ${snapshot.game}"
        activity == ScheduleActivityState.BETWEEN_GAMES -> "局间 · 等待下一小局"
        activity == ScheduleActivityState.EVENT_LIVE -> "赛事已开始 · 等待小局"
        status.phase == LiveSourcePhase.WAITING_FOR_MATCH -> "等待赛事开始"
        status.phase == LiveSourcePhase.ERROR -> "实时源异常"
        else -> "实时源待机"
    }
    val feedLabel = when {
        isLive -> "小局实时数据"
        activity == ScheduleActivityState.BETWEEN_GAMES -> "局间待机"
        activity == ScheduleActivityState.EVENT_LIVE -> "赛事进行中 · 等待小局数据"
        status.phase == LiveSourcePhase.WAITING_FOR_MATCH -> "等待赛事"
        status.phase == LiveSourcePhase.ERROR -> "实时源异常"
        else -> "实时源待机"
    }
    val displayBlue = if (isLive) snapshot.blue else scheduled.blue.takeUnless { it.isBlank() || it == "—" } ?: "—"
    val displayRed = if (isLive) snapshot.red else scheduled.red.takeUnless { it.isBlank() || it == "—" } ?: "—"
    val ai = remember { MockAiInsightEngine() }
    var insight by remember { androidx.compose.runtime.mutableStateOf("等待 Riot 实时帧；暂不生成局势判断。") }

    LaunchedEffect(snapshot, status.phase) {
        insight = if (isLive && (snapshot.blueGold > 0 || snapshot.redGold > 0)) {
            ai.analyze(snapshot, null)
        } else {
            "等待 Riot 实时帧；本地局势解读暂不生成，避免把占位数据当真。"
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Panel(accent = eventActive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        phaseLabel,
                        color = when {
                            status.phase == LiveSourcePhase.ERROR -> RiftRed
                            eventActive -> RiftCyan
                            else -> RiftMuted
                        },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (isLive) MatchSessionStore.formatTime(snapshot.elapsedSeconds) else "--:--",
                        color = RiftMuted,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(16.dp))
                val leftAsset = target?.teams?.firstOrNull { teamLabelMatches(displayBlue, it) } ?: target?.teams?.getOrNull(0)
                val rightAsset = target?.teams?.firstOrNull { teamLabelMatches(displayRed, it) } ?: target?.teams?.getOrNull(1)
                TeamMatchupVisual(
                    leftCode = displayBlue,
                    leftImageUrl = leftAsset?.imageUrl.orEmpty(),
                    rightCode = displayRed,
                    rightImageUrl = rightAsset?.imageUrl.orEmpty(),
                    centerText = if (isLive) formatGoldDiff(snapshot.goldDiff) else "VS",
                    leftSubtext = if (isLive && snapshot.blueGold > 0) "%.1fK".format(snapshot.blueGold / 1000f) else "—",
                    rightSubtext = if (isLive && snapshot.redGold > 0) "%.1fK".format(snapshot.redGold / 1000f) else "—",
                    centerSubtext = if (isLive) "GOLD DIFF" else null,
                    logoSize = 54.dp,
                    centerFontSize = 24.sp
                )
                Spacer(Modifier.height(14.dp))
                MetricRow(
                    if (isLive) "K ${snapshot.blueKills}:${snapshot.redKills}" else "K —",
                    if (isLive) "T ${snapshot.blueTowers}:${snapshot.redTowers}" else "T —",
                    if (isLive) "D ${snapshot.blueDragons}:${snapshot.redDragons}" else "D —",
                    if (isLive) "B ${snapshot.blueBarons}:${snapshot.redBarons}" else "B —"
                )
            }
        }

        item { SectionTitle("DATA FEED / 实时源") }
        item {
            Panel(accent = eventActive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        feedLabel,
                        color = when {
                            status.phase == LiveSourcePhase.ERROR -> RiftRed
                            eventActive -> RiftCyan
                            else -> RiftMuted
                        },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Text("POLL 3s", color = RiftMuted, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(7.dp))
                Text(status.message, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(7.dp))
                Text("SOURCE  ${snapshot.source}", color = RiftMuted, fontSize = 10.sp)
                Text("EVENT   ${status.eventId.ifBlank { "—" }}", color = RiftMuted, fontSize = 10.sp)
                Text("GAME    ${status.gameId.ifBlank { snapshot.gameId.ifBlank { "—" } }}", color = RiftMuted, fontSize = 10.sp)
                Text("NO MOCK FALLBACK", color = RiftRed.copy(alpha = 0.85f), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Analytics, null, tint = if (isLive) RiftCyan else RiftMuted)
                    Spacer(Modifier.width(8.dp))
                    Text("LOCAL LIVE READ", color = if (isLive) RiftCyan else RiftMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(insight, fontWeight = FontWeight.Medium, lineHeight = 21.sp)
                Spacer(Modifier.height(8.dp))
                Text(snapshot.latestEvent, color = RiftMuted, fontSize = 11.sp)
            }
        }

        if (isLive && (snapshot.bluePlayers.isNotEmpty() || snapshot.redPlayers.isNotEmpty())) {
            item { SectionTitle("LIVE PLAYERS / 选手实时数据") }
            items(maxOf(snapshot.bluePlayers.size, snapshot.redPlayers.size)) { index ->
                LivePlayerRow(
                    left = snapshot.bluePlayers.getOrNull(index),
                    right = snapshot.redPlayers.getOrNull(index)
                )
            }
        }

        item { SectionTitle("RIFTSCREEN / 赛事副屏") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("开启副屏", Icons.Default.PictureInPictureAlt, Modifier.weight(1f), startOverlay)
                ActionButton("B站观赛", Icons.AutoMirrored.Filled.OpenInNew, Modifier.weight(1f), watchBili)
                ActionButton("虎牙观赛", Icons.AutoMirrored.Filled.OpenInNew, Modifier.weight(1f), watchHuya)
            }
        }
        item {
            Text(
                "赛事开始状态与小局 LIVE 分开判定：选手入场、评论席等阶段只标记“赛事进行中”，只有实时 Provider 拿到小局帧才进入“小局直播”。直播跳转只是快捷入口，赛事数据与直播平台完全解耦。",
                color = RiftMuted,
                fontSize = 11.sp,
                lineHeight = 17.sp
            )
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun PostScreen() {
    val context = LocalContext.current
    val series by MatchSessionStore.completedSeries.collectAsState()
    val latest by MatchSessionStore.completedGame.collectAsState()
    val postStatus by MatchSessionStore.postSourceStatus.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val resolved = series
        if (resolved == null) {
            item {
                Panel(accent = false) {
                    Text("POST MATCH · SYNCING", color = RiftMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("正在同步赛后数据", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "赛后数据会从 LPL/TJStats 终局记录重新构建，不要求比赛时一直打开 RiftLab。",
                        color = RiftMuted,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )
                }
            }
            item { SectionTitle("REAL POST DATA / 赛后真实源") }
            item {
                Panel {
                    Text("当前没有可用终局记录", color = RiftCyan, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        latest?.latestEvent ?: postStatus,
                        color = RiftMuted,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        } else {
            item {
                Panel(
                    accent = resolved.seriesFinished,
                    onClick = {
                        EntityDetailLauncher.openMatch(
                            context,
                            resolved.matchKey,
                            resolved.teamA,
                            resolved.teamB
                        )
                    }
                ) {
                    Text(
                        if (resolved.seriesFinished) "POST MATCH · FINAL" else "POST MATCH · COMPLETED GAMES",
                        color = if (resolved.seriesFinished) RiftCyan else RiftMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    TeamMatchupVisual(
                        leftCode = resolved.teamA,
                        rightCode = resolved.teamB,
                        centerText = "${resolved.scoreA} : ${resolved.scoreB}",
                        centerSubtext = if (resolved.seriesFinished) "WINNER · ${resolved.winner}" else "系列赛进行中",
                        logoSize = 62.dp,
                        centerFontSize = 24.sp
                    )
                }
            }

            item { SectionTitle("GAME RESULTS / 小局终局数据") }
            items(resolved.games) { game ->
                Panel(
                    accent = false,
                    onClick = {
                        EntityDetailLauncher.openMatch(
                            context,
                            resolved.matchKey,
                            resolved.teamA,
                            resolved.teamB
                        )
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("GAME ${game.game}", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(MatchSessionStore.formatTime(game.elapsedSeconds), color = RiftMuted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    TeamMatchupVisual(
                        leftCode = game.blue,
                        rightCode = game.red,
                        centerText = "${game.blueKills} : ${game.redKills}",
                        leftSubtext = if (game.blueGold > 0) "%.1fK".format(game.blueGold / 1000f) else "—",
                        rightSubtext = if (game.redGold > 0) "%.1fK".format(game.redGold / 1000f) else "—",
                        centerSubtext = "DIFF ${formatGoldDiff(game.goldDiff)}",
                        logoSize = 42.dp,
                        centerFontSize = 18.sp
                    )
                    Spacer(Modifier.height(5.dp))
                    MetricRow(
                        "K ${game.blueKills}:${game.redKills}",
                        "T ${game.blueTowers}:${game.redTowers}",
                        "D ${game.blueDragons}:${game.redDragons}",
                        "B ${game.blueBarons}:${game.redBarons}"
                    )
                }
            }

            item { SectionTitle("REAL POST DATA / 赛后真实源") }
            item {
                Panel {
                    Text(resolved.source, color = RiftCyan, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "共恢复 ${resolved.games.size} 局终局数据。MVP / 赛后官方评选只有在上游提供可核实字段后才展示，不生成 Mock 结论。",
                        color = RiftMuted,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun MatchHero(blue: String, red: String, time: String, label: String, match: ScheduledEsportsMatch?) {
    val left = match?.teams?.getOrNull(0)
    val right = match?.teams?.getOrNull(1)
    Panel(accent = true) {
        Text(label, color = RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        TeamMatchupVisual(
            leftCode = left?.code?.ifBlank { left.name } ?: blue,
            leftImageUrl = left?.imageUrl.orEmpty(),
            rightCode = right?.code?.ifBlank { right.name } ?: red,
            rightImageUrl = right?.imageUrl.orEmpty(),
            centerText = "VS",
            centerSubtext = time,
            logoSize = 64.dp,
            centerFontSize = 20.sp
        )
    }
}

@Composable
private fun RosterRow(left: PlayerCard?, right: PlayerCard?) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(left?.role ?: right?.role ?: "—", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Text(left?.id ?: "未确认", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (left != null) {
                    Text(left.rank, color = RiftMuted, fontSize = 10.sp)
                    Text(left.recent, color = RiftMuted, fontSize = 9.sp)
                }
            }
            Text("↔", color = RiftLine, fontSize = 18.sp)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(right?.role ?: left?.role ?: "—", color = RiftRed, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Text(right?.id ?: "未确认", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (right != null) {
                    Text(right.rank, color = RiftMuted, fontSize = 10.sp)
                    Text(right.recent, color = RiftMuted, fontSize = 9.sp)
                }
            }
        }
    }
}

private fun rosterPoolLabel(team: String, pool: List<PlayerCard>): String = buildString {
    append(team).append("\n")
    if (pool.isEmpty()) append("名单池待同步")
    else pool.forEach { player -> append(player.role).append("  ").append(player.id).append("\n") }
}.trimEnd()

private fun staffLabel(team: String, staff: List<EsportsStaffRef>): String = buildString {
    append(team).append("\n")
    if (staff.isEmpty()) append("Staff 待同步")
    else staff.take(8).forEach { person ->
        append(person.displayRole.ifBlank { person.role }.ifBlank { "STAFF" })
            .append("  ").append(person.name).append("\n")
    }
}.trimEnd()

private fun recentSeriesLabel(team: String, rows: List<PreRecentSeries>): String = buildString {
    append(team).append("\n")
    if (rows.isEmpty()) append("当前历史窗口暂无已结束 Series")
    else rows.forEach { row ->
        append(row.outcome).append("  ")
            .append(row.scoreFor).append(':').append(row.scoreAgainst)
            .append(" vs ").append(row.opponentCode)
            .append(" · ").append(row.startTimeIso.take(10))
            .append("\n")
    }
}.trimEnd()

@Composable
private fun LivePlayerRow(left: LivePlayerSnapshot?, right: LivePlayerSnapshot?) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(left?.role?.uppercase().orEmpty(), color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Text(left?.summonerName ?: "—", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    left?.let { "${it.kills}/${it.deaths}/${it.assists} · CS ${it.creepScore} · G ${it.gold}" } ?: "—",
                    color = RiftMuted,
                    fontSize = 9.sp
                )
            }
            Text("↔", color = RiftLine, fontSize = 16.sp)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(right?.role?.uppercase().orEmpty(), color = RiftRed, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Text(right?.summonerName ?: "—", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    right?.let { "${it.kills}/${it.deaths}/${it.assists} · CS ${it.creepScore} · G ${it.gold}" } ?: "—",
                    color = RiftMuted,
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun TeamGold(name: String, gold: Int, alignment: Alignment.Horizontal) {
    Column(Modifier.width(90.dp), horizontalAlignment = alignment) {
        Text(name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(if (gold > 0) "%.1fK".format(gold / 1000f) else "—", color = RiftMuted, fontSize = 11.sp)
    }
}

@Composable
private fun Panel(
    accent: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val base = Modifier.fillMaxWidth()
    val interactive = if (onClick != null) base.clickable(onClick = onClick) else base
    Column(
        interactive
            .background(RiftPanel, CutCornerShape(topEnd = 18.dp, bottomStart = 10.dp))
            .border(
                1.dp,
                if (accent) RiftCyan.copy(alpha = 0.38f) else RiftLine,
                CutCornerShape(topEnd = 18.dp, bottomStart = 10.dp)
            )
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun SectionTitle(value: String) {
    Text(value, color = RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

@Composable
private fun MetricRow(left: String, centerLeft: String, centerRight: String, right: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(left, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        Text(centerLeft, color = RiftCyan, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        Text(centerRight, color = RiftRed, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        Text(right, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(58.dp),
        colors = ButtonDefaults.buttonColors(containerColor = RiftPanelAlt, contentColor = RiftText),
        shape = CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = RiftCyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(3.dp))
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}


private fun teamLabelMatches(label: String, team: com.riftlab.app.data.EsportsTeamRef): Boolean {
    val normalized = label.trim().replace(Regex("[^A-Za-z0-9]+"), "").uppercase()
    if (normalized.isBlank()) return false
    return listOf(team.code, team.name, team.slug, team.id).any { raw ->
        val candidate = raw.trim().replace(Regex("[^A-Za-z0-9]+"), "").uppercase()
        candidate.isNotBlank() && (candidate == normalized || candidate.contains(normalized) || normalized.contains(candidate))
    }
}

private fun formatGoldDiff(value: Int): String {
    val sign = if (value >= 0) "+" else "-"
    val n = abs(value)
    return if (n >= 1000) "$sign%.1fK".format(n / 1000f) else "$sign$n"
}
