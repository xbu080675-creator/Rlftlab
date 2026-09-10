package com.riftlab.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.ComprehensiveDataCenter
import com.riftlab.app.data.DataCoverageState

/**
 * dev.68 makes missing esports data visible instead of masking it with placeholders.
 * dev.69 adds the durable Tournament Edition archive.
 * dev.70 adds annual points/qualification routes as an independent data plane below it.
 */
@Composable
fun ComprehensiveDataCoveragePanel() {
    val snapshot by ComprehensiveDataCenter.snapshot.collectAsState()
    val report = snapshot.coverage
    val graph = snapshot.graph

    Column(
        Modifier
            .fillMaxWidth()
            .background(RiftPanel, CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
            .border(1.dp, RiftLine, CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("COMPREHENSIVE DATA / 全面数据", color = RiftCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("覆盖度只计算已拿到的真实结构，不拿占位值凑完整", color = RiftMuted, fontSize = 10.sp)
            }
            Text("${report.scorePercent}%", color = RiftText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(10.dp))
        report.cells.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { cell ->
                    val color = coverageColor(cell.state)
                    Column(
                        Modifier
                            .weight(1f)
                            .background(RiftPanelAlt, CutCornerShape(topStart = 5.dp, bottomEnd = 5.dp))
                            .padding(horizontal = 7.dp, vertical = 6.dp)
                    ) {
                        Text(cell.domain.label, color = RiftText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(cell.state.label, color = color, fontSize = 10.sp)
                        Text("${cell.availableFields}/${cell.requiredFields}", color = RiftMuted, fontSize = 9.sp)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
        }

        Text(
            "GRAPH  SERIES ${if (graph.series != null) 1 else 0} · GAME ${graph.games.size} · TEAM ${graph.teams.size} · PLAYER ${graph.players.size} · STATS ${graph.playerGameStats.size} · QUAL ${graph.qualificationPaths.size}",
            color = RiftMuted,
            fontSize = 10.sp
        )
        val missing = report.cells.filter { it.state != DataCoverageState.COMPLETE && it.state != DataCoverageState.NOT_APPLICABLE }
        if (missing.isNotEmpty()) {
            Text(
                "NEXT GAP  " + missing.take(3).joinToString(" · ") { "${it.domain.label}:${it.missing.firstOrNull() ?: it.state.label}" },
                color = RiftMuted,
                fontSize = 9.sp
            )
        }

        Spacer(Modifier.height(10.dp))
        TournamentEditionArchiveInlinePanel()
        Spacer(Modifier.height(10.dp))
        QualificationPathCenterPanel()
    }
}

@Composable
private fun coverageColor(state: DataCoverageState): Color = when (state) {
    DataCoverageState.COMPLETE -> RiftCyan
    DataCoverageState.PARTIAL -> RiftText
    DataCoverageState.PENDING -> RiftMuted
    DataCoverageState.SOURCE_ERROR -> RiftRed
    DataCoverageState.NOT_APPLICABLE -> RiftMuted
}
