package com.riftlab.app.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
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
import com.riftlab.app.data.RiotVodLink
import com.riftlab.app.data.RiotVodRepository
import com.riftlab.app.data.ScheduledEsportsMatch

internal fun isLplReplayMatch(match: ScheduledEsportsMatch): Boolean =
    match.leagueSlug.equals("lpl", ignoreCase = true) ||
        match.league.equals("LPL", ignoreCase = true) ||
        match.league.contains("PRO LEAGUE", ignoreCase = true)

@Composable
internal fun GlobalOfficialReplayContent(match: ScheduledEsportsMatch) {
    val state by RiotVodRepository.state.collectAsState()
    val key = RiotVodRepository.keyFor(match)
    LaunchedEffect(key) { RiotVodRepository.open(match) }
    val links = state.links.takeIf { state.matchKey == key }.orEmpty()
    val playedGames = remember(match, links) {
        val scoreGames = match.teams.sumOf { it.gameWins }.takeIf { it > 0 } ?: 0
        val vodGames = links.map { it.game }.filter { it > 0 }.distinct().sorted()
        when {
            vodGames.isNotEmpty() -> vodGames
            scoreGames > 0 -> (1..scoreGames).toList()
            else -> listOf(1)
        }
    }
    var selectedGame by remember(key) { mutableIntStateOf(playedGames.firstOrNull() ?: 1) }
    LaunchedEffect(playedGames) {
        if (playedGames.isNotEmpty() && selectedGame !in playedGames) selectedGame = playedGames.first()
    }
    val selectedLink = links
        .filter { it.game == selectedGame }
        .sortedWith(compareByDescending<RiotVodLink> { it.isYoutube }.thenBy { it.locale != "en-US" })
        .firstOrNull()

    Column(Modifier.fillMaxSize()) {
        val shape = CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp)
        Column(
            Modifier.fillMaxWidth()
                .background(RiftPanel, shape)
                .border(1.dp, RiftCyan.copy(alpha = 0.35f), shape)
                .padding(12.dp)
        ) {
            Text("GLOBAL OFFICIAL REPLAY / 海外官方回放", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "海外赛区与国际赛事在 RiftLab 内播放 Riot / YouTube 官方 VOD；不请求、不解析 Bilibili，也不要求跳转 YouTube APP。",
                color = RiftMuted,
                fontSize = 9.sp,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(5.dp))
            Text(
                if (state.matchKey == key) state.status else "正在切换 Riot VOD…",
                color = RiftText,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(playedGames, key = { it }) { game ->
                val gameLinks = links.filter { it.game == game }
                val youtube = gameLinks.firstOrNull { it.isYoutube }
                val selected = game == selectedGame
                Column(
                    Modifier.width(156.dp)
                        .clickable { selectedGame = game }
                        .background(if (selected) RiftPanel else RiftPanelAlt, shape)
                        .border(1.dp, if (selected) RiftCyan.copy(alpha = 0.55f) else RiftLine, shape)
                        .padding(10.dp)
                ) {
                    Text("G$game", color = if (selected) RiftCyan else RiftText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            youtube != null -> "YouTube 官方 · 内嵌"
                            gameLinks.isNotEmpty() -> "Riot 官方 VOD · 内嵌"
                            else -> "Riot 官方页 · 内嵌"
                        },
                        color = RiftMuted,
                        fontSize = 8.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OfficialReplayPlayer(match, selectedGame, selectedLink)

        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) { MatchTimelineContent() }
    }
}

@Composable
private fun OfficialReplayPlayer(match: ScheduledEsportsMatch, game: Int, link: RiotVodLink?) {
    val embed = link?.embedUrl.orEmpty()
    val sourceUrl = embed
    val youtubeEmbed = embed.isNotBlank()
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "G$game · ${if (youtubeEmbed) "YOUTUBE OFFICIAL EMBED" else "RIOT OFFICIAL VOD"}",
                color = RiftText, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            Text("APP 内播放 · 全屏可旋转", color = RiftMuted, fontSize = 8.sp, textAlign = TextAlign.End)
        }
        Spacer(Modifier.height(5.dp))
        if (youtubeEmbed) {
            OfficialWebVideoPlayer(sourceUrl, true)
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
                    color = RiftMuted, fontSize = 8.sp, lineHeight = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun OfficialWebVideoPlayer(url: String, youtubeEmbed: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val chromeClient = remember(context, activity) { EmbeddedVideoChromeClient(context, activity) }
    val webView = remember(url, youtubeEmbed) {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
            webViewClient = WebViewClient()
            webChromeClient = chromeClient
        }
    }

    DisposableEffect(webView) {
        onDispose {
            chromeClient.release()
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
    }

    LaunchedEffect(url, youtubeEmbed, webView) {
        webView.stopLoading()
        if (youtubeEmbed) {
            webView.loadUrl(
                url,
                mapOf(
                    "Referer" to "https://lolesports.com/",
                    "Origin" to "https://lolesports.com"
                )
            )
        } else {
            webView.loadUrl(url)
        }
    }

    AndroidView(
        factory = { webView },
        update = { },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .background(Color.Black, CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp))
            .border(1.dp, RiftLine, CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp))
    )
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
