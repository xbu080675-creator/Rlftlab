package com.riftlab.app.ui

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.riftlab.app.data.BilibiliMatchVod
import com.riftlab.app.data.BilibiliVodPart
import com.riftlab.app.data.BilibiliVodRepository
import com.riftlab.app.data.MatchDetailRepository

@Composable
internal fun MatchVodContent() {
    val detail by MatchDetailRepository.state.collectAsState()
    val match = detail.match
    val vodState by BilibiliVodRepository.state.collectAsState()
    val key = match?.let(BilibiliVodRepository::keyFor).orEmpty()

    LaunchedEffect(key) {
        if (match != null && key.isNotBlank()) BilibiliVodRepository.open(match)
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
                Text("官方录像 / B站", color = RiftText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(
                    "RiftLab 只解析官方稿件的 BVID、分P与章节元数据；视频仍由哔哩哔哩官方托管并在官方播放器中播放，不保存整场录像。",
                    color = RiftMuted,
                    fontSize = 9.sp
                )
            }
        }

        if (!stateMatches || vodState.loading) {
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
                item { BilibiliEmbeddedPlayer(vod, part, startSecond = seekSecond) }
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
            Text("VOD", color = RiftRed, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "本机没有当时的连续实时快照，因此这里用官方录像章节补历史事件锚点。视频时间与事件均来自官方稿件；不会从终局比分伪造经济过程。",
            color = RiftMuted,
            fontSize = 9.sp
        )
        Spacer(Modifier.height(10.dp))
        BilibiliEmbeddedPlayer(vod, part, startSecond = seekVideoSecond)

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("00:00", color = RiftMuted, fontSize = 9.sp)
            Spacer(Modifier.weight(1f))
            Text(formatVodClock(selectedGameSecond), color = RiftText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(formatVodClock(part.gameSecondFor(duration)), color = RiftMuted, fontSize = 9.sp)
        }
        Slider(
            value = scrub.coerceIn(0f, duration.toFloat()),
            onValueChange = { scrub = it },
            onValueChangeFinished = { seekVideoSecond = scrub.toInt().coerceIn(0, duration) },
            valueRange = 0f..duration.toFloat()
        )
        Text(
            "拖动后松手即可让官方播放器跳到对应录像位置。",
            color = RiftMuted,
            fontSize = 8.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End
        )

        Spacer(Modifier.height(10.dp))
        Text("EVENT ANCHORS / 官方录像章节", color = RiftMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
                    Text(formatVodClock(gameSecond), color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f).padding(start = 9.dp)) {
                        Text(
                            chapter.title,
                            color = chapterColor(chapter.title),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (chapter.teamName.isNotBlank()) {
                            Text(chapter.teamName, color = RiftMuted, fontSize = 8.sp)
                        }
                    }
                    Text("跳转 ›", color = RiftMuted, fontSize = 8.sp)
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
        Text("UP · ${vod.ownerName} · ${vod.bvid}", color = RiftMuted, fontSize = 9.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "在哔哩哔哩打开 ›",
            color = RiftCyan,
            fontSize = 9.sp,
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
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        chapter.title,
                        color = chapterColor(chapter.title),
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f).padding(start = 9.dp)
                    )
                    Text("跳转 ›", color = RiftMuted, fontSize = 8.sp)
                }
            }
        }
    }
}

@Composable
private fun BilibiliEmbeddedPlayer(vod: BilibiliMatchVod, part: BilibiliVodPart, startSecond: Int) {
    val context = LocalContext.current
    val backgroundArgb = RiftBg.toArgb()
    val line = RiftLine
    val url = remember(vod.bvid, part.cid, part.page, startSecond) { vod.playerUrl(part, startSecond) }
    val webView = remember(vod.bvid, part.cid, backgroundArgb) {
        WebView(context).apply {
            setBackgroundColor(backgroundArgb)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    val playerShape = CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp)
    AndroidView(
        factory = { webView },
        modifier = Modifier.fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black, playerShape)
            .border(1.dp, line, playerShape),
        update = { view ->
            if (view.url != url) {
                view.loadUrl(
                    url,
                    mapOf(
                        "Referer" to "https://www.bilibili.com/",
                        "Origin" to "https://www.bilibili.com"
                    )
                )
            }
        }
    )
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
