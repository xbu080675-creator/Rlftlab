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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.QualificationCenterStore
import com.riftlab.app.data.TournamentEditionArchiveRecord
import com.riftlab.app.data.TournamentEditionArchiveStore
import com.riftlab.app.data.TournamentEditionSlotState

/** Browse durable Tournament Edition identities and inspect real archival/qualification gaps. */
@Composable
fun TournamentEditionArchiveInlinePanel() {
    val state by TournamentEditionArchiveStore.state.collectAsState()
    val qualificationCenter by QualificationCenterStore.state.collectAsState()
    val selected = state.selected
    val qualification = qualificationCenter.snapshotsByTournamentId[state.selectedTournamentId]
    var showAllHistory by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(RiftPanelAlt, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .border(1.dp, RiftLine, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .padding(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("TOURNAMENT EDITIONS / 年度赛事档案", color = RiftCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("旧届次追加保留，不因上游分页滚动被新赛事覆盖", color = RiftMuted, fontSize = 10.sp)
            }
            Column {
                Text("${state.editions.size} EDITIONS", color = RiftText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                if (!state.followingCurrent) {
                    Text(
                        "跟随当前赛事",
                        color = RiftCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable { TournamentEditionArchiveStore.followCurrentTournament() }
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        if (state.editions.isEmpty()) {
            Text(state.statusMessage, color = RiftMuted, fontSize = 10.sp)
            return@Column
        }

        val completeTrail = fullHistoryTrail(state.editions, selected?.edition)
        if (completeTrail.size > 6) {
            Text(
                if (showAllHistory) "收起 · 最近 6 届" else "查看全部历史 · ${completeTrail.size} 届",
                color = RiftCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { showAllHistory = !showAllHistory }.padding(vertical = 6.dp)
            )
        }
        val trail = if (showAllHistory) completeTrail else recentTrail(state.editions, state.selectedTournamentId)
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
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    edition.startDate.take(10).ifBlank { edition.seasonYear?.toString() ?: "DATE ?" },
                    color = RiftMuted,
                    fontSize = 10.sp
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
                "SERIES ${detail.edition.scheduleSeriesCount} · TEAMS ${detail.edition.participantTeamCodes.size} · ${detail.research?.version?.versionLabel ?: detail.edition.archivedSlots.firstOrNull { it.key == "patch" }?.detail ?: "PATCH 待同步"}",
                color = RiftMuted,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(7.dp))

            detail.slots.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { slot ->
                        val qualificationAvailable = slot.key == "qualification" && qualification?.routes?.isNotEmpty() == true
                        val displayState = if (qualificationAvailable) TournamentEditionSlotState.PARTIAL else slot.state
                        val displayDetail = if (qualificationAvailable) {
                            "已接入 ${qualification?.routes?.size ?: 0} 支队伍的资格路径 / 参赛来源；具体机制见下方 Qualification Center。"
                        } else slot.detail
                        Column(
                            Modifier
                                .weight(1f)
                                .background(RiftPanel, CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp))
                                .padding(horizontal = 6.dp, vertical = 5.dp)
                        ) {
                            Text(slot.label, color = RiftText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                displayState.label,
                                color = when (displayState) {
                                    TournamentEditionSlotState.COMPLETE -> RiftCyan
                                    TournamentEditionSlotState.PARTIAL -> RiftText
                                    TournamentEditionSlotState.PENDING -> RiftMuted
                                    TournamentEditionSlotState.SOURCE_ERROR -> RiftRed
                                },
                                fontSize = 10.sp
                            )
                            Text(displayDetail, color = RiftMuted, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 3)
                        }
                    }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(5.dp))
            }
        }

        Text(state.statusMessage, color = RiftMuted, fontSize = 10.sp)
    }
}

private fun fullHistoryTrail(
    editions: List<TournamentEditionArchiveRecord>,
    selected: TournamentEditionArchiveRecord?
): List<TournamentEditionArchiveRecord> {
    if (selected == null) return editions.sortedByDescending { it.startDate }
    val contextual = editions.filter { candidate ->
        when {
            selected.family.isNotBlank() && selected.family != "OTHER" -> candidate.family == selected.family
            selected.leagueId.isNotBlank() -> candidate.leagueId == selected.leagueId
            selected.leagueSlug.isNotBlank() -> candidate.leagueSlug.equals(selected.leagueSlug, ignoreCase = true)
            else -> true
        }
    }
    return contextual.sortedByDescending { it.startDate }
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
