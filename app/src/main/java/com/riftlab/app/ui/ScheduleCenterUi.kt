package com.riftlab.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.ScheduleMatchPhase
import com.riftlab.app.data.ScheduledEsportsMatch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val STAGE_GAP_DAYS = 21L

private data class ScheduleCompetitionBucket(
    val key: String,
    val title: String,
    val matches: List<ScheduledEsportsMatch>,
    val firstEpochMs: Long
)

@Composable
fun RiftLabRoot() {
    Box(Modifier.fillMaxSize()) {
        RiftLabApp()
        ScheduleCenterLauncher(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp)
        )
    }
}

@Composable
private fun ScheduleCenterLauncher(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }

    Button(
        onClick = { open = true },
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = RiftPanelAlt,
            contentColor = RiftText
        ),
        shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
    ) {
        Icon(Icons.Default.CalendarMonth, null, tint = RiftCyan)
        Spacer(Modifier.width(7.dp))
        Text("赛程中心", color = RiftText, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    }

    if (open) {
        ScheduleCenterDialog(onClose = { open = false })
    }
}

@Composable
private fun ScheduleCenterDialog(onClose: () -> Unit) {
    val center by MatchSessionStore.scheduleCenter.collectAsState()
    val buckets = remember(center.matches) { buildCompetitionBuckets(center.matches) }
    var selectedBucketKey by remember { mutableStateOf<String?>(null) }
    val selectedBucket = buckets.firstOrNull { it.key == selectedBucketKey }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = RiftBg,
            contentColor = RiftText
        ) {
            Column(
                Modifier.fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                ScheduleCenterHeader(
                    title = selectedBucket?.title ?: "LPL 赛程数据中心",
                    subtitle = if (selectedBucket == null) {
                        "按赛季 / 赛段整理 · 不再平铺全部比赛"
                    } else {
                        "${selectedBucket.matches.size} 场 · 按比赛日期排列"
                    },
                    canGoBack = selectedBucket != null,
                    onBack = { selectedBucketKey = null },
                    onClose = onClose
                )

                Spacer(Modifier.height(10.dp))
                ScheduleStatusPanel(
                    status = center.statusMessage,
                    current = center.currentMatch,
                    next = center.nextMatch
                )

                Spacer(Modifier.height(12.dp))
                if (selectedBucket == null) {
                    CompetitionDirectory(
                        buckets = buckets,
                        currentMatchId = center.currentMatch?.matchId,
                        nextMatchId = center.nextMatch?.matchId,
                        onSelect = { selectedBucketKey = it.key }
                    )
                } else {
                    CompetitionMatches(
                        bucket = selectedBucket,
                        selectedMatchId = center.selectedMatch?.matchId,
                        onMatchClick = { match ->
                            MatchSessionStore.selectScheduleMatch(match.matchId)
                            onClose()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleCenterHeader(
    title: String,
    subtitle: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (canGoBack) {
            Box(
                Modifier.clickable(onClick = onBack).padding(end = 10.dp, top = 10.dp, bottom = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.ArrowBack, null, tint = RiftMuted)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = RiftText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.Normal)
        }
        Box(
            Modifier.clickable(onClick = onClose).padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, null, tint = RiftMuted)
        }
    }
}

@Composable
private fun ScheduleStatusPanel(
    status: String,
    current: ScheduledEsportsMatch?,
    next: ScheduledEsportsMatch?
) {
    Column(
        Modifier.fillMaxWidth()
            .background(RiftPanel, CutCornerShape(topEnd = 14.dp, bottomStart = 10.dp))
            .border(1.dp, RiftLine, CutCornerShape(topEnd = 14.dp, bottomStart = 10.dp))
            .padding(12.dp)
    ) {
        Text(status, color = RiftText, fontWeight = FontWeight.Medium, fontSize = 11.sp)
        current?.let {
            Spacer(Modifier.height(5.dp))
            Text(
                "● NOW  ${matchLabel(it)}",
                color = RiftCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                MatchSessionStore.scheduleTimingNote(it),
                color = RiftMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Normal
            )
        }
        next?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                "NEXT  ${matchLabel(it)} · ${MatchSessionStore.scheduleTimingNote(it)}",
                color = RiftMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal
            )
        }
    }
}

@Composable
private fun CompetitionDirectory(
    buckets: List<ScheduleCompetitionBucket>,
    currentMatchId: String?,
    nextMatchId: String?,
    onSelect: (ScheduleCompetitionBucket) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(buckets, key = { it.key }) { bucket ->
            val hasCurrent = bucket.matches.any { it.matchId == currentMatchId }
            val hasNext = bucket.matches.any { it.matchId == nextMatchId }
            val completed = bucket.matches.count {
                MatchSessionStore.schedulePhase(it) == ScheduleMatchPhase.COMPLETED
            }
            CompetitionBucketCard(
                bucket = bucket,
                completed = completed,
                hasCurrent = hasCurrent,
                hasNext = hasNext,
                onClick = { onSelect(bucket) }
            )
        }
    }
}

