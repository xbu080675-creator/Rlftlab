from pathlib import Path

# --- Global replay: domestic Bilibili priority for major international events, keep Riot/YouTube. ---
p = Path('app/src/main/java/com/riftlab/app/ui/GlobalReplayContent.kt')
s = p.read_text()
s = s.replace('import android.webkit.WebChromeClient\n', 'import android.webkit.CookieManager\nimport android.webkit.WebChromeClient\n', 1)
s = s.replace('import androidx.compose.runtime.mutableIntStateOf\n', 'import androidx.compose.runtime.mutableIntStateOf\nimport androidx.compose.runtime.mutableStateOf\n', 1)
s = s.replace('import com.riftlab.app.data.RiotVodLink\n', 'import com.riftlab.app.data.BilibiliMatchVod\nimport com.riftlab.app.data.BilibiliVodPart\nimport com.riftlab.app.data.BilibiliVodRepository\nimport com.riftlab.app.data.RiotVodLink\n', 1)

marker = '''internal fun isLplReplayMatch(match: ScheduledEsportsMatch): Boolean =
    match.leagueSlug.equals("lpl", ignoreCase = true) ||
        match.league.equals("LPL", ignoreCase = true) ||
        match.league.contains("PRO LEAGUE", ignoreCase = true)
'''
addition = marker + '''
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
'''
if marker not in s:
    raise SystemExit('isLplReplayMatch marker missing')
s = s.replace(marker, addition, 1)

start = s.index('@Composable\ninternal fun GlobalOfficialReplayContent')
end = s.index('@Composable\nprivate fun OfficialReplayPlayer', start)
new_global = r'''@Composable
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
                fontSize = 9.sp,
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
                fontSize = 9.sp,
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
                    Modifier.width(156.dp)
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
                        fontSize = 8.sp,
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
        Text(label, color = if (selected) RiftCyan else RiftMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
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
        Text(message, color = RiftMuted, fontSize = 9.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
    }
}

'''
s = s[:start] + new_global + s[end:]

# Replace the web player with a persistent, real-UA, cookie-capable session and an in-app verification entry.
start = s.index('@Composable\nprivate fun OfficialWebVideoPlayer')
end = s.index('private class EmbeddedVideoChromeClient', start)
new_web = r'''@Composable
private fun OfficialWebVideoPlayer(url: String) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var sessionNonce by remember(url) { mutableIntStateOf(0) }
    val chromeClient = remember(context, activity) { EmbeddedVideoChromeClient(context, activity) }
    val webView = remember(context) {
        WebView(context).apply {
            configureOfficialWebView(this, chromeClient)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            CookieManager.getInstance().flush()
            chromeClient.release()
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
    }

    LaunchedEffect(url, sessionNonce, webView) {
        webView.stopLoading()
        webView.loadUrl(
            url,
            mapOf(
                "Referer" to "https://lolesports.com/",
                "Origin" to "https://lolesports.com"
            )
        )
    }

    Column {
        AndroidView(
            factory = { webView },
            update = { },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .background(Color.Black, CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp))
                .border(1.dp, RiftLine, CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp))
        )
        Text(
            "遇到 YouTube“请登录确认不是机器人”？在 RiftLab 内打开官方 YouTube 会话完成正常验证 / 登录 ›",
            color = RiftCyan,
            fontSize = 8.sp,
            lineHeight = 12.sp,
            modifier = Modifier.clickable {
                openYoutubeSessionDialog(context, activity) {
                    sessionNonce += 1
                }
            }.padding(horizontal = 4.dp, vertical = 8.dp)
        )
    }
}

private fun configureOfficialWebView(webView: WebView, chromeClient: WebChromeClient) {
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }
    webView.setBackgroundColor(AndroidColor.BLACK)
    webView.settings.javaScriptEnabled = true
    webView.settings.domStorageEnabled = true
    webView.settings.useWideViewPort = true
    webView.settings.loadWithOverviewMode = true
    webView.settings.mediaPlaybackRequiresUserGesture = false
    webView.settings.allowFileAccess = false
    webView.settings.allowContentAccess = false
    webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    webView.settings.javaScriptCanOpenWindowsAutomatically = false
    webView.settings.setSupportMultipleWindows(false)
    // Do not spoof Chrome: YouTube sees the real Android System WebView UA and the cookie/session matches it.
    webView.webViewClient = WebViewClient()
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

'''
s = s[:start] + new_web + s[end:]
p.write_text(s)

# --- Reuse the proven native Bilibili player inside the international hybrid surface. ---
p = Path('app/src/main/java/com/riftlab/app/ui/MatchReplayContent.kt')
s = p.read_text()
insert = '''@Composable
private fun ReplayAnchorRow(anchor: ReplayAnchor, onSeek: () -> Unit) {'''
wrappers = '''@Composable
internal fun InternationalBilibiliReplayPlayer(vod: BilibiliMatchVod, part: BilibiliVodPart) {
    StickyNativeReplayPlayer(
        vod = vod,
        part = part,
        startSecond = part.gameStartOffsetSeconds,
        anchors = emptyList()
    )
}

@Composable
internal fun InternationalBilibiliSourceCard(vod: BilibiliMatchVod) {
    ReplaySourceCard(vod)
}

'''
if insert not in s:
    raise SystemExit('ReplayAnchorRow insert point missing')
s = s.replace(insert, wrappers + insert, 1)
p.write_text(s)

# --- Standard youtube.com embed so a normal authenticated YouTube cookie session can be reused. ---
p = Path('app/src/main/java/com/riftlab/app/data/RiotVodRepository.kt')
s = p.read_text()
s = s.replace(
    '"https://www.youtube-nocookie.com/embed/$it?playsinline=1&rel=0&fs=1&enablejsapi=1&origin=https%3A%2F%2Flolesports.com"',
    '"https://www.youtube.com/embed/$it?playsinline=1&rel=0&fs=1&enablejsapi=1&origin=https%3A%2F%2Flolesports.com"',
    1
)
p.write_text(s)

# --- Version bump. ---
p = Path('app/build.gradle.kts')
s = p.read_text()
if 'versionCode = 50' not in s or 'versionName = "1.0.0-dev.50"' not in s:
    raise SystemExit('dev50 version markers missing')
s = s.replace('versionCode = 50', 'versionCode = 51', 1)
s = s.replace('versionName = "1.0.0-dev.50"', 'versionName = "1.0.0-dev.51"', 1)
s += '\n// dev.51: major international events prefer official Bilibili China VOD while retaining Riot/YouTube; persistent real-UA YouTube WebView session for normal verification/login.\n'
p.write_text(s)
