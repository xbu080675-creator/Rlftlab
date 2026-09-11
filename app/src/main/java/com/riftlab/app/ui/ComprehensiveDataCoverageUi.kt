package com.riftlab.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.ComprehensiveDataCenter
import com.riftlab.app.data.ComprehensiveDataDomain
import com.riftlab.app.data.ComprehensiveCoverageReport
import com.riftlab.app.data.DataCoverageCell
import com.riftlab.app.data.DataCoverageState

/**
 * Visual coverage HUD.
 *
 * Coverage is an operator signal, not a wall of diagnostics. The full data contract still exists in
 * the graph, but the normal surface shows shape, progress and one actionable gap instead of twelve
 * equally loud text cards.
 */
@Composable
fun ComprehensiveDataCoveragePanel() {
    val snapshot by ComprehensiveDataCenter.snapshot.collectAsState()
    val report = snapshot.coverage
    val graph = snapshot.graph

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RiftHudPanel(accent = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverageDial(report.scorePercent)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "DATA COVERAGE",
                        color = RiftMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        coverageHeadline(report.scorePercent),
                        color = RiftText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(7.dp))
                    CoverageStateRail(report.cells)
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TinyMetric("OK", report.completeDomains, RiftCyan)
                        TinyMetric("PART", report.partialDomains, RiftText)
                        TinyMetric("WAIT", report.pendingDomains, RiftMuted)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoverageClusterCard(
                modifier = Modifier.weight(1f),
                label = "赛事",
                icon = { Icon(Icons.Outlined.Event, null, tint = RiftCyan, modifier = Modifier.size(20.dp)) },
                cells = cellsFor(
                    report,
                    ComprehensiveDataDomain.TOURNAMENT,
                    ComprehensiveDataDomain.SCHEDULE,
                    ComprehensiveDataDomain.STANDINGS,
                    ComprehensiveDataDomain.QUALIFICATION,
                    ComprehensiveDataDomain.PROVENANCE
                )
            )
            CoverageClusterCard(
                modifier = Modifier.weight(1f),
                label = "赛前",
                icon = { Icon(Icons.Outlined.Insights, null, tint = RiftCyan, modifier = Modifier.size(20.dp)) },
                cells = cellsFor(
                    report,
                    ComprehensiveDataDomain.TEAM,
                    ComprehensiveDataDomain.PLAYER,
                    ComprehensiveDataDomain.ROSTER,
                    ComprehensiveDataDomain.PREMATCH
                )
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoverageClusterCard(
                modifier = Modifier.weight(1f),
                label = "赛中",
                icon = { Icon(Icons.Outlined.Bolt, null, tint = RiftCyan, modifier = Modifier.size(20.dp)) },
                cells = cellsFor(report, ComprehensiveDataDomain.LIVE)
            )
            CoverageClusterCard(
                modifier = Modifier.weight(1f),
                label = "赛后",
                icon = { Icon(Icons.Outlined.EmojiEvents, null, tint = RiftCyan, modifier = Modifier.size(20.dp)) },
                cells = cellsFor(
                    report,
                    ComprehensiveDataDomain.POSTMATCH,
                    ComprehensiveDataDomain.HISTORY
                )
            )
        }

        val missing = report.cells.firstOrNull {
            it.state != DataCoverageState.COMPLETE && it.state != DataCoverageState.NOT_APPLICABLE
        }
        if (missing != null) {
            Row(
                Modifier.fillMaxWidth()
                    .background(RiftPanelAlt.copy(alpha = 0.74f), CutCornerShape(topEnd = 10.dp, bottomStart = 7.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(6.dp).background(coverageColor(missing.state), CutCornerShape(2.dp)))
                Spacer(Modifier.width(8.dp))
                Text("NEXT", color = RiftMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${missing.domain.label} · ${humanGap(missing.missing.firstOrNull())}",
                    color = RiftText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${graph.games.size}G / ${graph.timeline.size}E",
                    color = RiftMuted,
                    fontSize = 11.sp
                )
            }
        }

        TournamentEditionArchiveInlinePanel()
        Spacer(Modifier.height(2.dp))
        QualificationPathCenterPanel()
    }
}

