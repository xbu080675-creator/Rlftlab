from pathlib import Path
import re

root = Path('.')

component = '''package com.riftlab.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Global visual standard for every team-vs-team score surface in RiftLab.
 * Team logos are the primary identity; team codes are secondary labels only.
 */
@Composable
internal fun TeamMatchupVisual(
    leftCode: String,
    rightCode: String,
    centerText: String,
    modifier: Modifier = Modifier,
    leftImageUrl: String = "",
    rightImageUrl: String = "",
    leftSubtext: String? = null,
    rightSubtext: String? = null,
    centerSubtext: String? = null,
    logoSize: Dp = 62.dp,
    centerFontSize: TextUnit = 24.sp,
    teamNameFontSize: TextUnit = 10.sp,
    centerAccent: Boolean = true
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TeamIdentityVisual(
            code = leftCode,
            imageUrl = leftImageUrl,
            subtext = leftSubtext,
            logoSize = logoSize,
            teamNameFontSize = teamNameFontSize,
            modifier = Modifier.weight(1f)
        )
        Column(
            modifier = Modifier.widthIn(min = 66.dp, max = 104.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                centerText,
                color = if (centerAccent) RiftCyan else RiftText,
                fontSize = centerFontSize,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            centerSubtext?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, color = RiftMuted, fontSize = 8.sp, textAlign = TextAlign.Center)
            }
        }
        TeamIdentityVisual(
            code = rightCode,
            imageUrl = rightImageUrl,
            subtext = rightSubtext,
            logoSize = logoSize,
            teamNameFontSize = teamNameFontSize,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TeamIdentityVisual(
    code: String,
    imageUrl: String,
    subtext: String?,
    logoSize: Dp,
    teamNameFontSize: TextUnit,
    modifier: Modifier
) {
    val label = code.ifBlank { "—" }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TeamLogo(imageUrl = imageUrl, code = label, modifier = Modifier.size(logoSize))
        Spacer(Modifier.height(5.dp))
        Text(
            label,
            color = RiftText,
            fontSize = teamNameFontSize,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        subtext?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, color = RiftMuted, fontSize = 8.sp, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}
'''

(root / 'app/src/main/java/com/riftlab/app/ui/TeamMatchupVisual.kt').write_text(component, encoding='utf-8')


def load(path: str) -> str:
    return (root / path).read_text(encoding='utf-8')


def save(path: str, text: str) -> None:
    (root / path).write_text(text, encoding='utf-8')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'missing exact block: {label}')
    return text.replace(old, new, 1)


def replace_fun(text: str, name: str, next_name: str, new_body: str) -> str:
    pattern = rf'@Composable\nprivate fun {re.escape(name)}\(.*?\n\}}\n\n@Composable\nprivate fun {re.escape(next_name)}'
    match = re.search(pattern, text, flags=re.S)
    if not match:
        raise SystemExit(f'missing function block: {name} -> {next_name}')
    return text[:match.start()] + new_body.rstrip() + '\n\n@Composable\nprivate fun ' + next_name + text[match.end():]


