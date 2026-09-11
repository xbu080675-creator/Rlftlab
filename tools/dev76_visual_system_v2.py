#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel: str, text: str) -> None:
    (ROOT / rel).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one target, found {count}")
    return text.replace(old, new, 1)


def replace_between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    i = text.find(start)
    if i < 0:
        raise SystemExit(f"{label}: start marker not found")
    j = text.find(end, i)
    if j < 0:
        raise SystemExit(f"{label}: end marker not found")
    return text[:i] + replacement + text[j:]


# --- Global skin / brand layer -------------------------------------------------
rel = "app/src/main/java/com/riftlab/app/ui/TeamSkin.kt"
text = read(rel)
text = replace_once(
    text,
    '''        lightPalette = light(
            background = 0xFFF4F7FA,
            panelAlt = 0xEAF0F4F8,
            line = 0xFFCAD4DF,
            accent = 0xFF087A91,
            secondary = 0xFF147E69
        ),
        darkPalette = dark(
            background = 0xFF0C1118,
            panel = 0xEE121A24,
            panelAlt = 0xE8182330,
            line = 0xFF2A3A4E,
            accent = 0xFF6CEBFF,
            secondary = 0xFF8AF3C9
        )
''',
    '''        lightPalette = light(
            background = 0xFFF6F4F6,
            panelAlt = 0xEAF0EEF2,
            line = 0xFFC9CBD3,
            accent = 0xFFD92345,
            secondary = 0xFF238FB9
        ),
        darkPalette = dark(
            background = 0xFF090B10,
            panel = 0xEF10141C,
            panelAlt = 0xE8161C26,
            line = 0xFF2A3340,
            accent = 0xFFFF365D,
            secondary = 0xFF43D6F1
        )
''',
    "default skin palette"
)
text = replace_once(
    text,
    '''    if (skin.id == RiftSkinId.DEFAULT) {
        Box(modifier.fillMaxSize().background(palette.background.copy(alpha = 1f)))
        return
    }

''',
    '''    // The default RiftLab skin also gets the broadcast/grid backdrop. V1 returned early here,
    // leaving the most common screens as a flat sheet while club skins looked much richer.

''',
    "default backdrop early return"
)
write(rel, text)


# --- App shell / homepage hierarchy ------------------------------------------
rel = "app/src/main/java/com/riftlab/app/ui/RiftLabApp.kt"
text = read(rel)
text = replace_between(
    text,
    '@Composable\nprivate fun PhaseTabs(selected: Int, onSelect: (Int) -> Unit) {',
    '\n@Composable\nprivate fun PreScreen()',
    '''@Composable
private fun PhaseTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 2.dp)
            .background(RiftPanelAlt.copy(alpha = 0.72f), CutCornerShape(topEnd = 16.dp, bottomStart = 10.dp))
            .padding(4.dp)
    ) {
        Phase.entries.forEachIndexed { index, phase ->
            val active = index == selected
            Column(
                Modifier.weight(1f)
                    .clickable { onSelect(index) }
                    .background(
                        if (active) RiftPanel else Color.Transparent,
                        CutCornerShape(topEnd = 11.dp, bottomStart = 7.dp)
                    )
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    phase.label,
                    color = if (active) RiftText else RiftMuted,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier.width(if (active) 30.dp else 12.dp)
                        .height(if (active) 3.dp else 1.dp)
                        .background(if (active) RiftCyan else RiftLine.copy(alpha = 0.55f))
                )
            }
        }
    }
}
''',
    "phase tabs"
)
text = replace_between(
    text,
    '        item { SectionTitle("REAL DATA SOURCE / 赛程源") }',
    '        item { SideSelectionPrePanel() }',
    '''        item { SectionTitle("MATCH FEED / 赛程状态") }
        item {
            Panel(accent = target != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (target != null) "赛程已锁定" else "等待可核实赛程",
                            color = RiftText,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(scheduleStatus, color = RiftMuted, fontSize = 10.sp, lineHeight = 15.sp)
                    }
                    RiftStatusBadge(if (target != null) "READY" else "SYNC")
                }
                target?.let { match ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        match.teams.take(2).joinToString("  VS  ") { it.code.ifBlank { it.name } },
                        color = RiftCyan,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${match.league} · ${match.blockName.ifBlank { "赛程" }} · BO${match.bestOf} · ${MatchSessionStore.scheduleDateTimeLabel(match)}",
                        color = RiftMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "数据来源 · Riot / Cito / International · 缺失字段保持未知",
                    color = RiftMuted,
                    fontSize = 10.sp
                )
            }
        }

''',
    "pre match feed"
)
text = replace_between(
    text,
    '@Composable\nprivate fun Panel(',
    '\n@Composable\nprivate fun SectionTitle',
    '''@Composable
private fun Panel(
    accent: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    RiftHudPanel(accent = accent, onClick = onClick, content = content)
}
''',
    "app panel"
)
text = replace_between(
    text,
    '@Composable\nprivate fun SectionTitle(value: String) {',
    '\n@Composable\nprivate fun MetricRow',
    '''@Composable
private fun SectionTitle(value: String) {
    RiftSectionLabel(value)
}
''',
    "app section label"
)
write(rel, text)


