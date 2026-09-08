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
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.EsportsPlayerRef
import com.riftlab.app.data.EsportsTeamRef
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.ScheduledEsportsMatch
import com.riftlab.app.data.TeamDetailRepository

private val TEAM_ROLES = listOf("TOP", "JUG", "MID", "BOT", "SUP")

@Composable
internal fun TeamDetailContent(
    team: EsportsTeamRef,
    matches: List<ScheduledEsportsMatch>,
    onMatchClick: (ScheduledEsportsMatch) -> Unit
) {
    val state by TeamDetailRepository.state.collectAsState()
    val details = state.details
    val roster = details?.players.orEmpty()
    val roleMap = roster.mapNotNull { player -> canonicalTeamRole(player.role)?.let { it to player } }.toMap()
    val teamMatches = matches
        .filter { match -> match.teams.any { sameTeam(it, team) } }
        .sortedByDescending { matchEpoch(it) }

    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(RiftPanel, CutCornerShape(topEnd = 16.dp, bottomStart = 10.dp))
                    .border(1.dp, RiftCyan.copy(alpha = 0.42f), CutCornerShape(topEnd = 16.dp, bottomStart = 10.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamLogo(
                        imageUrl = team.imageUrl,
                        code = team.code.ifBlank { team.name },
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(team.code.ifBlank { team.name }, color = RiftText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        if (team.name.isNotBlank() && team.name != team.code) {
                            Text(team.name, color = RiftMuted, fontSize = 10.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(state.status, color = RiftMuted, fontSize = 9.sp)
                    }
                }
            }
        }

        item { TeamSectionTitle("ROSTER / 战队名单") }
        if (state.loading && roster.isEmpty()) {
            item { TeamStatus("正在同步 Riot Teams 阵容…") }
        } else {
            TEAM_ROLES.forEach { role ->
                item(key = "roster-$role") {
                    TeamPlayerRow(
                        role = role,
                        player = roleMap[role],
                        team = team
                    )
                }
            }
            val extras = roster.filter { canonicalTeamRole(it.role) == null }
            if (extras.isNotEmpty()) {
                items(extras, key = { "extra-${it.id}-${it.summonerName}" }) { player ->
                    TeamPlayerRow(role = player.role.ifBlank { "SUB" }, player = player, team = team)
                }
            }
        }

        item { TeamSectionTitle("MATCHES / 近期赛程") }
        if (teamMatches.isEmpty()) {
            item { TeamStatus("当前赛事目录没有找到该战队比赛") }
        } else {
            items(teamMatches.take(12), key = { it.eventId.ifBlank { it.matchId } }) { match ->
                TeamMatchRow(team = team, match = match, onClick = { onMatchClick(match) })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TeamPlayerRow(role: String, player: EsportsPlayerRef?, team: EsportsTeamRef) {
    Row(
        Modifier.fillMaxWidth()
            .background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .border(1.dp, RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TeamLogo(
            imageUrl = team.imageUrl,
            code = team.code.ifBlank { team.name },
            modifier = Modifier.size(34.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(teamRoleLabel(role), color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
        Column(Modifier.weight(1f)) {
            Text(
                player?.summonerName ?: "数据缺失",
                color = if (player == null) RiftMuted else RiftText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(player?.role?.uppercase().orEmpty(), color = RiftMuted, fontSize = 8.sp)
        }
    }
}

@Composable
private fun TeamMatchRow(team: EsportsTeamRef, match: ScheduledEsportsMatch, onClick: () -> Unit) {
    val opponent = match.teams.firstOrNull { !sameTeam(it, team) }
    val phase = MatchSessionStore.schedulePhase(match)
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .border(1.dp, if (phase.name == "LIVE") RiftCyan.copy(alpha = 0.5f) else RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${team.code.ifBlank { team.name }} vs ${opponent?.code?.ifBlank { opponent.name } ?: "TBD"}",
                color = RiftText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${MatchSessionStore.scheduleDateKey(match)} · ${match.blockName.ifBlank { match.league }} · BO${match.bestOf}",
                color = RiftMuted,
                fontSize = 8.sp
            )
        }
        Text(
            when (phase.name) {
                "LIVE" -> "LIVE"
                "COMPLETED" -> MatchSessionStore.scheduleScore(match)
                else -> "待开"
            },
            color = if (phase.name == "LIVE") RiftCyan else RiftMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun TeamSectionTitle(value: String) {
    Text(value, color = RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TeamStatus(value: String) {
    Box(
        Modifier.fillMaxWidth()
            .background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(value, color = RiftMuted, fontSize = 10.sp)
    }
}

private fun canonicalTeamRole(raw: String): String? = when (raw.trim().uppercase()) {
    "TOP", "1" -> "TOP"
    "JUG", "JUNGLE", "JGL", "2" -> "JUG"
    "MID", "MIDDLE", "3" -> "MID"
    "BOT", "BOTTOM", "ADC", "4" -> "BOT"
    "SUP", "SUPPORT", "5" -> "SUP"
    else -> null
}

private fun teamRoleLabel(role: String): String = when (canonicalTeamRole(role) ?: role.uppercase()) {
    "TOP" -> "上"
    "JUG" -> "野"
    "MID" -> "中"
    "BOT" -> "下"
    "SUP" -> "辅"
    else -> "替"
}

private fun sameTeam(a: EsportsTeamRef, b: EsportsTeamRef): Boolean =
    (a.id.isNotBlank() && b.id.isNotBlank() && a.id == b.id) ||
        a.code.equals(b.code, ignoreCase = true) ||
        (a.slug.isNotBlank() && b.slug.isNotBlank() && a.slug.equals(b.slug, ignoreCase = true))

private fun matchEpoch(match: ScheduledEsportsMatch): Long =
    runCatching { java.time.Instant.parse(match.startTimeIso).toEpochMilli() }.getOrDefault(0L)
