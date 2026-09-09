package com.riftlab.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.riftlab.app.data.BilibiliMatchVod
import com.riftlab.app.data.BilibiliNativePlaybackResolver
import com.riftlab.app.data.BilibiliNativePlaybackSource
import com.riftlab.app.data.BilibiliVodPart
import com.riftlab.app.data.BilibiliVodRepository
import com.riftlab.app.data.GameTimeline
import com.riftlab.app.data.MatchDetailRepository
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.MatchTimelineEvent
import com.riftlab.app.data.MatchTimelineStore
import com.riftlab.app.data.ScheduleMatchPhase
import com.riftlab.app.data.TimelineEventType
import kotlinx.coroutines.delay

private data class ReplayAnchor(
    val id: String,
    val gameSecond: Int,
    val videoSecond: Int,
    val title: String,
    val team: String = "",
    val detail: String = "",
    val source: String = ""
)

/**
 * One completed-match surface for both replay video and historical timeline.
 *
 * The player is pinned above the scrolling event list so jumping to a late event never scrolls the
 * game picture off-screen. The former standalone VOD page and historical VOD timeline are merged
 * here intentionally: they are two views of the same source, not two different product features.
 */
@Composable
internal fun MatchReplayContent() {
    val detail by MatchDetailRepository.state.collectAsState()
    val allTimelines by MatchTimelineStore.timelines.collectAsState()
    val vodState by BilibiliVodRepository.state.collectAsState()
    val match = detail.match

    if (match == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("未选择比赛", color = RiftMuted)
        }
        return
    }

    val completed = MatchSessionStore.schedulePhase(match) == ScheduleMatchPhase.COMPLETED
    if (!completed) {
        MatchTimelineContent()
        return
    }

    if (!isLplReplayMatch(match)) {
        GlobalOfficialReplayContent(match)
        return
    }

    val key = BilibiliVodRepository.keyFor(match)
    LaunchedEffect(key) {
        if (key.isNotBlank()) BilibiliVodRepository.open(match)
    }

    val stateMatches = vodState.matchKey == key
    val vod = vodState.vod.takeIf { stateMatches }
    val series = detail.series
    val games = remember(series?.games, vod?.parts) {
        buildList {
            series?.games.orEmpty().map { it.game }.filter { it > 0 }.forEach(::add)
            vod?.parts.orEmpty().map { it.game }.filter { it > 0 && it !in this }.forEach(::add)
        }.distinct().sorted()
    }
    var selectedGame by remember(key) {
        mutableIntStateOf(games.firstOrNull() ?: 1)
    }
    LaunchedEffect(games) {
        if (games.isNotEmpty() && selectedGame !in games) selectedGame = games.first()
    }

    val part = vod?.parts?.firstOrNull { it.game == selectedGame }
    val snapshot = series?.games?.firstOrNull { it.game == selectedGame }
    val localTimeline = snapshot?.let { MatchTimelineStore.find(it, allTimelines) }
    val anchors = remember(part, localTimeline) { buildReplayAnchors(part, localTimeline) }
    var seekVideoSecond by remember(key, selectedGame, part?.cid) {
        mutableIntStateOf(part?.gameStartOffsetSeconds ?: 0)
    }

    Column(Modifier.fillMaxSize()) {
        ReplayHeader(
            game = selectedGame,
            sourceText = when {
                part?.chapters?.isNotEmpty() == true -> "B站官方章节 · ${anchors.size} 个事件锚点"
                localTimeline?.events?.isNotEmpty() == true -> "RiftLab 本机实时记录 · ${anchors.size} 个事件锚点"
                stateMatches && vodState.loading -> "正在解析官方录像…"
                else -> "等待历史回放数据"
            }
        )
        if (games.size > 1) {
            Spacer(Modifier.height(7.dp))
            ReplayGameTabs(games, selectedGame) { selectedGame = it }
        }
        Spacer(Modifier.height(8.dp))

        if (vod != null && part != null) {
            StickyNativeReplayPlayer(
                vod = vod,
                part = part,
                startSecond = seekVideoSecond,
                anchors = anchors
            )
        } else {
            ReplayPlayerPlaceholder(
                when {
                    !stateMatches || vodState.loading -> "正在匹配 B站官方完整录像…"
                    vodState.errorMessage != null -> "录像解析失败 · ${vodState.errorMessage?.take(100)}"
                    else -> "暂未找到这一局的官方录像"
                }
            )
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (vod != null) {
                item(key = "source") { ReplaySourceCard(vod) }
            }
            item(key = "event-title") {
                Text(
                    "EVENT TIMELINE / 点击事件直接跳转录像",
                    color = RiftMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 9.dp)
                )
            }
            if (anchors.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(RiftPanel, CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
                            .border(1.dp, RiftLine, CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
                            .padding(14.dp)
                    ) {
                        Text("暂无可核实事件锚点", color = RiftText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "录像仍可播放，但上游没有公开章节且本机没有连续实时事件。RiftLab 不会根据终局比分虚构时间轴。",
                            color = RiftMuted,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                    }
                }
            } else {
                items(anchors, key = { it.id }) { anchor ->
                    ReplayAnchorRow(anchor) {
                        seekVideoSecond = anchor.videoSecond
                    }
                }
            }
            item(key = "bottom-space") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ReplayHeader(game: Int, sourceText: String) {
    val shape = CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp)
    Row(
        Modifier.fillMaxWidth()
            .background(RiftPanel, shape)
            .border(1.dp, RiftLine, shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("MATCH REPLAY", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text("G$game · 官方录像 + 时间轴", color = RiftText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(sourceText, color = RiftMuted, fontSize = 8.sp, textAlign = TextAlign.End)
    }
}

@Composable
private fun ReplayGameTabs(games: List<Int>, selectedGame: Int, onSelect: (Int) -> Unit) {
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
                        if (selected) RiftPanel else Color.Transparent,
                        CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp)
                    )
                    .clickable { onSelect(game) }
                    .padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun ReplaySourceCard(vod: BilibiliMatchVod) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(vod.title, color = RiftText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("官方来源 · ${vod.ownerName} · ${vod.bvid}", color = RiftMuted, fontSize = 8.sp)
        }
        Text(
            "原稿 ›",
            color = RiftCyan,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vod.sourceUrl)))
                }
            }.padding(8.dp)
        )
    }
}

