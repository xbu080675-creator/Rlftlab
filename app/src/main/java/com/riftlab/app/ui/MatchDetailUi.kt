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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.riftlab.app.data.ChampionCatalog
import com.riftlab.app.data.DraftPickRecord
import com.riftlab.app.data.EsportsTeamRef
import com.riftlab.app.data.LivePlayerSnapshot
import com.riftlab.app.data.LiveSnapshot
import com.riftlab.app.data.MatchDetailRepository
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.OfficialMvpRecord
import com.riftlab.app.data.OfficialVoteRecord
import com.riftlab.app.data.PlayerPortraitResolver
import com.riftlab.app.data.ScheduleMatchPhase
import com.riftlab.app.data.ScheduledEsportsMatch

private val DETAIL_ROLES = listOf("TOP", "JUG", "MID", "BOT", "SUP")

@Composable
internal fun MatchDetailContent() {
    val state by MatchDetailRepository.state.collectAsState()
    val match = state.match

    if (match == null) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("未选择比赛", color = RiftMuted)
        }
        return
    }

    val left = match.teams.getOrNull(0)
    val right = match.teams.getOrNull(1)
    val phase = MatchSessionStore.schedulePhase(match)
    val series = state.series
    val live = state.liveGame
    val playedGamesFromScore = if (phase == ScheduleMatchPhase.COMPLETED) {
        match.teams.take(2).sumOf { it.gameWins }.coerceAtMost(match.bestOf.takeIf { it > 0 } ?: 7)
    } else 0
    val gameNumbers = remember(series?.games, live?.game, state.drafts, state.gameMvps, playedGamesFromScore) {
        buildList {
            series?.games.orEmpty().map { it.game }.filter { it > 0 }.distinct().sorted().forEach(::add)
            state.drafts.map { it.game }.filter { it > 0 && it !in this }.forEach(::add)
            state.gameMvps.mapNotNull { it.game }.filter { it > 0 && it !in this }.forEach(::add)
            if (playedGamesFromScore > 0) (1..playedGamesFromScore).filter { it !in this }.forEach(::add)
            live?.game?.takeIf { it > 0 && it !in this }?.let(::add)
        }.distinct().sorted()
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
            MatchHeroPanel(
                match = match,
                scoreA = series?.scoreA ?: left?.gameWins?.takeIf { phase == ScheduleMatchPhase.COMPLETED },
                scoreB = series?.scoreB ?: right?.gameWins?.takeIf { phase == ScheduleMatchPhase.COMPLETED },
                phase = phase
            )
        }

        item {
            if (gameNumbers.isNotEmpty()) {
                DetailGameTabs(gameNumbers, selectedGame) { selectedGame = it }
            }
        }

        if (selectedGame == 0) {
            if (series != null) {
                item { DetailSectionTitle("SERIES / 系列赛") }
                item { SeriesOverview(series.games) }
            } else if (phase == ScheduleMatchPhase.LIVE && live != null) {
                item { DetailSectionTitle("CURRENT GAME / 当前小局") }
                item { GameSummaryCard(live) }
            }
        } else if (selectedSnapshot != null) {
            val blueTeam = findTeamForSide(match.teams, selectedSnapshot.blue)
            val redTeam = findTeamForSide(match.teams, selectedSnapshot.red)
            item { DetailSectionTitle("GAME $selectedGame") }
            item { GameDetailCard(selectedSnapshot, blueTeam, redTeam) }
        }

        item { DetailSectionTitle("MVP / POG") }
        item {
            MvpVisualPanel(
                match = match,
                selectedGame = selectedGame,
                seriesMvp = state.seriesMvp,
                gameMvps = state.gameMvps
            )
        }

        val scopedVotes = votesForGame(state.votes, selectedGame)
        if (scopedVotes.isNotEmpty()) {
            item { DetailSectionTitle("RATING / 评选面板") }
            items(scopedVotes, key = { it.title + it.source }) { vote ->
                VoteVisualCard(match, vote)
            }
        }

        item { DetailSectionTitle("BP / DRAFT") }
        val scopedDrafts = draftsForGame(state.drafts, selectedGame)
        if (scopedDrafts.isEmpty()) {
            item { CompactStatusPanel(if (selectedGame > 0) "G$selectedGame · BP 暂无数据" else "BP 暂无数据") }
        } else {
            items(scopedDrafts, key = { "draft-${it.game}-${it.source}" }) { draft ->
                DraftVisualPanel(draft)
            }
        }

        item {
            DataSourceStrip(
                loading = state.loading,
                status = state.status,
                canRefresh = phase == ScheduleMatchPhase.COMPLETED,
                onRefresh = MatchDetailRepository::refresh
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
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
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text("BO${match.bestOf}", color = RiftMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(10.dp))
        TeamMatchupVisual(
            leftCode = left?.code?.ifBlank { left.name } ?: "—",
            leftImageUrl = left?.imageUrl.orEmpty(),
            rightCode = right?.code?.ifBlank { right.name } ?: "—",
            rightImageUrl = right?.imageUrl.orEmpty(),
            centerText = if (scoreA != null && scoreB != null) "$scoreA : $scoreB" else "VS",
            logoSize = 58.dp,
            centerFontSize = 25.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${match.blockName.ifBlank { match.league }} · ${MatchSessionStore.scheduleTimingNote(match)}",
            color = RiftMuted,
            fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
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
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
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
                logoSize = 30.dp,
                centerFontSize = 15.sp,
                teamNameFontSize = 10.sp
            )
        }
    }
}

