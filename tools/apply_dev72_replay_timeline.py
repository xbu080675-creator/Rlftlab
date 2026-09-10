#!/usr/bin/env python3
from pathlib import Path


def read(path): return Path(path).read_text(encoding="utf-8")
def write(path, text): Path(path).write_text(text, encoding="utf-8")
def replace_once(path, old, new):
    text = read(path)
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:160]!r}")
    write(path, text.replace(old, new, 1))

# Riot VOD: preserve the official offset all the way to the actual YouTube start position.
path = "app/src/main/java/com/riftlab/app/data/RiotVodRepository.kt"
replace_once(
    path,
    '''    val embedUrl: String\n        get() = youtubeVideoId.takeIf { it.isNotBlank() }\n            ?.let { "https://www.youtube.com/embed/$it?playsinline=1&rel=0&fs=1" }\n            .orEmpty()''',
    '''    val embedUrl: String\n        get() = youtubeVideoId.takeIf { it.isNotBlank() }\n            ?.let { "https://www.youtube.com/embed/$it?playsinline=1&rel=0&fs=1&start=${offsetSeconds.coerceAtLeast(0)}" }\n            .orEmpty()'''
)

# Global replay UX: display the synchronization anchor and actually start the embedded VOD there.
path = "app/src/main/java/com/riftlab/app/ui/GlobalReplayContent.kt"
replace_once(
    path,
    '''        if (youtubeEmbed) {\n            OfficialWebVideoPlayer(videoId)''',
    '''        if (youtubeEmbed) {\n            val startSeconds = link?.offsetSeconds?.coerceAtLeast(0) ?: 0\n            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {\n                Text("Riot VOD 对齐", color = RiftMuted, fontSize = 9.sp)\n                Spacer(Modifier.weight(1f))\n                Text(\n                    if (startSeconds > 0) "同步起点 ${formatReplayTimestamp(startSeconds)}" else "官方源未提供额外偏移",\n                    color = if (startSeconds > 0) RiftCyan else RiftMuted,\n                    fontSize = 9.sp,\n                    fontWeight = FontWeight.SemiBold\n                )\n            }\n            OfficialWebVideoPlayer(videoId, startSeconds)'''
)
replace_once(
    path,
    '''@Composable\nprivate fun OfficialWebVideoPlayer(videoId: String) {''',
    '''@Composable\nprivate fun OfficialWebVideoPlayer(videoId: String, startSeconds: Int) {'''
)
replace_once(
    path,
    '''    var sessionNonce by remember(videoId) { mutableIntStateOf(0) }''',
    '''    var sessionNonce by remember(videoId, startSeconds) { mutableIntStateOf(0) }'''
)
replace_once(
    path,
    '''    LaunchedEffect(videoId, sessionNonce, webView) {\n        webView.stopLoading()\n        webView.loadDataWithBaseURL(\n            "https://www.youtube.com/",\n            youtubeEmbedDocument(videoId),''',
    '''    LaunchedEffect(videoId, startSeconds, sessionNonce, webView) {\n        webView.stopLoading()\n        webView.loadDataWithBaseURL(\n            "https://www.youtube.com/",\n            youtubeEmbedDocument(videoId, startSeconds),'''
)
replace_once(
    path,
    '''private fun youtubeEmbedDocument(videoId: String): String {\n    val safeId = videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }''',
    '''private fun youtubeEmbedDocument(videoId: String, startSeconds: Int): String {\n    val safeId = videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }\n    val safeStart = startSeconds.coerceAtLeast(0)'''
)
replace_once(
    path,
    '''  src="https://www.youtube.com/embed/$safeId?playsinline=1&rel=0&fs=1"''',
    '''  src="https://www.youtube.com/embed/$safeId?playsinline=1&rel=0&fs=1&start=$safeStart"'''
)
# Increase the tiny source/game labels slightly; the old overseas replay surface was too dense on phone.
text = read(path)
text = text.replace('Text(label, color = if (selected) RiftCyan else RiftMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)',
                    'Text(label, color = if (selected) RiftCyan else RiftMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)', 1)
text = text.replace('Modifier.width(156.dp)', 'Modifier.width(132.dp)', 1)
write(path, text)
# Add timestamp formatter before WebView config.
text = read(path)
anchor = "private fun configureOfficialWebView(webView: WebView, chromeClient: WebChromeClient) {"
if "private fun formatReplayTimestamp" not in text:
    idx = text.index(anchor)
    helper = '''private fun formatReplayTimestamp(seconds: Int): String {\n    val safe = seconds.coerceAtLeast(0)\n    val hours = safe / 3600\n    val minutes = (safe % 3600) / 60\n    val secs = safe % 60\n    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%02d:%02d".format(minutes, secs)\n}\n\n'''
    text = text[:idx] + helper + text[idx:]
