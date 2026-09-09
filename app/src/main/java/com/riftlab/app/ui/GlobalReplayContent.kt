package com.riftlab.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.RiotVodRepository
import com.riftlab.app.data.ScheduledEsportsMatch

internal fun isLplReplayMatch(match: ScheduledEsportsMatch): Boolean =
    match.leagueSlug.equals("lpl", ignoreCase = true) ||
        match.league.equals("LPL", ignoreCase = true) ||
        match.league.contains("PRO LEAGUE", ignoreCase = true)

@Composable
internal fun GlobalOfficialReplayContent(match: ScheduledEsportsMatch) {
    val context = LocalContext.current
    val state by RiotVodRepository.state.collectAsState()
    val key = RiotVodRepository.keyFor(match)
    LaunchedEffect(key) { RiotVodRepository.open(match) }
    val links = state.links.takeIf { state.matchKey == key }.orEmpty()
    val playedGames = remember(match, links) {
        val scoreGames = match.teams.sumOf { it.gameWins }.takeIf { it > 0 } ?: 0
        val vodGames = links.map { it.game }.filter { it > 0 }.distinct().sorted()
        when {
            vodGames.isNotEmpty() -> vodGames
            scoreGames > 0 -> (1..scoreGames).toList()
            else -> listOf(1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        val shape = CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp)
        Column(
            Modifier.fillMaxWidth()
                .background(RiftPanel, shape)
                .border(1.dp, RiftCyan.copy(alpha = 0.35f), shape)
                .padding(12.dp)
        ) {
            Text("GLOBAL OFFICIAL REPLAY / 海外官方回放", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "海外赛区与国际赛事只走 Riot LoL Esports / YouTube 官方 VOD，不请求、不解析 Bilibili。",
                color = RiftMuted,
                fontSize = 9.sp,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(5.dp))
            Text(
                if (state.matchKey == key) state.status else "正在切换 Riot VOD…",
                color = RiftText,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(8.dp))
        LazyRow {
            items(playedGames, key = { it }) { game ->
                val youtube = links.firstOrNull { it.game == game && it.provider.equals("youtube", true) }
                Column(
                    Modifier.width(188.dp)
                        .padding(end = 8.dp)
                        .background(RiftPanelAlt, shape)
                        .border(1.dp, RiftLine, shape)
                        .padding(10.dp)
                ) {
                    Text("G$game", color = RiftText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        youtube?.let { "YouTube · ${it.locale}" } ?: "Riot VOD · 等待/搜索官方源",
                        color = RiftMuted,
                        fontSize = 8.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 7.dp)
                    )
                    Row {
                        Text(
                            if (youtube != null) "YouTube ›" else "YouTube 搜索 ›",
                            color = RiftCyan,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                openReplayUrl(context, youtube?.sourceUrl?.takeIf { it.isNotBlank() }
                                    ?: RiotVodRepository.youtubeSearch(match, game))
                            }.padding(vertical = 5.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "LoL Esports ›",
                            color = RiftCyan,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                openReplayUrl(context, RiotVodRepository.riotVodPage(match, game))
                            }.padding(vertical = 5.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MatchTimelineContent()
        }
    }
}

private fun openReplayUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