@Composable
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
            logoSize = 42.dp,
            centerFontSize = 20.sp
        )
    }
}

@Composable
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
            logoSize = 44.dp,
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
}

@Composable
private fun VisualPlayerRow(
    role: String,
    left: LivePlayerSnapshot?,
    right: LivePlayerSnapshot?,
    leftTeamName: String,
    rightTeamName: String,
    leftTeam: EsportsTeamRef?,
    rightTeam: EsportsTeamRef?
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChampionMiniIcon(left?.championId.orEmpty())
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                left?.let { cleanPlayerName(it.summonerName, leftTeamName) } ?: "数据缺失",
                color = if (left == null) RiftMuted else RiftText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Text(playerStats(left), color = RiftMuted, fontSize = 10.sp)
        }
        Text(roleLabel(role), modifier = Modifier.width(30.dp), color = RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                right?.let { cleanPlayerName(it.summonerName, rightTeamName) } ?: "数据缺失",
                color = if (right == null) RiftMuted else RiftText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End
            )
            Text(playerStats(right), color = RiftMuted, fontSize = 10.sp, textAlign = TextAlign.End)
        }
        Spacer(Modifier.width(6.dp))
        ChampionMiniIcon(right?.championId.orEmpty())
    }
}

@Composable
private fun MvpVisualPanel(
    match: ScheduledEsportsMatch,
    selectedGame: Int,
    seriesMvp: OfficialMvpRecord?,
    gameMvps: List<OfficialMvpRecord>
) {
    val scoped = if (selectedGame > 0) gameMvps.filter { it.game == selectedGame } else gameMvps
    if (selectedGame > 0 && scoped.isEmpty()) {
        CompactStatusPanel("G$selectedGame · MVP 暂无数据")
        return
    }
    if (selectedGame == 0 && seriesMvp == null && scoped.isEmpty()) {
        CompactStatusPanel("MVP 暂无数据")
        return
    }

    if (selectedGame > 0) {
        scoped.firstOrNull()?.let { MvpHeroCard(match, it, Modifier.fillMaxWidth()) }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        seriesMvp?.let { MvpHeroCard(match, it, Modifier.fillMaxWidth(), series = true) }
        if (scoped.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(scoped, key = { "mvp-${it.game}-${it.playerName}" }) { mvp ->
                    MvpHeroCard(match, mvp, Modifier.width(210.dp))
                }
            }
        }
    }
}

