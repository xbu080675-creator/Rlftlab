from pathlib import Path

# 1) Global staff integration: LPL keeps richer curated directory; non-LPL uses global mirror/Cargo.
p = Path('app/src/main/java/com/riftlab/app/data/DynamicTeamDataProvider.kt')
s = p.read_text()
s = s.replace(
'''internal class DynamicTeamDataProvider(
    private val fallbackProfile: LeaguepediaProfileProvider = LeaguepediaProfileProvider(),
    private val fallbackStaff: LplStaffSnapshotProvider = LplStaffSnapshotProvider()
) {''',
'''internal class DynamicTeamDataProvider(
    private val fallbackProfile: LeaguepediaProfileProvider = LeaguepediaProfileProvider(),
    private val fallbackStaff: LplStaffSnapshotProvider = LplStaffSnapshotProvider(),
    private val globalStaff: GlobalTeamStaffProvider = GlobalTeamStaffProvider()
) {''',
1)
old_fetch = '''    suspend fun fetch(team: EsportsTeamRef, details: EsportsTeamDetails): TeamDynamicSupplement {
        val code = resolveCode(team, details)
        val remote = runCatching { fetchRemote(code) }.getOrNull()
        if (remote != null) return remote

        val profile = runCatching { fallbackProfile.fetch(team, details) }
            .getOrElse { TeamProfileSupplement(status = "管理层离线快照读取失败") }
        val staff = runCatching { fallbackStaff.fetch(team, details) }
            .getOrElse { TeamStaffSupplement(status = "教练组离线快照读取失败") }
        return TeamDynamicSupplement(
            profile = profile.copy(status = "RiftLab Dynamic Data 暂不可达 · ${profile.status}"),
            staff = staff.copy(status = "RiftLab Dynamic Data 暂不可达 · ${staff.status}"),
            sourceMode = "fallback"
        )
    }
'''
new_fetch = '''    suspend fun fetch(team: EsportsTeamRef, details: EsportsTeamDetails): TeamDynamicSupplement {
        val code = resolveCode(team, details)
        val remote = if (code.isNotBlank()) runCatching { fetchRemote(code) }.getOrNull() else null
        if (remote != null) return remote

        // Non-LPL teams were previously sent into LPL-only snapshots, which guaranteed an empty
        // management/coaching section. Use the global current-roster mirror / Leaguepedia resolver
        // instead. Riot getTeams remains the player-roster authority.
        if (code.isBlank()) {
            val global = runCatching { globalStaff.fetch(team, details) }
                .getOrElse { error ->
                    GlobalTeamStaffSnapshot(
                        status = "海外人员资料同步失败 · ${error.message?.take(80).orEmpty()}",
                        sourceMode = "global-error"
                    )
                }
            return TeamDynamicSupplement(
                profile = TeamProfileSupplement(
                    management = global.management,
                    status = global.status
                ),
                staff = TeamStaffSupplement(
                    staff = global.staff,
                    status = global.status
                ),
                sourceMode = global.sourceMode
            )
        }

        val profile = runCatching { fallbackProfile.fetch(team, details) }
            .getOrElse { TeamProfileSupplement(status = "管理层离线快照读取失败") }
        val staff = runCatching { fallbackStaff.fetch(team, details) }
            .getOrElse { TeamStaffSupplement(status = "教练组离线快照读取失败") }
        return TeamDynamicSupplement(
            profile = profile.copy(status = "RiftLab Dynamic Data 暂不可达 · ${profile.status}"),
            staff = staff.copy(status = "RiftLab Dynamic Data 暂不可达 · ${staff.status}"),
            sourceMode = "fallback"
        )
    }
'''
if old_fetch not in s:
    raise SystemExit('DynamicTeamDataProvider fetch block not found')
s = s.replace(old_fetch, new_fetch, 1)
p.write_text(s)

# 2) YouTube: stop loading the embed document with a fake LoL Esports origin. Build a black,
# correctly-sized wrapper page, use the real WebView session, and force the inline video layer to
# stay hardware accelerated/redrawable inside Compose.
p = Path('app/src/main/java/com/riftlab/app/ui/GlobalReplayContent.kt')
s = p.read_text()
s = s.replace('''    val embed = link?.embedUrl.orEmpty()
    val youtubeEmbed = embed.isNotBlank()''', '''    val videoId = link?.youtubeVideoId.orEmpty()
    val youtubeEmbed = videoId.isNotBlank()''', 1)
s = s.replace('OfficialWebVideoPlayer(embed)', 'OfficialWebVideoPlayer(videoId)', 1)
start = s.index('@Composable\nprivate fun OfficialWebVideoPlayer')
end = s.index('private fun openYoutubeSessionDialog', start)
new_webview = r'''@Composable
private fun OfficialWebVideoPlayer(videoId: String) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var sessionNonce by remember(videoId) { mutableIntStateOf(0) }
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

    LaunchedEffect(videoId, sessionNonce, webView) {
        webView.stopLoading()
        webView.loadDataWithBaseURL(
            "https://www.youtube.com/",
            youtubeEmbedDocument(videoId),
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

private fun youtubeEmbedDocument(videoId: String): String {
    val safeId = videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
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
  src="https://www.youtube.com/embed/$safeId?playsinline=1&rel=0&fs=1"
  allow="autoplay; encrypted-media; picture-in-picture; web-share; fullscreen"
  allowfullscreen></iframe>
</body>
</html>"""
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

'''
s = s[:start] + new_webview + s[end:]
p.write_text(s)

# 3) Keep the reusable link itself free of the mismatched origin/jsapi parameters.
p = Path('app/src/main/java/com/riftlab/app/data/RiotVodRepository.kt')
s = p.read_text().replace(
    '"https://www.youtube.com/embed/$it?playsinline=1&rel=0&fs=1&enablejsapi=1&origin=https%3A%2F%2Flolesports.com"',
    '"https://www.youtube.com/embed/$it?playsinline=1&rel=0&fs=1"'
)
p.write_text(s)

# 4) Make hardware acceleration explicit for inline WebView video surfaces.
p = Path('app/src/main/AndroidManifest.xml')
s = p.read_text()
if 'android:hardwareAccelerated="true"' not in s:
    s = s.replace('''    <application
        android:name=".RiftLabApplication"''', '''    <application
        android:name=".RiftLabApplication"
        android:hardwareAccelerated="true"''', 1)
p.write_text(s)

# 5) Role label for overseas positional coaching titles.
p = Path('app/src/main/java/com/riftlab/app/ui/TeamDetailUi.kt')
s = p.read_text()
s = s.replace('''    "STRATEGICCOACH" -> "战术教练"
    "COACH" -> "教练"''', '''    "STRATEGICCOACH" -> "战术教练"
    "POSITIONALCOACH" -> "位置教练"
    "COACH" -> "教练"''', 1)
p.write_text(s)

# 6) Version bump.
p = Path('app/build.gradle.kts')
s = p.read_text()
s = s.replace('versionCode = 51', 'versionCode = 52', 1)
s = s.replace('versionName = "1.0.0-dev.51"', 'versionName = "1.0.0-dev.52"', 1)
if '// dev.52:' not in s:
    s += '\n// dev.52: global non-LPL management/coaching resolver plus stable hardware-accelerated inline YouTube embed rendering.\n'
p.write_text(s)