@Composable
private fun CoverageDial(percent: Int) {
    val clamped = percent.coerceIn(0, 100)
    Box(Modifier.size(86.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            drawArc(
                color = RiftLine.copy(alpha = 0.32f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(stroke)
            )
            drawArc(
                color = RiftCyan,
                startAngle = -90f,
                sweepAngle = 360f * clamped / 100f,
                useCenter = false,
                style = Stroke(stroke)
            )
            val tickRadius = size.minDimension / 2f - stroke * 0.25f
            repeat(4) { index ->
                val angle = Math.toRadians((index * 90 - 90).toDouble())
                val center = Offset(size.width / 2f, size.height / 2f)
                val x = center.x + kotlin.math.cos(angle).toFloat() * tickRadius
                val y = center.y + kotlin.math.sin(angle).toFloat() * tickRadius
                drawCircle(RiftPanel, 2.2.dp.toPx(), Offset(x, y))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$clamped", color = RiftText, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("%", color = RiftMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CoverageStateRail(cells: List<DataCoverageCell>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        cells.forEach { cell ->
            Box(
                Modifier.weight(1f).height(5.dp)
                    .background(coverageColor(cell.state), CutCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun TinyMetric(label: String, value: Int, tone: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).background(tone, CutCornerShape(2.dp)))
        Spacer(Modifier.width(4.dp))
        Text("$label $value", color = RiftMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CoverageClusterCard(
    modifier: Modifier,
    label: String,
    icon: @Composable () -> Unit,
    cells: List<DataCoverageCell>
) {
    val available = cells.sumOf { it.availableFields }
    val required = cells.sumOf { it.requiredFields }.coerceAtLeast(1)
    val fraction = (available.toFloat() / required).coerceIn(0f, 1f)
    Column(
        modifier
            .background(RiftPanelAlt.copy(alpha = 0.82f), CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(7.dp))
            Text(label, color = RiftText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "$available/$required",
                color = if (fraction >= 0.999f) RiftCyan else RiftMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().height(4.dp)
                .background(RiftLine.copy(alpha = 0.24f), CutCornerShape(2.dp))
        ) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceAtLeast(0.02f)).height(4.dp)
                    .background(if (fraction >= 0.999f) RiftCyan else RiftRed.copy(alpha = 0.82f), CutCornerShape(2.dp))
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            cells.forEach { cell ->
                Box(
                    Modifier.weight(1f).height(18.dp)
                        .background(coverageColor(cell.state).copy(alpha = 0.12f), CutCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        cell.domain.label.take(2),
                        color = coverageColor(cell.state),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

private fun cellsFor(report: ComprehensiveCoverageReport, vararg domains: ComprehensiveDataDomain): List<DataCoverageCell> =
    domains.mapNotNull(report::cell)

private fun coverageHeadline(percent: Int): String = when {
    percent >= 90 -> "数据链路完整"
    percent >= 70 -> "核心链路可用"
    percent >= 45 -> "持续补全中"
    else -> "数据底座建设中"
}

private fun humanGap(raw: String?): String = when (raw.orEmpty()) {
    "players", "ten-player coverage" -> "选手覆盖"
    "roles" -> "位置身份"
    "roster memberships", "two five-player sides" -> "阵容"
    "prematch record" -> "赛前上下文"
    "live game" -> "实时小局"
    "player snapshots", "player stats" -> "选手统计"
    "timeline" -> "事件时间线"
    "standings rows" -> "排名"
    "qualification paths" -> "晋级路径"
    "historical archive", "historical games" -> "历史归档"
    "source stamps" -> "数据来源"
    else -> raw?.takeIf { it.isNotBlank() } ?: "待同步"
}

@Composable
private fun coverageColor(state: DataCoverageState): Color = when (state) {
    DataCoverageState.COMPLETE -> RiftCyan
    DataCoverageState.PARTIAL -> RiftText
    DataCoverageState.PENDING -> RiftMuted
    DataCoverageState.SOURCE_ERROR -> RiftRed
    DataCoverageState.NOT_APPLICABLE -> RiftMuted.copy(alpha = 0.55f)
}