# Match detail: every series/game score uses team marks as the primary identity.
p = 'app/src/main/java/com/riftlab/app/ui/MatchDetailUi.kt'
t = load(p)
t = replace_fun(t, 'MatchHeroPanel', 'DetailGameTabs', '''@Composable
private fun MatchHeroPanel(
    match: ScheduledEsportsMatch,
    scoreA: Int?,
    scoreB: Int?,
    phase: ScheduleMatchPhase
) {
    val left = match.teams.getOrNull(0)
    val right = match.teams.getOrNull(1)
    DetailPanel(accent = phase == ScheduleMatchPhase.LIVE) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (phase) {
                    ScheduleMatchPhase.LIVE -> "LIVE"
                    ScheduleMatchPhase.UPCOMING -> "UPCOMING"
                    ScheduleMatchPhase.COMPLETED -> "FINAL"
                },
                color = if (phase == ScheduleMatchPhase.LIVE) RiftCyan else RiftMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text("BO${match.bestOf}", color = RiftMuted, fontSize = 9.sp)
        }
        Spacer(Modifier.height(10.dp))
        TeamMatchupVisual(
            leftCode = left?.code?.ifBlank { left.name } ?: "—",
            leftImageUrl = left?.imageUrl.orEmpty(),
            rightCode = right?.code?.ifBlank { right.name } ?: "—",
            rightImageUrl = right?.imageUrl.orEmpty(),
            centerText = if (scoreA != null && scoreB != null) "$scoreA : $scoreB" else "VS",
            logoSize = 68.dp,
            centerFontSize = 25.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${match.blockName.ifBlank { match.league }} · ${MatchSessionStore.scheduleTimingNote(match)}",
            color = RiftMuted,
            fontSize = 9.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}''')
t = replace_fun(t, 'SeriesOverview', 'GameSummaryCard', '''@Composable
private fun SeriesOverview(games: List<LiveSnapshot>) {
    DetailPanel(accent = true) {
        games.sortedBy { it.game }.forEachIndexed { index, game ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            Text("G${game.game}", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            TeamMatchupVisual(
                leftCode = game.blue,
                rightCode = game.red,
                centerText = "${game.blueKills} : ${game.redKills}",
                centerSubtext = MatchSessionStore.formatTime(game.elapsedSeconds),
                logoSize = 34.dp,
                centerFontSize = 15.sp,
                teamNameFontSize = 9.sp
            )
        }
    }
}''')
t = replace_fun(t, 'GameSummaryCard', 'GameDetailCard', '''@Composable
private fun GameSummaryCard(game: LiveSnapshot) {
    DetailPanel(accent = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE · G${game.game}", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(MatchSessionStore.formatTime(game.elapsedSeconds), color = RiftMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(10.dp))
        TeamMatchupVisual(
            leftCode = game.blue,
            rightCode = game.red,
            centerText = "${game.blueKills} : ${game.redKills}",
            leftSubtext = "GOLD ${gold(game.blueGold)}",
            rightSubtext = "GOLD ${gold(game.redGold)}",
            logoSize = 48.dp,
            centerFontSize = 20.sp
        )
    }
}''')
t = replace_fun(t, 'GameDetailCard', 'VisualPlayerRow', '''@Composable
private fun GameDetailCard(game: LiveSnapshot, blueTeam: EsportsTeamRef?, redTeam: EsportsTeamRef?) {
    DetailPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GAME ${game.game}", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(MatchSessionStore.formatTime(game.elapsedSeconds), color = RiftMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(10.dp))
        TeamMatchupVisual(
            leftCode = game.blue,
            leftImageUrl = blueTeam?.imageUrl.orEmpty(),
            rightCode = game.red,
            rightImageUrl = redTeam?.imageUrl.orEmpty(),
            centerText = "${game.blueKills} : ${game.redKills}",
            leftSubtext = gold(game.blueGold),
            rightSubtext = gold(game.redGold),
            logoSize = 50.dp,
            centerFontSize = 20.sp
        )
        Spacer(Modifier.height(8.dp))
        StatStrip(
            "${gold(game.blueGold)} : ${gold(game.redGold)}",
            "T ${game.blueTowers}:${game.redTowers}",
            "D ${game.blueDragons}:${game.redDragons}",
            "B ${game.blueBarons}:${game.redBarons}"
        )
        Spacer(Modifier.height(12.dp))

        val leftMapped = roleMap(game.bluePlayers)
        val rightMapped = roleMap(game.redPlayers)
        DETAIL_ROLES.forEach { role ->
            VisualPlayerRow(
                role = role,
                left = leftMapped[role],
                right = rightMapped[role],
                leftTeamName = game.blue,
                rightTeamName = game.red,
                leftTeam = blueTeam,
                rightTeam = redTeam
            )
        }
        Spacer(Modifier.height(5.dp))
        SourceLabel(game.source)
    }
}''')
save(p, t)


