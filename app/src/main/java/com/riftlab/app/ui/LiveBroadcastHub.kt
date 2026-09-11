package com.riftlab.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.riftlab.app.data.LiveSourcePhase
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.ScheduleActivityState
import com.riftlab.app.stream.StreamLauncher
import com.riftlab.app.stream.StreamPlatform
import com.riftlab.app.stream.StreamRegion

/**
 * dev.60 root shell.
 *
 * The LIVE badge is driven only by real match lifecycle state. It disappears automatically when
 * the current game leaves GAME_LIVE. EVENT_LIVE/BETWEEN_GAMES keep a separate ON AIR state so
 * RiftLab does not blur "event started" and "game live".
 */
@Composable
fun RiftLabLiveRoot() {
    MatchSessionStore.ensureDataRunning()
    val center by MatchSessionStore.scheduleCenter.collectAsState()
    val liveSource by MatchSessionStore.liveSourceStatus.collectAsState()
    val current = center.currentMatch
    val activity = current?.let(MatchSessionStore::scheduleActivity)

    val gameLive = liveSource.phase == LiveSourcePhase.LIVE ||
        activity == ScheduleActivityState.GAME_LIVE
    val eventActive = gameLive ||
        activity == ScheduleActivityState.EVENT_LIVE ||
        activity == ScheduleActivityState.BETWEEN_GAMES

    var watchHubOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        RiftLabRoot()
        BroadcastHubLauncher(
            gameLive = gameLive,
            eventActive = eventActive,
            matchup = current?.teams
                ?.take(2)
                ?.joinToString(" VS ") { it.code.ifBlank { it.name } }
                .orEmpty(),
            onClick = { watchHubOpen = true },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp, bottom = 18.dp)
                .zIndex(10f)
        )
    }

    if (watchHubOpen) {
        BroadcastSourceDialog(onClose = { watchHubOpen = false })
    }
}

@Composable
private fun BroadcastHubLauncher(
    gameLive: Boolean,
    eventActive: Boolean,
    matchup: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "riftlab-live-badge")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(760),
            repeatMode = RepeatMode.Reverse
        ),
        label = "riftlab-live-pulse"
    )
    val shape = CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp)

    Column(
        modifier
            .clickable(onClick = onClick)
            .background(
                when {
                    gameLive -> RiftRed.copy(alpha = 0.12f)
                    eventActive -> RiftCyan.copy(alpha = 0.10f)
                    else -> RiftPanel
                },
                shape
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (gameLive) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(RiftRed.copy(alpha = pulse), CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text("LIVE", color = RiftRed, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.width(6.dp))
                Text("观赛", color = RiftText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            } else {
                Text(
                    if (eventActive) "ON AIR · 观赛" else "直播入口",
                    color = if (eventActive) RiftCyan else RiftText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (gameLive && matchup.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(matchup, color = RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun BroadcastSourceDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            color = RiftBg,
            contentColor = RiftText,
            shape = CutCornerShape(topEnd = 20.dp, bottomStart = 14.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("LIVE / 观赛入口", color = RiftText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(3.dp))
                        Text("国内 + 海外官方/主流直播入口", color = RiftMuted, fontSize = 10.sp)
                    }
                    Text(
                        "关闭",
                        color = RiftCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onClose).padding(8.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))
                BroadcastRegionSection(
                    title = "中国大陆",
                    platforms = StreamPlatform.entries.filter { it.region == StreamRegion.MAINLAND },
                    onOpen = { platform ->
                        onClose()
                        StreamLauncher.watch(context, platform)
                    }
                )

                Spacer(Modifier.height(16.dp))
                BroadcastRegionSection(
                    title = "海外 / GLOBAL",
                    platforms = StreamPlatform.entries.filter { it.region == StreamRegion.GLOBAL },
                    onOpen = { platform ->
                        onClose()
                        StreamLauncher.watch(context, platform)
                    }
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    "直播入口只负责跳转到对应平台；RiftScreen 与赛事实时数据仍由 RiftLab 独立运行。海外入口包括 LoL Esports 官方站、YouTube、Twitch 与 X。",
                    color = RiftMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
private fun BroadcastRegionSection(
    title: String,
    platforms: List<StreamPlatform>,
    onOpen: (StreamPlatform) -> Unit
) {
    RiftSectionLabel(title)
    Spacer(Modifier.height(7.dp))
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        platforms.forEach { platform ->
            RiftHudPanel(onClick = { onOpen(platform) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(platform.displayName, color = RiftText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (platform.packages.isEmpty()) "网页入口" else "优先打开已安装 APP · 否则网页",
                            color = RiftMuted,
                            fontSize = 10.sp
                        )
                    }
                    RiftStatusBadge("OPEN")
                }
            }
        }
    }
}