# --- Team-vs-team visual hierarchy -------------------------------------------
rel = "app/src/main/java/com/riftlab/app/ui/TeamMatchupVisual.kt"
text = read(rel)
text = replace_once(text, '    teamNameFontSize: TextUnit = 10.sp,\n', '    teamNameFontSize: TextUnit = 12.sp,\n', "team name size")
text = replace_once(text, '                Text(it, color = RiftMuted, fontSize = 10.sp, textAlign = TextAlign.Center)\n', '                Text(it, color = RiftMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)\n', "center subtext size")
write(rel, text)


# --- Match detail --------------------------------------------------------------
rel = "app/src/main/java/com/riftlab/app/ui/MatchDetailUi.kt"
text = read(rel)
text = replace_between(
    text,
    '@Composable\nprivate fun DetailSectionTitle(text: String) {',
    '\n@Composable\nprivate fun DetailPanel',
    '''@Composable
private fun DetailSectionTitle(text: String) {
    RiftSectionLabel(text)
}
''',
    "detail section label"
)
text = replace_between(
    text,
    '@Composable\nprivate fun DetailPanel(accent: Boolean = false, content: @Composable () -> Unit) {',
    '\nprivate fun roleMap',
    '''@Composable
private fun DetailPanel(accent: Boolean = false, content: @Composable () -> Unit) {
    RiftHudPanel(accent = accent) {
        content()
    }
}
''',
    "detail panel"
)
write(rel, text)