# Schedule center: schedule cards and elimination bracket cards use the same component.
p = 'app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt'
t = load(p)
t = replace_fun(t, 'ScheduleMatchCard', 'StandingsView', '''@Composable
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
            logoSize = 48.dp,
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
}''')
pattern = r'@Composable\nprivate fun BracketMatchCard\(.*?\n\}\n\n@Composable\nprivate fun BracketTeamLine'
match = re.search(pattern, t, flags=re.S)
if not match:
    raise SystemExit('missing BracketMatchCard')
new_bracket = '''@Composable
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
            logoSize = 34.dp,
            centerFontSize = 14.sp,
            teamNameFontSize = 8.sp
        )
        if (bracketMatch.previousMatchIds.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("承接上一轮", color = RiftMuted, fontSize = 8.sp)
        }
    }
}'''
t = t[:match.start()] + new_bracket + '\n\n@Composable\nprivate fun BracketTeamLine' + t[match.end():]
save(p, t)


# Team detail recent matches.
p = 'app/src/main/java/com/riftlab/app/ui/TeamDetailUi.kt'
t = load(p)
t = replace_fun(t, 'TeamMatchRow', 'TeamSectionTitle', '''@Composable
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
            logoSize = 40.dp,
            centerFontSize = 15.sp,
            teamNameFontSize = 9.sp
        )
    }
}''')
save(p, t)


# Main PRE/LIVE/POST pages.
p = 'app/src/main/java/com/riftlab/app/ui/RiftLabApp.kt'
t = load(p)
if 'import com.riftlab.app.data.ScheduledEsportsMatch\n' not in t:
    t = t.replace('import com.riftlab.app.data.PlayerCard\n', 'import com.riftlab.app.data.PlayerCard\nimport com.riftlab.app.data.ScheduledEsportsMatch\n', 1)
t = replace_once(
    t,
    'item { MatchHero(data.blue, data.red, data.startTime, "${data.league} · ${data.stage}") }',
    'item { MatchHero(data.blue, data.red, data.startTime, "${data.league} · ${data.stage}", target) }',
    'PreScreen MatchHero call'
)
t = replace_once(
    t,
    '    val scheduled by MatchSessionStore.preMatchFlow.collectAsState()\n',
    '    val scheduled by MatchSessionStore.preMatchFlow.collectAsState()\n    val target by MatchSessionStore.targetMatch.collectAsState()\n',
    'LiveScreen target state'
)
old_live = '''                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamGold(displayBlue, if (isLive) snapshot.blueGold else 0, Alignment.Start)
                    AnimatedContent(snapshot.goldDiff, label = "goldDiff") { diff ->
                        Text(
                            if (isLive) formatGoldDiff(diff) else "—",
                            color = if (diff >= 0) RiftCyan else RiftRed,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    TeamGold(displayRed, if (isLive) snapshot.redGold else 0, Alignment.End)
                }'''
new_live = '''                val leftAsset = target?.teams?.firstOrNull { teamLabelMatches(displayBlue, it) } ?: target?.teams?.getOrNull(0)
                val rightAsset = target?.teams?.firstOrNull { teamLabelMatches(displayRed, it) } ?: target?.teams?.getOrNull(1)
                TeamMatchupVisual(
                    leftCode = displayBlue,
                    leftImageUrl = leftAsset?.imageUrl.orEmpty(),
                    rightCode = displayRed,
                    rightImageUrl = rightAsset?.imageUrl.orEmpty(),
                    centerText = if (isLive) formatGoldDiff(snapshot.goldDiff) else "VS",
                    leftSubtext = if (isLive && snapshot.blueGold > 0) "%.1fK".format(snapshot.blueGold / 1000f) else "—",
                    rightSubtext = if (isLive && snapshot.redGold > 0) "%.1fK".format(snapshot.redGold / 1000f) else "—",
                    centerSubtext = if (isLive) "GOLD DIFF" else null,
                    logoSize = 54.dp,
                    centerFontSize = 24.sp
                )'''
