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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.riftlab.app.data.EsportsTeamRef
import com.riftlab.app.data.EsportsTournamentRef
import com.riftlab.app.data.MatchDetailRepository
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.ScheduleMatchPhase
import com.riftlab.app.data.ScheduledEsportsMatch
import com.riftlab.app.data.StandingBracketMatch
import com.riftlab.app.data.StandingTeam
import com.riftlab.app.data.StandingsCenterStore
import com.riftlab.app.data.TeamDetailRepository
import com.riftlab.app.data.TeamAssetCatalog
import com.riftlab.app.data.TournamentStandings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val FALLBACK_STAGE_GAP_DAYS = 21L

private enum class EventCenterTab(val label: String) {
    SCHEDULE("赛程"),
    STANDINGS("排名"),
    BRACKET("淘汰赛"),
    TEAMS("战队")
}

private data class ScheduleCompetitionBucket(
    val key: String,
    val title: String,
    val matches: List<ScheduledEsportsMatch>,
    val firstEpochMs: Long,
    val tournamentId: String? = null,
    val tournament: EsportsTournamentRef? = null
)

@Composable
fun RiftLabRoot() {
    MatchSessionStore.ensureDataRunning()
    StandingsCenterStore.ensureRunning()
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
        colors = ButtonDefaults.buttonColors(containerColor = RiftPanelAlt, contentColor = RiftText),
        shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
    ) {
        Icon(Icons.Default.CalendarMonth, null, tint = RiftCyan)
        Spacer(Modifier.width(7.dp))
        Text("赛事中心", color = RiftText, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    }
    if (open) ScheduleCenterDialog(onClose = { open = false })
}