@Composable
private fun CompetitionBucketCard(
    bucket: ScheduleCompetitionBucket,
    completed: Int,
    hasCurrent: Boolean,
    hasNext: Boolean,
    onClick: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .background(RiftPanel, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
            .border(
                1.dp,
                if (hasCurrent) RiftCyan.copy(alpha = 0.55f) else RiftLine,
                CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp)
            )
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    bucket.title,
                    color = if (hasCurrent) RiftCyan else RiftText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    competitionRange(bucket.matches),
                    color = RiftMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                when {
                    hasCurrent -> Text("LIVE", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    hasNext -> Text("NEXT", color = RiftText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "${bucket.matches.size} 场 · 已结束 $completed",
                    color = RiftMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Normal
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, null, tint = RiftMuted)
        }
    }
}

@Composable
private fun CompetitionMatches(
    bucket: ScheduleCompetitionBucket,
    selectedMatchId: String?,
    onMatchClick: (ScheduledEsportsMatch) -> Unit
) {
    val groups = bucket.matches.groupBy(MatchSessionStore::scheduleDateKey).toSortedMap()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        groups.forEach { (date, matches) ->
            item(key = "date-${bucket.key}-$date") {
                Text(
                    date,
                    color = RiftMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
            }
            items(matches, key = { it.eventId.ifBlank { it.matchId } }) { match ->
                ScheduleMatchCard(
                    match = match,
                    selected = selectedMatchId == match.matchId,
                    onClick = { onMatchClick(match) }
                )
            }
        }
    }
}