write(path, text)

# Match operations: turn the historical economy curve into a scrubber with a selected node and events.
path = "app/src/main/java/com/riftlab/app/ui/MatchOperationsContent.kt"
text = read(path)
if "import androidx.compose.material3.Slider" not in text:
    text = text.replace("import androidx.compose.material3.Text\n", "import androidx.compose.material3.Slider\nimport androidx.compose.material3.Text\n", 1)
if "import com.riftlab.app.data.GameTimeline" not in text:
    text = text.replace("import com.riftlab.app.data.LivePlayerSnapshot\n", "import com.riftlab.app.data.GameTimeline\nimport com.riftlab.app.data.LivePlayerSnapshot\n", 1)
if "import com.riftlab.app.data.MatchTimelineStore" not in text:
    text = text.replace("import com.riftlab.app.data.MatchSessionStore\n", "import com.riftlab.app.data.MatchSessionStore\nimport com.riftlab.app.data.MatchTimelineStore\n", 1)
if "import kotlin.math.abs" not in text:
    text = text.replace("import kotlin.math.max\n", "import kotlin.math.abs\nimport kotlin.math.max\n", 1)
if "import kotlin.math.roundToInt" not in text:
    text = text.replace("import kotlin.math.min\n", "import kotlin.math.min\nimport kotlin.math.roundToInt\n", 1)
write(path, text)

replace_once(
    path,
    '''    val opggHistory by OpggHistoricalFrameResolver.states.collectAsState()''',
    '''    val opggHistory by OpggHistoricalFrameResolver.states.collectAsState()\n    val timelines by MatchTimelineStore.timelines.collectAsState()'''
)
replace_once(
    path,
    '''    val backfill = riotHistory[RiotLiveStatsHistoryResolver.stateKey(match, selectedGame)]\n    val opggBackfill = opggHistory[OpggHistoricalFrameResolver.stateKey(match, selectedGame)]''',
    '''    val backfill = riotHistory[RiotLiveStatsHistoryResolver.stateKey(match, selectedGame)]\n    val opggBackfill = opggHistory[OpggHistoricalFrameResolver.stateKey(match, selectedGame)]\n    val timeline = currentSnapshot?.let { MatchTimelineStore.find(it, timelines) }'''
)
replace_once(
    path,
    '''                item { GoldHistoryPanel(currentSnapshot, frames, phase, backfill, opggBackfill) }''',
    '''                item { GoldHistoryPanel(currentSnapshot, frames, phase, backfill, opggBackfill, timeline) }'''
)

