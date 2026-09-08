package com.riftlab.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.ChampionCatalog
import com.riftlab.app.data.DraftPickRecord
import com.riftlab.app.data.EsportsTeamRef
import com.riftlab.app.data.LivePlayerSnapshot
import com.riftlab.app.data.LiveSnapshot
import com.riftlab.app.data.MatchDetailRepository
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.OfficialMvpRecord
import com.riftlab.app.data.OfficialVoteRecord
import com.riftlab.app.data.ScheduleMatchPhase

private val DETAIL_ROLES = listOf("TOP", "JUG", "MID", "BOT", "SUP")

@Composable
internal fun MatchDetailContent() {
    val state by MatchDetailRepository.state.collectAsState()
    val match = state.match

    if (match == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("未选择比赛", color = RiftMuted)
        }
        return
    }

    val left = match.teams.getOrNull(0)
    val right = match.teams.getOrNull(1)
    val phase = MatchSessionStore.schedulePhase(match)
    val series = state.series
    val live = state.liveGame
    val gameNumbers = remember(series?.games, live?.game) {
        buildList {
            series?.games.orEmpty().map { it.game }.filter { it > 0 }.distinct().sorted().forEach(::add)
            live?.game?.takeIf { it > 0 && it !in this }?.let(::add)
        }.sorted()
    }
    var selectedGame by remember(state.key?.stableId) { mutableIntStateOf(0) }
    val selectedSnapshot = when {
        selectedGame <= 0 -> null
        else -> series?.games?.firstOrNull { it.game == selectedGame }
            ?: live?.takeIf { it.game == selectedGame }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            DetailPanel(accent = phase == ScheduleMatchPhase.LIVE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (phase) {
                            ScheduleMatchPhase.LIVE -> "LIVE MATCH"
                            ScheduleMatchPhase.UPCOMING -> "UPCOMING MATCH"
                            ScheduleMatchPhase.COMPLETED -> "MATCH FINAL"
                        },
                        color = if (phase == ScheduleMatchPhase.LIVE) RiftCyan else RiftMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    Text("BO${match.bestOf}", color = RiftMuted, fontSize = 10.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    left?.let {
                        TeamLogo(it.imageUrl, it.code.ifBlank { it.name }, Modifier.size(34.dp))
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(left?.code?.ifBlank { left.name } ?: "—", modifier = Modifier.weight(1f), fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text(
                        series?.let { "${it.scoreA} : ${it.scoreB}" }
                            ?: if (phase == ScheduleMatchPhase.COMPLETED || match.teams.any { it.gameWins > 0 }) MatchSessionStore.scheduleScore(match) else "VS",
                        color = RiftCyan,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        right?.code?.ifBlank { right.name } ?: "—",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.End,
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Bold
                    )
                    right?.let {
                        Spacer(Modifier.width(7.dp))
                        TeamLogo(it.imageUrl, it.code.ifBlank { it.name }, Modifier.size(34.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(match.blockName.ifBlank { match.league }, color = RiftMuted, fontSize = 10.sp)
                Text(MatchSessionStore.scheduleTimingNote(match), color = RiftMuted, fontSize = 10.sp)
                Text("MATCH KEY · ${state.key?.stableId ?: "—"}", color = RiftMuted, fontSize = 8.sp)
            }
        }

        item { DetailSectionTitle("DATA STATUS / 数据状态") }
        item {
            DetailPanel(accent = series != null || phase == ScheduleMatchPhase.LIVE) {
                Text(
                    when {
                        state.loading -> "正在加载单场详情…"
                        series != null -> "终局数据已连接"
                        phase == ScheduleMatchPhase.UPCOMING -> "等待比赛开始"
                        phase == ScheduleMatchPhase.LIVE -> "赛中数据由 Live Provider Router 提供"
                        else -> "终局数据暂不可用"
                    },
                    color = if (series != null || phase == ScheduleMatchPhase.LIVE) RiftCyan else RiftMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(state.status, color = RiftMuted, fontSize = 10.sp, lineHeight = 16.sp)
                if (phase == ScheduleMatchPhase.COMPLETED) {
                    Spacer(Modifier.height(9.dp))
                    Button(
                        onClick = MatchDetailRepository::refresh,
                        enabled = !state.loading,
                        colors = ButtonDefaults.buttonColors(containerColor = RiftPanelAlt, contentColor = RiftText),
                        shape = CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp)
                    ) {
                        Text(if (state.loading) "同步中" else "重新同步", fontSize = 10.sp)
                    }
                }
            }
        }

        if (gameNumbers.isNotEmpty()) {
            item {
                DetailGameTabs(
                    gameNumbers = gameNumbers,
                    selectedGame = selectedGame,
                    onSelect = { selectedGame = it }
                )
            }
        }

        if (selectedGame == 0) {
            if (series != null) {
                item { DetailSectionTitle("SERIES / 系列赛总览") }
                item { SeriesOverview(series.games) }
            } else if (phase == ScheduleMatchPhase.LIVE && live != null) {
                item { DetailSectionTitle("CURRENT GAME / 当前小局") }
                item { GameSummaryCard(live) }
            }
        } else if (selectedSnapshot != null) {
            val blueTeam = findTeamForSide(match.teams, selectedSnapshot.blue)
            val redTeam = findTeamForSide(match.teams, selectedSnapshot.red)
            item { DetailSectionTitle("GAME $selectedGame / 小局数据") }
            item { GameDetailCard(selectedSnapshot, blueTeam, redTeam) }
        } else {
            item { CompactStatusPanel("G$selectedGame 数据尚未连接") }
        }

        item { DetailSectionTitle("MVP / 评选数据") }
        item {
            MvpPanel(
                selectedGame = selectedGame,
                seriesMvp = state.seriesMvp,
                gameMvps = state.gameMvps
            )
        }

        item { DetailSectionTitle("POG / MVP VOTES / 数据面板") }
        val scopedVotes = votesForGame(state.votes, selectedGame)
        if (scopedVotes.isEmpty()) {
            item { CompactStatusPanel(if (selectedGame > 0) "G$selectedGame · POG / MVP 投票数据暂不可用" else "POG / MVP 投票数据暂不可用") }
        } else {
            items(scopedVotes, key = { it.title + it.source }) { vote -> VotePanel(vote) }
        }

        item { DetailSectionTitle("BP / DRAFT") }
        val scopedDrafts = draftsForGame(state.drafts, selectedGame)
        if (scopedDrafts.isEmpty()) {
            item { CompactStatusPanel(if (selectedGame > 0) "G$selectedGame · BP 数据暂不可用" else "BP 数据暂不可用") }
        } else {
            items(scopedDrafts, key = { "draft-${it.game}-${it.source}" }) { draft -> DraftPanel(draft) }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DetailGameTabs(gameNumbers: List<Int>, selectedGame: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf(0) + gameNumbers
    Row(
        Modifier.fillMaxWidth()
            .background(RiftPanelAlt, CutCornerShape(topEnd = 10.dp, bottomStart = 8.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tabs.forEach { game ->
            val selected = selectedGame == game
            Text(
                text = if (game == 0) "总览" else "G$game",
                modifier = Modifier.weight(1f)
                    .clickable { onSelect(game) }
                    .background(
                        if (selected) RiftPanel else androidx.compose.ui.graphics.Color.Transparent,
                        CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp)
                    )
                    .padding(vertical = 9.dp),
                color = if (selected) RiftCyan else RiftMuted,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SeriesOverview(games: List<LiveSnapshot>) {
    DetailPanel(accent = true) {
        games.sortedBy { it.game }.forEachIndexed { index, game ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("G${game.game}", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(30.dp))
                Text(game.blue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${game.blueKills} : ${game.redKills}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(game.red, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                Spacer(Modifier.width(8.dp))
                Text(MatchSessionStore.formatTime(game.elapsedSeconds), color = RiftMuted, fontSize = 8.sp)
            }
        }
    }
}

@Composable
private fun GameSummaryCard(game: LiveSnapshot) {
    DetailPanel(accent = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE · G${game.game}", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(MatchSessionStore.formatTime(game.elapsedSeconds), color = RiftMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(7.dp))
        Text("${game.blue}  ${game.blueKills} : ${game.redKills}  ${game.red}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("GOLD ${gold(game.blueGold)} : ${gold(game.redGold)}", color = RiftMuted, fontSize = 9.sp)
    }
}

@Composable
private fun GameDetailCard(game: LiveSnapshot, blueTeam: EsportsTeamRef?, redTeam: EsportsTeamRef?) {
    DetailPanel(accent = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GAME ${game.game}", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(MatchSessionStore.formatTime(game.elapsedSeconds), color = RiftMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(game.blue, modifier = Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("${game.blueKills} : ${game.redKills}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(game.red, modifier = Modifier.weight(1f), textAlign = TextAlign.End, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "GOLD ${gold(game.blueGold)} : ${gold(game.redGold)} · T ${game.blueTowers}:${game.redTowers} · D ${game.blueDragons}:${game.redDragons} · B ${game.blueBarons}:${game.redBarons}",
            color = RiftMuted,
            fontSize = 9.sp,
            lineHeight = 14.sp
        )

        Spacer(Modifier.height(10.dp))
        val leftMapped = roleMap(game.bluePlayers)
        val rightMapped = roleMap(game.redPlayers)
        Text(
            "PLAYERS · ${leftMapped.size}/5 : ${rightMapped.size}/5",
            color = if (leftMapped.size == 5 && rightMapped.size == 5) RiftCyan else RiftMuted,
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(5.dp))
        DETAIL_ROLES.forEach { role ->
            CompactPlayerRow(
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
        Text(game.source, color = RiftMuted, fontSize = 8.sp)
    }
}

@Composable
private fun CompactPlayerRow(
    role: String,
    left: LivePlayerSnapshot?,
    right: LivePlayerSnapshot?,
    leftTeamName: String,
    rightTeamName: String,
    leftTeam: EsportsTeamRef?,
    rightTeam: EsportsTeamRef?
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(
            imageUrl = leftTeam?.imageUrl.orEmpty(),
            code = leftTeam?.code?.ifBlank { leftTeam.name } ?: leftTeamName,
            modifier = Modifier.size(25.dp)
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                left?.let { cleanPlayerName(it.summonerName, leftTeamName) } ?: "数据缺失",
                color = if (left == null) RiftMuted else RiftText,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(playerStats(left), color = RiftMuted, fontSize = 8.sp)
            left?.championId?.takeIf { it.isNotBlank() }?.let {
                ChampionStatLabel(it, left.gold)
            }
        }
        Text(roleLabel(role), modifier = Modifier.width(34.dp), color = RiftMuted, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                right?.let { cleanPlayerName(it.summonerName, rightTeamName) } ?: "数据缺失",
                color = if (right == null) RiftMuted else RiftText,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End
            )
            Text(playerStats(right), color = RiftMuted, fontSize = 8.sp, textAlign = TextAlign.End)
            right?.championId?.takeIf { it.isNotBlank() }?.let {
                ChampionStatLabel(it, right.gold, TextAlign.End)
            }
        }
        Spacer(Modifier.width(6.dp))
        TeamLogo(
            imageUrl = rightTeam?.imageUrl.orEmpty(),
            code = rightTeam?.code?.ifBlank { rightTeam.name } ?: rightTeamName,
            modifier = Modifier.size(25.dp)
        )
    }
}

@Composable
private fun ChampionStatLabel(championId: String, gold: Int, textAlign: TextAlign = TextAlign.Start) {
    val label by produceState(initialValue = championId, championId) {
        value = ChampionCatalog.displayName(championId)
    }
    Text("HERO $label · G $gold", color = RiftMuted, fontSize = 7.sp, textAlign = textAlign)
}

@Composable
private fun MvpPanel(selectedGame: Int, seriesMvp: OfficialMvpRecord?, gameMvps: List<OfficialMvpRecord>) {
    val scoped = if (selectedGame > 0) gameMvps.filter { it.game == selectedGame } else gameMvps
    if (selectedGame > 0 && scoped.isEmpty()) {
        CompactStatusPanel("G$selectedGame · MVP 数据暂不可用")
        return
    }
    if (selectedGame == 0 && seriesMvp == null && scoped.isEmpty()) {
        CompactStatusPanel("MVP 数据暂不可用 · 允许赛事官方 / OP.GG 等来源，但不使用 KDA / 伤害规则自行推断")
        return
    }

    DetailPanel(accent = true) {
        if (selectedGame == 0) {
            seriesMvp?.let {
                Text("SERIES MVP · ${it.playerName}", color = RiftCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("${it.team} · ${it.role.ifBlank { "ROLE —" }}", color = RiftMuted, fontSize = 8.sp)
                Spacer(Modifier.height(3.dp))
                Text("来源 · ${it.source}", color = RiftMuted, fontSize = 7.sp)
                Spacer(Modifier.height(6.dp))
            }
        }
        scoped.forEachIndexed { index, mvp ->
            if (index > 0) Spacer(Modifier.height(5.dp))
            Text("G${mvp.game ?: 0} MVP · ${mvp.playerName} · ${mvp.team}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text("来源 · ${mvp.source}", color = RiftMuted, fontSize = 7.sp)
        }
    }
}

@Composable
private fun VotePanel(vote: OfficialVoteRecord) {
    DetailPanel(accent = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(vote.title, modifier = Modifier.weight(1f), color = RiftCyan, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            vote.totalVotes?.let { Text("TOTAL $it", color = RiftMuted, fontSize = 8.sp) }
        }
        vote.options.forEach { option ->
            Spacer(Modifier.height(4.dp))
            Row {
                Text(option.label, modifier = Modifier.weight(1f), fontSize = 9.sp)
                val percent = option.percent ?: vote.totalVotes
                    ?.takeIf { it > 0L }
                    ?.let { total -> option.votes * 100.0 / total }
                Text(
                    percent?.let { "${option.votes} · %.1f%%".format(it) } ?: option.votes.toString(),
                    color = RiftMuted,
                    fontSize = 9.sp
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("来源 · ${vote.source}", color = RiftMuted, fontSize = 7.sp)
    }
}

@Composable
private fun DraftPanel(draft: DraftPickRecord) {
    DetailPanel(accent = true) {
        Text("GAME ${draft.game} · BP", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        DraftLine("BLUE BAN", draft.blueBans)
        DraftLine("RED BAN", draft.redBans)
        Spacer(Modifier.height(5.dp))
        DraftLine("BLUE PICK", draft.bluePicks)
        DraftLine("RED PICK", draft.redPicks)
        Spacer(Modifier.height(5.dp))
        Text("来源 · ${draft.source}", color = RiftMuted, fontSize = 7.sp)
    }
}

@Composable
private fun DraftLine(label: String, values: List<String>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.width(68.dp), color = RiftMuted, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
        Text(
            values.joinToString(" · ").ifBlank { "当前来源未提供" },
            modifier = Modifier.weight(1f),
            color = if (values.isEmpty()) RiftMuted else RiftText,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun CompactStatusPanel(message: String) {
    DetailPanel {
        Text(message, color = RiftMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DetailSectionTitle(text: String) {
    Text(text, color = RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun DetailPanel(accent: Boolean = false, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(RiftPanel, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
            .border(1.dp, if (accent) RiftCyan.copy(alpha = 0.55f) else RiftLine, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
            .padding(13.dp)
    ) {
        content()
    }
}

private fun roleMap(players: List<LivePlayerSnapshot>): Map<String, LivePlayerSnapshot> {
    val exact = linkedMapOf<String, LivePlayerSnapshot>()
    players.forEach { player -> canonicalRole(player.role)?.let { role -> exact.putIfAbsent(role, player) } }
    if (exact.size >= 3 || players.size < 5) return exact

    // Some terminal schemas omit role entirely. Only fall back to array order when a complete
    // five-player row exists; never shift a four-player payload into the wrong positions.
    return DETAIL_ROLES.mapIndexedNotNull { index, role -> players.getOrNull(index)?.let { role to it } }.toMap()
}

private fun canonicalRole(raw: String): String? {
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

private fun roleLabel(role: String): String = when (role) {
    "TOP" -> "上"
    "JUG" -> "野"
    "MID" -> "中"
    "BOT" -> "下"
    "SUP" -> "辅"
    else -> role
}

private fun playerStats(player: LivePlayerSnapshot?): String =
    player?.let { "${it.kills}/${it.deaths}/${it.assists} · CS ${it.creepScore}" } ?: "—"

private fun cleanPlayerName(name: String, team: String): String {
    val trimmed = name.trim()
    val teamToken = team.trim().replace(Regex("[^A-Za-z0-9]"), "")
    if (teamToken.isBlank()) return trimmed
    return if (trimmed.startsWith(teamToken, ignoreCase = true) && trimmed.length > teamToken.length) {
        trimmed.drop(teamToken.length).trimStart('-', '_', ' ')
    } else trimmed
}

private fun findTeamForSide(teams: List<EsportsTeamRef>, label: String): EsportsTeamRef? {
    val target = teamToken(label)
    return teams.firstOrNull { team ->
        listOf(team.code, team.name, team.slug)
            .map(::teamToken)
            .any { it.isNotBlank() && (it == target || it.contains(target) || target.contains(it)) }
    }
}

private fun teamToken(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

private fun votesForGame(votes: List<OfficialVoteRecord>, selectedGame: Int): List<OfficialVoteRecord> {
    if (selectedGame <= 0) return votes
    val token = "G$selectedGame"
    return votes.filter { it.title.contains(token, ignoreCase = true) }
}

private fun draftsForGame(drafts: List<DraftPickRecord>, selectedGame: Int): List<DraftPickRecord> =
    if (selectedGame <= 0) drafts else drafts.filter { it.game == selectedGame }

private fun gold(value: Int): String = if (value > 0) "%.1fK".format(value / 1000f) else "—"