# --- Match operations / economy replay ---------------------------------------
rel = "app/src/main/java/com/riftlab/app/ui/MatchOperationsContent.kt"
text = read(rel)
text = replace_once(
    text,
    'import androidx.compose.foundation.clickable\n',
    'import androidx.compose.foundation.clickable\nimport androidx.compose.foundation.gestures.detectDragGestures\nimport androidx.compose.foundation.gestures.detectTapGestures\n',
    "gesture imports"
)
text = replace_once(
    text,
    'import androidx.compose.ui.graphics.Color\n',
    'import androidx.compose.ui.graphics.Brush\nimport androidx.compose.ui.graphics.Color\n',
    "brush import"
)
text = replace_once(
    text,
    'import androidx.compose.ui.graphics.drawscope.Stroke\n',
    'import androidx.compose.ui.graphics.drawscope.Stroke\nimport androidx.compose.ui.input.pointer.pointerInput\n',
    "pointer import"
)
text = replace_between(
    text,
    '@Composable\nprivate fun GoldHistoryPanel(',
    '\nprivate fun nodeEventSummary',
    '''@Composable
private fun GoldHistoryPanel(
    current: LiveSnapshot,
    frames: List<MatchLifecycleFrame>,
    phase: ScheduleMatchPhase,
    backfill: RiotHistoryBackfillState?,
    opggBackfill: OpggHistoryBackfillState?,
    timeline: GameTimeline?
) {
    val snapshots = remember(frames, current) {
        val archived = frames.map { it.snapshot }
            .filter { it.game == current.game }
            .sortedBy { it.elapsedSeconds }
        (archived + current)
            .distinctBy { it.elapsedSeconds }
            .sortedBy { it.elapsedSeconds }
    }
    // Selection is anchored to actual game time. Backfill can insert/rebuild frames without moving
    // the user's chosen moment or drawing a backwards/looping curve.
    var selectedElapsedSecond by remember(current.gameId, current.game) {
        mutableIntStateOf(-1)
    }
    OperatorPanel(accent = snapshots.size >= 2) {
        if (snapshots.size < 2) {
            Text("ECONOMY REPLAY", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("经济差回放", color = RiftText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(108.dp), contentAlignment = Alignment.Center) {
                Text(
                    when (phase) {
                        ScheduleMatchPhase.LIVE -> "正在积累实时经济帧…"
                        ScheduleMatchPhase.COMPLETED -> when {
                            opggBackfill?.phase == OpggHistoryPhase.LOADING -> opggBackfill.message
                            opggBackfill?.phase == OpggHistoryPhase.UNAVAILABLE || opggBackfill?.phase == OpggHistoryPhase.ERROR -> "${opggBackfill.message}；当前仅保留终局快照。"
                            backfill?.phase == RiotHistoryPhase.LOADING -> backfill.message
                            backfill?.phase == RiotHistoryPhase.UNAVAILABLE || backfill?.phase == RiotHistoryPhase.ERROR -> "Riot 历史帧不可用，正在尝试 OP.GG GOLD/XP 过程帧…"
                            else -> "正在恢复历史经济帧…"
                        }
                        ScheduleMatchPhase.UPCOMING -> "比赛尚未开始"
                    },
                    color = RiftMuted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            return@OperatorPanel
        }

        val safeIndex = when {
            selectedElapsedSecond < 0 -> snapshots.lastIndex
            else -> snapshots.indices.minByOrNull { index ->
                abs(snapshots[index].elapsedSeconds - selectedElapsedSecond)
            } ?: snapshots.lastIndex
        }
        val selected = snapshots[safeIndex]
        val previous = snapshots.getOrNull(safeIndex - 1)
        val dark = LocalRiftDarkMode.current
        val resolvedBlue = RiftTeamSkins.accentFor(selected.blue, dark)
        val resolvedRed = RiftTeamSkins.accentFor(selected.red, dark)
        val blueColor = if (resolvedBlue == resolvedRed) RiftCyan else resolvedBlue
        val redColor = if (resolvedBlue == resolvedRed) RiftRed else resolvedRed
        val leaderText = when {
            selected.goldDiff > 0 -> "${selected.blue} +${formatGold(selected.goldDiff)}"
            selected.goldDiff < 0 -> "${selected.red} +${formatGold(-selected.goldDiff)}"
            else -> "经济持平"
        }
        val leaderColor = when {
            selected.goldDiff > 0 -> blueColor
            selected.goldDiff < 0 -> redColor
            else -> RiftText
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("ECONOMY SWING", color = RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                Text(
                    if (phase == ScheduleMatchPhase.COMPLETED) "历史经济差 · 直接拖动图表回看" else "实时经济差",
                    color = RiftText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatOperatorClock(selected.elapsedSeconds), color = RiftText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(leaderText, color = leaderColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))

        GoldDifferenceChart(
            points = snapshots,
            selectedIndex = safeIndex,
            blueColor = blueColor,
            redColor = redColor,
            onSelectSecond = { selectedElapsedSecond = it }
        )
        Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
            Text(formatOperatorClock(snapshots.first().elapsedSeconds), color = RiftMuted, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text("拖动曲线选择时间", color = RiftMuted, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(formatOperatorClock(snapshots.last().elapsedSeconds), color = RiftMuted, fontSize = 10.sp)
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(selected.blue, color = RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(formatGold(selected.blueGold), color = blueColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("GOLD DIFF", color = RiftMuted, fontSize = 10.sp)
                Text(leaderText, color = leaderColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                if (selected.blueXp > 0 || selected.redXp > 0) {
                    Text("XP ${signedGold(selected.blueXp - selected.redXp)}", color = RiftMuted, fontSize = 10.sp)
                }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(selected.red, color = RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(formatGold(selected.redGold), color = redColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

        val nearestEvent = timeline?.events
            ?.minByOrNull { event -> abs(event.seconds - selected.elapsedSeconds) }
            ?.takeIf { event -> abs(event.seconds - selected.elapsedSeconds) <= 20 }
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(leaderColor.copy(alpha = 0.12f), RiftPanelAlt.copy(alpha = 0.74f))
                    ),
                    CutCornerShape(topEnd = 10.dp, bottomStart = 7.dp)
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                nearestEvent?.let { "${formatOperatorClock(it.seconds)} · ${it.title}" } ?: nodeEventSummary(previous, selected),
                color = if (nearestEvent != null) leaderColor else RiftText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                nearestEvent?.detail?.ifBlank { "来自连续状态帧的可核实事件。" }
                    ?: "没有离散事件时只展示真实状态变化，不补写不存在的击杀或资源事件。",
                color = RiftMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Text(
            "${snapshots.size} 个已验证状态帧 · ${selected.source}",
            color = RiftMuted,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 9.dp)
        )
    }
}

@Composable
private fun GoldDifferenceChart(
    points: List<LiveSnapshot>,
    selectedIndex: Int,
    blueColor: Color,
    redColor: Color,
    onSelectSecond: (Int) -> Unit
) {
    val grid = RiftLine.copy(alpha = 0.34f)
    val zero = RiftText.copy(alpha = 0.45f)
    val minSecond = points.minOfOrNull { it.elapsedSeconds } ?: 0
    val maxSecond = max(points.maxOfOrNull { it.elapsedSeconds } ?: 1, minSecond + 1)
    val maxAbsDiff = (points.maxOfOrNull { abs(it.goldDiff) } ?: 0).coerceAtLeast(1500)
    val shape = CutCornerShape(topEnd = 14.dp, bottomStart = 9.dp)

    fun nearestSecondForX(x: Float, width: Float): Int {
        val fraction = if (width <= 1f) 1f else (x / width).coerceIn(0f, 1f)
        val target = minSecond + ((maxSecond - minSecond) * fraction).roundToInt()
        return points.minByOrNull { abs(it.elapsedSeconds - target) }?.elapsedSeconds ?: maxSecond
    }

    Canvas(
        Modifier.fillMaxWidth()
            .height(184.dp)
            .background(
                Brush.verticalGradient(
                    listOf(RiftPanelAlt.copy(alpha = 0.88f), RiftBg.copy(alpha = 0.50f))
                ),
                shape
            )
            .pointerInput(points, minSecond, maxSecond) {
                detectTapGestures { offset ->
                    onSelectSecond(nearestSecondForX(offset.x, size.width.toFloat()))
                }
            }
            .pointerInput(points, minSecond, maxSecond) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onSelectSecond(nearestSecondForX(offset.x, size.width.toFloat()))
                    },
                    onDrag = { change, _ ->
                        onSelectSecond(nearestSecondForX(change.position.x, size.width.toFloat()))
                        change.consume()
                    }
                )
            }
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        val midY = size.height / 2f
        val halfRange = size.height * 0.40f
        for (i in 1..3) {
            val y = size.height * i / 4f
            drawLine(grid.copy(alpha = if (i == 2) 0.55f else 0.22f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        drawLine(zero, Offset(0f, midY), Offset(size.width, midY), strokeWidth = 1.6f)

        fun xOf(second: Int): Float =
            ((second - minSecond).toFloat() / (maxSecond - minSecond).toFloat()).coerceIn(0f, 1f) * size.width
        fun yOf(diff: Int): Float =
            midY - (diff.toFloat() / maxAbsDiff.toFloat()).coerceIn(-1f, 1f) * halfRange

        points.zipWithNext().forEach { (a, b) ->
            val avg = (a.goldDiff + b.goldDiff) / 2
            drawLine(
                color = if (avg >= 0) blueColor else redColor,
                start = Offset(xOf(a.elapsedSeconds), yOf(a.goldDiff)),
                end = Offset(xOf(b.elapsedSeconds), yOf(b.goldDiff)),
                strokeWidth = 3.4f
            )
        }
        points.getOrNull(selectedIndex)?.let { point ->
            val x = xOf(point.elapsedSeconds)
            val y = yOf(point.goldDiff)
            val pointColor = if (point.goldDiff >= 0) blueColor else redColor
            drawLine(RiftText.copy(alpha = 0.55f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.4f)
            drawCircle(pointColor.copy(alpha = 0.20f), radius = 10f, center = Offset(x, y))
            drawCircle(pointColor, radius = 5.5f, center = Offset(x, y))
        }
    }
}
''',
    "economy replay"
)
text = replace_between(
    text,
    '@Composable\nprivate fun OperatorPanel(accent: Boolean = false, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {',
    '\nprivate fun currentMatchMatches',
    '''@Composable
private fun OperatorPanel(accent: Boolean = false, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    RiftHudPanel(accent = accent, content = content)
}
''',
    "operator panel"
)
write(rel, text)