@Composable
private fun ScheduleCenterDialog(onClose: () -> Unit) {
    val center by MatchSessionStore.scheduleCenter.collectAsState()
    val standingsCenter by StandingsCenterStore.state.collectAsState()
    val buckets = remember(center.matches, standingsCenter.tournaments) {
        buildCompetitionBuckets(center.matches, standingsCenter.tournaments)
    }
    var selectedBucketKey by remember { mutableStateOf<String?>(null) }
    var selectedDetailMatch by remember { mutableStateOf<ScheduledEsportsMatch?>(null) }
    var selectedTeam by remember { mutableStateOf<EsportsTeamRef?>(null) }
    var tabIndex by remember { mutableIntStateOf(0) }
    var initialPositionResolved by remember { mutableStateOf(false) }
    val selectedBucket = buckets.firstOrNull { it.key == selectedBucketKey }
    val selectedStandings = standingsCenter.standings
        ?.takeIf { it.tournamentId == selectedBucket?.tournamentId }

    LaunchedEffect(buckets, center.currentMatch?.matchId, center.nextMatch?.matchId) {
        if (initialPositionResolved || buckets.isEmpty()) return@LaunchedEffect
        val initial = chooseInitialBucket(
            buckets = buckets,
            currentMatchId = center.currentMatch?.matchId,
            nextMatchId = center.nextMatch?.matchId,
            today = LocalDate.now()
        )
        if (initial != null) {
            selectedBucketKey = initial.key
            tabIndex = EventCenterTab.SCHEDULE.ordinal
            initial.tournamentId?.let(StandingsCenterStore::selectTournament)
        }
        initialPositionResolved = true
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = RiftBg, contentColor = RiftText) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
                ScheduleCenterHeader(
                    title = selectedDetailMatch?.let(::matchLabel)
                        ?: selectedTeam?.let(::teamCode)
                        ?: selectedBucket?.title
                        ?: "英雄联盟赛事",
                    subtitle = selectedDetailMatch?.let { match ->
                        "${match.blockName.ifBlank { match.league }} · BO${match.bestOf} · ${MatchSessionStore.scheduleDateKey(match)}"
                    } ?: selectedTeam?.let { team ->
                        "战队资料 · ${team.name.ifBlank { teamCode(team) }}"
                    } ?: if (selectedBucket == null) {
                        "按官方 Tournament 整理"
                    } else {
                        competitionRange(selectedBucket.matches)
                    },
                    canGoBack = selectedDetailMatch != null || selectedTeam != null || selectedBucket != null,
                    onBack = {
                        when {
                            selectedDetailMatch != null -> selectedDetailMatch = null
                            selectedTeam != null -> {
                                selectedTeam = null
                                TeamDetailRepository.close()
                            }
                            else -> {
                                selectedBucketKey = null
                                tabIndex = 0
                            }
                        }
                    },
                    onClose = onClose
                )

                Spacer(Modifier.height(12.dp))
                when {
                    selectedDetailMatch != null -> MatchDetailContent()
                    selectedTeam != null -> TeamDetailContent(
                        team = selectedTeam!!,
                        matches = selectedBucket?.matches ?: center.matches,
                        onMatchClick = { match ->
                            MatchDetailRepository.open(match)
                            selectedDetailMatch = match
                        }
                    )
                    selectedBucket == null -> CompetitionDirectory(
                        buckets = buckets,
                        currentMatchId = center.currentMatch?.matchId,
                        nextMatchId = center.nextMatch?.matchId,
                        onSelect = { bucket ->
                            selectedDetailMatch = null
                            selectedTeam = null
                            selectedBucketKey = bucket.key
                            tabIndex = 0
                            bucket.tournamentId?.let(StandingsCenterStore::selectTournament)
                        }
                    )
                    else -> {
                        EventSummaryCard(
                            bucket = selectedBucket,
                            current = center.currentMatch,
                            next = center.nextMatch,
                            standingsStatus = standingsCenter.statusMessage
                        )
                        Spacer(Modifier.height(10.dp))
                        EventTabs(tabIndex) { tabIndex = it }
                        Spacer(Modifier.height(10.dp))

                        when (EventCenterTab.entries[tabIndex]) {
                            EventCenterTab.SCHEDULE -> CompetitionMatches(
                                bucket = selectedBucket,
                                selectedMatchId = center.selectedMatch?.matchId,
                                onMatchClick = { match ->
                                    MatchDetailRepository.open(match)
                                    selectedDetailMatch = match
                                }
                            )
                            EventCenterTab.STANDINGS -> StandingsView(selectedStandings)
                            EventCenterTab.BRACKET -> BracketView(
                                standings = selectedStandings,
                                scheduleMatches = selectedBucket.matches
                            )
                            EventCenterTab.TEAMS -> TeamsView(
                                standings = selectedStandings,
                                scheduleMatches = selectedBucket.matches,
                                onTeamClick = { team ->
                                    TeamDetailRepository.open(team, selectedBucket.matches)
                                    selectedTeam = team
                                }
                            )
                        }
                    }
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = RiftMuted)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = RiftText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = RiftMuted, fontSize = 10.sp)
        }
        Box(Modifier.clickable(onClick = onClose).padding(10.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, null, tint = RiftMuted)
        }
    }
}

