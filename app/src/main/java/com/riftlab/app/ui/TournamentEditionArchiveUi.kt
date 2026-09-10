package com.riftlab.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.TournamentEditionArchiveRecord
import com.riftlab.app.data.TournamentEditionArchiveStore
import com.riftlab.app.data.TournamentEditionSlotState

/** Compact dev.69 surface: browse durable Tournament Edition identities and inspect real slot gaps. */
@Composable
fun TournamentEditionArchiveInlinePanel() {
    val state by TournamentEditionArchiveStore.state.collectAsState()
    val selected = state.selected

    Column(
        Modifier
            .fillMaxWidth()
            .background(RiftPanelAlt, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .border(1.dp, RiftLine, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .padding(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("TOURNAMENT EDITIONS / 年度赛事档案", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("旧届次追加保留，不因上游分页滚动被新赛事覆盖", color = RiftMuted, fontSize = 8.sp)
            }
            Text("${state.editions.size} EDITIONS", color = RiftText, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(8.dp))
        if (state.editions.isEmpty()) {
            Text(state.statusMessage, color = RiftMuted, fontSize = 9.sp)
            return@Column
        }

        val trail = recentTrail(state.editions, state.selectedTournamentId)
        trail.forEach { edition ->
            val active = edition.tournamentId == state.selectedTournamentId
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { TournamentEditionArchiveStore.selectTournament(edition.tournamentId) }
                    .background(if (active) RiftPanel else RiftPanelAlt, CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp))
                    .padding(horizontal = 7.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    edition.displayName,
                    color = if (active) RiftCyan else RiftText,
                    fontSize = 9.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    edition.startDate.take(10).ifBlank { edition.seasonYear?.toString() ?: "DATE ?" },
                    color = RiftMuted,
                    fontSize = 8.sp
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        selected?.let { detail ->
            Spacer(Modifier.height(5.dp))
            Text(
                "${detail.edition.displayName} · ${detail.edition.startDate.take(10)} → ${detail.edition.endDate.take(10)}",
                color = RiftText,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "SERIES ${detail.matchedSeries.size} · TEAMS ${detail.edition.participantTeamCodes.size} · ${detail.research?.version?.versionLabel ?: "PATCH 待同步"}",
                color = RiftMuted,
                fontSize = 8.sp
            )
            Spacer(Modifier.height(7.dp))

            detail.slots.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { slot ->
                        Column(
                            Modifier
                                .weight(1f)
                                .background(RiftPanel, CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp))
                                .padding(horizontal = 6.dp, vertical = 5.dp)
                        ) {
                            Text(slot.label, color = RiftText, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                slot.state.label,
                                color = when (slot.state) {
                                    TournamentEditionSlotState.COMPLETE -> RiftCyan
                                    TournamentEditionSlotState.PARTIAL -> RiftText
                                    TournamentEditionSlotState.PENDING -> RiftMuted
                                    TournamentEditionSlotState.SOURCE_ERROR -> RiftRed
                                },
                                fontSize = 8.sp
                            )
                            Text(slot.detail, color = RiftMuted, fontSize = 7.sp, lineHeight = 10.sp, maxLines = 3)
                        }
                    }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(5.dp))
            }
        }

        Text(state.statusMessage, color = RiftMuted, fontSize = 8.sp)
    }
}

private fun recentTrail(
    editions: List<TournamentEditionArchiveRecord>,
    selectedTournamentId: String
): List<TournamentEditionArchiveRecord> {
    if (editions.size <= 6) return editions
    val selected = editions.firstOrNull { it.tournamentId == selectedTournamentId }
    if (selected == null) return editions.takeLast(6)

    val sameContext = editions.filter { candidate ->
        (candidate.family.isNotBlank() && candidate.family == selected.family) ||
            (candidate.leagueId.isNotBlank() && candidate.leagueId == selected.leagueId) ||
            (candidate.leagueSlug.isNotBlank() && candidate.leagueSlug.equals(selected.leagueSlug, ignoreCase = true))
    }
    val contextual = sameContext.takeLast(6)
    return if (contextual.any { it.tournamentId == selectedTournamentId }) contextual
    else (editions.takeLast(5) + selected).distinctBy { it.tournamentId }.sortedBy { it.startDate }
}
