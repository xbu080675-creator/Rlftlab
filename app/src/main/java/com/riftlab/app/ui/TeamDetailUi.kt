package com.riftlab.app.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.riftlab.app.data.EsportsAssetCache
import com.riftlab.app.data.EsportsPlayerRef
import com.riftlab.app.data.EsportsSocialLink
import com.riftlab.app.data.EsportsStaffRef
import com.riftlab.app.data.EsportsTeamRef
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.ScheduledEsportsMatch
import com.riftlab.app.data.TeamDetailRepository
import com.riftlab.app.data.TeamHistoryRef

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
    val snapshotStaff = details?.staff.orEmpty()
    val management = (details?.management.orEmpty() + snapshotStaff.filter(::isManagementStaff))
        .distinctBy { playerToken(it.name) to playerToken(it.role) }
    val coachingStaff = snapshotStaff.filterNot(::isManagementStaff)
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

        if (details?.socialLinks?.isNotEmpty() == true) {
            item { TeamSectionTitle("OFFICIAL SOCIALS / 官方账号") }
            item { SocialLinkRow(details.socialLinks) }
            item { TeamSourceNote(state.profileStatus) }
        }

        if (state.organizationSummary.isNotBlank()) {
            item { TeamSectionTitle("ORGANIZATION / 当前运营") }
            item { TeamStatus("运营主体：${state.organizationSummary}") }
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

        item { TeamSectionTitle("TEAM MANAGEMENT / 战队管理层") }
        if (management.isEmpty()) {
            item { TeamStatus(state.profileStatus) }
        } else {
            items(management, key = { "management-${it.name}-${it.role}" }) { staff ->
                TeamStaffRow(staff, management = true)
            }
            item { TeamSourceNote(state.profileStatus) }
        }

        if (state.history.isNotEmpty()) {
            item { TeamSectionTitle("RIFT LEGACY / 历史荣誉") }
            items(state.history, key = { "legacy-${it.name}-${it.formerRole}" }) { legacy ->
                TeamHistoryRow(legacy)
            }
            item { TeamSourceNote("RiftLab 历史档案称号，不代表俱乐部官方现任职务或官方授予头衔。") }
        }

        item { TeamSectionTitle("COACHING STAFF / 教练组") }
        if (coachingStaff.isEmpty()) {
            item { TeamStatus(state.staffStatus) }
        } else {
            items(coachingStaff, key = { "staff-${it.name}-${it.role}" }) { staff ->
                TeamStaffRow(staff, management = false)
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
            if (!player?.socialLinks.isNullOrEmpty()) {
                Spacer(Modifier.height(4.dp))
                SocialLinkRow(player!!.socialLinks, compact = true)
            }
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
private fun PersonAvatar(
    imageUrl: String,
    label: String,
    fallbackGlyph: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier.background(RiftPanelAlt, CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl.isBlank()) {
            Text(fallbackGlyph, color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        } else {
            SubcomposeAsyncImage(
                model = imageUrl,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = {
                    Text(fallbackGlyph, color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                },
                error = {
                    Text(fallbackGlyph, color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                },
                success = { SubcomposeAsyncImageContent() }
            )
        }
    }
}

@Composable
private fun TeamStaffRow(staff: EsportsStaffRef, management: Boolean) {
    Row(
        Modifier.fillMaxWidth()
            .background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .border(1.dp, RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PersonAvatar(
            imageUrl = staff.imageUrl,
            label = staff.name,
            fallbackGlyph = if (management) "管" else "教",
            modifier = Modifier.size(34.dp)
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(staff.name, color = RiftText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (staff.realName.isNotBlank()) Text(staff.realName, color = RiftMuted, fontSize = 8.sp, maxLines = 1)
            val former = staff.careerHistory.filterNot { it.current }.takeLast(2)
            if (former.isNotEmpty()) {
                Text(
                    "履历 · " + former.joinToString(" · ") { "${it.team} ${it.displayRole.ifBlank { staffRoleLabel(it.role) }}" },
                    color = RiftMuted,
                    fontSize = 7.sp,
                    maxLines = 1
                )
            }
            if (staff.socialLinks.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                SocialLinkRow(staff.socialLinks, compact = true)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(staff.displayRole.ifBlank { staffRoleLabel(staff.role) }, color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            if (staff.avatarSource.isNotBlank()) Text("头像 · ${staff.avatarSource}", color = RiftMuted, fontSize = 6.sp)
            Text(staff.source, color = RiftMuted, fontSize = 7.sp)
        }
    }
}

@Composable
private fun TeamHistoryRow(history: TeamHistoryRef) {
    Row(
        Modifier.fillMaxWidth()
            .background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .border(1.dp, RiftCyan.copy(alpha = 0.22f), CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PersonAvatar(
            imageUrl = history.imageUrl,
            label = history.name,
            fallbackGlyph = "誉",
            modifier = Modifier.size(34.dp)
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(history.name, color = RiftText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (history.realName.isNotBlank()) Text(history.realName, color = RiftMuted, fontSize = 8.sp, maxLines = 1)
            if (history.note.isNotBlank()) Text(history.note, color = RiftMuted, fontSize = 8.sp, maxLines = 2)
            val timeline = history.careerHistory.takeLast(2)
            if (timeline.isNotEmpty()) {
                Text(
                    "履历 · " + timeline.joinToString(" · ") { "${it.team} ${it.displayRole.ifBlank { staffRoleLabel(it.role) }}" },
                    color = RiftMuted,
                    fontSize = 7.sp,
                    maxLines = 1
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(history.honoraryTitle, color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Text("曾任${staffRoleLabel(history.formerRole)}", color = RiftMuted, fontSize = 8.sp)
        }
    }
}

@Composable
private fun SocialLinkRow(links: List<EsportsSocialLink>, compact: Boolean = false) {
    val context = LocalContext.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
        items(links.distinctBy { it.platform to it.url }, key = { "${it.platform}-${it.url}" }) { link ->
            Text(
                link.label.ifBlank { link.platform },
                color = RiftCyan,
                fontSize = if (compact) 7.sp else 9.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(RiftPanelAlt, CutCornerShape(topEnd = 5.dp, bottomStart = 4.dp))
                    .clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                    .padding(horizontal = if (compact) 5.dp else 8.dp, vertical = if (compact) 3.dp else 5.dp)
            )
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

private fun staffRoleLabel(role: String): String = when (playerToken(role)) {
    "HEADCOACH" -> "主教练"
    "ASSISTANTCOACH" -> "助理教练"
    "STRATEGICCOACH" -> "战术教练"
    "COACH" -> "教练"
    "ANALYST" -> "分析师"
    "MANAGER", "TEAMMANAGER" -> "经理"
    "GENERALMANAGER" -> "总经理"
    "ASSISTANTMANAGER" -> "助理经理"
    "DEPUTYMANAGER" -> "副经理"
    "LEADER" -> "领队"
    "SUPERVISOR" -> "监督"
    "DIRECTOR" -> "主管"
    "ESPORTSDIRECTOR" -> "赛训总监"
    "ESPORTSDIRECTORANDMANAGER" -> "赛训总监 / 经理"
    "MANAGINGDIRECTOR" -> "执行董事"
    "CHAIRMAN" -> "董事长"
    "VICEPRESIDENT" -> "副总裁"
    "OWNER" -> "负责人"
    "COOWNER" -> "联合负责人"
    "FOUNDER" -> "创始人"
    "FOUNDERANDCEO" -> "创始人 / CEO"
    "CEO", "CHIEFEXECUTIVEOFFICER" -> "CEO"
    "COO", "CHIEFOPERATINGOFFICER" -> "COO"
    "HEADOFESPORTS" -> "电竞负责人"
    "HEADOFLOL" -> "英雄联盟负责人"
    else -> role.replace('_', ' ')
}

private fun isManagementStaff(staff: EsportsStaffRef): Boolean {
    val key = playerToken(staff.role)
    return key.contains("MANAGER") || key in setOf(
        "LEADER", "SUPERVISOR", "DIRECTOR", "ESPORTSDIRECTOR", "MANAGINGDIRECTOR",
        "CHAIRMAN", "VICEPRESIDENT", "OWNER", "COOWNER", "FOUNDER", "FOUNDERANDCEO", "CEO",
        "CHIEFEXECUTIVEOFFICER", "COO", "CHIEFOPERATINGOFFICER", "HEADOFESPORTS", "HEADOFLOL"
    )
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