text = read(path)
start = text.index("@Composable\nprivate fun GoldHistoryPanel(\n")
end = text.index("@Composable\nprivate fun PlayerOperatorTable", start)
replacement = '''@Composable\nprivate fun GoldHistoryPanel(\n    current: LiveSnapshot,\n    frames: List<MatchLifecycleFrame>,\n    phase: ScheduleMatchPhase,\n    backfill: RiotHistoryBackfillState?,\n    opggBackfill: OpggHistoryBackfillState?,\n    timeline: GameTimeline?\n) {\n    val snapshots = remember(frames, current) {\n        val archived = frames.map { it.snapshot }.filter { it.game == current.game }\n        if (archived.lastOrNull()?.elapsedSeconds == current.elapsedSeconds) archived else archived + current\n    }\n    var selectedIndex by remember(current.gameId, current.game, snapshots.size) {\n        mutableIntStateOf((snapshots.size - 1).coerceAtLeast(0))\n    }\n    LaunchedEffect(snapshots.size, phase) {\n        if (snapshots.isNotEmpty()) {\n            selectedIndex = if (phase == ScheduleMatchPhase.LIVE) snapshots.lastIndex else selectedIndex.coerceIn(0, snapshots.lastIndex)\n        }\n    }\n    OperatorPanel {\n        Row(verticalAlignment = Alignment.CenterVertically) {\n            Column(Modifier.weight(1f)) {\n                Text("ECONOMY OVER TIME", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)\n                Text(\n                    if (phase == ScheduleMatchPhase.COMPLETED) "历史经济过程 · 可拖动回看" else "经济随比赛时间动态变化",\n                    color = RiftText,\n                    fontSize = 13.sp,\n                    fontWeight = FontWeight.Bold\n                )\n            }\n            Text("${snapshots.size} points", color = RiftMuted, fontSize = 9.sp)\n        }\n        Spacer(Modifier.height(8.dp))\n        if (snapshots.size < 2) {\n            Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {\n                Text(\n                    when (phase) {\n                        ScheduleMatchPhase.LIVE -> "正在积累实时经济帧…"\n                        ScheduleMatchPhase.COMPLETED -> when {\n                            opggBackfill?.phase == OpggHistoryPhase.LOADING -> opggBackfill.message\n                            opggBackfill?.phase == OpggHistoryPhase.UNAVAILABLE || opggBackfill?.phase == OpggHistoryPhase.ERROR -> "${opggBackfill.message}；当前保留终局快照，不伪造过程曲线。"\n                            backfill?.phase == RiotHistoryPhase.LOADING -> backfill.message\n                            backfill?.phase == RiotHistoryPhase.UNAVAILABLE || backfill?.phase == RiotHistoryPhase.ERROR -> "Riot 历史帧不可用，正在尝试 OP.GG GOLD/XP 过程帧…"\n                            else -> "正在从 Riot LiveStats 恢复历史经济帧…"\n                        }\n                        ScheduleMatchPhase.UPCOMING -> "比赛尚未开始"\n                    },\n                    color = RiftMuted,\n                    fontSize = 9.sp,\n                    textAlign = TextAlign.Center,\n                    modifier = Modifier.padding(horizontal = 20.dp)\n                )\n            }\n        } else {\n            val safeIndex = selectedIndex.coerceIn(0, snapshots.lastIndex)\n            val selected = snapshots[safeIndex]\n            val previous = snapshots.getOrNull(safeIndex - 1)\n            GoldHistoryChart(snapshots, safeIndex)\n            Slider(\n                value = safeIndex.toFloat(),\n                onValueChange = { selectedIndex = it.roundToInt().coerceIn(0, snapshots.lastIndex) },\n                valueRange = 0f..snapshots.lastIndex.toFloat(),\n                steps = (snapshots.size - 2).coerceAtLeast(0)\n            )\n            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n                Text("节点 ${formatOperatorClock(selected.elapsedSeconds)}", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)\n                Spacer(Modifier.weight(1f))\n                Text("${safeIndex + 1}/${snapshots.size}", color = RiftMuted, fontSize = 9.sp)\n            }\n            Spacer(Modifier.height(5.dp))\n            MetricRow(\n                "${selected.blue} ${formatGold(selected.blueGold)}",\n                "Δ ${signedGold(selected.goldDiff)}",\n                if (selected.blueXp > 0 || selected.redXp > 0) "XP Δ ${signedGold(selected.blueXp - selected.redXp)}" else "XP —",\n                "${selected.red} ${formatGold(selected.redGold)}"\n            )\n            val nearestEvent = timeline?.events\n                ?.minByOrNull { event -> abs(event.seconds - selected.elapsedSeconds) }\n                ?.takeIf { event -> abs(event.seconds - selected.elapsedSeconds) <= 20 }\n            Spacer(Modifier.height(7.dp))\n            Column(\n                Modifier.fillMaxWidth()\n                    .background(RiftPanelAlt, CutCornerShape(topEnd = 8.dp, bottomStart = 6.dp))\n                    .padding(10.dp)\n            ) {\n                Text(\n                    nearestEvent?.let { "${formatOperatorClock(it.seconds)} · ${it.title}" } ?: nodeEventSummary(previous, selected),\n                    color = if (nearestEvent != null) RiftCyan else RiftText,\n                    fontSize = 10.sp,\n                    fontWeight = FontWeight.SemiBold\n                )\n                Text(\n                    nearestEvent?.detail?.ifBlank { "来自连续状态帧的可核实事件。" }\n                        ?: "节点由真实状态帧选取；没有离散事件时不会为了曲线观感编造击杀/资源事件。",\n                    color = RiftMuted,\n                    fontSize = 8.sp,\n                    lineHeight = 12.sp,\n                    modifier = Modifier.padding(top = 3.dp)\n                )\n            }\n            Spacer(Modifier.height(7.dp))\n            val first = snapshots.first()\n            val last = snapshots.last()\n            Row(Modifier.fillMaxWidth()) {\n                Text(\n                    "${first.blue} ${formatGold(first.blueGold)} → ${formatGold(last.blueGold)}",\n                    color = RiftCyan,\n                    fontSize = 8.sp,\n                    modifier = Modifier.weight(1f)\n                )\n                Text(\n                    "${first.red} ${formatGold(first.redGold)} → ${formatGold(last.redGold)}",\n                    color = RiftRed,\n                    fontSize = 8.sp,\n                    textAlign = TextAlign.End,\n                    modifier = Modifier.weight(1f)\n                )\n            }\n        }\n    }\n}\n\n@Composable\nprivate fun GoldHistoryChart(points: List<LiveSnapshot>, selectedIndex: Int) {\n    val cyan = RiftCyan\n    val red = RiftRed\n    val line = RiftLine.copy(alpha = 0.55f)\n    val minSecond = points.firstOrNull()?.elapsedSeconds ?: 0\n    val maxSecond = max(points.maxOfOrNull { it.elapsedSeconds } ?: 1, minSecond + 1)\n    val minGold = min(\n        points.minOfOrNull { it.blueGold } ?: 0,\n        points.minOfOrNull { it.redGold } ?: 0\n    )\n    val maxGold = max(\n        points.maxOfOrNull { it.blueGold } ?: 1,\n        points.maxOfOrNull { it.redGold } ?: 1\n    ).coerceAtLeast(minGold + 1)\n\n    Canvas(\n        Modifier.fillMaxWidth()\n            .height(132.dp)\n            .background(Color.Black.copy(alpha = 0.18f), CutCornerShape(topEnd = 8.dp, bottomStart = 6.dp))\n            .border(1.dp, line, CutCornerShape(topEnd = 8.dp, bottomStart = 6.dp))\n            .padding(8.dp)\n    ) {\n        for (i in 1..3) {\n            val y = size.height * i / 4f\n            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)\n        }\n\n        fun xOf(second: Int): Float =\n            ((second - minSecond).toFloat() / (maxSecond - minSecond).toFloat()).coerceIn(0f, 1f) * size.width\n\n        fun yOf(gold: Int): Float =\n            size.height - ((gold - minGold).toFloat() / (maxGold - minGold).toFloat()).coerceIn(0f, 1f) * size.height\n\n        val bluePath = Path()\n        val redPath = Path()\n        points.forEachIndexed { index, point ->\n            val x = xOf(point.elapsedSeconds)\n            val by = yOf(point.blueGold)\n            val ry = yOf(point.redGold)\n            if (index == 0) {\n                bluePath.moveTo(x, by)\n                redPath.moveTo(x, ry)\n            } else {\n                bluePath.lineTo(x, by)\n                redPath.lineTo(x, ry)\n            }\n        }\n        drawPath(bluePath, cyan, style = Stroke(width = 2.4f))\n        drawPath(redPath, red, style = Stroke(width = 2.4f))\n        points.getOrNull(selectedIndex)?.let { point ->\n            val x = xOf(point.elapsedSeconds)\n            drawLine(line.copy(alpha = 0.9f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)\n            drawCircle(cyan, radius = 5.5f, center = Offset(x, yOf(point.blueGold)))\n            drawCircle(red, radius = 5.5f, center = Offset(x, yOf(point.redGold)))\n        }\n    }\n}\n\nprivate fun nodeEventSummary(previous: LiveSnapshot?, current: LiveSnapshot): String {\n    current.latestEvent.takeIf { it.isNotBlank() }?.let { return it }\n    if (previous == null) return "比赛状态起点"\n    val events = buildList {\n        val bk = current.blueKills - previous.blueKills\n        val rk = current.redKills - previous.redKills\n        val bt = current.blueTowers - previous.blueTowers\n        val rt = current.redTowers - previous.redTowers\n        val bd = current.blueDragons - previous.blueDragons\n        val rd = current.redDragons - previous.redDragons\n        val bb = current.blueBarons - previous.blueBarons\n        val rb = current.redBarons - previous.redBarons\n        if (bk > 0) add("${current.blue} +$bk 击杀")\n        if (rk > 0) add("${current.red} +$rk 击杀")\n        if (bt > 0) add("${current.blue} +$bt 塔")\n        if (rt > 0) add("${current.red} +$rt 塔")\n        if (bd > 0) add("${current.blue} +$bd 龙")\n        if (rd > 0) add("${current.red} +$rd 龙")\n        if (bb > 0) add("${current.blue} +$bb 男爵")\n        if (rb > 0) add("${current.red} +$rb 男爵")\n        val swing = current.goldDiff - previous.goldDiff\n        if (abs(swing) >= 1000) add("经济摆动 ${signedGold(swing)}")\n    }\n    return events.joinToString(" · ").ifBlank { "状态采样点 · 无离散事件" }\n}\n\n'''
write(path, text[:start] + replacement + text[end:])

print("dev72 replay/timeline patch applied")