@Composable
private fun ScheduleMatchCard(
    match: ScheduledEsportsMatch,
    selected: Boolean,
    onClick: () -> Unit
) {
    val phase = MatchSessionStore.schedulePhase(match)
    val phaseText = when (phase) {
        ScheduleMatchPhase.LIVE -> "LIVE"
        ScheduleMatchPhase.UPCOMING -> "待开"
        ScheduleMatchPhase.COMPLETED -> "已结束"
    }
    val phaseColor = when (phase) {
        ScheduleMatchPhase.LIVE -> RiftCyan
        ScheduleMatchPhase.UPCOMING -> RiftText
        ScheduleMatchPhase.COMPLETED -> RiftMuted
    }

    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .background(RiftPanel, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
            .border(
                1.dp,
                if (selected || phase == ScheduleMatchPhase.LIVE) RiftCyan.copy(alpha = 0.48f) else RiftLine,
                CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp)
            )
            .padding(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                phaseText,
                color = phaseColor,
                fontSize = 10.sp,
                fontWeight = if (phase == ScheduleMatchPhase.LIVE) FontWeight.Bold else FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                "BO${match.bestOf}",
                color = RiftMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                match.teams.getOrNull(0)?.code ?: "—",
                color = RiftText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (phase == ScheduleMatchPhase.COMPLETED || match.teams.any { it.gameWins > 0 }) {
                    MatchSessionStore.scheduleScore(match)
                } else {
                    "VS"
                },
                color = if (phase == ScheduleMatchPhase.LIVE) RiftCyan else RiftMuted,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                match.teams.getOrNull(1)?.code ?: "—",
                color = RiftText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            MatchSessionStore.scheduleTimingNote(match),
            color = RiftMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Normal
        )
        if (match.blockName.isNotBlank()) {
            Text(
                match.blockName.uppercase(),
                color = RiftMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun buildCompetitionBuckets(matches: List<ScheduledEsportsMatch>): List<ScheduleCompetitionBucket> {
    val withDates = matches.mapNotNull { match ->
        matchStartDate(match)?.let { date -> match to date }
    }
    val buckets = mutableListOf<ScheduleCompetitionBucket>()

    withDates
        .groupBy { (match, date) -> match.league.trim().lowercase() to date.year }
        .forEach { (leagueYear, entries) ->
            val league = leagueYear.first
            val year = leagueYear.second
            val sorted = entries.sortedBy { (_, date) -> date }

            if (league.contains("world")) {
                val bucketMatches = sorted.map { it.first }
                buckets += ScheduleCompetitionBucket(
                    key = "$year-worlds",
                    title = "$year 全球总决赛",
                    matches = bucketMatches,
                    firstEpochMs = bucketMatches.minOfOrNull(::matchStartEpochMs) ?: Long.MAX_VALUE
                )
                return@forEach
            }

            if (league.contains("lpl")) {
                var stageIndex = 1
                var stageEntries = mutableListOf<Pair<ScheduledEsportsMatch, LocalDate>>()
                var previousDate: LocalDate? = null

                fun flushStage() {
                    if (stageEntries.isEmpty()) return
                    val stageMatches = stageEntries.map { it.first }
                    buckets += ScheduleCompetitionBucket(
                        key = "$year-lpl-stage-$stageIndex",
                        title = "$year ${stageName(stageIndex)}",
                        matches = stageMatches,
                        firstEpochMs = stageMatches.minOfOrNull(::matchStartEpochMs) ?: Long.MAX_VALUE
                    )
                    stageIndex += 1
                    stageEntries = mutableListOf()
                }

                sorted.forEach { entry ->
                    val date = entry.second
                    val gapDays = previousDate?.let { ChronoUnit.DAYS.between(it, date) } ?: 0L
                    if (stageEntries.isNotEmpty() && gapDays >= STAGE_GAP_DAYS) {
                        flushStage()
                    }
                    stageEntries += entry
                    previousDate = date
                }
                flushStage()
            } else {
                val bucketMatches = sorted.map { it.first }
                val displayLeague = bucketMatches.firstOrNull()?.league?.ifBlank { "赛事" } ?: "赛事"
                buckets += ScheduleCompetitionBucket(
                    key = "$year-${league.ifBlank { "other" }}",
                    title = "$year $displayLeague",
                    matches = bucketMatches,
                    firstEpochMs = bucketMatches.minOfOrNull(::matchStartEpochMs) ?: Long.MAX_VALUE
                )
            }
        }

    val unknown = matches.filter { matchStartDate(it) == null }
    if (unknown.isNotEmpty()) {
        buckets += ScheduleCompetitionBucket(
            key = "unknown-date",
            title = "日期待确认",
            matches = unknown,
            firstEpochMs = Long.MAX_VALUE
        )
    }

    return buckets.sortedWith(compareBy<ScheduleCompetitionBucket> { it.firstEpochMs }.thenBy { it.title })
}

private fun stageName(index: Int): String = when (index) {
    1 -> "第一赛段"
    2 -> "第二赛段"
    3 -> "第三赛段"
    4 -> "第四赛段"
    else -> "第${index}赛段"
}

private fun competitionRange(matches: List<ScheduledEsportsMatch>): String {
    val dates = matches.mapNotNull(::matchStartDate).sorted()
    if (dates.isEmpty()) return "日期待确认"
    val first = dates.first()
    val last = dates.last()
    return if (first == last) {
        "%02d-%02d".format(first.monthValue, first.dayOfMonth)
    } else {
        "%02d-%02d → %02d-%02d".format(
            first.monthValue,
            first.dayOfMonth,
            last.monthValue,
            last.dayOfMonth
        )
    }
}

private fun matchStartDate(match: ScheduledEsportsMatch): LocalDate? = runCatching {
    Instant.parse(match.startTimeIso).atZone(ZoneId.systemDefault()).toLocalDate()
}.getOrNull()

private fun matchStartEpochMs(match: ScheduledEsportsMatch): Long = runCatching {
    Instant.parse(match.startTimeIso).toEpochMilli()
}.getOrElse { Long.MAX_VALUE }

private fun matchLabel(match: ScheduledEsportsMatch): String =
    match.teams.take(2).joinToString(" vs ") { it.code.ifBlank { it.name } }
