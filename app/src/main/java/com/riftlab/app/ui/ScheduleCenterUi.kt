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
import androidx.compose.foundation.lazy.LazyRow
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
import com.riftlab.app.data.LplChampionshipPoints2026
import com.riftlab.app.data.LplWorldsStatus
import com.riftlab.app.data.MatchDetailRepository
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.ScheduleActivityState
import com.riftlab.app.data.ScheduleMatchPhase
import com.riftlab.app.data.ScheduledEsportsMatch
import com.riftlab.app.data.StandingBracketMatch
import com.riftlab.app.data.StandingTeam
import com.riftlab.app.data.StandingsCenterStore
import com.riftlab.app.data.TeamDetailRepository
import com.riftlab.app.data.TeamAssetCatalog
import com.riftlab.app.data.TournamentStandings
import com.riftlab.app.data.TournamentDrawSlot
import com.riftlab.app.data.TournamentGovernanceProvider
import com.riftlab.app.data.TournamentEditionArchiveRecord
import com.riftlab.app.data.TournamentEditionArchiveStore
import com.riftlab.app.data.TournamentResearchProvider
import com.riftlab.app.data.Worlds2026QualifiedTeams
import com.riftlab.app.data.ResearchEvidence
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val FALLBACK_STAGE_GAP_DAYS = 21L

private enum class EventCenterTab(val label: String) {
    RESEARCH("研究"),
    SCHEDULE("赛程"),
    STANDINGS("排名"),
    POINTS("积分"),
    BRACKET("淘汰赛"),
    RULES("规则"),
    DRAW("抽签"),
    TEAMS("战队")
}

private enum class ScheduleDirectorySection(val label: String) {
    REGIONAL("联赛"),
    WORLDS("全球总决赛"),
    INTERNATIONAL("国际赛")
}

private enum class InternationalCompetitionMenu(val label: String) {
    WORLDS("全球总决赛"),
    DEMACIA_GLOBAL("德杯国际邀请赛"),
    WSCI("WSCI"),
    WSCL("WSCL"),
    FIRST_STAND("全球先锋赛"),
    MSI("季中冠军赛"),
    AMERICAS_CUP("美洲杯"),
    EWC("EWC"),
    EMEA_MASTERS("EMEA 大师赛")
}

private data class ScheduleCompetitionBucket(
    val key: String,
    val title: String,
    val matches: List<ScheduledEsportsMatch>,
    val firstEpochMs: Long,
    val tournamentId: String? = null,
    val tournament: EsportsTournamentRef? = null,
    val researchOnly: Boolean = false
)