@Composable
private fun EventSummaryCard(
    bucket: ScheduleCompetitionBucket,
    current: ScheduledEsportsMatch?,
    next: ScheduledEsportsMatch?,
    standingsStatus: String
) {
    val hasCurrent = bucket.matches.any { it.matchId == current?.matchId }
    val hasNext = bucket.matches.any { it.matchId == next?.matchId }
    Column(
        Modifier.fillMaxWidth()
            .background(RiftPanel, CutCornerShape(topEnd = 16.dp, bottomStart = 10.dp))
            .border(1.dp, if (hasCurrent) RiftCyan.copy(alpha = 0.5f) else RiftLine, CutCornerShape(topEnd = 16.dp, bottomStart = 10.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    hasCurrent -> "进行中"
                    hasNext -> "当前赛段"
                    bucket.matches.all { MatchSessionStore.schedulePhase(it) == ScheduleMatchPhase.COMPLETED } -> "已结束"
                    else -> "赛事"
                },
                color = if (hasCurrent) RiftCyan else RiftText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
            Spacer(Modifier.weight(1f))
            Text("${bucket.matches.size} 场", color = RiftMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(5.dp))
        Text(competitionRange(bucket.matches), color = RiftMuted, fontSize = 10.sp)
        if (hasCurrent && current != null) {
            Spacer(Modifier.height(5.dp))
            Text("LIVE · ${matchLabel(current)}", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        } else if (hasNext && next != null) {
            Spacer(Modifier.height(5.dp))
            Text("NEXT · ${matchLabel(next)} · ${MatchSessionStore.scheduleTimingNote(next)}", color = RiftMuted, fontSize = 10.sp)
        }
        if (bucket.tournamentId != null) {
            Spacer(Modifier.height(5.dp))
            Text(standingsStatus, color = RiftMuted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun EventTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(RiftPanelAlt, CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        EventCenterTab.entries.forEachIndexed { index, tab ->
            Box(
                Modifier.weight(1f)
                    .clickable { onSelect(index) }
                    .background(
                        if (selected == index) RiftPanel else androidx.compose.ui.graphics.Color.Transparent,
                        CutCornerShape(topEnd = 8.dp, bottomStart = 6.dp)
                    )
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    tab.label,
                    color = if (selected == index) RiftCyan else RiftMuted,
                    fontSize = 11.sp,
                    fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal
                )
            }
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
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(buckets, key = { it.key }) { bucket ->
            val hasCurrent = bucket.matches.any { it.matchId == currentMatchId }
            val hasNext = bucket.matches.any { it.matchId == nextMatchId }
            val completed = bucket.matches.count { MatchSessionStore.schedulePhase(it) == ScheduleMatchPhase.COMPLETED }
            Column(
                Modifier.fillMaxWidth()
                    .clickable { onSelect(bucket) }
                    .background(RiftPanel, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
                    .border(1.dp, if (hasCurrent) RiftCyan.copy(alpha = 0.55f) else RiftLine, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
                    .padding(horizontal = 14.dp, vertical = 13.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(bucket.title, color = if (hasCurrent) RiftCyan else RiftText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(competitionRange(bucket.matches), color = RiftMuted, fontSize = 10.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        when {
                            hasCurrent -> Text("LIVE", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            hasNext -> Text("NEXT", color = RiftText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text("${bucket.matches.size} 场 · 已结束 $completed", color = RiftMuted, fontSize = 9.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.ChevronRight, null, tint = RiftMuted)
                }
            }
        }
    }
}

@Composable
private fun CompetitionMatches(
    bucket: ScheduleCompetitionBucket,
    selectedMatchId: String?,
    onMatchClick: (ScheduledEsportsMatch) -> Unit
) {
    val groups = remember(bucket.key, bucket.matches) {
        bucket.matches.groupBy(MatchSessionStore::scheduleDateKey).toSortedMap()
    }
    val listState = rememberLazyListState()
    val today = LocalDate.now().toString()

    LaunchedEffect(bucket.key, groups.keys.toList(), today) {
        if (groups.isEmpty()) return@LaunchedEffect
        val dates = groups.keys.toList()
        val targetDate = when {
            groups.containsKey(today) -> today
            else -> dates.firstOrNull { it > today } ?: dates.last()
        }
        var targetIndex = 0
        for (date in dates) {
            if (date == targetDate) break
            targetIndex += 1 + groups[date].orEmpty().size
        }
        listState.scrollToItem(targetIndex)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        groups.forEach { (date, matches) ->
            item(key = "date-${bucket.key}-$date") {
                Text(
                    if (date == today) "今天 · $date" else date,
                    color = if (date == today) RiftCyan else RiftMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
            }
            items(matches, key = { it.eventId.ifBlank { it.matchId } }) { match ->
                ScheduleMatchCard(match, selectedMatchId == match.matchId) { onMatchClick(match) }
            }
        }
    }
}

@Composable
private fun ScheduleMatchCard(match: ScheduledEsportsMatch, selected: Boolean, onClick: () -> Unit) {
    val phase = MatchSessionStore.schedulePhase(match)
    val left = match.teams.getOrNull(0)
    val right = match.teams.getOrNull(1)
    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .background(RiftPanel, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
            .border(1.dp, if (selected || phase == ScheduleMatchPhase.LIVE) RiftCyan.copy(alpha = 0.48f) else RiftLine, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
            .padding(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (phase) {
                    ScheduleMatchPhase.LIVE -> "LIVE"
                    ScheduleMatchPhase.UPCOMING -> "待开"
                    ScheduleMatchPhase.COMPLETED -> "已结束"
                },
                color = if (phase == ScheduleMatchPhase.LIVE) RiftCyan else RiftMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text("BO${match.bestOf}", color = RiftMuted, fontSize = 9.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        TeamMatchupVisual(
            leftCode = teamCode(left),
            leftImageUrl = left?.imageUrl.orEmpty(),
            rightCode = teamCode(right),
            rightImageUrl = right?.imageUrl.orEmpty(),
            centerText = if (phase == ScheduleMatchPhase.COMPLETED || match.teams.any { it.gameWins > 0 }) MatchSessionStore.scheduleScore(match) else "VS",
            centerSubtext = MatchSessionStore.scheduleTimingNote(match),
            logoSize = 44.dp,
            centerFontSize = 18.sp
        )
        if (match.blockName.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                translateStageName(match.blockName),
                color = RiftMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun StandingsView(standings: TournamentStandings?) {
    val sections = standings?.stages.orEmpty().flatMap { stage ->
        stage.sections.filter { it.rankings.isNotEmpty() }
    }
    if (sections.isEmpty()) {
        EmptyData("等待 Riot Standings 排名数据")
        return
    }

    var selectedSection by remember(standings?.tournamentId) { mutableIntStateOf(0) }
    val safeIndex = selectedSection.coerceIn(0, sections.lastIndex)
    val section = sections[safeIndex]

    Column(Modifier.fillMaxSize()) {
        if (sections.size > 1) {
            Row(
                Modifier.fillMaxWidth().background(RiftPanelAlt, CutCornerShape(topEnd = 10.dp, bottomStart = 8.dp)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                sections.forEachIndexed { index, item ->
                    Box(
                        Modifier.weight(1f).clickable { selectedSection = index }
                            .background(if (safeIndex == index) RiftPanel else androidx.compose.ui.graphics.Color.Transparent, CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp))
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(translateSectionName(item.name), color = if (safeIndex == index) RiftCyan else RiftMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                    TableText("名次", 0.7f, RiftMuted, FontWeight.Medium)
                    TableText("战队", 1.5f, RiftMuted, FontWeight.Medium)
                    TableText("胜/负", 1f, RiftMuted, FontWeight.Medium)
                    TableText("积分", 0.8f, RiftMuted, FontWeight.Medium, end = true)
                }
            }
            items(section.rankings, key = { "${it.ordinal}-${it.team.id}-${it.team.code}" }) { row ->
                StandingRow(row)
            }
        }
    }
}

@Composable
private fun StandingRow(row: StandingTeam) {
    Row(
        Modifier.fillMaxWidth()
            .background(RiftPanel, CutCornerShape(topEnd = 8.dp, bottomStart = 5.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TableText(row.ordinal.toString(), 0.7f, RiftText, FontWeight.SemiBold)
        TableText(teamCode(row.team), 1.5f, RiftText, FontWeight.SemiBold)
        TableText("${row.wins}/${row.losses}", 1f, RiftText, FontWeight.Normal)
        TableText((row.points ?: row.wins).toString(), 0.8f, RiftText, FontWeight.SemiBold, end = true)
    }
    Spacer(Modifier.height(5.dp))
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TableText(
    value: String,
    tableWeight: Float,
    color: androidx.compose.ui.graphics.Color,
    fontWeight: FontWeight,
    end: Boolean = false
) {
    Text(
        value,
        modifier = Modifier.weight(tableWeight),
        color = color,
        fontSize = 12.sp,
        fontWeight = fontWeight,
        textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start
    )
}

@Composable
private fun BracketView(standings: TournamentStandings?, scheduleMatches: List<ScheduledEsportsMatch>) {
    val stages = standings?.stages.orEmpty().filter { stage ->
        stage.sections.any { it.matches.isNotEmpty() } &&
            (stage.slug.contains("playoff", true) || stage.slug.contains("regional", true))
    }
    if (stages.isEmpty()) {
        EmptyData("等待 Riot Standings 淘汰赛数据")
        return
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        stages.forEach { stage ->
            item(key = "stage-${stage.id}-${stage.slug}") {
                Text(translateStageName(stage.name), color = RiftText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            stage.sections.forEach { section ->
                val matches = section.matches
                items(matches.chunked(2), key = { chunk -> chunk.joinToString("-") { it.id } }) { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { bracketMatch ->
                            BracketMatchCard(bracketMatch, scheduleMatches, Modifier.weight(1f))
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun BracketMatchCard(
    bracketMatch: StandingBracketMatch,
    scheduleMatches: List<ScheduledEsportsMatch>,
    modifier: Modifier
) {
    val schedule = scheduleMatches.firstOrNull { it.matchId == bracketMatch.id || it.eventId == bracketMatch.id }
    val left = bracketMatch.teams.getOrNull(0)
    val right = bracketMatch.teams.getOrNull(1)
    val leftCode = teamCode(left)
    val rightCode = teamCode(right)
    val leftScore = scoreFor(left, schedule)
    val rightScore = scoreFor(right, schedule)
    val leftAsset = schedule?.teams?.firstOrNull { teamCode(it).equals(leftCode, true) }
    val rightAsset = schedule?.teams?.firstOrNull { teamCode(it).equals(rightCode, true) }
    Column(
        modifier.background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .border(1.dp, RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .padding(10.dp)
    ) {
        Text(bracketState(bracketMatch.state), color = RiftMuted, fontSize = 9.sp)
        Spacer(Modifier.height(7.dp))
        TeamMatchupVisual(
            leftCode = leftCode,
            leftImageUrl = leftAsset?.imageUrl.orEmpty(),
            rightCode = rightCode,
            rightImageUrl = rightAsset?.imageUrl.orEmpty(),
            centerText = if (leftScore != "—" || rightScore != "—") "$leftScore : $rightScore" else "VS",
            logoSize = 30.dp,
            centerFontSize = 14.sp,
            teamNameFontSize = 8.sp
        )
        if (bracketMatch.previousMatchIds.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("承接上一轮", color = RiftMuted, fontSize = 8.sp)
        }
    }
}

@Composable
private fun BracketTeamLine(code: String, score: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(code, color = RiftText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(score, color = RiftCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TeamsView(
    standings: TournamentStandings?,
    scheduleMatches: List<ScheduledEsportsMatch>,
    onTeamClick: (EsportsTeamRef) -> Unit
) {
    val teams = remember(standings?.tournamentId, standings?.stages, scheduleMatches) {
        val fromSchedule = scheduleMatches.flatMap { it.teams }
        val fromRankings = standings?.stages.orEmpty().flatMap { stage ->
            stage.sections.flatMap { section -> section.rankings.map { it.team } }
        }
        val fromMatches = standings?.stages.orEmpty().flatMap { stage ->
            stage.sections.flatMap { section -> section.matches.flatMap { it.teams } }
        }
        (fromSchedule + fromRankings + fromMatches)
            .filter { teamCode(it) != "TBD" && teamCode(it) != "—" }
            .groupBy(TeamAssetCatalog::canonicalKey)
            .values
            .map { variants ->
                variants.maxByOrNull { team ->
                    (if (team.id.isNotBlank()) 8 else 0) +
                        (if (team.slug.isNotBlank()) 4 else 0) +
                        (if (team.imageUrl.isNotBlank()) 2 else 0) +
                        (if (team.code.isNotBlank()) 1 else 0)
                } ?: variants.first()
            }
            .sortedBy { teamCode(it) }
    }
    if (teams.isEmpty()) {
        EmptyData("等待参赛战队数据")
        return
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(teams.chunked(3), key = { row -> row.joinToString("-") { TeamAssetCatalog.canonicalKey(it) } }) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { team -> TeamTile(team, Modifier.weight(1f)) { onTeamClick(team) } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TeamTile(team: EsportsTeamRef, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable(onClick = onClick)
            .background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .border(1.dp, RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TeamLogo(
            imageUrl = team.imageUrl,
            code = teamCode(team),
            modifier = Modifier.size(34.dp)
        )
        Spacer(Modifier.height(7.dp))
        Text(teamCode(team), color = RiftText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (team.name.isNotBlank() && team.name != teamCode(team)) {
            Spacer(Modifier.height(3.dp))
            Text(team.name, color = RiftMuted, fontSize = 8.sp, maxLines = 1)
        }
    }
}

@Composable
private fun EmptyData(message: String) {
    Box(
        Modifier.fillMaxWidth().background(RiftPanel, CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp)).padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(message, color = RiftMuted, fontSize = 11.sp)
    }
}

private fun chooseInitialBucket(
    buckets: List<ScheduleCompetitionBucket>,
    currentMatchId: String?,
    nextMatchId: String?,
    today: LocalDate
): ScheduleCompetitionBucket? {
    if (buckets.isEmpty()) return null
    buckets.firstOrNull { bucket -> bucket.matches.any { it.matchId == currentMatchId } }?.let { return it }
    buckets.firstOrNull { bucket -> bucket.matches.any { matchStartDate(it) == today } }?.let { return it }
    buckets.firstOrNull { bucket -> bucket.matches.any { it.matchId == nextMatchId } }?.let { return it }

    val dated = buckets.mapNotNull { bucket ->
        val dates = bucket.matches.mapNotNull(::matchStartDate)
        if (dates.isEmpty()) null else Triple(bucket, dates.minOrNull()!!, dates.maxOrNull()!!)
    }
    dated.firstOrNull { (_, first, last) -> !today.isBefore(first) && !today.isAfter(last) }
        ?.first?.let { return it }
    return dated
        .filter { (_, first, _) -> !first.isAfter(today) }
        .maxByOrNull { (_, _, last) -> last }
        ?.first
        ?: dated.minByOrNull { (_, first, _) -> kotlin.math.abs(ChronoUnit.DAYS.between(today, first)) }?.first
        ?: buckets.last()
}

private fun buildCompetitionBuckets(
    matches: List<ScheduledEsportsMatch>,
    tournaments: List<EsportsTournamentRef>
): List<ScheduleCompetitionBucket> {
    if (tournaments.isNotEmpty()) {
        val official = tournaments.mapNotNull { tournament ->
            val tournamentMatches = matches.filter { match ->
                matchStartDate(match)?.let { StandingsCenterStore.containsDate(tournament, it) } == true
            }
            if (tournamentMatches.isEmpty()) return@mapNotNull null
            ScheduleCompetitionBucket(
                key = tournament.id,
                title = StandingsCenterStore.displayTournamentName(tournament),
                matches = tournamentMatches.sortedBy(::matchStartEpochMs),
                firstEpochMs = tournamentMatches.minOfOrNull(::matchStartEpochMs) ?: Long.MAX_VALUE,
                tournamentId = tournament.id,
                tournament = tournament
            )
        }
        if (official.isNotEmpty()) return official.sortedBy { it.firstEpochMs }
    }
    return buildFallbackBuckets(matches)
}

private fun buildFallbackBuckets(matches: List<ScheduledEsportsMatch>): List<ScheduleCompetitionBucket> {
    val entries = matches.mapNotNull { match -> matchStartDate(match)?.let { match to it } }.sortedBy { it.second }
    val result = mutableListOf<ScheduleCompetitionBucket>()
    entries.groupBy { it.second.year }.forEach { (year, yearEntries) ->
        var index = 1
        var current = mutableListOf<Pair<ScheduledEsportsMatch, LocalDate>>()
        var previous: LocalDate? = null
        fun flush() {
            if (current.isEmpty()) return
            val stageMatches = current.map { it.first }
            result += ScheduleCompetitionBucket(
                key = "$year-lpl-stage-$index",
                title = "$year LPL ${stageName(index)}",
                matches = stageMatches,
                firstEpochMs = stageMatches.minOfOrNull(::matchStartEpochMs) ?: Long.MAX_VALUE
            )
            index += 1
            current = mutableListOf()
        }
        yearEntries.forEach { entry ->
            val gap = previous?.let { ChronoUnit.DAYS.between(it, entry.second) } ?: 0L
            if (current.isNotEmpty() && gap >= FALLBACK_STAGE_GAP_DAYS) flush()
            current += entry
            previous = entry.second
        }
        flush()
    }
    return result.sortedBy { it.firstEpochMs }
}

private fun stageName(index: Int): String = when (index) {
    1 -> "第一赛段"
    2 -> "第二赛段"
    3 -> "第三赛段"
    else -> "第${index}赛段"
}

private fun translateSectionName(value: String): String = when {
    value.contains("Ascend", true) -> "登峰组"
    value.contains("Nirvana", true) -> "涅槃组"
    else -> translateStageName(value)
}

private fun translateStageName(value: String): String = when {
    value.contains("Knights", true) -> "骑士之路"
    value.equals("Playoffs", true) -> "淘汰赛"
    value.contains("Regional", true) -> "区域资格赛"
    value.contains("Group Stage", true) -> "组内赛"
    value.equals("Finals", true) -> "决赛"
    else -> value.uppercase()
}

private fun bracketState(value: String): String = when {
    value.contains("complete", true) -> "已结束"
    value.contains("progress", true) || value.equals("live", true) -> "LIVE"
    else -> "待开"
}

private fun scoreFor(team: EsportsTeamRef?, schedule: ScheduledEsportsMatch?): String {
    if (team == null) return "—"
    val scheduled = schedule?.teams?.firstOrNull { it.id == team.id || teamCode(it) == teamCode(team) }
    val score = scheduled?.gameWins ?: team.gameWins
    val completed = schedule?.let { MatchSessionStore.schedulePhase(it) == ScheduleMatchPhase.COMPLETED } == true
    return if (score > 0 || completed) score.toString() else "—"
}

private fun competitionRange(matches: List<ScheduledEsportsMatch>): String {
    val dates = matches.mapNotNull(::matchStartDate).sorted()
    if (dates.isEmpty()) return "日期待确认"
    val first = dates.first()
    val last = dates.last()
    return "%d.%02d.%02d - %d.%02d.%02d".format(
        first.year, first.monthValue, first.dayOfMonth,
        last.year, last.monthValue, last.dayOfMonth
    )
}

private fun matchStartDate(match: ScheduledEsportsMatch): LocalDate? = runCatching {
    Instant.parse(match.startTimeIso).atZone(ZoneId.systemDefault()).toLocalDate()
}.getOrNull()

private fun matchStartEpochMs(match: ScheduledEsportsMatch): Long = runCatching {
    Instant.parse(match.startTimeIso).toEpochMilli()
}.getOrElse { Long.MAX_VALUE }

private fun matchLabel(match: ScheduledEsportsMatch): String =
    match.teams.take(2).joinToString(" vs ") { teamCode(it) }

private fun teamCode(team: EsportsTeamRef?): String =
    team?.code?.ifBlank { team.name }?.ifBlank { "—" } ?: "—"
