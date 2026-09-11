package com.riftlab.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.ScheduleMatchPhase
import com.riftlab.app.data.ScheduledEsportsMatch

private enum class CenterMatchPane(val label: String) {
    DETAIL("比赛详情"),
    OPERATIONS("运营数据"),
    REPLAY("进程 / 回放")
}

/**
 * Inline match detail used by the event center.
 *
 * Every match phase gets the operator data plane. Upcoming matches keep dynamic schedule revisions,
 * live matches expose continuously changing telemetry, and completed matches keep the same archived
 * frames plus the official replay surface.
 */
@Composable
internal fun MatchCenterDetailSurface(match: ScheduledEsportsMatch) {
    val completed = MatchSessionStore.schedulePhase(match) == ScheduleMatchPhase.COMPLETED
    var pane by remember(match.eventId, match.matchId) {
        mutableStateOf(CenterMatchPane.DETAIL)
    }

    Column {
        MatchPaneTabs(pane, completed) { pane = it }
        Spacer(Modifier.height(10.dp))
        when (pane) {
            CenterMatchPane.DETAIL -> MatchDetailContent()
            CenterMatchPane.OPERATIONS -> MatchOperationsContent()
            CenterMatchPane.REPLAY -> if (completed) MatchReplayContent() else MatchTimelineContent()
        }
    }
}

@Composable
private fun MatchPaneTabs(
    selected: CenterMatchPane,
    completed: Boolean,
    onSelect: (CenterMatchPane) -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .background(RiftPanelAlt, CutCornerShape(topEnd = 10.dp, bottomStart = 8.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        CenterMatchPane.entries.forEach { pane ->
            val active = pane == selected
            val label = when (pane) {
                CenterMatchPane.REPLAY -> if (completed) "比赛回放" else "时间轴"
                else -> pane.label
            }
            Text(
                label,
                color = if (active) RiftCyan else RiftMuted,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
                    .background(
                        if (active) RiftPanel else androidx.compose.ui.graphics.Color.Transparent,
                        CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp)
                    )
                    .clickable { onSelect(pane) }
                    .padding(vertical = 9.dp)
            )
        }
    }
}
