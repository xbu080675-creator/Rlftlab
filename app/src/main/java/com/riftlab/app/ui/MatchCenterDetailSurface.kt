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

private enum class CenterCompletedMatchPane(val label: String) {
    DETAIL("比赛详情"),
    REPLAY("比赛回放")
}

/**
 * Inline match detail used by the event center.
 *
 * Every completed schedule match gets one unified replay surface: official VOD + event timeline.
 * This is available for every completed schedule match, not only the home screen's latest POST item.
 */
@Composable
internal fun MatchCenterDetailSurface(match: ScheduledEsportsMatch) {
    if (MatchSessionStore.schedulePhase(match) != ScheduleMatchPhase.COMPLETED) {
        MatchDetailContent()
        return
    }

    var pane by remember(match.eventId, match.matchId) {
        mutableStateOf(CenterCompletedMatchPane.DETAIL)
    }

    Column {
        CompletedMatchPaneTabs(pane) { pane = it }
        Spacer(Modifier.height(10.dp))
        when (pane) {
            CenterCompletedMatchPane.DETAIL -> MatchDetailContent()
            CenterCompletedMatchPane.REPLAY -> MatchReplayContent()
        }
    }
}

@Composable
private fun CompletedMatchPaneTabs(
    selected: CenterCompletedMatchPane,
    onSelect: (CenterCompletedMatchPane) -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .background(RiftPanelAlt, CutCornerShape(topEnd = 10.dp, bottomStart = 8.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        CenterCompletedMatchPane.entries.forEach { pane ->
            val active = pane == selected
            Text(
                pane.label,
                color = if (active) RiftCyan else RiftMuted,
                fontSize = 10.sp,
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