@Composable
private fun MvpHeroCard(
    match: ScheduledEsportsMatch,
    mvp: OfficialMvpRecord,
    modifier: Modifier,
    series: Boolean = false
) {
    val portrait by produceState(
        initialValue = "",
        match.eventId,
        mvp.playerName,
        mvp.team
    ) {
        value = PlayerPortraitResolver.resolve(match, mvp.playerName, mvp.team)
    }
    val team = findTeamForSide(match.teams, mvp.team)

    Row(
        modifier
            .height(132.dp)
            .background(RiftPanel, CutCornerShape(topEnd = 18.dp, bottomStart = 10.dp))
            .border(1.dp, RiftCyan.copy(alpha = 0.58f), CutCornerShape(topEnd = 18.dp, bottomStart = 10.dp))
    ) {
        PlayerPortrait(
            imageUrl = portrait,
            fallbackTeam = team,
            fallbackCode = mvp.team,
            modifier = Modifier.width(82.dp).height(132.dp)
        )
        Column(
            Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                if (series) "SERIES MVP" else "G${mvp.game ?: 0} MVP",
                color = RiftCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.8.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(mvp.playerName, fontSize = 25.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Text(
                listOf(mvp.team, mvp.role).filter { it.isNotBlank() }.joinToString(" · "),
                color = RiftMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            SourceLabel(mvp.source)
        }
    }
}

@Composable
private fun VoteVisualCard(match: ScheduledEsportsMatch, vote: OfficialVoteRecord) {
    val leader = vote.options.maxByOrNull { it.percent ?: it.votes.toDouble() }
    val (leaderName, leaderTeam) = splitVoteLabel(leader?.label.orEmpty())
    val portrait by produceState(initialValue = "", match.eventId, leaderName, leaderTeam) {
        value = if (leaderName.isBlank()) "" else PlayerPortraitResolver.resolve(match, leaderName, leaderTeam)
    }
    val team = findTeamForSide(match.teams, leaderTeam)

    DetailPanel(accent = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerPortrait(
                imageUrl = portrait,
                fallbackTeam = team,
                fallbackCode = leaderTeam,
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(vote.title, color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
                if (leaderName.isNotBlank()) {
                    Text(leaderName, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    Text(leaderTeam, color = RiftMuted, fontSize = 10.sp)
                }
                leader?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        voteScoreLabel(vote, it.votes, it.percent),
                        color = RiftText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
        if (vote.options.size > 1) {
            Spacer(Modifier.height(10.dp))
            vote.options.sortedByDescending { it.percent ?: it.votes.toDouble() }.take(5).forEachIndexed { index, option ->
                val (name, teamCode) = splitVoteLabel(option.label)
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", color = if (index == 0) RiftCyan else RiftMuted, fontSize = 10.sp, modifier = Modifier.width(18.dp))
                    Text(name, modifier = Modifier.weight(1f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    if (teamCode.isNotBlank()) Text(teamCode, color = RiftMuted, fontSize = 10.sp)
                    Spacer(Modifier.width(7.dp))
                    Text(voteScoreLabel(vote, option.votes, option.percent), color = RiftMuted, fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        SourceLabel(vote.source)
    }
}

@Composable
private fun DraftVisualPanel(draft: DraftPickRecord) {
    DetailPanel(accent = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GAME ${draft.game}", color = RiftCyan, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            SourceLabel(draft.source)
        }
        Spacer(Modifier.height(10.dp))
        DraftTeamBlock("BLUE", draft.bluePicks, draft.blueBans)
        Spacer(Modifier.height(12.dp))
        DraftTeamBlock("RED", draft.redPicks, draft.redBans)
    }
}

@Composable
private fun DraftTeamBlock(side: String, picks: List<String>, bans: List<String>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(side, color = if (side == "BLUE") RiftCyan else RiftRed, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(42.dp))
        Text("PICK", color = RiftMuted, fontSize = 10.sp)
    }
    Spacer(Modifier.height(5.dp))
    if (picks.isEmpty()) {
        Text("PICK 暂无数据", color = RiftMuted, fontSize = 10.sp)
    } else {
        ChampionIconRow(picks, iconSize = 40)
    }
    Spacer(Modifier.height(7.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("BAN", color = RiftMuted, fontSize = 10.sp, modifier = Modifier.width(42.dp))
        if (bans.isEmpty()) {
            Text("当前来源未提供", color = RiftMuted, fontSize = 10.sp)
        } else {
            ChampionIconRow(bans, iconSize = 28)
        }
    }
}

@Composable
private fun ChampionIconRow(values: List<String>, iconSize: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.take(5).forEach { champion ->
            ChampionTile(champion, iconSize)
        }
    }
}

@Composable
private fun ChampionTile(champion: String, iconSize: Int) {
    val icon by produceState(initialValue = "", champion) {
        value = ChampionCatalog.iconUrl(champion)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(iconSize.dp)
                .clip(CutCornerShape(topEnd = 8.dp, bottomStart = 6.dp))
                .background(RiftPanelAlt)
                .border(1.dp, RiftLine, CutCornerShape(topEnd = 8.dp, bottomStart = 6.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (icon.isNotBlank()) {
                AsyncImage(
                    model = icon,
                    contentDescription = champion,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(champion.take(2), color = RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (iconSize >= 40) {
            Spacer(Modifier.height(2.dp))
            Text(champion, color = RiftMuted, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ChampionMiniIcon(championId: String) {
    if (championId.isBlank()) {
        Box(Modifier.size(26.dp).background(RiftPanelAlt, CutCornerShape(6.dp)))
        return
    }
    val icon by produceState(initialValue = "", championId) {
        value = ChampionCatalog.iconUrl(championId)
    }
    Box(
        Modifier.size(26.dp)
            .clip(CutCornerShape(6.dp))
            .background(RiftPanelAlt)
    ) {
        if (icon.isNotBlank()) {
            AsyncImage(
                model = icon,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun PlayerPortrait(
    imageUrl: String,
    fallbackTeam: EsportsTeamRef?,
    fallbackCode: String,
    modifier: Modifier
) {
    Box(
        modifier
            .clip(CutCornerShape(topEnd = 16.dp, bottomStart = 10.dp))
            .background(RiftPanelAlt),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl.isNotBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            TeamLogo(
                imageUrl = fallbackTeam?.imageUrl.orEmpty(),
                code = fallbackTeam?.code?.ifBlank { fallbackTeam.name } ?: fallbackCode.ifBlank { "?" },
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

@Composable
private fun DataSourceStrip(
    loading: Boolean,
    status: String,
    canRefresh: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .background(RiftPanelAlt, CutCornerShape(topEnd = 10.dp, bottomStart = 7.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (loading) "SYNCING" else "DATA",
            color = if (loading) RiftCyan else RiftMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(8.dp))
        Text(status, color = RiftMuted, fontSize = 10.sp, maxLines = 2, modifier = Modifier.weight(1f))
        if (canRefresh) {
            Button(
                onClick = onRefresh,
                enabled = !loading,
                colors = ButtonDefaults.buttonColors(containerColor = RiftPanel, contentColor = RiftText),
                shape = CutCornerShape(topEnd = 7.dp, bottomStart = 7.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(if (loading) "…" else "刷新", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun StatStrip(vararg values: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        values.forEachIndexed { index, value ->
            Text(
                value,
                color = if (index == 0) RiftText else RiftMuted,
                fontSize = 10.sp,
                fontWeight = if (index == 0) FontWeight.Bold else FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun SourceLabel(source: String) {
    Text(
        source,
        color = RiftMuted,
        fontSize = 10.sp,
        maxLines = 1,
        modifier = Modifier
            .background(RiftPanelAlt, CutCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

@Composable
private fun CompactStatusPanel(message: String) {
    DetailPanel {
        Text(message, color = RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DetailSectionTitle(text: String) {
    Text(
        text,
        color = RiftMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.7.sp,
        modifier = Modifier.padding(top = 4.dp)
    )
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
    player?.let { "${it.kills}/${it.deaths}/${it.assists} · CS ${it.creepScore} · G ${it.gold}" } ?: "—"

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

private fun splitVoteLabel(label: String): Pair<String, String> {
    val parts = label.split("·").map { it.trim() }.filter { it.isNotBlank() }
    return (parts.getOrNull(0) ?: label.trim()) to parts.getOrNull(1).orEmpty()
}

private fun voteScoreLabel(vote: OfficialVoteRecord, votes: Long, explicitPercent: Double?): String {
    val percent = explicitPercent ?: vote.totalVotes?.takeIf { it > 0L }?.let { total -> votes * 100.0 / total }
    return percent?.let { "$votes · %.1f%%".format(it) } ?: votes.toString()
}

private fun gold(value: Int): String = if (value > 0) "%.1fK".format(value / 1000f) else "—"
