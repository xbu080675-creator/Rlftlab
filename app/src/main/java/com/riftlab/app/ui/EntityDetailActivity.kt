package com.riftlab.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.EsportsTeamRef
import com.riftlab.app.data.MatchDetailRepository
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.ScheduleMatchPhase
import com.riftlab.app.data.ScheduledEsportsMatch
import com.riftlab.app.data.StandingsCenterStore
import com.riftlab.app.data.TeamDetailRepository
import com.riftlab.app.data.TournamentStandings
import java.time.Instant

private enum class MatchPane { DETAIL, OPERATIONS, REPLAY }

class EntityDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MatchSessionStore.ensureDataRunning()
        StandingsCenterStore.ensureRunning()
        setContent {
            RiftTheme {
                Surface(Modifier.fillMaxSize(), color = RiftBg, contentColor = RiftText) {
                    EntityDetailRoute(onClose = { finish() })
                }
            }
        }
    }

    @Composable
    private fun EntityDetailRoute(onClose: () -> Unit) {
        val center by MatchSessionStore.scheduleCenter.collectAsState()
        val standingsState by StandingsCenterStore.state.collectAsState()
        val initialMode = intent.getStringExtra(EntityDetailLauncher.EXTRA_MODE).orEmpty()
        val initialTeam = remember {
            EsportsTeamRef(
                id = intent.getStringExtra(EntityDetailLauncher.EXTRA_TEAM_ID).orEmpty(),
                code = intent.getStringExtra(EntityDetailLauncher.EXTRA_TEAM_CODE).orEmpty(),
                name = intent.getStringExtra(EntityDetailLauncher.EXTRA_TEAM_NAME).orEmpty(),
                slug = intent.getStringExtra(EntityDetailLauncher.EXTRA_TEAM_SLUG).orEmpty(),
                imageUrl = intent.getStringExtra(EntityDetailLauncher.EXTRA_TEAM_IMAGE).orEmpty()
            )
        }
        val requestedMatchId = remember { intent.getStringExtra(EntityDetailLauncher.EXTRA_MATCH_ID).orEmpty() }
        val requestedTeamA = remember { intent.getStringExtra(EntityDetailLauncher.EXTRA_TEAM_A).orEmpty() }
        val requestedTeamB = remember { intent.getStringExtra(EntityDetailLauncher.EXTRA_TEAM_B).orEmpty() }

        var activeTeam by remember { mutableStateOf<EsportsTeamRef?>(null) }
        var activeMatch by remember { mutableStateOf<ScheduledEsportsMatch?>(null) }
        var matchPane by remember(activeMatch?.matchId) { mutableStateOf(MatchPane.DETAIL) }

        LaunchedEffect(
            initialMode,
            center.matches,
            standingsState.standings,
            initialTeam,
            requestedMatchId,
            requestedTeamA,
            requestedTeamB
        ) {
            when (initialMode) {
                EntityDetailLauncher.MODE_TEAM -> {
                    val resolved = resolveBestTeam(
                        candidate = initialTeam,
                        matches = center.matches,
                        standings = standingsState.standings
                    )
                    activeTeam = resolved
                    if (resolved.code.isNotBlank() || resolved.name.isNotBlank()) {
                        TeamDetailRepository.open(resolved, center.matches)
                    }
                }
                EntityDetailLauncher.MODE_MATCH -> {
                    val resolved = resolveMatch(
                        matches = center.matches,
                        requestedId = requestedMatchId,
                        teamA = requestedTeamA,
                        teamB = requestedTeamB
                    )
                    if (resolved != null && activeMatch?.matchId != resolved.matchId) {
                        activeMatch = resolved
                        MatchDetailRepository.open(resolved)
                    }
                }
            }
        }

        val showingMatch = activeMatch != null
        val completedMatch = activeMatch?.let {
            MatchSessionStore.schedulePhase(it) == ScheduleMatchPhase.COMPLETED
        } == true
        val title = when {
            showingMatch -> activeMatch?.teams?.take(2)?.joinToString(" vs ") {
                it.code.ifBlank { it.name }
            }.orEmpty().ifBlank { "比赛详情" }
            activeTeam != null -> activeTeam?.code?.ifBlank { activeTeam?.name.orEmpty() }.orEmpty()
            initialMode == EntityDetailLauncher.MODE_MATCH -> "比赛详情"
            else -> initialTeam.code.ifBlank { initialTeam.name }.ifBlank { "战队详情" }
        }
        val subtitle = when {
            showingMatch -> activeMatch?.let { "${it.blockName.ifBlank { it.league }} · BO${it.bestOf}" }.orEmpty()
            else -> "战队人员 · 管理层 · 社交账号 · 近期场次"
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
            EntityDetailHeader(
                title = title,
                subtitle = subtitle,
                canGoBack = showingMatch && activeTeam != null,
                onBack = {
                    if (showingMatch && activeTeam != null) {
                        activeMatch = null
                    } else {
                        onClose()
                    }
                },
                onClose = onClose
            )
            Spacer(Modifier.height(12.dp))

            if (activeMatch != null) {
                MatchPaneTabs(matchPane, completedMatch) { matchPane = it }
                Spacer(Modifier.height(10.dp))
            }

            when {
                activeMatch != null && matchPane == MatchPane.OPERATIONS -> MatchOperationsContent()
                activeMatch != null && matchPane == MatchPane.REPLAY && completedMatch -> MatchReplayContent()
                activeMatch != null && matchPane == MatchPane.REPLAY -> MatchTimelineContent()
                activeMatch != null -> MatchDetailContent()
                activeTeam != null -> TeamDetailContent(
                    team = activeTeam!!,
                    matches = center.matches,
                    onMatchClick = { match ->
                        MatchDetailRepository.open(match)
                        activeMatch = match
                    }
                )
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("正在同步详细数据…", color = RiftMuted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun MatchPaneTabs(
    selected: MatchPane,
    completed: Boolean,
    onSelect: (MatchPane) -> Unit
) {
    val shape = CutCornerShape(topEnd = 10.dp, bottomStart = 8.dp)
    Row(
        Modifier.fillMaxWidth()
            .background(RiftPanelAlt, shape)
            .border(1.dp, RiftLine.copy(alpha = 0.65f), shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(
            MatchPane.DETAIL to "比赛详情",
            MatchPane.OPERATIONS to "运营数据",
            MatchPane.REPLAY to if (completed) "比赛回放" else "时间轴"
        ).forEach { (pane, label) ->
            val active = pane == selected
            Text(
                label,
                color = if (active) RiftCyan else RiftMuted,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
                    .background(
                        if (active) RiftPanel else androidx.compose.ui.graphics.Color.Transparent,
                        CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp)
                    )
                    .clickable { onSelect(pane) }
                    .padding(vertical = 9.dp)
            )
        }
    }
}

@Composable
private fun EntityDetailHeader(
    title: String,
    subtitle: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.clickable(onClick = onBack).padding(end = 10.dp, top = 10.dp, bottom = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = RiftMuted)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(title, color = RiftText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = RiftMuted, fontSize = 11.sp)
        }
        Box(Modifier.clickable(onClick = onClose).padding(10.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Close, null, tint = RiftMuted)
        }
    }
}

private fun resolveBestTeam(
    candidate: EsportsTeamRef,
    matches: List<ScheduledEsportsMatch>,
    standings: TournamentStandings?
): EsportsTeamRef {
    val fromSchedule = matches.flatMap { it.teams }
    val fromRankings = standings?.stages.orEmpty().flatMap { stage ->
        stage.sections.flatMap { section -> section.rankings.map { it.team } }
    }
    val fromBrackets = standings?.stages.orEmpty().flatMap { stage ->
        stage.sections.flatMap { section -> section.matches.flatMap { it.teams } }
    }
    val variants = (fromSchedule + fromRankings + fromBrackets)
        .filter { sameTeam(it, candidate) }
    return (variants + candidate).maxByOrNull(::teamQuality) ?: candidate
}

private fun resolveMatch(
    matches: List<ScheduledEsportsMatch>,
    requestedId: String,
    teamA: String,
    teamB: String
): ScheduledEsportsMatch? {
    if (requestedId.isNotBlank()) {
        matches.firstOrNull { it.matchId == requestedId || it.eventId == requestedId }?.let { return it }
    }
    val a = teamToken(teamA)
    val b = teamToken(teamB)
    if (a.isBlank() || b.isBlank()) return null
    return matches.asSequence()
        .filter { match ->
            val tokens = match.teams.map { teamToken(it.code.ifBlank { it.name }) }.toSet()
            a in tokens && b in tokens
        }
        .maxByOrNull(::matchEpoch)
}

private fun sameTeam(a: EsportsTeamRef, b: EsportsTeamRef): Boolean {
    if (a.id.isNotBlank() && b.id.isNotBlank() && a.id == b.id) return true
    val keysA = listOf(a.code, a.name, a.slug).map(::teamToken).filter { it.isNotBlank() }.toSet()
    val keysB = listOf(b.code, b.name, b.slug).map(::teamToken).filter { it.isNotBlank() }.toSet()
    return keysA.intersect(keysB).isNotEmpty()
}

/** Riot identity fields must beat a cosmetic-only Schedule record with a logo. */
private fun teamQuality(team: EsportsTeamRef): Int =
    (if (team.id.isNotBlank()) 16 else 0) +
        (if (team.slug.isNotBlank()) 8 else 0) +
        (if (team.imageUrl.isNotBlank()) 4 else 0) +
        (if (team.code.isNotBlank()) 2 else 0) +
        (if (team.name.isNotBlank()) 1 else 0)

private fun matchEpoch(match: ScheduledEsportsMatch): Long =
    runCatching { Instant.parse(match.startTimeIso).toEpochMilli() }.getOrDefault(0L)

private fun teamToken(value: String): String =
    value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