# --- Schedule center: make directory feel like an event product, not a file list ----------
rel = "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt"
text = read(rel)
text = replace_between(
    text,
    '@Composable\nprivate fun EventSummaryCard(',
    '\n@Composable\nprivate fun EventTabs',
    '''@Composable
private fun EventSummaryCard(
    bucket: ScheduleCompetitionBucket,
    current: ScheduledEsportsMatch?,
    next: ScheduledEsportsMatch?,
    standingsStatus: String
) {
    val hasCurrent = bucket.matches.any { it.matchId == current?.matchId }
    val hasNext = bucket.matches.any { it.matchId == next?.matchId }
    val currentActivity = current?.takeIf { hasCurrent }?.let(MatchSessionStore::scheduleActivity)
    RiftHudPanel(accent = hasCurrent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    bucket.title,
                    color = RiftText,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (bucket.researchOnly) "年度赛事档案" else competitionRange(bucket.matches),
                    color = RiftMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            RiftStatusBadge(
                when {
                    bucket.researchOnly -> "RESEARCH"
                    hasCurrent -> currentActivity?.let(::scheduleActivityText)?.uppercase() ?: "LIVE"
                    hasNext -> "NEXT"
                    bucket.matches.isNotEmpty() && bucket.matches.all { MatchSessionStore.schedulePhase(it) == ScheduleMatchPhase.COMPLETED } -> "FINAL"
                    else -> "EVENT"
                }
            )
        }
        if (hasCurrent && current != null) {
            Spacer(Modifier.height(11.dp))
            Text(matchLabel(current), color = RiftCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(MatchSessionStore.scheduleTimingNote(current), color = RiftMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
        } else if (hasNext && next != null) {
            Spacer(Modifier.height(11.dp))
            Text("下一场 · ${matchLabel(next)}", color = RiftText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(MatchSessionStore.scheduleTimingNote(next), color = RiftMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
        }
        if (bucket.tournamentId != null) {
            Text(standingsStatus, color = RiftMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
''',
    "event summary card"
)
text = replace_between(
    text,
    '@Composable\nprivate fun InternationalEventPlaceholder(menu: InternationalCompetitionMenu) {',
    '\n@Composable\nprivate fun DirectorySectionHeader',
    '''@Composable
private fun InternationalEventPlaceholder(menu: InternationalCompetitionMenu) {
    val (title, meta, detail) = when (menu) {
        InternationalCompetitionMenu.WORLDS -> Triple(
            "2026 全球总决赛 · WORLDS 2026",
            "年度重头赛事 · 固定一级优先入口",
            "2026 全球总决赛位置永久保留在国际赛事首位。参赛队、赛程、开赛时间、场馆和直播信息只使用 Riot / 官方赛事源动态填充；数据未发布时不伪造。"
        )
        InternationalCompetitionMenu.DEMACIA_GLOBAL -> Triple(
            "2026 德杯国际邀请赛", "最新国际赛事 · 固定入口",
            "德杯国际邀请赛独立归入国际赛事，不归入 LPL 常规联赛。参赛队、分组、赛程和直播信息在可信赛事源可用后自动填充。"
        )
        InternationalCompetitionMenu.WSCI -> Triple(
            "WSCI", "国际赛事 · 独立赛事入口",
            "WSCI 作为独立国际赛事建档。赛程、比分和战队来自已标注 Provider；缺少 Standings、Seed 或晋级来源时保持未知。"
        )
        InternationalCompetitionMenu.WSCL -> Triple(
            "WSCL", "国际赛事 · 当前赛事入口保留",
            "WSCL 独立归入国际赛事。赛程、比分和战队只在可信源返回后展示；不会因为参赛队曾属于次级联赛而错误归类回联赛目录。"
        )
        else -> Triple(menu.label, "国际赛事 · 固定入口", "当前暂无可核实赛程；可信赛事源发布后自动填充。")
    }
    RiftHudPanel(accent = menu == InternationalCompetitionMenu.WORLDS) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = RiftText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(meta, color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            }
            RiftStatusBadge("WAITING")
        }
        Spacer(Modifier.height(9.dp))
        Text(detail, color = RiftMuted, fontSize = 10.sp, lineHeight = 15.sp)
    }
}
''',
    "international placeholder"
)
text = replace_between(
    text,
    '@Composable\nprivate fun CompetitionDirectoryCard(',
    '\nprivate fun internationalCompetitionKind',
    '''@Composable
private fun CompetitionDirectoryCard(
    bucket: ScheduleCompetitionBucket,
    currentMatchId: String?,
    nextMatchId: String?,
    onClick: () -> Unit
) {
    val currentMatch = bucket.matches.firstOrNull { it.matchId == currentMatchId }
    val hasCurrent = currentMatch != null
    val hasNext = bucket.matches.any { it.matchId == nextMatchId }
    val completed = bucket.matches.count { MatchSessionStore.schedulePhase(it) == ScheduleMatchPhase.COMPLETED }
    RiftHudPanel(accent = hasCurrent, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(bucket.title, color = RiftText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (bucket.researchOnly) "年度赛事档案" else competitionRange(bucket.matches),
                    color = RiftMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            RiftStatusBadge(
                when {
                    hasCurrent -> currentMatch?.let { scheduleActivityText(MatchSessionStore.scheduleActivity(it)) }?.uppercase() ?: "LIVE"
                    hasNext -> "NEXT"
                    bucket.researchOnly -> "RESEARCH"
                    else -> "${bucket.matches.size} MATCHES"
                }
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, null, tint = RiftMuted)
        }
        if (!bucket.researchOnly) {
            Text("已结束 $completed / ${bucket.matches.size}", color = RiftMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 9.dp))
        }
    }
}
''',
    "competition directory card"
)
write(rel, text)