@Composable
fun RiftLabRoot() {
    MatchSessionStore.ensureDataRunning()
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
    LaunchedEffect(Unit) { StandingsCenterStore.ensureRunning() }
    val center by MatchSessionStore.scheduleCenter.collectAsState()
    val standingsCenter by StandingsCenterStore.state.collectAsState()
    val archiveCenter by TournamentEditionArchiveStore.state.collectAsState()
    val buckets = remember(center.matches, standingsCenter.tournaments, archiveCenter.editions) {
        buildCompetitionBuckets(center.matches, standingsCenter.tournaments, archiveCenter.editions)
    }
    var selectedBucketKey by remember { mutableStateOf<String?>(null) }
    var selectedDetailMatch by remember { mutableStateOf<ScheduledEsportsMatch?>(null) }
    var selectedTeam by remember { mutableStateOf<EsportsTeamRef?>(null) }
    var tabIndex by remember { mutableIntStateOf(0) }
    val selectedBucket = buckets.firstOrNull { it.key == selectedBucketKey }
    val selectedArchive = archiveCenter.editions.firstOrNull { it.tournamentId == selectedBucket?.tournamentId }
    val selectedStandings = standingsCenter.standings
        ?.takeIf { it.tournamentId == selectedBucket?.tournamentId }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = RiftBg, contentColor = RiftText) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
                ScheduleCenterHeader(
                    title = selectedDetailMatch?.let(::matchFullLabel)
                        ?: selectedTeam?.let(::teamCode)
                        ?: selectedBucket?.title
                        ?: "英雄联盟赛事",
                    subtitle = selectedDetailMatch?.let { match ->
                        "${match.blockName.ifBlank { match.league }} · BO${match.bestOf} · ${MatchSessionStore.scheduleDateTimeLabel(match)}"
                    } ?: selectedTeam?.let { team ->
                        "战队资料 · ${team.name.ifBlank { teamCode(team) }}"
                    } ?: if (selectedBucket == null) {
                        "联赛 / 国际赛 · 赛区订阅已同步"
                    } else {
                        if (selectedBucket.researchOnly) "年度赛事研究 · 版本 / 规则 / 抽签 / 赛程" else competitionRange(selectedBucket.matches)
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
                    selectedDetailMatch != null -> MatchCenterDetailSurface(selectedDetailMatch!!)
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
                            EventCenterTab.RESEARCH -> ResearchView(
                                bucket = selectedBucket,
                                standings = selectedStandings,
                                onOpenSchedule = { tabIndex = EventCenterTab.SCHEDULE.ordinal }
                            )
                            EventCenterTab.SCHEDULE -> CompetitionMatches(
                                bucket = selectedBucket,
                                selectedMatchId = center.selectedMatch?.matchId,
                                onMatchClick = { match ->
                                    MatchDetailRepository.open(match)
                                    selectedDetailMatch = match
                                }
                            )
                            EventCenterTab.STANDINGS -> StandingsView(selectedStandings)
                            EventCenterTab.POINTS -> ChampionshipPointsView(selectedBucket)
                            EventCenterTab.BRACKET -> BracketView(
                                standings = selectedStandings,
                                scheduleMatches = selectedBucket.matches,
                                bucket = selectedBucket
                            )
                            EventCenterTab.RULES -> RulesView(selectedBucket, selectedStandings)
                            EventCenterTab.DRAW -> DrawView(selectedBucket, selectedStandings)
                            EventCenterTab.TEAMS -> TeamsView(
                                standings = selectedStandings,
                                scheduleMatches = leagueWideTeamMatches(selectedBucket, center.matches),
                                knownParticipantCodes = (selectedArchive?.participantTeamCodes.orEmpty() + officialParticipantCodesForBucket(selectedBucket)).distinct(),
                                onTeamClick = { team ->
                                    TeamDetailRepository.open(team, center.matches)
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
    val currentActivity = current?.takeIf { hasCurrent }?.let(MatchSessionStore::scheduleActivity)
    Column(
        Modifier.fillMaxWidth()
            .background(RiftPanel, CutCornerShape(topEnd = 16.dp, bottomStart = 10.dp))
            .border(1.dp, if (hasCurrent) RiftCyan.copy(alpha = 0.5f) else RiftLine, CutCornerShape(topEnd = 16.dp, bottomStart = 10.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    bucket.researchOnly -> "年度研究档案"
                    hasCurrent -> currentActivity?.let(::scheduleActivityText) ?: "进行中"
                    hasNext -> "当前赛段"
                    bucket.matches.isNotEmpty() && bucket.matches.all { MatchSessionStore.schedulePhase(it) == ScheduleMatchPhase.COMPLETED } -> "已结束"
                    else -> "赛事"
                },
                color = if (hasCurrent) RiftCyan else RiftText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
            Spacer(Modifier.weight(1f))
            Text(if (bucket.researchOnly) "RESEARCH" else "${bucket.matches.size} 场", color = RiftMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(5.dp))
        Text(if (bucket.researchOnly) "赛程待可信源发布 · 研究档案先行" else competitionRange(bucket.matches), color = RiftMuted, fontSize = 10.sp)
        if (hasCurrent && current != null) {
            Spacer(Modifier.height(5.dp))
            Text(
                "${currentActivity?.let(::scheduleActivityText) ?: "进行中"} · ${matchLabel(current)} · ${MatchSessionStore.scheduleTimingNote(current)}",
                color = RiftCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
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
    LazyRow(
        Modifier.fillMaxWidth()
            .background(RiftPanelAlt, CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(EventCenterTab.entries.size) { index ->
            val tab = EventCenterTab.entries[index]
            Box(
                Modifier.width(72.dp)
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
    val subscribed by LeagueSubscriptionStore.subscribed.collectAsState()
    val internationalBuckets = buckets.filter { internationalCompetitionKind(it) != null }
    val regional = buckets.filter { internationalCompetitionKind(it) == null }
        .groupBy(::bucketLeagueLabel)
        .toList()
        .sortedWith(
            compareBy<Pair<String, List<ScheduleCompetitionBucket>>> {
                if (leagueSubscriptionKey(it.first) in subscribed) 0 else 1
            }.thenBy { regionalLeagueOrder(it.first) }.thenBy { it.first }
        )
    val activeBucket = buckets.firstOrNull { bucket ->
        bucket.matches.any { it.matchId == currentMatchId || it.matchId == nextMatchId }
    }
    val activeIntl = activeBucket?.let(::internationalCompetitionKind)
    var rootIndex by remember { mutableIntStateOf(0) }
    val preferredLeague = regional.firstOrNull { leagueSubscriptionKey(it.first) in subscribed }?.first
        ?: activeBucket?.takeIf { internationalCompetitionKind(it) == null }?.let(::bucketLeagueLabel)
        ?: regional.firstOrNull()?.first.orEmpty()
    var selectedLeague by remember(regional.map { it.first }, subscribed, preferredLeague) {
        mutableStateOf(preferredLeague)
    }
    var selectedInternational by remember(activeIntl) {
        mutableStateOf(activeIntl ?: InternationalCompetitionMenu.WORLDS)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .background(RiftPanelAlt, CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("联赛", "国际赛").forEachIndexed { index, label ->
                Box(
                    Modifier.weight(1f)
                        .clickable { rootIndex = index }
                        .background(if (rootIndex == index) RiftPanel else androidx.compose.ui.graphics.Color.Transparent, CutCornerShape(topEnd = 8.dp, bottomStart = 6.dp))
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = if (rootIndex == index) RiftCyan else RiftMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(9.dp))

        if (rootIndex == 0) {
            if (regional.isEmpty()) {
                EmptyData("等待 Riot 联赛赛程数据")
                return@Column
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(regional, key = { it.first }) { (league, _) ->
                    val selected = league == selectedLeague
                    val subscribedLeague = leagueSubscriptionKey(league) in subscribed
                    val shape = CutCornerShape(topEnd = 8.dp, bottomStart = 6.dp)
                    Text(
                        if (subscribedLeague) "★ $league" else league,
                        color = if (selected) RiftCyan else RiftMuted,
                        fontSize = 9.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.clickable { selectedLeague = league }
                            .background(if (selected) RiftPanel else RiftPanelAlt, shape)
                            .border(1.dp, if (selected) RiftCyan.copy(alpha = 0.45f) else RiftLine, shape)
                            .padding(horizontal = 11.dp, vertical = 8.dp)
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            val selectedBuckets = regional.firstOrNull { it.first == selectedLeague }?.second.orEmpty()
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(selectedBuckets.sortedByDescending { it.firstEpochMs }, key = { it.key }) { bucket ->
                    CompetitionDirectoryCard(bucket, currentMatchId, nextMatchId) { onSelect(bucket) }
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(InternationalCompetitionMenu.entries, key = { it.name }) { menu ->
                    val selected = menu == selectedInternational
                    val shape = CutCornerShape(topEnd = 8.dp, bottomStart = 6.dp)
                    Text(
                        menu.label,
                        color = if (selected) RiftCyan else RiftMuted,
                        fontSize = 9.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.clickable { selectedInternational = menu }
                            .background(if (selected) RiftPanel else RiftPanelAlt, shape)
                            .border(1.dp, if (selected) RiftCyan.copy(alpha = 0.45f) else RiftLine, shape)
                            .padding(horizontal = 11.dp, vertical = 8.dp)
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            val selectedBuckets = internationalBuckets.filter { internationalCompetitionKind(it) == selectedInternational }
            if (selectedBuckets.isEmpty()) {
                InternationalEventPlaceholder(selectedInternational)
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(selectedBuckets.sortedByDescending { it.firstEpochMs }, key = { it.key }) { bucket ->
                        CompetitionDirectoryCard(bucket, currentMatchId, nextMatchId) { onSelect(bucket) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InternationalEventPlaceholder(menu: InternationalCompetitionMenu) {
    val (title, meta, detail) = when (menu) {
        InternationalCompetitionMenu.WORLDS -> Triple(
            "2026 全球总决赛 · WORLDS 2026",
            "年度重头赛事 · 固定一级优先入口",
            "2026 全球总决赛位置永久保留在国际赛事首位。参赛队、赛程、开赛时间、场馆和直播信息只使用 Riot / 官方赛事源动态填充；数据未发布时不伪造。"
        )
        InternationalCompetitionMenu.DEMACIA_GLOBAL -> Triple(
            "2026 德杯国际邀请赛",
            "最新国际赛事 · 固定入口",
            "德杯国际邀请赛独立归入国际赛事，不归入 LPL 常规联赛。参赛队、分组、赛程和直播信息在可信赛事源可用后自动填充。"
        )
        InternationalCompetitionMenu.WSCI -> Triple(
            "WSCI",
            "国际赛事 · 独立赛事入口",
            "WSCI 作为独立国际赛事建档。赛程、比分和战队来自已标注 Provider；缺少 Standings、Seed 或晋级来源时保持未知。"
        )
        InternationalCompetitionMenu.WSCL -> Triple(
            "WSCL",
            "国际赛事 · 当前赛事入口保留",
            "WSCL 独立归入国际赛事。赛程、比分和战队只在可信源返回后展示；不会因为参赛队曾属于次级联赛而错误归类回联赛目录。"
        )
        else -> Triple(
            menu.label,
            "国际赛事 · 固定入口",
            "当前分页暂无可核实赛程；RiftLab 保留赛事入口，待 Riot / 官方赛事源返回数据后自动填充。"
        )
    }
    Column(
        Modifier.fillMaxWidth()
            .background(RiftPanel, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
            .border(1.dp, if (menu == InternationalCompetitionMenu.WORLDS) RiftCyan.copy(alpha = 0.55f) else RiftLine, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
            .padding(14.dp)
    ) {
        Text(if (menu == InternationalCompetitionMenu.WORLDS) "SEASON FINALE · MAIN EVENT" else "INTERNATIONAL EVENT", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(title, color = RiftText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(meta, color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(detail, color = RiftMuted, fontSize = 9.sp, lineHeight = 14.sp)
    }
}

@Composable
private fun DirectorySectionHeader(title: String) {
    Text(
        title,
        color = RiftCyan,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun DirectoryLeagueHeader(league: String) {
    Text(
        league,
        color = RiftText,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 5.dp, start = 4.dp)
    )
}

@Composable
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
    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .background(RiftPanel, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
            .border(1.dp, if (hasCurrent) RiftCyan.copy(alpha = 0.55f) else RiftLine, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(bucket.title, color = if (hasCurrent) RiftCyan else RiftText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(if (bucket.researchOnly) "年度研究档案 · 赛程待同步" else competitionRange(bucket.matches), color = RiftMuted, fontSize = 10.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                when {
                    hasCurrent -> Text(
                        currentMatch?.let { scheduleActivityText(MatchSessionStore.scheduleActivity(it)) } ?: "进行中",
                        color = RiftCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    hasNext -> Text("NEXT", color = RiftText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(if (bucket.researchOnly) "VERSION · RULES · DRAW · SCHEDULE" else "${bucket.matches.size} 场 · 已结束 $completed", color = RiftMuted, fontSize = 9.sp)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, null, tint = RiftMuted)
        }
    }
}

private fun internationalCompetitionKind(bucket: ScheduleCompetitionBucket): InternationalCompetitionMenu? {
    val sample = bucket.matches.firstOrNull()
    val identity = listOf(
        bucket.tournament?.leagueSlug.orEmpty(), bucket.tournament?.leagueName.orEmpty(),
        sample?.leagueSlug.orEmpty(), sample?.league.orEmpty(), bucket.title
    ).joinToString(" ").lowercase()
    return when {
        identity.contains("demacia cup") || identity.contains("德玛西亚杯") || identity.contains("德杯国际邀请赛") || identity.contains("demacia global invitational") -> InternationalCompetitionMenu.DEMACIA_GLOBAL
        Regex("(^|[^a-z])wsci([^a-z]|$)").containsMatchIn(identity) -> InternationalCompetitionMenu.WSCI
        Regex("(^|[^a-z])wscl([^a-z]|$)").containsMatchIn(identity) -> InternationalCompetitionMenu.WSCL
        identity.contains("first stand") || identity.contains("first-stand") || identity.contains("first_stand") || identity.contains("全球先锋赛") -> InternationalCompetitionMenu.FIRST_STAND
        identity.contains("mid-season") || Regex("(^|[^a-z])msi([^a-z]|$)").containsMatchIn(identity) || identity.contains("季中冠军赛") -> InternationalCompetitionMenu.MSI
        identity.contains("americas cup") || identity.contains("america cup") || identity.contains("美洲杯") -> InternationalCompetitionMenu.AMERICAS_CUP
        identity.contains("worlds") || identity.contains("world championship") || identity.contains("全球总决赛") -> InternationalCompetitionMenu.WORLDS
        identity.contains("esports world cup") || Regex("(^|[^a-z])ewc([^a-z]|$)").containsMatchIn(identity) -> InternationalCompetitionMenu.EWC
        identity.contains("emea masters") || identity.contains("emea 大师赛") -> InternationalCompetitionMenu.EMEA_MASTERS
        else -> null
    }
}

private fun leagueWideTeamMatches(bucket: ScheduleCompetitionBucket, allMatches: List<ScheduledEsportsMatch>): List<ScheduledEsportsMatch> {
    if (internationalCompetitionKind(bucket) != null) return bucket.matches
    val sample = bucket.matches.firstOrNull() ?: return bucket.matches
    return allMatches.filter { match ->
        when {
            sample.leagueId.isNotBlank() && match.leagueId.isNotBlank() -> sample.leagueId == match.leagueId
            sample.leagueSlug.isNotBlank() && match.leagueSlug.isNotBlank() -> normalizeLeagueToken(sample.leagueSlug) == normalizeLeagueToken(match.leagueSlug)
            else -> normalizeLeagueToken(sample.league) == normalizeLeagueToken(match.league)
        }
    }.ifEmpty { bucket.matches }
}

private fun bucketSection(bucket: ScheduleCompetitionBucket): ScheduleDirectorySection {
    val sample = bucket.matches.firstOrNull()
    val identity = listOf(
        bucket.tournament?.leagueSlug.orEmpty(),
        bucket.tournament?.leagueName.orEmpty(),
        sample?.leagueSlug.orEmpty(),
        sample?.league.orEmpty(),
        bucket.title
    ).joinToString(" ").lowercase()
    return when (internationalCompetitionKind(bucket)) {
        InternationalCompetitionMenu.WORLDS -> ScheduleDirectorySection.WORLDS
        null -> ScheduleDirectorySection.REGIONAL
        else -> ScheduleDirectorySection.INTERNATIONAL
    }
}

private fun bucketLeagueLabel(bucket: ScheduleCompetitionBucket): String {
    val sample = bucket.matches.firstOrNull()
    val slug = bucket.tournament?.leagueSlug.orEmpty().ifBlank { sample?.leagueSlug.orEmpty() }.lowercase()
    val name = bucket.tournament?.leagueName.orEmpty().ifBlank { sample?.league.orEmpty() }
    return when {
        slug == "lpl" -> "LPL"
        slug == "lck" -> "LCK"
        slug == "lec" -> "LEC"
        slug == "lcs" -> "LCS"
        slug.startsWith("lta") -> "LTA"
        slug == "lcp" -> "LCP"
        slug.startsWith("cblol") -> "CBLOL"
        slug == "pcs" -> "PCS"
        slug == "vcs" -> "VCS"
        slug == "ljl" -> "LJL"
        slug == "lla" -> "LLA"
        slug == "lrn" -> "LRN"
        slug == "lrs" -> "LRS"
        slug == "fls" -> "FLS"
        slug.contains("lck") && (slug.contains("challenger") || slug.contains("cl")) -> "LCK Challengers"
        slug.contains("lcp") && slug.contains("wild") -> "LCP Wild Card"
        slug.contains("development") || slug == "ldl" -> "LDL"
        slug == "nlc" -> "NLC"
        slug == "lit" -> "LIT"
        slug == "tcl" -> "TCL"
        name.isNotBlank() -> name
        else -> slug.uppercase().ifBlank { "LoL Esports" }
    }
}

private fun regionalLeagueOrder(label: String): Int = when (label.uppercase()) {
    "LPL" -> 0
    "LCK" -> 1
    "LEC" -> 2
    "LCS" -> 3
    "LTA" -> 4
    "LCP" -> 5
    "CBLOL" -> 6
    "PCS" -> 7
    "VCS" -> 8
    "LJL" -> 9
    "LLA" -> 10
    "LRN" -> 11
    "LRS" -> 12
    else -> 99
}

@Composable
private fun CompetitionMatches(
    bucket: ScheduleCompetitionBucket,
    selectedMatchId: String?,
    onMatchClick: (ScheduledEsportsMatch) -> Unit
) {
    if (bucket.matches.isEmpty()) {
        EmptyData("该年度赛事赛程尚未由可信源发布；研究档案会先保存版本、规则、抽签和赛事更新，赛程发布后自动并入同一年度页面。")
        return
    }
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
    val phase = MatchSessionStore.scheduleActivity(match)
    val active = phase == ScheduleActivityState.GAME_LIVE ||
        phase == ScheduleActivityState.EVENT_LIVE ||
        phase == ScheduleActivityState.BETWEEN_GAMES
    val left = match.teams.getOrNull(0)
    val right = match.teams.getOrNull(1)
    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .background(RiftPanel, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
            .border(1.dp, if (selected || active) RiftCyan.copy(alpha = 0.48f) else RiftLine, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
            .padding(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                scheduleActivityText(phase),
                color = if (active) RiftCyan else RiftMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text("BO${match.bestOf}", color = RiftMuted, fontSize = 9.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        TeamMatchupVisual(
            leftCode = matchTeamLabel(match, left),
            leftImageUrl = left?.imageUrl.orEmpty(),
            rightCode = matchTeamLabel(match, right),
            rightImageUrl = right?.imageUrl.orEmpty(),
            centerText = if (phase == ScheduleActivityState.COMPLETED || match.teams.any { it.gameWins > 0 }) MatchSessionStore.scheduleScore(match) else "VS",
            centerSubtext = MatchSessionStore.scheduleTimingNote(match),
            logoSize = 44.dp,
            centerFontSize = 18.sp,
            teamNameFontSize = 8.sp
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
        EmptyData("当前可信数据源尚未提供 Standings 排名数据")
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
                    TableText("组内积分", 0.8f, RiftMuted, FontWeight.Medium, end = true)
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
        TableText(row.points?.toString() ?: "—", 0.8f, RiftText, FontWeight.SemiBold, end = true)
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
private fun ChampionshipPointsView(bucket: ScheduleCompetitionBucket) {
    val seasonYear = bucket.matches.mapNotNull(::matchStartDate).firstOrNull()?.year
        ?: bucket.tournament?.startDate?.take(4)?.toIntOrNull()
    val league = bucketLeagueLabel(bucket).uppercase()
    if (league != "LPL") {
        val message = when (league) {
            "LCK" -> "LCK 的 Worlds 资格由最终阶段 / 季后赛名次直接产生，不使用 LPL 式年度 Championship Points 表。请查看“规则”和资格路径。"
            "LCS" -> "LCS 的 Worlds 资格按 Split 3 最终阶段名次直接产生；这里不显示一张不存在的 LPL 式年度积分表。"
            "CBLOL" -> "CBLOL 的 Worlds 资格按 Split 3 最终名次产生；这里不把“不使用 Championship Points”误报成“积分未同步”。"
            "LCP" -> "LCP 采用混合资格体系：部分席位由季后赛名次直通，另有席位由 Championship Points 直接决定；当前队伍总分只在可信完整数据可重算/官方总表可用时展示。"
            "LEC" -> "LEC Worlds 席位按最终阶段资格规则结算；官方第三席描述仍需更明确映射时保持待确认，不套用 LPL 年度积分。"
            else -> "$league 当前资格机制不等同于 LPL Championship Points；请以该赛区官方规则 / 资格路径为准。"
        }
        EmptyData(message)
        return
    }
    if (seasonYear != LplChampionshipPoints2026.season) {
        EmptyData("${seasonYear ?: "该"} 赛季年度积分尚未接入；不会显示 2026 数据作为替代。")
        return
    }
    val rows = LplChampionshipPoints2026.rows
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth()
                .background(RiftPanel, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
                .border(1.dp, RiftCyan.copy(alpha = 0.34f), CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
                .padding(14.dp)
        ) {
            Text(
                "${LplChampionshipPoints2026.season} 全球总决赛实时积分",
                color = RiftText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "更新至 ${LplChampionshipPoints2026.updatedThrough}",
                color = RiftCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                LplChampionshipPoints2026.note,
                color = RiftMuted,
                fontSize = 9.sp,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(LplChampionshipPoints2026.sourceLabel, color = RiftMuted, fontSize = 8.sp)
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
            TableText("#", 0.42f, RiftMuted, FontWeight.Medium)
            TableText("战队", 0.92f, RiftMuted, FontWeight.Medium)
            TableText("S1", 0.52f, RiftMuted, FontWeight.Medium, end = true)
            TableText("S2", 0.52f, RiftMuted, FontWeight.Medium, end = true)
            TableText("S3保底", 0.76f, RiftMuted, FontWeight.Medium, end = true)
            TableText("总分", 0.70f, RiftMuted, FontWeight.Medium, end = true)
        }

        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(rows, key = { "${it.rank}-${it.teamCode}" }) { row ->
                val statusColor = when (row.status) {
                    LplWorldsStatus.WORLDS_LOCKED -> RiftCyan
                    LplWorldsStatus.REGIONAL_LOCKED -> RiftText
                    LplWorldsStatus.ELIMINATED -> RiftMuted
                }
                Column(
                    Modifier.fillMaxWidth()
                        .background(RiftPanel, CutCornerShape(topEnd = 8.dp, bottomStart = 5.dp))
                        .border(
                            1.dp,
                            if (row.status == LplWorldsStatus.WORLDS_LOCKED) RiftCyan.copy(alpha = 0.38f) else RiftLine,
                            CutCornerShape(topEnd = 8.dp, bottomStart = 5.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TableText(row.rank.toString(), 0.42f, RiftMuted, FontWeight.SemiBold)
                        TableText(row.teamCode, 0.92f, RiftText, FontWeight.Bold)
                        TableText(row.split1.toString(), 0.52f, RiftText, FontWeight.Normal, end = true)
                        TableText(row.split2.toString(), 0.52f, RiftText, FontWeight.Normal, end = true)
                        TableText(row.split3Floor.toString(), 0.76f, RiftText, FontWeight.Normal, end = true)
                        TableText(row.total.toString(), 0.70f, RiftCyan, FontWeight.Bold, end = true)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        row.status.label,
                        color = statusColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 34.dp)
                    )
                }
            }
            item {
                Text(
                    "注：本页为年度 Championship Points；“排名”页中的组内积分属于当前 Tournament Standings，两者不是同一个积分体系。",
                    color = RiftMuted,
                    fontSize = 9.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun BracketView(
    standings: TournamentStandings?,
    scheduleMatches: List<ScheduledEsportsMatch>,
    bucket: ScheduleCompetitionBucket
) {
    val governance = remember(bucket.key, standings?.tournamentId, standings?.stages, scheduleMatches) {
        TournamentGovernanceProvider.resolve(bucket.tournament, bucket.title, scheduleMatches, standings)
    }
    val stages = standings?.stages.orEmpty().filter { stage ->
        stage.sections.any { it.matches.isNotEmpty() } &&
            (stage.slug.contains("playoff", true) || stage.slug.contains("regional", true))
    }
    if (stages.isEmpty()) {
        EmptyData("当前可信数据源尚未提供淘汰赛 / Bracket 数据")
        return
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        stages.forEach { stage ->
            item(key = "stage-${stage.id}-${stage.slug}") {
                Text(translateStageName(stage.name), color = RiftText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            stage.sections.forEach { section ->
                val matches = section.matches
                val regionalStage = stage.slug.contains("regional", true) || stage.name.contains("regional", true) || stage.name.contains("资格", true)
                val slotByMatch = if (regionalStage) governance.draw.slots.associateBy { it.bracketMatchId } else emptyMap()
                items(matches.chunked(2), key = { chunk -> chunk.joinToString("-") { it.id } }) { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { bracketMatch ->
                            BracketMatchCard(
                                bracketMatch = bracketMatch,
                                scheduleMatches = scheduleMatches,
                                modifier = Modifier.weight(1f),
                                verifiedOverride = slotByMatch[bracketMatch.id]
                            )
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
    modifier: Modifier,
    verifiedOverride: TournamentDrawSlot? = null
) {
    val schedule = scheduleMatches.firstOrNull { it.matchId == bracketMatch.id || it.eventId == bracketMatch.id }
    val left = bracketMatch.teams.getOrNull(0)
    val right = bracketMatch.teams.getOrNull(1)
    val leftCode = verifiedOverride?.left?.takeIf { it.isNotBlank() } ?: teamCode(left)
    val rightCode = verifiedOverride?.right?.takeIf { it.isNotBlank() } ?: teamCode(right)
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
        if (verifiedOverride != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "官方确认 · ${verifiedOverride.scheduledAt.ifBlank { verifiedOverride.status }}",
                color = RiftCyan,
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold
            )
        } else if (bracketMatch.previousMatchIds.isNotEmpty()) {
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
private fun ResearchView(
    bucket: ScheduleCompetitionBucket,
    standings: TournamentStandings?,
    onOpenSchedule: () -> Unit
) {
    val governance = remember(bucket.key, standings?.tournamentId, standings?.stages, bucket.matches) {
        TournamentGovernanceProvider.resolve(bucket.tournament, bucket.title, bucket.matches, standings)
    }
    val research = remember(bucket.key, standings?.tournamentId, standings?.stages, bucket.matches, governance) {
        TournamentResearchProvider.resolve(
            tournament = bucket.tournament,
            competitionTitle = bucket.title,
            matches = bucket.matches,
            standings = standings,
            governance = governance
        )
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(RiftPanel, CutCornerShape(topEnd = 16.dp, bottomStart = 10.dp))
                    .border(1.dp, RiftCyan.copy(alpha = 0.45f), CutCornerShape(topEnd = 16.dp, bottomStart = 10.dp))
                    .padding(14.dp)
            ) {
                Text("TOURNAMENT RESEARCH / 赛事研究档案", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(research.title, color = RiftText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text("${research.year} · ${research.scope} · ${research.family}", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("同一赛事按年份独立归档。版本、赛事更新、规则、抽签、赛程、排名和运营历史都挂在这一年度 Edition 下，不用跨页面找。", color = RiftMuted, fontSize = 9.sp, lineHeight = 14.sp)
                Spacer(Modifier.height(5.dp))
                Text("SOURCE · ${research.sourceSummary}", color = RiftMuted, fontSize = 8.sp)
            }
        }

        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(RiftPanel, CutCornerShape(topEnd = 11.dp, bottomStart = 7.dp))
                    .border(1.dp, if (research.version.evidence == ResearchEvidence.PENDING) RiftLine else RiftCyan.copy(alpha = 0.30f), CutCornerShape(topEnd = 11.dp, bottomStart = 7.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("VERSION / 赛事版本", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(researchEvidenceLabel(research.version.evidence), color = researchEvidenceColor(research.version.evidence), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text(research.version.versionLabel, color = RiftText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(research.version.detail, color = RiftMuted, fontSize = 9.sp, lineHeight = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("SOURCE · ${research.version.source}", color = RiftMuted, fontSize = 8.sp)
            }
        }

        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(RiftPanel, CutCornerShape(topEnd = 11.dp, bottomStart = 7.dp))
                    .padding(12.dp)
            ) {
                Text("ARCHIVE COVERAGE / 年度档案覆盖", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                research.coverage.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.label, color = RiftText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(item.state, color = if (item.state.contains("待")) RiftMuted else RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(item.detail, color = RiftMuted, fontSize = 8.sp)
                    if (index != research.coverage.lastIndex) Spacer(Modifier.height(7.dp))
                }
            }
        }

        item {
            Text("EVENT UPDATE / 赛事更新", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp, start = 2.dp))
        }
        items(research.updates, key = { "${it.category}-${it.title}-${it.source}" }) { update ->
            Column(
                Modifier.fillMaxWidth()
                    .background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
                    .border(1.dp, if (update.evidence == ResearchEvidence.VERIFIED) RiftCyan.copy(alpha = 0.30f) else RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(update.category, color = RiftCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(researchEvidenceLabel(update.evidence), color = researchEvidenceColor(update.evidence), fontSize = 8.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(update.title, color = RiftText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(update.detail, color = RiftMuted, fontSize = 9.sp, lineHeight = 14.sp)
                if (update.effectiveAt.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(update.effectiveAt, color = RiftMuted, fontSize = 8.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text("SOURCE · ${update.source}", color = RiftMuted, fontSize = 8.sp)
            }
        }

        item {
            Column(
                Modifier.fillMaxWidth()
                    .clickable(onClick = onOpenSchedule)
                    .background(RiftPanelAlt, CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
                    .border(1.dp, RiftCyan.copy(alpha = 0.35f), CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
                    .padding(13.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SCHEDULE / 年度赛程", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(if (bucket.matches.isEmpty()) "赛程待可信源发布" else "${bucket.matches.size} 场 · ${competitionRange(bucket.matches)}", color = RiftText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        Text("进入该年度赛事的完整赛程；后续比赛详情、运营历史和回放仍沿用同一个赛事 Edition。", color = RiftMuted, fontSize = 8.sp, lineHeight = 13.sp)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = RiftCyan)
                }
            }
        }
    }
}

private fun researchEvidenceLabel(value: ResearchEvidence): String = when (value) {
    ResearchEvidence.VERIFIED -> "已核实"
    ResearchEvidence.PROVIDER -> "数据源确认"
    ResearchEvidence.PENDING -> "待同步"
}

@Composable
private fun researchEvidenceColor(value: ResearchEvidence) = when (value) {
    ResearchEvidence.VERIFIED -> RiftCyan
    ResearchEvidence.PROVIDER -> RiftText
    ResearchEvidence.PENDING -> RiftMuted
}

@Composable
private fun RulesView(bucket: ScheduleCompetitionBucket, standings: TournamentStandings?) {
    val governance = remember(bucket.key, standings?.tournamentId, standings?.stages, bucket.matches) {
        TournamentGovernanceProvider.resolve(bucket.tournament, bucket.title, bucket.matches, standings)
    }
    val snapshot = governance.rules
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(RiftPanel, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
                    .border(1.dp, RiftCyan.copy(alpha = 0.35f), CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
                    .padding(14.dp)
            ) {
                Text("TOURNAMENT RULEBOOK / 赛事规则", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(snapshot.title, color = RiftText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(snapshot.sourceSummary, color = RiftMuted, fontSize = 9.sp)
            }
        }
        items(snapshot.items, key = { "${it.title}-${it.source}" }) { rule ->
            Column(
                Modifier.fillMaxWidth()
                    .background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
                    .border(1.dp, if (rule.verified) RiftCyan.copy(alpha = 0.30f) else RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.title, color = RiftText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(if (rule.verified) "已核实" else "结构推导", color = if (rule.verified) RiftCyan else RiftMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(5.dp))
                Text(rule.detail, color = RiftMuted, fontSize = 10.sp, lineHeight = 15.sp)
                Spacer(Modifier.height(5.dp))
                Text("SOURCE · ${rule.source}", color = RiftMuted, fontSize = 8.sp)
            }
        }
    }
}

@Composable
private fun DrawView(bucket: ScheduleCompetitionBucket, standings: TournamentStandings?) {
    val governance = remember(bucket.key, standings?.tournamentId, standings?.stages, bucket.matches) {
        TournamentGovernanceProvider.resolve(bucket.tournament, bucket.title, bucket.matches, standings)
    }
    val snapshot = governance.draw
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(RiftPanel, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
                    .border(1.dp, RiftCyan.copy(alpha = 0.35f), CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
                    .padding(14.dp)
            ) {
                Text("DRAW / SLOT ASSIGNMENT · 抽签与签位", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(snapshot.title, color = RiftText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(snapshot.note, color = RiftMuted, fontSize = 9.sp, lineHeight = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("SOURCE · ${snapshot.sourceSummary}", color = RiftMuted, fontSize = 8.sp)
            }
        }
        if (snapshot.slots.isEmpty()) {
            item { EmptyData("官方抽签 / 签位尚未同步；不会根据排名自行猜测。") }
        } else {
            items(snapshot.slots, key = { "${it.label}-${it.bracketMatchId}-${it.left}-${it.right}" }) { slot ->
                Column(
                    Modifier.fillMaxWidth()
                        .background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
                        .border(1.dp, if (slot.verified) RiftCyan.copy(alpha = 0.28f) else RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(slot.label, color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(if (slot.verified) "已确认" else "待确认", color = if (slot.verified) RiftCyan else RiftMuted, fontSize = 8.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("${slot.left}  VS  ${slot.right}", color = RiftText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (slot.scheduledAt.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(slot.scheduledAt, color = RiftMuted, fontSize = 9.sp)
                    }
                    if (slot.status.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(slot.status, color = RiftMuted, fontSize = 9.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text("SOURCE · ${slot.source}", color = RiftMuted, fontSize = 8.sp)
                }
            }
        }
    }
}

@Composable
private fun TeamsView(
    standings: TournamentStandings?,
    scheduleMatches: List<ScheduledEsportsMatch>,
    knownParticipantCodes: List<String> = emptyList(),
    onTeamClick: (EsportsTeamRef) -> Unit
) {
    val teams = remember(standings?.tournamentId, standings?.stages, scheduleMatches, knownParticipantCodes) {
        val fromSchedule = scheduleMatches.flatMap { it.teams }
        val fromRankings = standings?.stages.orEmpty().flatMap { stage ->
            stage.sections.flatMap { section -> section.rankings.map { it.team } }
        }
        val fromMatches = standings?.stages.orEmpty().flatMap { stage ->
            stage.sections.flatMap { section -> section.matches.flatMap { it.teams } }
        }
        val knownVariants = knownParticipantCodes
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() && it != "TBD" && it != "—" }
            .distinct()
            .map { code -> EsportsTeamRef(id = "", code = code, name = code) }
        (fromSchedule + fromRankings + fromMatches + knownVariants)
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
    tournaments: List<EsportsTournamentRef>,
    archivedEditions: List<TournamentEditionArchiveRecord> = emptyList()
): List<ScheduleCompetitionBucket> {
    // Tournament existence comes from the Tournament Directory / durable archive. A temporarily
    // empty schedule only means that the match list is still syncing (or has not been published);
    // it must never delete the event itself from the directory.
    val official = tournaments.map { tournament ->
        val tournamentMatches = matches.filter { match ->
            sameLeague(match, tournament) &&
                matchStartDate(match)?.let { StandingsCenterStore.containsDate(tournament, it) } == true
        }
        ScheduleCompetitionBucket(
            key = tournament.id,
            title = StandingsCenterStore.displayTournamentName(tournament),
            matches = tournamentMatches.sortedBy(::matchStartEpochMs),
            firstEpochMs = tournamentMatches.minOfOrNull(::matchStartEpochMs) ?: tournamentStartEpochMs(tournament),
            tournamentId = tournament.id,
            tournament = tournament,
            researchOnly = tournamentMatches.isEmpty()
        )
    }

    val officialIds = official.mapNotNull { it.tournamentId }.toSet()
    val archived = archivedEditions
        .filter { it.tournamentId.isNotBlank() && it.tournamentId !in officialIds }
        .map { edition ->
            val tournament = EsportsTournamentRef(
                id = edition.tournamentId,
                slug = edition.slug,
                startDate = edition.startDate,
                endDate = edition.endDate,
                leagueId = edition.leagueId,
                leagueSlug = edition.leagueSlug,
                leagueName = edition.leagueName
            )
            val editionMatches = matches.filter { match ->
                sameLeague(match, tournament) &&
                    matchStartDate(match)?.let { StandingsCenterStore.containsDate(tournament, it) } == true
            }
            ScheduleCompetitionBucket(
                key = edition.tournamentId,
                title = edition.displayName.ifBlank { StandingsCenterStore.displayTournamentName(tournament) },
                matches = editionMatches.sortedBy(::matchStartEpochMs),
                firstEpochMs = editionMatches.minOfOrNull(::matchStartEpochMs) ?: tournamentStartEpochMs(tournament),
                tournamentId = edition.tournamentId,
                tournament = tournament,
                researchOnly = editionMatches.isEmpty()
            )
        }

    val directory = official + archived
    val used = directory.flatMap { it.matches }.map(::scheduleIdentity).toSet()
    val fallback = buildFallbackBuckets(matches.filterNot { scheduleIdentity(it) in used })
    val base = (directory + fallback)
        .distinctBy { it.key }
        .sortedBy { it.firstEpochMs }
    return addAnnualResearchPlaceholders(base)
        .distinctBy { it.key }
        .sortedBy { it.firstEpochMs }
}

private fun tournamentStartEpochMs(tournament: EsportsTournamentRef): Long = runCatching {
    LocalDate.parse(tournament.startDate.take(10))
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}.getOrElse { Long.MAX_VALUE }

private fun officialParticipantCodesForBucket(bucket: ScheduleCompetitionBucket): List<String> {
    val year = researchEditionYear(bucket)
    val isWorlds = internationalCompetitionKind(bucket) == InternationalCompetitionMenu.WORLDS
    return if (year == 2026 && isWorlds) Worlds2026QualifiedTeams.teams.map { it.code } else emptyList()
}

private fun addAnnualResearchPlaceholders(
    base: List<ScheduleCompetitionBucket>
): List<ScheduleCompetitionBucket> {
    val year = LocalDate.now().year
    val required = buildList {
        add(InternationalCompetitionMenu.WORLDS to "$year 全球总决赛")
        if (year == 2026) add(InternationalCompetitionMenu.DEMACIA_GLOBAL to "2026 德杯国际邀请赛")
    }
    val output = base.toMutableList()
    required.forEach { (kind, title) ->
        val exists = output.any { bucket ->
            internationalCompetitionKind(bucket) == kind && researchEditionYear(bucket) == year
        }
        if (!exists) {
            output += ScheduleCompetitionBucket(
                key = "research-${kind.name.lowercase()}-$year",
                title = title,
                matches = emptyList(),
                firstEpochMs = runCatching { Instant.parse("$year-01-01T00:00:00Z").toEpochMilli() }.getOrDefault(Long.MAX_VALUE),
                researchOnly = true
            )
        }
    }
    return output
}

private fun researchEditionYear(bucket: ScheduleCompetitionBucket): Int? {
    bucket.tournament?.startDate?.take(4)?.toIntOrNull()?.let { return it }
    bucket.matches.firstOrNull()?.let(::matchStartDate)?.year?.let { return it }
    return Regex("""(?:19|20)\d{2}""").find(bucket.title)?.value?.toIntOrNull()
}

private fun sameLeague(match: ScheduledEsportsMatch, tournament: EsportsTournamentRef): Boolean {
    if (match.leagueId.isNotBlank() && tournament.leagueId.isNotBlank()) {
        return match.leagueId == tournament.leagueId
    }
    val matchSlug = normalizeLeagueToken(match.leagueSlug)
    val tournamentSlug = normalizeLeagueToken(tournament.leagueSlug)
    if (matchSlug.isNotBlank() && tournamentSlug.isNotBlank()) return matchSlug == tournamentSlug
    val matchName = normalizeLeagueToken(match.league)
    val tournamentName = normalizeLeagueToken(tournament.leagueName)
    return matchName.isNotBlank() && tournamentName.isNotBlank() && matchName == tournamentName
}

private fun buildFallbackBuckets(matches: List<ScheduledEsportsMatch>): List<ScheduleCompetitionBucket> {
    val result = mutableListOf<ScheduleCompetitionBucket>()
    matches
        .mapNotNull { match -> matchStartDate(match)?.let { Triple(competitionLeagueKey(match), match, it) } }
        .groupBy { it.first }
        .forEach { (leagueKey, leagueEntries) ->
            leagueEntries
                .sortedBy { it.third }
                .groupBy { it.third.year }
                .forEach { (year, yearEntries) ->
                    var index = 1
                    var current = mutableListOf<Pair<ScheduledEsportsMatch, LocalDate>>()
                    var previous: LocalDate? = null
                    fun flush() {
                        if (current.isEmpty()) return
                        val stageMatches = current.map { it.first }
                        result += ScheduleCompetitionBucket(
                            key = "$year-$leagueKey-stage-$index",
                            title = fallbackBucketTitle(stageMatches, year, index),
                            matches = stageMatches,
                            firstEpochMs = stageMatches.minOfOrNull(::matchStartEpochMs) ?: Long.MAX_VALUE
                        )
                        index += 1
                        current = mutableListOf()
                    }
                    yearEntries.forEach { entry ->
                        val date = entry.third
                        val gap = previous?.let { ChronoUnit.DAYS.between(it, date) } ?: 0L
                        if (current.isNotEmpty() && gap >= FALLBACK_STAGE_GAP_DAYS) flush()
                        current += entry.second to date
                        previous = date
                    }
                    flush()
                }
        }
    return result.sortedBy { it.firstEpochMs }
}

private fun fallbackBucketTitle(matches: List<ScheduledEsportsMatch>, year: Int, index: Int): String {
    val sample = matches.firstOrNull()
    val identity = listOf(sample?.leagueSlug.orEmpty(), sample?.league.orEmpty()).joinToString(" ").lowercase()
    return when {
        identity.contains("worlds") || identity.contains("world championship") -> "$year 全球总决赛"
        identity.contains("mid-season") || Regex("(^|[^a-z])msi([^a-z]|$)").containsMatchIn(identity) -> "$year 季中冠军赛"
        identity.contains("first stand") || identity.contains("first-stand") || identity.contains("first_stand") -> "$year First Stand"
        identity.contains("esports world cup") || Regex("(^|[^a-z])ewc([^a-z]|$)").containsMatchIn(identity) -> "$year Esports World Cup"
        else -> "$year ${sample?.league?.ifBlank { sample.leagueSlug.uppercase() } ?: "LoL Esports"} ${stageName(index)}"
    }
}

private fun competitionLeagueKey(match: ScheduledEsportsMatch): String =
    normalizeLeagueToken(match.leagueId.ifBlank { match.leagueSlug.ifBlank { match.league } }).ifBlank { "lol-esports" }

private fun normalizeLeagueToken(value: String): String = value
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')

private fun scheduleIdentity(match: ScheduledEsportsMatch): String =
    match.eventId.ifBlank { match.matchId }.ifBlank { "${match.leagueId}:${match.startTimeIso}:${match.teams.joinToString { it.id.ifBlank { it.code } }}" }

private fun stageName(index: Int): String = when (index) {
    1 -> "第一赛段"
    2 -> "第二赛段"
    3 -> "第三赛段"
    else -> "第${index}赛段"
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

private fun matchFullLabel(match: ScheduledEsportsMatch): String =
    match.teams.take(2).joinToString(" vs ") { matchTeamLabel(match, it) }

private fun matchLabel(match: ScheduledEsportsMatch): String =
    match.teams.take(2).joinToString(" vs ") { matchTeamLabel(match, it) }

private fun teamCode(team: EsportsTeamRef?): String =
    team?.code?.ifBlank { team.name }?.ifBlank { "—" } ?: "—"

private fun matchTeamLabel(match: ScheduledEsportsMatch, team: EsportsTeamRef?): String {
    val code = teamCode(team)
    if (!isDevelopmentLeague(match)) return code
    val name = team?.name?.trim().orEmpty()
    return when {
        name.isNotBlank() -> name
        code.equals("T1A", ignoreCase = true) -> "T1 Esports Academy"
        else -> code
    }
}

private fun isDevelopmentLeague(match: ScheduledEsportsMatch): Boolean {
    val identity = "${match.leagueSlug} ${match.league}".lowercase()
    return identity.contains("challenger") || identity.contains("academy") || identity.contains("development") || identity.contains("youth") || identity.contains("lck-cl")
}