@Composable
private fun ReplayAnchorRow(anchor: ReplayAnchor, onSeek: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onSeek)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            formatReplayClock(anchor.gameSecond),
            color = RiftCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                anchor.title,
                color = replayAnchorColor(anchor.title),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            if (anchor.team.isNotBlank()) {
                Text(anchor.team, color = RiftMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp))
            }
            if (anchor.detail.isNotBlank()) {
                Text(anchor.detail, color = RiftMuted, fontSize = 8.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 2)
            }
        }
        Text("跳转 ›", color = RiftMuted, fontSize = 8.sp)
    }
}

@Composable
private fun ReplayPlayerPlaceholder(text: String) {
    Box(
        Modifier.fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black, CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp))
            .border(1.dp, RiftLine, CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp))
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = RiftMuted, fontSize = 10.sp, textAlign = TextAlign.Center)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun StickyNativeReplayPlayer(
    vod: BilibiliMatchVod,
    part: BilibiliVodPart,
    startSecond: Int,
    anchors: List<ReplayAnchor>
) {
    var refreshNonce by remember(vod.bvid, part.cid) { mutableIntStateOf(0) }
    var loading by remember(vod.bvid, part.cid) { mutableStateOf(true) }
    var source by remember(vod.bvid, part.cid) { mutableStateOf<BilibiliNativePlaybackSource?>(null) }
    var error by remember(vod.bvid, part.cid) { mutableStateOf<String?>(null) }

    LaunchedEffect(vod.bvid, part.cid, refreshNonce) {
        loading = true
        error = null
        val result = runCatching {
            BilibiliNativePlaybackResolver.resolve(
                vod = vod,
                part = part,
                forceRefresh = refreshNonce > 0
            )
        }
        source = result.getOrNull()
        error = result.exceptionOrNull()?.message
        loading = false
    }

    when {
        loading -> ReplayPlayerPlaceholder("正在获取 B站当前播放源…")
        source == null -> {
            Column(
                Modifier.fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black, CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp))
                    .border(1.dp, RiftLine, CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("原生播放源暂不可用", color = RiftText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(error?.take(150) ?: "B站没有返回可用播放描述。", color = RiftMuted, fontSize = 8.sp, modifier = Modifier.padding(top = 5.dp))
                Text(
                    "重新获取 ›",
                    color = RiftCyan,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp).clickable { refreshNonce += 1 }
                )
            }
        }
        else -> ReplayPlayerSurface(
            source = source!!,
            part = part,
            startSecond = startSecond,
            anchors = anchors,
            onRefreshSource = { refreshNonce += 1 }
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ReplayPlayerSurface(
    source: BilibiliNativePlaybackSource,
    part: BilibiliVodPart,
    startSecond: Int,
    anchors: List<ReplayAnchor>,
    onRefreshSource: () -> Unit
) {
    val context = LocalContext.current
    val hostActivity = remember(context) { context.replayActivity() }
    var fullscreen by remember(source.bvid, source.cid) { mutableStateOf(false) }
    var playbackError by remember(source.bvid, source.cid) { mutableStateOf<String?>(null) }
    var currentVideoSecond by remember(source.bvid, source.cid) { mutableIntStateOf(startSecond.coerceAtLeast(0)) }

    DisposableEffect(fullscreen, hostActivity) {
        val previous = hostActivity?.requestedOrientation
        if (fullscreen) hostActivity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose {
            if (fullscreen) hostActivity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val exoPlayer = remember(context, source.bvid, source.cid) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(BilibiliNativePlaybackResolver.USER_AGENT)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://www.bilibili.com/",
                    "Origin" to "https://www.bilibili.com"
                )
            )
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = false
            }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = error.errorCodeName
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(source.resolvedAtEpochMs) {
        playbackError = null
        exoPlayer.setMediaItems(source.segments.map { MediaItem.fromUri(it.primaryUrl) }, true)
        exoPlayer.prepare()
        seekReplayPlayer(exoPlayer, source, startSecond)
    }
    LaunchedEffect(startSecond) {
        seekReplayPlayer(exoPlayer, source, startSecond)
    }
    LaunchedEffect(exoPlayer, source.resolvedAtEpochMs) {
        while (true) {
            currentVideoSecond = replayVideoSecond(exoPlayer, source)
            delay(750)
        }
    }

    val activeAnchor = anchors.lastOrNull { it.videoSecond <= currentVideoSecond }

    if (!fullscreen) {
        ReplayPlayerView(
            player = exoPlayer,
            part = part,
            currentVideoSecond = currentVideoSecond,
            activeAnchor = activeAnchor,
            fullscreen = false,
            onFullscreenToggle = { fullscreen = true },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        )
        if (playbackError != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("播放失败 · $playbackError", color = RiftMuted, fontSize = 8.sp, modifier = Modifier.weight(1f))
                Text(
                    "刷新播放源 ›",
                    color = RiftCyan,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onRefreshSource)
                )
            }
        }
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                ReplayPlayerView(
                    player = exoPlayer,
                    part = part,
                    currentVideoSecond = currentVideoSecond,
                    activeAnchor = activeAnchor,
                    fullscreen = true,
                    onFullscreenToggle = { fullscreen = false },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ReplayPlayerView(
    player: ExoPlayer,
    part: BilibiliVodPart,
    currentVideoSecond: Int,
    activeAnchor: ReplayAnchor?,
    fullscreen: Boolean,
    onFullscreenToggle: () -> Unit,
    modifier: Modifier
) {
    val shape = if (fullscreen) CutCornerShape(0.dp) else CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp)
    Box(modifier.background(Color.Black, shape).border(if (fullscreen) 0.dp else 1.dp, RiftLine, shape)) {
        val playerForView = player
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = playerForView
                    useController = true
                    controllerAutoShow = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                if (view.player !== playerForView) view.player = playerForView
            }
        )

        Text(
            if (fullscreen) "退出全屏" else "全屏 ⛶",
            color = Color.White,
            fontSize = if (fullscreen) 11.sp else 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd)
                .padding(10.dp)
                .background(Color.Black.copy(alpha = 0.66f), CutCornerShape(topEnd = 6.dp, bottomStart = 6.dp))
                .clickable(onClick = onFullscreenToggle)
                .padding(horizontal = 10.dp, vertical = 7.dp)
        )

        if (fullscreen) {
            Column(
                Modifier.align(Alignment.TopStart)
                    .padding(14.dp)
                    .fillMaxWidth(0.62f)
                    .background(Color.Black.copy(alpha = 0.70f), CutCornerShape(topEnd = 10.dp, bottomStart = 8.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(
                    "G${part.game} · ${formatReplayClock(part.gameSecondFor(currentVideoSecond))}",
                    color = RiftCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    activeAnchor?.title ?: "比赛回放",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 2.dp)
                )
                activeAnchor?.team?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color.White.copy(alpha = 0.72f), fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

private fun buildReplayAnchors(part: BilibiliVodPart?, localTimeline: GameTimeline?): List<ReplayAnchor> {
    if (part != null && part.chapters.isNotEmpty()) {
        return part.chapters.mapIndexed { index, chapter ->
            ReplayAnchor(
                id = "vod-${part.cid}-$index-${chapter.fromSeconds}",
                gameSecond = part.gameSecondFor(chapter.fromSeconds),
                videoSecond = chapter.fromSeconds,
                title = chapter.title,
                team = chapter.teamName,
                source = "B站官方章节"
            )
        }.sortedBy { it.videoSecond }
    }

    val timeline = localTimeline ?: return emptyList()
    val firstObservedSecond = timeline.points.firstOrNull()?.seconds ?: 0
    return timeline.events
        .filterNot { it.type == TimelineEventType.GAME_START && firstObservedSecond > 30 }
        .mapIndexed { index, event ->
            ReplayAnchor(
                id = "local-${timeline.gameId}-$index-${event.seconds}-${event.type}",
                gameSecond = event.seconds,
                videoSecond = (part?.gameStartOffsetSeconds ?: 0) + event.seconds,
                title = replayTimelineTitle(event),
                team = event.team,
                detail = event.detail,
                source = "RiftLab 本机实时记录"
            )
        }
        .sortedBy { it.gameSecond }
}

private fun replayTimelineTitle(event: MatchTimelineEvent): String = when (event.type) {
    TimelineEventType.GAME_START -> "比赛开始"
    TimelineEventType.KILL -> event.title.ifBlank { "击杀" }
    TimelineEventType.TOWER -> event.title.ifBlank { "防御塔" }
    TimelineEventType.DRAGON -> event.title.ifBlank { "元素亚龙" }
    TimelineEventType.BARON -> event.title.ifBlank { "纳什男爵" }
    TimelineEventType.GOLD_SWING -> event.title.ifBlank { "经济领先变化" }
    TimelineEventType.GAME_END -> "比赛结束"
}

@Composable
private fun replayAnchorColor(title: String): Color = when {
    title.contains("第一滴血") || title.contains("击杀") && !title.contains("亚龙") && !title.contains("男爵") -> RiftRed
    title.contains("龙") || title.contains("男爵") || title.contains("先锋") || title.contains("巢虫") -> RiftCyan
    title.contains("塔") -> RiftCyan
    else -> RiftText
}

private fun seekReplayPlayer(player: ExoPlayer, source: BilibiliNativePlaybackSource, videoSecond: Int) {
    if (source.segments.isEmpty()) return
    var remainingMs = videoSecond.coerceAtLeast(0) * 1000L
    if (source.segments.size == 1) {
        player.seekTo(remainingMs)
        return
    }
    source.segments.forEachIndexed { index, segment ->
        val duration = segment.durationMs
        if (duration <= 0L || remainingMs < duration || index == source.segments.lastIndex) {
            player.seekTo(index, remainingMs.coerceAtLeast(0L))
            return
        }
        remainingMs -= duration
    }
}

private fun replayVideoSecond(player: ExoPlayer, source: BilibiliNativePlaybackSource): Int {
    if (source.segments.isEmpty()) return 0
    val index = player.currentMediaItemIndex.coerceIn(0, source.segments.lastIndex)
    val beforeMs = source.segments.take(index).sumOf { it.durationMs.coerceAtLeast(0L) }
    return ((beforeMs + player.currentPosition.coerceAtLeast(0L)) / 1000L).toInt()
}

private fun formatReplayClock(seconds: Int): String =
    "%02d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)


private tailrec fun Context.replayActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.replayActivity()
    else -> null
}
