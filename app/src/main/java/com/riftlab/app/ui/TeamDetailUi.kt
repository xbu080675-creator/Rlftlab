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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.riftlab.app.data.EsportsAssetCache
import com.riftlab.app.data.EsportsPlayerRef
import com.riftlab.app.data.EsportsStaffRef
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
    val displayTeam = team.copy(
        id = details?.id?.ifBlank { team.id } ?: team.id,
        slug = details?.slug?.ifBlank { team.slug } ?: team.slug,
        code = details?.code?.ifBlank { team.code } ?: team.code,
        name = details?.name?.ifBlank { team.name } ?: team.name,
        imageUrl = state.imageUrl.ifBlank { details?.imageUrl.orEmpty() }.ifBlank { team.imageUrl }
    )
    val roster = details?.players.orEmpty()
    val starterTokens = state.starters.map(::playerToken).toSet()
    val hasConfirmedLineup = starterTokens.size >= 5
    val starters = if (hasConfirmedLineup) roster.filter { playerToken(it.summonerName) in starterTokens } else emptyList()
    val substitutes = if (hasConfirmedLineup) roster.filter { playerToken(it.summonerName) !in starterTokens } else emptyList()
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
                        imageUrl = displayTeam.imageUrl,
                        code = displayTeam.code.ifBlank { displayTeam.name },
                        modifier = Modifier.size(58.dp)
                    )
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(displayTeam.code.ifBlank { displayTeam.name }, color = RiftText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        if (displayTeam.name.isNotBlank() && displayTeam.name != displayTeam.code) {
                            Text(displayTeam.name, color = RiftMuted, fontSize = 9.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(state.status, color = RiftMuted, fontSize = 9.sp)
                    }
                }
            }
        }

        if (state.loading && roster.isEmpty()) {
            item { TeamSectionTitle("ROSTER / 战队名单") }
            item { TeamStatus("正在同步 Riot Teams 完整阵容…") }
        } else if (hasConfirmedLineup) {
            item { TeamSectionTitle("STARTING FIVE / 当前首发") }
            TEAM_ROLES.forEach { role ->
                val player = starters.firstOrNull { canonicalTeamRole(it.role) == role }
                item(key = "starter-$role-${player?.id.orEmpty()}") {
                    TeamPlayerRow(role = role, player = player, team = displayTeam, badge = "首发")
                }
            }

            if (substitutes.isNotEmpty()) {
                item { TeamSectionTitle("SUBSTITUTES / 替补") }
                items(
                    substitutes.sortedWith(compareBy({ roleOrder(canonicalTeamRole(it.role)) }, { it.summonerName })),
                    key = { "sub-${it.id}-${it.summonerName}" }
                ) { player ->
                    TeamPlayerRow(
                        role = canonicalTeamRole(player.role) ?: player.role.ifBlank { "SUB" },
                        player = player,
                        team = displayTeam,
                        badge = "替补"
                    )
                }
            }
            item { TeamSourceNote(state.lineupStatus) }
        } else {
            item { TeamSectionTitle("ACTIVE ROSTER / 现役名单") }
            TEAM_ROLES.forEach { role ->
                val rolePlayers = roster.filter { canonicalTeamRole(it.role) == role }
                if (rolePlayers.isEmpty()) {
                    item(key = "roster-missing-$role") {
                        TeamPlayerRow(role = role, player = null, team = displayTeam)
                    }
                } else {
                    items(rolePlayers, key = { "roster-$role-${it.id}-${it.summonerName}" }) { player ->
                        TeamPlayerRow(role = role, player = player, team = displayTeam)
                    }
                }
            }
            val extras = roster.filter { canonicalTeamRole(it.role) == null }
            items(extras, key = { "extra-${it.id}-${it.summonerName}" }) { player ->
                TeamPlayerRow(role = player.role.ifBlank { "SUB" }, player = player, team = displayTeam)
            }
            item { TeamSourceNote(state.lineupStatus) }
        }

        item { TeamSectionTitle("COACHING STAFF / 教练组") }
        if (details?.staff.isNullOrEmpty()) {
            item { TeamStatus(state.staffStatus) }
        } else {
            items(details!!.staff, key = { "staff-${it.name}-${it.role}" }) { staff ->
                TeamStaffRow(staff)
            }
        }

        item { TeamSectionTitle("MATCHES / 近期赛程") }
        if (teamMatches.isEmpty()) {
            item { TeamStatus("当前赛事目录没有找到该战队比赛") }
        } else {
            items(teamMatches.take(12), key = { it.eventId.ifBlank { it.matchId } }) { match ->
                TeamMatchRow(team = displayTeam, match = match, onClick = { onMatchClick(match) })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TeamPlayerRow(
    role: String,
    player: EsportsPlayerRef?,
    team: EsportsTeamRef,
    badge: String = ""
) {
    Row(
        Modifier.fillMaxWidth()
            .background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .border(1.dp, RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlayerAvatar(player = player, team = team, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(9.dp))
        Text(teamRoleLabel(role), color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
        Column(Modifier.weight(1f)) {
            Text(
                player?.summonerName ?: "数据缺失",
                color = if (player == null) RiftMuted else RiftText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            val realName = listOfNotNull(
                player?.firstName?.takeIf { it.isNotBlank() },
                player?.lastName?.takeIf { it.isNotBlank() }
            ).joinToString(" ")
            if (realName.isNotBlank()) Text(realName, color = RiftMuted, fontSize = 8.sp, maxLines = 1)
        }
        if (badge.isNotBlank()) {
            Text(
                badge,
                color = if (badge == "首发") RiftCyan else RiftMuted,
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(RiftPanelAlt, CutCornerShape(topEnd = 5.dp, bottomStart = 4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun PlayerAvatar(player: EsportsPlayerRef?, team: EsportsTeamRef, modifier: Modifier = Modifier) {
    val resolved = player?.let {
        EsportsAssetCache.normalize(it.imageUrl)
            .ifBlank { EsportsAssetCache.player(it.summonerName, team.code) }
    }.orEmpty()

    Box(
        modifier.background(RiftPanelAlt, CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (resolved.isBlank()) {
            TeamLogo(
                imageUrl = team.imageUrl,
                code = team.code.ifBlank { team.name },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            SubcomposeAsyncImage(
                model = resolved,
                contentDescription = player?.summonerName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = {
                    TeamLogo(team.imageUrl, team.code.ifBlank { team.name }, Modifier.fillMaxSize())
                },
                error = {
                    TeamLogo(team.imageUrl, team.code.ifBlank { team.name }, Modifier.fillMaxSize())
                },
                success = { SubcomposeAsyncImageContent() }
            )
        }
    }
}

@Composable
private fun TeamStaffRow(staff: EsportsStaffRef) {
    Row(
        Modifier.fillMaxWidth()
            .background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .border(1.dp, RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).background(RiftPanelAlt, CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("教", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(staff.name, color = RiftText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (staff.realName.isNotBlank()) Text(staff.realName, color = RiftMuted, fontSize = 8.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(staffRoleLabel(staff.role), color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Text(staff.source, color = RiftMuted, fontSize = 7.sp)
        }
    }
}

@Composable
private fun TeamSourceNote(value: String) {
    if (value.isBlank()) return
    Text(value, color = RiftMuted, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 2.dp))
}

@Composable
private fun TeamMatchRow(team: EsportsTeamRef, match: ScheduledEsportsMatch, onClick: () -> Unit) {
    val phase = MatchSessionStore.schedulePhase(match)
    val left = match.teams.getOrNull(0)
    val right = match.teams.getOrNull(1)
    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .border(1.dp, if (phase.name == "LIVE") RiftCyan.copy(alpha = 0.5f) else RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .padding(11.dp)
    ) {
        Text(
            "${MatchSessionStore.scheduleDateKey(match)} · ${match.blockName.ifBlank { match.league }} · BO${match.bestOf}",
            color = RiftMuted,
            fontSize = 8.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(7.dp))
        TeamMatchupVisual(
            leftCode = left?.code?.ifBlank { left.name } ?: "TBD",
            leftImageUrl = left?.imageUrl.orEmpty(),
            rightCode = right?.code?.ifBlank { right.name } ?: "TBD",
            rightImageUrl = right?.imageUrl.orEmpty(),
            centerText = when (phase.name) {
                "LIVE" -> "LIVE"
                "COMPLETED" -> MatchSessionStore.scheduleScore(match)
                else -> "VS"
            },
            logoSize = 38.dp,
            centerFontSize = 15.sp,
            teamNameFontSize = 9.sp
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
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(value, color = RiftMuted, fontSize = 10.sp)
    }
}

private fun canonicalTeamRole(raw: String): String? {
    val key = raw.trim().uppercase().replace(Regex("[^A-Z0-9]+"), "")
    return when (key) {
        "TOP", "TOPLANE", "1" -> "TOP"
        "JUN", "JUG", "JGL", "JUNG", "JUNGLE", "JUNGLER", "JUNGLEPOSITION", "2" -> "JUG"
        "MID", "MIDDLE", "MIDLANE", "3" -> "MID"
        "BOT", "BOTTOM", "ADC", "AD", "BOTTOMLANE", "4" -> "BOT"
        "SUP", "SUPPORT", "SUPP", "5" -> "SUP"
        else -> null
    }
}

private fun teamRoleLabel(role: String): String = when (canonicalTeamRole(role) ?: role.uppercase()) {
    "TOP" -> "上"
    "JUG" -> "野"
    "MID" -> "中"
    "BOT" -> "下"
    "SUP" -> "辅"
    else -> "替"
}

private fun staffRoleLabel(role: String): String = when (role.uppercase()) {
    "HEAD_COACH" -> "主教练"
    "ASSISTANT_COACH" -> "助理教练"
    "STRATEGIC_COACH" -> "战术教练"
    "COACH" -> "教练"
    "ANALYST" -> "分析师"
    "MANAGER" -> "经理"
    "SUPERVISOR" -> "监督"
    else -> role.replace('_', ' ')
}

private fun roleOrder(role: String?): Int = when (role) {
    "TOP" -> 0
    "JUG" -> 1
    "MID" -> 2
    "BOT" -> 3
    "SUP" -> 4
    else -> 9
}

private fun playerToken(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

private fun sameTeam(a: EsportsTeamRef, b: EsportsTeamRef): Boolean =
    (a.id.isNotBlank() && b.id.isNotBlank() && a.id == b.id) ||
        a.code.equals(b.code, ignoreCase = true) ||
        (a.slug.isNotBlank() && b.slug.isNotBlank() && a.slug.equals(b.slug, ignoreCase = true))

private fun matchEpoch(match: ScheduledEsportsMatch): Long =
    runCatching { java.time.Instant.parse(match.startTimeIso).toEpochMilli() }.getOrDefault(0L)