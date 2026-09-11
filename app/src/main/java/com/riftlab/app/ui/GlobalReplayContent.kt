package com.riftlab.app.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
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
import com.riftlab.app.data.BilibiliMatchVod
import com.riftlab.app.data.BilibiliVodPart
import com.riftlab.app.data.BilibiliVodRepository
import com.riftlab.app.data.RiotVodLink
import com.riftlab.app.data.RiotVodRepository
import com.riftlab.app.data.ScheduledEsportsMatch

internal fun isLplReplayMatch(match: ScheduledEsportsMatch): Boolean =
    match.leagueSlug.equals("lpl", ignoreCase = true) ||
        match.league.equals("LPL", ignoreCase = true) ||
        match.league.contains("PRO LEAGUE", ignoreCase = true)

private enum class GlobalReplaySource { DOMESTIC_BILIBILI, GLOBAL_RIOT }

/** Major international events with a China broadcast should try the official Bilibili archive first,
 * while keeping Riot/YouTube as an independent selectable source. */
internal fun prefersDomesticInternationalReplay(match: ScheduledEsportsMatch): Boolean {
    val identity = listOf(match.leagueSlug, match.league, match.blockName).joinToString(" ").lowercase()
    return listOf(
        "worlds", "world championship", "全球总决赛",
        "msi", "mid-season", "季中冠军赛",
        "first stand", "first-stand", "first_stand", "全球先锋",
        "esports world cup", "ewc",
        "demacia", "德玛西亚杯"
    ).any { identity.contains(it) }
}

