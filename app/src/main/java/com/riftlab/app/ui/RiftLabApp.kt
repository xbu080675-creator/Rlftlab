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
import com.riftlab.app.data.LivePlayerSnapshot
import com.riftlab.app.data.LiveSourcePhase
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.MockAiInsightEngine
import com.riftlab.app.data.PlayerCard
import com.riftlab.app.stream.StreamLauncher
import com.riftlab.app.stream.StreamPlatform
import kotlin.math.abs

private enum class Phase(val label: String) { PRE("赛前"), LIVE("赛中"), POST("赛后") }

@Composable
fun RiftLabApp() {
    RiftTheme {
        MatchSessionStore.ensureDataRunning()
        var phase by remember { mutableIntStateOf(1) }
        val context = LocalContext.current
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        Scaffold(containerColor = RiftBg) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Header()
                PhaseTabs(phase) { phase = it }
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
private fun Header() {
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
            Text("RIFTLAB", fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = 1.4.sp)
            Text("LEAGUE ESPORTS COMPANION", color = RiftMuted, fontSize = 9.sp, letterSpacing = 1.1.sp)
        }
        Text("1.0 DEV.5", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                    fontWeight = if (index == selected) FontWeight.Black else FontWeight.Medium
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
        item { MatchHero(data.blue, data.red, data.startTime, "${data.league} · ${data.stage}") }

        item { SectionTitle("REAL DATA SOURCE / 赛程源") }
        item {
            Panel(accent = target != null) {
                Text("RIOT LOL ESPORTS · SCHEDULE", color = if (target != null) RiftCyan else RiftMuted, fontWeight = FontWeight.Black, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Text(scheduleStatus, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                target?.let { match ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${match.teams.joinToString(" VS ") { it.code }} · BO${match.bestOf} · ${match.state.uppercase()}",
                        color = RiftText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                    Text("EVENT ${match.eventId}", color = RiftMuted, fontSize = 10.sp)
                    Text(match.startTimeIso, color = RiftMuted, fontSize = 10.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("NO MOCK FALLBACK", color = RiftRed.copy(alpha = 0.85f), fontSize = 9.sp, fontWeight = FontWeight.Black)
            }
        }

        item { SectionTitle("STARTING ROSTER / 首发") }
        items(data.blueRoster.zip(data.redRoster)) { pair -> RosterRow(pair.first, pair.second) }
        item {
            Panel {
                Text("ROSTER / RANK STATUS", color = RiftCyan, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Text(data.rosterNote, color = RiftMuted, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun LiveScreen(startOverlay: () -> Unit, watchBili: () -> Unit, watchHuya: () -> Unit) {
    val snapshot by MatchSessionStore.live.collectAsState()
    val status by MatchSessionStore.liveSourceStatus.collectAsState()
    val isLive = status.phase == LiveSourcePhase.LIVE
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
            Panel(accent = isLive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isLive) "LIVE · GAME ${snapshot.game}" else "${status.phase.name} · GAME ${snapshot.game}",
                        color = when (status.phase) {
                            LiveSourcePhase.LIVE -> RiftCyan
                            LiveSourcePhase.ERROR -> RiftRed
                            else -> RiftMuted
                        },
                        fontWeight = FontWeight.Black,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamGold(snapshot.blue, snapshot.blueGold, Alignment.Start)
                    AnimatedContent(snapshot.goldDiff, label = "goldDiff") { diff ->
                        Text(
                            if (isLive) formatGoldDiff(diff) else "—",
                            color = if (diff >= 0) RiftCyan else RiftRed,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    TeamGold(snapshot.red, snapshot.redGold, Alignment.End)
                }
                Spacer(Modifier.height(14.dp))
                MetricRow(
                    "K ${snapshot.blueKills}:${snapshot.redKills}",
                    "T ${snapshot.blueTowers}:${snapshot.redTowers}",
                    "D ${snapshot.blueDragons}:${snapshot.redDragons}",
                    "B ${snapshot.blueBarons}:${snapshot.redBarons}"
                )
            }
        }

        item { SectionTitle("DATA FEED / 实时源") }
        item {
            Panel(accent = isLive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        status.phase.name,
                        color = when (status.phase) {
                            LiveSourcePhase.LIVE -> RiftCyan
                            LiveSourcePhase.ERROR -> RiftRed
                            else -> RiftMuted
                        },
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Text("POLL 3s", color = RiftMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(7.dp))
                Text(status.message, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(7.dp))
                Text("SOURCE  ${snapshot.source}", color = RiftMuted, fontSize = 10.sp)
                Text("EVENT   ${status.eventId.ifBlank { "—" }}", color = RiftMuted, fontSize = 10.sp)
                Text("GAME    ${status.gameId.ifBlank { snapshot.gameId.ifBlank { "—" } }}", color = RiftMuted, fontSize = 10.sp)
                Text("NO MOCK FALLBACK", color = RiftRed.copy(alpha = 0.85f), fontSize = 9.sp, fontWeight = FontWeight.Black)
            }
        }

        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Analytics, null, tint = if (isLive) RiftCyan else RiftMuted)
                    Spacer(Modifier.width(8.dp))
                    Text("LOCAL LIVE READ", color = if (isLive) RiftCyan else RiftMuted, fontWeight = FontWeight.Black, fontSize = 11.sp)
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
                "直播跳转只是快捷入口；赛事数据、RiftScreen 与直播平台完全解耦。计划开赛时间仅作参考，Live 状态以 Riot 实际数据为准。",
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
    val post = MatchSessionStore.postMatch
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Panel(accent = false) {
                Text("POST MATCH · PENDING", color = RiftMuted, fontSize = 11.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                Text(post.winner, fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text(post.positionRank, color = RiftMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        item { SectionTitle("REAL POST DATA / 赛后真实源") }
        item {
            Panel {
                Text("当前不展示任何 Mock MVP / 排行榜", color = RiftCyan, fontWeight = FontWeight.Black, fontSize = 12.sp)
                Spacer(Modifier.height(7.dp))
                Text(post.keyPoint, color = RiftMuted, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun MatchHero(blue: String, red: String, time: String, label: String) {
    Panel(accent = true) {
        Text(label, color = RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(blue, fontSize = 34.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("VS", color = RiftCyan, fontWeight = FontWeight.Black)
                Text(time, color = RiftMuted, fontSize = 11.sp)
            }
            Text(
                red,
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
    }
}

@Composable
private fun RosterRow(left: PlayerCard, right: PlayerCard) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(left.role, color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Black)
                Text(left.id, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text(left.rank, color = RiftMuted, fontSize = 10.sp)
                Text(left.recent, color = RiftMuted, fontSize = 9.sp)
            }
            Text("↔", color = RiftLine, fontSize = 18.sp)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(right.role, color = RiftRed, fontSize = 9.sp, fontWeight = FontWeight.Black)
                Text(right.id, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text(right.rank, color = RiftMuted, fontSize = 10.sp)
                Text(right.recent, color = RiftMuted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun LivePlayerRow(left: LivePlayerSnapshot?, right: LivePlayerSnapshot?) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(left?.role?.uppercase().orEmpty(), color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Black)
                Text(left?.summonerName ?: "—", fontWeight = FontWeight.Black, fontSize = 15.sp)
                Text(
                    left?.let { "${it.kills}/${it.deaths}/${it.assists} · CS ${it.creepScore} · G ${it.gold}" } ?: "—",
                    color = RiftMuted,
                    fontSize = 9.sp
                )
            }
            Text("↔", color = RiftLine, fontSize = 16.sp)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(right?.role?.uppercase().orEmpty(), color = RiftRed, fontSize = 9.sp, fontWeight = FontWeight.Black)
                Text(right?.summonerName ?: "—", fontWeight = FontWeight.Black, fontSize = 15.sp)
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
        Text(name, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text(if (gold > 0) "%.1fK".format(gold / 1000f) else "—", color = RiftMuted, fontSize = 11.sp)
    }
}

@Composable
private fun Panel(accent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
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
    Text(value, color = RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
}

@Composable
private fun MetricRow(left: String, centerLeft: String, centerRight: String, right: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(left, fontWeight = FontWeight.Black, fontSize = 11.sp)
        Text(centerLeft, color = RiftCyan, fontWeight = FontWeight.Black, fontSize = 11.sp)
        Text(centerRight, color = RiftRed, fontWeight = FontWeight.Black, fontSize = 11.sp)
        Text(right, fontWeight = FontWeight.Black, fontSize = 11.sp)
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
        colors = ButtonDefaults.buttonColors(containerColor = RiftPanelAlt),
        shape = CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = RiftCyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(3.dp))
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatGoldDiff(value: Int): String {
    val sign = if (value >= 0) "+" else "-"
    val n = abs(value)
    return if (n >= 1000) "$sign%.1fK".format(n / 1000f) else "$sign$n"
}