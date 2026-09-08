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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.ScheduleMatchPhase
import com.riftlab.app.data.ScheduledEsportsMatch

@Composable
fun RiftLabRoot() {
    Box(Modifier.fillMaxSize()) {
        RiftLabApp()
        ScheduleCenterLauncher(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp)
        )
    }
}

@Composable
private fun ScheduleCenterLauncher(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }

    Button(
        onClick = { open = true },
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = RiftPanelAlt,
            contentColor = RiftText
        ),
        shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
    ) {
        Icon(Icons.Default.CalendarMonth, null, tint = RiftCyan)
        Spacer(Modifier.width(7.dp))
        Text("赛程中心", color = RiftText, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    }

    if (open) {
        ScheduleCenterDialog(onClose = { open = false })
    }
}

@Composable
private fun ScheduleCenterDialog(onClose: () -> Unit) {
    val center by MatchSessionStore.scheduleCenter.collectAsState()
    val groups = center.matches.groupBy(MatchSessionStore::scheduleDateKey).toSortedMap()

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = RiftBg,
            contentColor = RiftText
        ) {
            Column(
                Modifier.fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "LPL 赛程数据中心",
                            color = RiftText,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Riot Schedule · 计划时间只作参考，Live 状态优先",
                            color = RiftMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                    Box(
                        Modifier.clickable(onClick = onClose).padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Close, null, tint = RiftMuted)
                    }
                }

                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .background(RiftPanel, CutCornerShape(topEnd = 14.dp, bottomStart = 10.dp))
                        .border(1.dp, RiftLine, CutCornerShape(topEnd = 14.dp, bottomStart = 10.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        center.statusMessage,
                        color = RiftText,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp
                    )
                    center.currentMatch?.let {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "● NOW  ${matchLabel(it)}",
                            color = RiftCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            MatchSessionStore.scheduleTimingNote(it),
                            color = RiftMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                    center.nextMatch?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "NEXT  ${matchLabel(it)} · ${MatchSessionStore.scheduleTimingNote(it)}",
                            color = RiftMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    groups.forEach { (date, matches) ->
                        item(key = "date-$date") {
                            Text(
                                date,
                                color = RiftMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                            )
                        }
                        items(matches, key = { it.eventId.ifBlank { it.matchId } }) { match ->
                            ScheduleMatchCard(
                                match = match,
                                selected = center.selectedMatch?.matchId == match.matchId,
                                onClick = {
                                    MatchSessionStore.selectScheduleMatch(match.matchId)
                                    onClose()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleMatchCard(
    match: ScheduledEsportsMatch,
    selected: Boolean,
    onClick: () -> Unit
) {
    val phase = MatchSessionStore.schedulePhase(match)
    val phaseText = when (phase) {
        ScheduleMatchPhase.LIVE -> "LIVE"
        ScheduleMatchPhase.UPCOMING -> "待开"
        ScheduleMatchPhase.COMPLETED -> "已结束"
    }
    val phaseColor = when (phase) {
        ScheduleMatchPhase.LIVE -> RiftCyan
        ScheduleMatchPhase.UPCOMING -> RiftText
        ScheduleMatchPhase.COMPLETED -> RiftMuted
    }

    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .background(RiftPanel, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
            .border(
                1.dp,
                if (selected || phase == ScheduleMatchPhase.LIVE) RiftCyan.copy(alpha = 0.48f) else RiftLine,
                CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp)
            )
            .padding(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                phaseText,
                color = phaseColor,
                fontSize = 10.sp,
                fontWeight = if (phase == ScheduleMatchPhase.LIVE) FontWeight.Bold else FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                "BO${match.bestOf}",
                color = RiftMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                match.teams.getOrNull(0)?.code ?: "—",
                color = RiftText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (phase == ScheduleMatchPhase.COMPLETED || match.teams.any { it.gameWins > 0 }) {
                    MatchSessionStore.scheduleScore(match)
                } else {
                    "VS"
                },
                color = if (phase == ScheduleMatchPhase.LIVE) RiftCyan else RiftMuted,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                match.teams.getOrNull(1)?.code ?: "—",
                color = RiftText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            MatchSessionStore.scheduleTimingNote(match),
            color = RiftMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Normal
        )
        if (match.blockName.isNotBlank()) {
            Text(
                match.blockName.uppercase(),
                color = RiftMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun matchLabel(match: ScheduledEsportsMatch): String =
    match.teams.take(2).joinToString(" vs ") { it.code.ifBlank { it.name } }