# --- Release metadata ----------------------------------------------------------
rel = "app/build.gradle.kts"
text = read(rel)
text = replace_once(text, "        versionCode = 75\n", "        versionCode = 76\n", "versionCode")
text = replace_once(text, '        versionName = "1.0.0-dev.75"\n', '        versionName = "1.0.0-dev.76"\n', "versionName")
write(rel, text)

write(
    "DEV_CURRENT_CHANGELOG.txt",
    "dev.76：RiftLab Visual System 2.0 第一轮全局视觉重构。默认皮肤改为品牌红 + 冷色辅助的赛事转播风，并让默认页面也启用轻量网格/光晕背景；新增统一 HUD Surface、赛事段落标题和状态 Badge，首页、比赛详情、赛事中心与运营数据页逐步移除满屏同款细边框卡片，改为层级化信息面板。首页赛程源去掉 EVENT ID / NO MOCK FALLBACK 等工程字段直出，保留用户真正需要的对阵、BO、时间与来源可信度。战队 VS 组件增大队名与时间层级。历史经济完全重做：默认展示经济差而不是两条必然向上的总经济线；历史帧按游戏时间排序去重，避免 backfill 造成曲线回折；直接拖动/点击图表选时间，不再使用 Material Slider；选中时间显示领先方、经济差、双方总经济、XP 与最近可核实事件，且继续沿用 dev.75 的时间锚点避免松手跳回终局。版本升级为 1.0.0-dev.76 / versionCode 76。"
)

# Release-contract assertions.
assert "RiftHudPanel" in read("app/src/main/java/com/riftlab/app/ui/RiftLabApp.kt")
assert "ECONOMY SWING" in read("app/src/main/java/com/riftlab/app/ui/MatchOperationsContent.kt")
assert "GoldDifferenceChart" in read("app/src/main/java/com/riftlab/app/ui/MatchOperationsContent.kt")
assert "detectDragGestures" in read("app/src/main/java/com/riftlab/app/ui/MatchOperationsContent.kt")
assert "distinctBy { it.elapsedSeconds }" in read("app/src/main/java/com/riftlab/app/ui/MatchOperationsContent.kt")
assert "NO MOCK FALLBACK" not in read("app/src/main/java/com/riftlab/app/ui/RiftLabApp.kt")
assert "versionCode = 76" in read("app/build.gradle.kts")
assert 'versionName = "1.0.0-dev.76"' in read("app/build.gradle.kts")
print("dev.76 visual-system v2 patch applied")