@Composable
internal fun GlobalOfficialReplayContent(match: ScheduledEsportsMatch) {
    val riotState by RiotVodRepository.state.collectAsState()
    val riotKey = RiotVodRepository.keyFor(match)
    LaunchedEffect(riotKey) { RiotVodRepository.open(match) }
    val links = riotState.links.takeIf { riotState.matchKey == riotKey }.orEmpty()

    val preferDomestic = remember(match) { prefersDomesticInternationalReplay(match) }
    val biliState by BilibiliVodRepository.state.collectAsState()
    val biliKey = BilibiliVodRepository.keyFor(match)
    LaunchedEffect(biliKey, preferDomestic) {
        if (preferDomestic && biliKey.isNotBlank()) BilibiliVodRepository.open(match)
    }
    val biliVod = biliState.vod.takeIf { preferDomestic && biliState.matchKey == biliKey }

    val playedGames = remember(match, links, biliVod?.parts) {
        val scoreGames = match.teams.sumOf { it.gameWins }.takeIf { it > 0 } ?: 0
        val vodGames = buildList {
            links.map { it.game }.filter { it > 0 }.forEach(::add)
            biliVod?.parts.orEmpty().map { it.game }.filter { it > 0 }.forEach(::add)
        }.distinct().sorted()
        when {
            vodGames.isNotEmpty() -> vodGames
            scoreGames > 0 -> (1..scoreGames).toList()
            else -> listOf(1)
        }
    }
    var selectedGame by remember(riotKey) { mutableIntStateOf(playedGames.firstOrNull() ?: 1) }
    LaunchedEffect(playedGames) {
        if (playedGames.isNotEmpty() && selectedGame !in playedGames) selectedGame = playedGames.first()
    }

    var userSelectedSource by remember(riotKey) { mutableStateOf(false) }
    var selectedSource by remember(riotKey) {
        mutableStateOf(if (preferDomestic) GlobalReplaySource.DOMESTIC_BILIBILI else GlobalReplaySource.GLOBAL_RIOT)
    }
    LaunchedEffect(preferDomestic, biliState.matchKey, biliState.loading, biliVod) {
        if (!preferDomestic) {
            selectedSource = GlobalReplaySource.GLOBAL_RIOT
        } else if (!userSelectedSource && biliState.matchKey == biliKey && !biliState.loading) {
            selectedSource = if (biliVod != null) GlobalReplaySource.DOMESTIC_BILIBILI else GlobalReplaySource.GLOBAL_RIOT
        }
    }

    val selectedLink = links
        .filter { it.game == selectedGame }
        .sortedWith(compareByDescending<RiotVodLink> { it.isYoutube }.thenBy { it.locale != "en-US" })
        .firstOrNull()
    val selectedBiliPart = biliVod?.parts?.firstOrNull { it.game == selectedGame }

    Column(Modifier.fillMaxSize()) {
        val shape = CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp)
        Column(
            Modifier.fillMaxWidth()
                .background(RiftPanel, shape)
                .border(1.dp, RiftCyan.copy(alpha = 0.35f), shape)
                .padding(12.dp)
        ) {
            Text(
                if (preferDomestic) "INTERNATIONAL OFFICIAL REPLAY / 国际赛事官方回放" else "GLOBAL OFFICIAL REPLAY / 海外官方回放",
                color = RiftCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (preferDomestic)
                    "全球性国际赛事优先匹配 B站国内官方完整录像，同时永久保留 Riot / YouTube 海外官方源；任一来源不可用都不会影响另一路。"
                else
                    "海外赛区使用 Riot / YouTube 官方 VOD；不把无国内版权的地区联赛误接到 Bilibili。",
                color = RiftMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(5.dp))
            Text(
                if (preferDomestic) {
                    val domestic = if (biliState.matchKey == biliKey) biliState.status else "B站国内官方源 · 待匹配"
                    val global = if (riotState.matchKey == riotKey) riotState.status else "Riot 海外官方源 · 待读取"
                    "$domestic\n$global"
                } else if (riotState.matchKey == riotKey) riotState.status else "正在切换 Riot VOD…",
                color = RiftText,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        if (preferDomestic) {
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReplaySourceChip(
                    label = "国内 B站官方 · 优先",
                    selected = selectedSource == GlobalReplaySource.DOMESTIC_BILIBILI,
                    modifier = Modifier.weight(1f)
                ) {
                    userSelectedSource = true
                    selectedSource = GlobalReplaySource.DOMESTIC_BILIBILI
                }
                ReplaySourceChip(
                    label = "海外 Riot / YouTube",
                    selected = selectedSource == GlobalReplaySource.GLOBAL_RIOT,
                    modifier = Modifier.weight(1f)
                ) {
                    userSelectedSource = true
                    selectedSource = GlobalReplaySource.GLOBAL_RIOT
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(playedGames, key = { it }) { game ->
                val gameLinks = links.filter { it.game == game }
                val youtube = gameLinks.firstOrNull { it.isYoutube }
                val biliPart = biliVod?.parts?.firstOrNull { it.game == game }
                val selected = game == selectedGame
                Column(
                    Modifier.width(132.dp)
                        .clickable { selectedGame = game }
                        .background(if (selected) RiftPanel else RiftPanelAlt, shape)
                        .border(1.dp, if (selected) RiftCyan.copy(alpha = 0.55f) else RiftLine, shape)
                        .padding(10.dp)
                ) {
                    Text("G$game", color = if (selected) RiftCyan else RiftText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when (selectedSource) {
                            GlobalReplaySource.DOMESTIC_BILIBILI -> if (biliPart != null) "B站国内官方 · APP 内播" else "等待国内官方源"
                            GlobalReplaySource.GLOBAL_RIOT -> when {
                                youtube != null -> "YouTube 官方 · APP 内嵌"
                                gameLinks.isNotEmpty() -> "Riot VOD · 暂无可嵌入源"
                                else -> "等待 Riot 官方 VOD"
                            }
                        },
                        color = RiftMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        when (selectedSource) {
            GlobalReplaySource.DOMESTIC_BILIBILI -> {
                when {
                    biliVod != null && selectedBiliPart != null -> {
                        InternationalBilibiliReplayPlayer(biliVod, selectedBiliPart)
                        InternationalBilibiliSourceCard(biliVod)
                    }
                    biliState.matchKey != biliKey || biliState.loading -> OfficialReplayPlaceholder("正在匹配 B站国内官方完整录像…")
                    else -> OfficialReplayPlaceholder("该场暂未匹配到 B站国内官方完整录像。海外 Riot / YouTube 官方源仍保留，可切换继续播放。")
                }
            }
            GlobalReplaySource.GLOBAL_RIOT -> OfficialReplayPlayer(selectedGame, selectedLink)
        }

        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) { MatchTimelineContent() }
    }
}

@Composable
private fun ReplaySourceChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = CutCornerShape(topEnd = 8.dp, bottomStart = 6.dp)
    Box(
        modifier.clickable(onClick = onClick)
            .background(if (selected) RiftPanel else RiftPanelAlt, shape)
            .border(1.dp, if (selected) RiftCyan.copy(alpha = 0.6f) else RiftLine, shape)
            .padding(horizontal = 9.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) RiftCyan else RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun OfficialReplayPlaceholder(message: String) {
    val shape = CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp)
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .background(Color.Black, shape)
            .border(1.dp, RiftLine, shape)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(message, color = RiftMuted, fontSize = 10.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun OfficialReplayPlayer(game: Int, link: RiotVodLink?) {
    val videoId = link?.youtubeVideoId.orEmpty()
    val youtubeEmbed = videoId.isNotBlank()
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "G$game · ${if (youtubeEmbed) "YOUTUBE OFFICIAL EMBED" else "OFFICIAL VOD PENDING"}",
                color = RiftText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (youtubeEmbed) "APP 内播放 · 全屏可旋转" else "等待可嵌入官方源",
                color = RiftMuted,
                fontSize = 10.sp,
                textAlign = TextAlign.End
            )
        }
        Spacer(Modifier.height(5.dp))
        if (youtubeEmbed) {
            val startSeconds = link?.offsetSeconds?.coerceAtLeast(0) ?: 0
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Riot VOD 对齐", color = RiftMuted, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    if (startSeconds > 0) "同步起点 ${formatReplayTimestamp(startSeconds)}" else "官方源未提供额外偏移",
                    color = if (startSeconds > 0) RiftCyan else RiftMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            OfficialWebVideoPlayer(videoId, startSeconds)
        } else {
            val shape = CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp)
            Column(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .background(Color.Black, shape)
                    .border(1.dp, RiftLine, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("该局官方 VOD 暂无可嵌入视频源", color = RiftText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Riot EventDetails 当前没有返回可直接嵌入的 YouTube 参数。RiftLab 不再把整个 LoL Esports 网页伪装成播放器，也不会改用 Bilibili。",
                    color = RiftMuted,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun OfficialWebVideoPlayer(videoId: String, startSeconds: Int) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var sessionNonce by remember(videoId, startSeconds) { mutableIntStateOf(0) }
    val chromeClient = remember(context, activity) { EmbeddedVideoChromeClient(context, activity) }
    val webView = remember(context) {
        WebView(context).apply {
            configureOfficialWebView(this, chromeClient)
        }
    }

    DisposableEffect(webView) {
        webView.onResume()
        webView.resumeTimers()
        onDispose {
            CookieManager.getInstance().flush()
            chromeClient.release()
            webView.onPause()
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
    }

    LaunchedEffect(videoId, startSeconds, sessionNonce, webView) {
        webView.stopLoading()
        webView.loadDataWithBaseURL(
            "https://www.youtube.com/",
            youtubeEmbedDocument(videoId, startSeconds),
            "text/html",
            "UTF-8",
            null
        )
        webView.post {
            webView.requestLayout()
            webView.invalidate()
        }
    }

    Column {
        AndroidView(
            factory = { webView },
            update = { view ->
                view.onResume()
                view.requestLayout()
                view.invalidate()
            },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .background(Color.Black, CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp))
                .border(1.dp, RiftLine, CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp))
        )
        Text(
            "遇到 YouTube“请登录确认不是机器人”？在 RiftLab 内打开官方 YouTube 会话完成正常验证 / 登录 ›",
            color = RiftCyan,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            modifier = Modifier.clickable {
                openYoutubeSessionDialog(context, activity) {
                    sessionNonce += 1
                }
            }.padding(horizontal = 4.dp, vertical = 8.dp)
        )
    }
}

private fun youtubeEmbedDocument(videoId: String, startSeconds: Int): String {
    val safeId = videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    val safeStart = startSeconds.coerceAtLeast(0)
    return """<!doctype html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#000;}
#frame{position:fixed;inset:0;width:100%;height:100%;border:0;background:#000;}
</style>
</head>
<body>
<iframe id="frame"
  src="https://www.youtube.com/embed/$safeId?playsinline=1&rel=0&fs=1&start=$safeStart"
  allow="autoplay; encrypted-media; picture-in-picture; web-share; fullscreen"
  allowfullscreen></iframe>
</body>
</html>"""
}

private fun formatReplayTimestamp(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%02d:%02d".format(minutes, secs)
}

private fun configureOfficialWebView(webView: WebView, chromeClient: WebChromeClient) {
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }
    webView.setBackgroundColor(AndroidColor.BLACK)
    webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    webView.overScrollMode = View.OVER_SCROLL_NEVER
    webView.isVerticalScrollBarEnabled = false
    webView.isHorizontalScrollBarEnabled = false
    webView.settings.javaScriptEnabled = true
    webView.settings.domStorageEnabled = true
    webView.settings.loadsImagesAutomatically = true
    webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
    webView.settings.useWideViewPort = false
    webView.settings.loadWithOverviewMode = false
    webView.settings.mediaPlaybackRequiresUserGesture = false
    webView.settings.allowFileAccess = false
    webView.settings.allowContentAccess = false
    webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    webView.settings.javaScriptCanOpenWindowsAutomatically = false
    webView.settings.setSupportMultipleWindows(false)
    webView.settings.setSupportZoom(false)
    webView.settings.builtInZoomControls = false
    webView.settings.displayZoomControls = false
    // Keep the real Android System WebView UA/cookie jar. Do not attach a fake Origin/Referer.
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            view?.post {
                view.setBackgroundColor(AndroidColor.BLACK)
                view.requestLayout()
                view.invalidate()
            }
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            super.onPageCommitVisible(view, url)
            view?.post {
                view.requestLayout()
                view.invalidate()
            }
        }
    }
    webView.webChromeClient = chromeClient
}

private fun openYoutubeSessionDialog(context: Context, activity: Activity?, onClosed: () -> Unit) {
    val sessionChrome = EmbeddedVideoChromeClient(context, activity)
    val sessionWebView = WebView(context).apply {
        configureOfficialWebView(this, sessionChrome)
        loadUrl("https://www.youtube.com/")
    }
    Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
        setContentView(
            sessionWebView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setOnDismissListener {
            CookieManager.getInstance().flush()
            sessionChrome.release()
            sessionWebView.stopLoading()
            sessionWebView.loadUrl("about:blank")
            sessionWebView.removeAllViews()
            sessionWebView.destroy()
            onClosed()
        }
        show()
    }
}

private class EmbeddedVideoChromeClient(private val context: Context, private val activity: Activity?) : WebChromeClient() {
    private var fullscreenDialog: Dialog? = null
    private var fullscreenCallback: CustomViewCallback? = null
    private var previousOrientation: Int? = null

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null || fullscreenDialog != null) {
            callback?.onCustomViewHidden()
            return
        }
        fullscreenCallback = callback
        previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        fullscreenDialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnDismissListener { closeFullscreen(true) }
            show()
        }
    }

    override fun onHideCustomView() = closeFullscreen(true)
    fun release() = closeFullscreen(false)

    private fun closeFullscreen(notifyPlayer: Boolean) {
        val dialog = fullscreenDialog ?: return
        fullscreenDialog = null
        dialog.setOnDismissListener(null)
        if (dialog.isShowing) dialog.dismiss()
        if (notifyPlayer) fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        activity?.requestedOrientation = previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        previousOrientation = null
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