t = replace_once(t, old_live, new_live, 'LiveScreen matchup')
old_series = '''                    Text(
                        "${resolved.teamA}  ${resolved.scoreA} : ${resolved.scoreB}  ${resolved.teamB}",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (resolved.seriesFinished) "WINNER · ${resolved.winner}" else "系列赛仍在进行 · 已结束小局已归档",
                        color = RiftMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )'''
new_series = '''                    TeamMatchupVisual(
                        leftCode = resolved.teamA,
                        rightCode = resolved.teamB,
                        centerText = "${resolved.scoreA} : ${resolved.scoreB}",
                        centerSubtext = if (resolved.seriesFinished) "WINNER · ${resolved.winner}" else "系列赛进行中",
                        logoSize = 62.dp,
                        centerFontSize = 24.sp
                    )'''
t = replace_once(t, old_series, new_series, 'Post series score')
old_game = '''                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(game.blue, modifier = Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("${game.blueKills} : ${game.redKills}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            game.red,
                            modifier = Modifier.weight(1f),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "GOLD ${if (game.blueGold > 0) "%.1fK".format(game.blueGold / 1000f) else "—"} : ${if (game.redGold > 0) "%.1fK".format(game.redGold / 1000f) else "—"} · DIFF ${formatGoldDiff(game.goldDiff)}",
                        color = RiftMuted,
                        fontSize = 10.sp
                    )'''
new_game = '''                    TeamMatchupVisual(
                        leftCode = game.blue,
                        rightCode = game.red,
                        centerText = "${game.blueKills} : ${game.redKills}",
                        leftSubtext = if (game.blueGold > 0) "%.1fK".format(game.blueGold / 1000f) else "—",
                        rightSubtext = if (game.redGold > 0) "%.1fK".format(game.redGold / 1000f) else "—",
                        centerSubtext = "DIFF ${formatGoldDiff(game.goldDiff)}",
                        logoSize = 42.dp,
                        centerFontSize = 18.sp
                    )'''
t = replace_once(t, old_game, new_game, 'Post game score')
t = replace_fun(t, 'MatchHero', 'RosterRow', '''@Composable
private fun MatchHero(blue: String, red: String, time: String, label: String, match: ScheduledEsportsMatch?) {
    val left = match?.teams?.getOrNull(0)
    val right = match?.teams?.getOrNull(1)
    Panel(accent = true) {
        Text(label, color = RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        TeamMatchupVisual(
            leftCode = left?.code?.ifBlank { left.name } ?: blue,
            leftImageUrl = left?.imageUrl.orEmpty(),
            rightCode = right?.code?.ifBlank { right.name } ?: red,
            rightImageUrl = right?.imageUrl.orEmpty(),
            centerText = "VS",
            centerSubtext = time,
            logoSize = 64.dp,
            centerFontSize = 20.sp
        )
    }
}''')
marker = '\nprivate fun formatGoldDiff(value: Int): String {'
helper = '''

private fun teamLabelMatches(label: String, team: com.riftlab.app.data.EsportsTeamRef): Boolean {
    val normalized = label.trim().replace(Regex("[^A-Za-z0-9]+"), "").uppercase()
    if (normalized.isBlank()) return false
    return listOf(team.code, team.name, team.slug, team.id).any { raw ->
        val candidate = raw.trim().replace(Regex("[^A-Za-z0-9]+"), "").uppercase()
        candidate.isNotBlank() && (candidate == normalized || candidate.contains(normalized) || normalized.contains(candidate))
    }
}
'''
if marker not in t:
    raise SystemExit('missing formatGoldDiff marker')
t = t.replace(marker, helper + marker, 1)
save(p, t)

print('Global team matchup visual migration applied.')
