package com.riftlab.app.ui

import android.content.Intent
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
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.riftlab.app.data.BilibiliMatchVod
import com.riftlab.app.data.BilibiliNativePlaybackResolver
import com.riftlab.app.data.BilibiliNativePlaybackSource
import com.riftlab.app.data.BilibiliVodPart
import com.riftlab.app.data.BilibiliVodRepository
import com.riftlab.app.data.MatchDetailRepository
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.ScheduleMatchPhase

@Composable
internal fun MatchVodContent() {
    val detail by MatchDetailRepository.state.collectAsState()
    val match = detail.match
    val vodState by BilibiliVodRepository.state.collectAsState()
    val key = match?.let(BilibiliVodRepository::keyFor).orEmpty()
    val completed = match?.let { MatchSessionStore.schedulePhase(it) == ScheduleMatchPhase.COMPLETED } == true

    LaunchedEffect(key, completed) {
        if (match != null && completed && key.isNotBlank()) BilibiliVodRepository.open(match)
    }

    if (match == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("未选择比赛", color = RiftMuted)
        }
        return
    }

    val stateMatches = vodState.matchKey == key
    val vod = vodState.vod.takeIf { stateMatches }
    val parts = vod?.parts.orEmpty()
    var selectedGame by remember(key, parts.map { it.game }) {
        mutableIntStateOf(parts.firstOrNull()?.game ?: 0)
    }
    if (selectedGame !in parts.map { it.game } && parts.isNotEmpty()) selectedGame = parts.first().game
    val part = parts.firstOrNull { it.game == selectedGame }
    var seekSecond by remember(key, part?.cid) {
        mutableIntStateOf(part?.gameStartOffsetSeconds ?: 0)
    }

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
                Text("OFFICIAL MATCH VOD", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("官方录像 / RiftLab 原生播放", color = RiftText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(
                    "RiftLab 只保存官方稿件的 BVID、CID、分P和事件锚点。播放时临时解析当前可用播放地址，由原生 Media3 播放器直接从 B站 CDN 串流；不下载、不保存整场录像，也不再套 WebView 播放页。",
                    color = RiftMuted,
                    fontSize = 10.sp
                )
            }
        }

        if (!completed) {
            item { VodStatusPanel("官方录像只在比赛结束后建立历史映射；当前比赛尚未结束。") }
        } else if (!stateMatches || vodState.loading) {
            item { VodStatusPanel(if (stateMatches) vodState.status else "B站官方录像 · 正在建立比赛映射…") }
        } else if (vod == null) {
            item { VodStatusPanel(vodState.status + vodState.errorMessage?.let { " · ${it.take(120)}" }.orEmpty()) }
        } else {
            item { VodSourceHeader(vod) }
            if (parts.size > 1) {
                item {
                    VodGameTabs(parts, selectedGame) {
                        selectedGame = it
                    }
                }
            }
            if (part != null) {
                item { RiftNativeVodPlayer(vod, part, startSecond = seekSecond) }
                item {
                    VodChapterList(part) { second ->
                        seekSecond = second
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
internal fun BilibiliHistoricalTimelinePanel(vod: BilibiliMatchVod, part: BilibiliVodPart) {
    val shape = CutCornerShape(topEnd = 14.dp, bottomStart = 10.dp)
    var scrub by remember(vod.bvid, part.cid) { mutableFloatStateOf(part.gameStartOffsetSeconds.toFloat()) }
    var seekVideoSecond by remember(vod.bvid, part.cid) { mutableIntStateOf(part.gameStartOffsetSeconds) }
    val duration = part.durationSeconds.coerceAtLeast(1)
    val selectedVideoSecond = scrub.toInt().coerceIn(0, duration)
    val selectedGameSecond = part.gameSecondFor(selectedVideoSecond)

    Column(
        Modifier.fillMaxWidth()
            .background(RiftPanel, shape)
            .border(1.dp, RiftLine, shape)
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("OFFICIAL VOD TIMELINE", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("G${part.game} · B站官方录像历史回放", color = RiftText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Text("NATIVE", color = RiftRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "本机没有当时的连续实时快照时，用官方录像章节补历史事件锚点。拖动时间轴或点击事件会直接 seek RiftLab 原生播放器；不会从终局比分伪造经济过程。",
            color = RiftMuted,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(10.dp))
        RiftNativeVodPlayer(vod, part, startSecond = seekVideoSecond)

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("00:00", color = RiftMuted, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(formatVodClock(selectedGameSecond), color = RiftText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(formatVodClock(part.gameSecondFor(duration)), color = RiftMuted, fontSize = 10.sp)
        }
        Slider(
            value = scrub.coerceIn(0f, duration.toFloat()),
            onValueChange = { scrub = it },
            onValueChangeFinished = { seekVideoSecond = scrub.toInt().coerceIn(0, duration) },
            valueRange = 0f..duration.toFloat()
        )
        Text(
            "拖动后松手即可跳到对应官方录像位置。",
            color = RiftMuted,
            fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End
        )

        Spacer(Modifier.height(10.dp))
        Text("EVENT ANCHORS / 官方录像章节", color = RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        if (part.chapters.isEmpty()) {
            Text("这一个分P没有公开章节锚点；录像仍可正常播放。", color = RiftMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        } else {
            part.chapters.forEach { chapter ->
                val gameSecond = part.gameSecondFor(chapter.fromSeconds)
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            scrub = chapter.fromSeconds.toFloat()
                            seekVideoSecond = chapter.fromSeconds
                        }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(formatVodClock(gameSecond), color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f).padding(start = 9.dp)) {
                        Text(
                            chapter.title,
                            color = chapterColor(chapter.title),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (chapter.teamName.isNotBlank()) {
                            Text(chapter.teamName, color = RiftMuted, fontSize = 10.sp)
                        }
                    }
                    Text("跳转 ›", color = RiftMuted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun VodSourceHeader(vod: BilibiliMatchVod) {
    val context = LocalContext.current
    val shape = CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp)
    Column(
        Modifier.fillMaxWidth()
            .background(RiftPanel, shape)
            .border(1.dp, RiftLine, shape)
            .padding(14.dp)
    ) {
        Text(vod.title, color = RiftText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("UP · ${vod.ownerName} · ${vod.bvid}", color = RiftMuted, fontSize = 10.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "在哔哩哔哩打开原稿 ›",
            color = RiftCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vod.sourceUrl)))
                }
            }
        )
    }
}

@Composable
private fun VodGameTabs(parts: List<BilibiliVodPart>, selectedGame: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(RiftPanelAlt, CutCornerShape(topEnd = 10.dp, bottomStart = 8.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        parts.forEach { part ->
            val selected = part.game == selectedGame
            Text(
                "G${part.game}",
                color = if (selected) RiftCyan else RiftMuted,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
                    .background(
                        if (selected) RiftPanel else Color.Transparent,
                        CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp)
                    )
                    .clickable { onSelect(part.game) }
                    .padding(vertical = 9.dp)
            )
        }
    }
}

@Composable
private fun VodChapterList(part: BilibiliVodPart, onSeek: (Int) -> Unit) {
    val shape = CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp)
    Column(
        Modifier.fillMaxWidth()
            .background(RiftPanel, shape)
            .border(1.dp, RiftLine, shape)
            .padding(14.dp)
    ) {
        Text("OFFICIAL CHAPTERS / 录像看点", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        if (part.chapters.isEmpty()) {
            Text("当前分P没有公开章节信息。", color = RiftMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp))
        } else {
            part.chapters.forEach { chapter ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onSeek(chapter.fromSeconds) }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        formatVodClock(part.gameSecondFor(chapter.fromSeconds)),
                        color = RiftCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        chapter.title,
                        color = chapterColor(chapter.title),
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f).padding(start = 9.dp)
                    )
                    Text("跳转 ›", color = RiftMuted, fontSize = 10.sp)
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun RiftNativeVodPlayer(vod: BilibiliMatchVod, part: BilibiliVodPart, startSecond: Int) {
    val context = LocalContext.current
    val shape = CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp)
    var refreshNonce by remember(vod.bvid, part.cid) { mutableIntStateOf(0) }
    var loading by remember(vod.bvid, part.cid) { mutableStateOf(true) }
    var source by remember(vod.bvid, part.cid) { mutableStateOf<BilibiliNativePlaybackSource?>(null) }
    var sourceError by remember(vod.bvid, part.cid) { mutableStateOf<String?>(null) }

    LaunchedEffect(vod.bvid, part.cid, refreshNonce) {
        loading = true
        sourceError = null
        val result = runCatching {
            BilibiliNativePlaybackResolver.resolve(
                vod = vod,
                part = part,
                forceRefresh = refreshNonce > 0
            )
        }
        source = result.getOrNull()
        sourceError = result.exceptionOrNull()?.message
        loading = false
    }

    when {
        loading -> NativePlayerStatus("正在获取 B站当前播放源…")
        source == null -> {
            Column(
                Modifier.fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black, shape)
                    .border(1.dp, RiftLine, shape)
                    .padding(14.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("原生播放源暂不可用", color = RiftText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    sourceError?.take(180) ?: "B站没有返回可用播放描述。",
                    color = RiftMuted,
                    fontSize = 10.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        "重新获取 ›",
                        color = RiftCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { refreshNonce += 1 }
                    )
                    Text(
                        "打开官方原稿 ›",
                        color = RiftMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("${vod.sourceUrl}?p=${part.page}")
                                    )
                                )
                            }
                        }
                    )
                }
            }
        }
        else -> NativePlayerSurface(
            source = source!!,
            startSecond = startSecond,
            onRefreshSource = { refreshNonce += 1 }
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun NativePlayerSurface(
    source: BilibiliNativePlaybackSource,
    startSecond: Int,
    onRefreshSource: () -> Unit
) {
    val context = LocalContext.current
    val shape = CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp)
    var playbackError by remember(source.bvid, source.cid) { mutableStateOf<String?>(null) }

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
        val items = source.segments.map { MediaItem.fromUri(it.primaryUrl) }
        exoPlayer.setMediaItems(items, true)
        exoPlayer.prepare()
        seekNativePlayer(exoPlayer, source, startSecond)
    }

    LaunchedEffect(startSecond) {
        seekNativePlayer(exoPlayer, source, startSecond)
    }

    Column {
        val playerForView = exoPlayer
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    player = playerForView
                    useController = true
                    controllerAutoShow = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            modifier = Modifier.fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black, shape)
                .border(1.dp, RiftLine, shape),
            update = { view ->
                if (view.player !== playerForView) view.player = playerForView
            }
        )
        if (playbackError != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "播放失败 · $playbackError",
                    color = RiftMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "刷新播放源 ›",
                    color = RiftCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onRefreshSource)
                )
            }
        } else {
            Text(
                "NATIVE · QN ${source.quality} · ${source.format.uppercase()} · ${source.segments.size} 段",
                color = RiftMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

private fun seekNativePlayer(
    player: ExoPlayer,
    source: BilibiliNativePlaybackSource,
    videoSecond: Int
) {
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

@Composable
private fun NativePlayerStatus(text: String) {
    val shape = CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp)
    Box(
        Modifier.fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black, shape)
            .border(1.dp, RiftLine, shape)
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = RiftMuted, fontSize = 10.sp)
    }
}

@Composable
private fun VodStatusPanel(text: String) {
    val shape = CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp)
    Column(
        Modifier.fillMaxWidth()
            .background(RiftPanel, shape)
            .border(1.dp, RiftLine, shape)
            .padding(14.dp)
    ) {
        Text("VOD STATUS", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(text, color = RiftMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun chapterColor(title: String): Color = when {
    title.contains("第一滴血") || title.contains("击杀") && !title.contains("亚龙") && !title.contains("男爵") -> RiftRed
    title.contains("亚龙") || title.contains("龙") || title.contains("男爵") || title.contains("纳什") || title.contains("先锋") || title.contains("巢虫") -> RiftCyan
    title.contains("塔") -> RiftCyan
    else -> RiftText
}

private fun formatVodClock(seconds: Int): String =
    "%02d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)
