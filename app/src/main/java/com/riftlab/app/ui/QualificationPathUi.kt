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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.QualificationCenterStore
import com.riftlab.app.data.QualificationEvidence
import com.riftlab.app.data.QualificationMechanism
import com.riftlab.app.data.QualificationNodeState
import com.riftlab.app.data.QualificationTeamState
import com.riftlab.app.data.TeamQualificationRoute
import com.riftlab.app.data.TournamentEditionArchiveStore

/** dev.70: reverse-queryable qualification routes, kept separate from ordinary standings. */
@Composable
fun QualificationPathCenterPanel() {
    val center by QualificationCenterStore.state.collectAsState()
    val archive by TournamentEditionArchiveStore.state.collectAsState()
    val snapshot = center.snapshotsByTournamentId[archive.selectedTournamentId]

    Column(
        Modifier
            .fillMaxWidth()
            .background(RiftPanelAlt, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .border(1.dp, RiftLine, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .padding(10.dp)
    ) {
        Text(
            "QUALIFICATION / 资格体系与晋级路径",
            color = RiftCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "积分、名次直通、资格赛与国际赛参赛来源分开建模；未知机制不会伪装成“积分缺失”",
            color = RiftMuted,
            fontSize = 8.sp
        )
        Spacer(Modifier.height(8.dp))

        if (snapshot == null) {
            Text(
                archive.selected?.edition?.displayName?.let { "$it · 资格来源尚未接入可信映射，不根据排名猜晋级" }
                    ?: "当前届次尚无可信资格路径源",
                color = RiftMuted,
                fontSize = 9.sp
            )
            return@Column
        }

        Text(snapshot.title, color = RiftText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text("TARGET  ${snapshot.targetEvent}", color = RiftMuted, fontSize = 8.sp)
        Text(
            "MODE  ${snapshot.mechanism.label} · ${snapshot.mechanismEvidence.label}",
            color = evidenceColor(snapshot.mechanismEvidence),
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (snapshot.mechanismDetail.isNotBlank()) {
            Text(snapshot.mechanismDetail, color = RiftMuted, fontSize = 7.sp, lineHeight = 10.sp)
        }
        if (snapshot.segments.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("MECHANISM SEGMENTS / 资格机制拆分", color = RiftText, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            snapshot.segments.forEach { segment ->
                Spacer(Modifier.height(4.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(RiftPanel, CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp))
                        .padding(6.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(segment.type.label, color = RiftCyan, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                        Text(segment.evidence.label, color = evidenceColor(segment.evidence), fontSize = 7.sp)
                    }
                    Text(segment.detail, color = RiftMuted, fontSize = 7.sp, lineHeight = 10.sp)
                    Text("SOURCE  ${segment.source}", color = RiftMuted, fontSize = 6.sp)
                }
            }
        }
        Spacer(Modifier.height(7.dp))

        if (snapshot.routes.isEmpty()) {
            Text(snapshot.note.ifBlank { "资格规则/路径等待可信来源" }, color = RiftMuted, fontSize = 9.sp)
            Text("SOURCE  ${snapshot.sourceSummary}", color = RiftMuted, fontSize = 8.sp)
            return@Column
        }

        snapshot.routes.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { route ->
                    val active = route.teamCode.equals(center.selectedTeamCode, ignoreCase = true)
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { QualificationCenterStore.selectTeam(route.teamCode) }
                            .background(
                                if (active) RiftPanel else RiftPanelAlt,
                                CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp)
                            )
                            .border(
                                1.dp,
                                if (active) statusColor(route.status) else RiftLine,
                                CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 5.dp)
                    ) {
                        Text(
                            route.teamCode,
                            color = if (active) statusColor(route.status) else RiftText,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(route.status.label, color = statusColor(route.status), fontSize = 7.sp)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(5.dp))
        }

        val route = snapshot.routes.firstOrNull {
            it.teamCode.equals(center.selectedTeamCode, ignoreCase = true)
        } ?: snapshot.routes.first()
        Spacer(Modifier.height(3.dp))
        RouteDetail(route, snapshot.mechanism)

        val visibleRules = snapshot.rules.take(5)
        if (visibleRules.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("QUALIFICATION RULES / 资格规则", color = RiftText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            visibleRules.forEach { rule ->
                Spacer(Modifier.height(4.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(RiftPanel, CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp))
                        .padding(6.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(rule.title, color = RiftText, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                        Text(rule.evidence.label, color = evidenceColor(rule.evidence), fontSize = 7.sp)
                    }
                    Text(rule.detail, color = RiftMuted, fontSize = 7.sp, lineHeight = 10.sp)
                    Text("SOURCE  ${rule.source}", color = RiftMuted, fontSize = 6.sp)
                }
            }
        }

        Spacer(Modifier.height(7.dp))
        Text(snapshot.note, color = RiftMuted, fontSize = 7.sp, lineHeight = 10.sp)
        Text("SOURCE  ${snapshot.sourceSummary}", color = RiftMuted, fontSize = 7.sp)
    }
}

@Composable
private fun RouteDetail(route: TeamQualificationRoute, mechanism: QualificationMechanism) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(RiftPanel, CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp))
            .padding(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${route.teamCode} → ${route.targetEvent}",
                color = RiftText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Text(route.evidence.label, color = evidenceColor(route.evidence), fontSize = 8.sp)
        }

        Spacer(Modifier.height(5.dp))
        if (route.championshipPoints != null || route.leagueStandingPoints != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (route.championshipPoints != null) {
                    PointCell(
                        title = "CHAMPIONSHIP POINTS",
                        value = route.championshipPoints.toString(),
                        detail = route.annualPointBreakdown.ifBlank { "年度积分" },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (route.leagueStandingPoints != null) {
                    PointCell(
                        title = "STANDINGS POINTS",
                        value = route.leagueStandingPoints.toString(),
                        detail = "仅代表当前届次/阶段返回的排名积分",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            Text(
                when (mechanism) {
                    QualificationMechanism.PARTICIPANT_ORIGIN -> "这里不是 Championship Points 缺失：该目标赛事按参赛席位 / Qualification Origin 建档。"
                    QualificationMechanism.DIRECT_PLACEMENT -> "该资格体系按联赛或季后赛名次直通，不需要 Championship Points 占位。"
                    QualificationMechanism.REGIONAL_QUALIFIER -> "该资格体系按资格赛 / 附加赛路径记录，不需要 Championship Points 占位。"
                    QualificationMechanism.UNKNOWN -> "当前资格机制尚未核实，因此不显示虚假的 Championship Points 缺失项。"
                    QualificationMechanism.CHAMPIONSHIP_POINTS -> "该赛事确认采用 Championship Points，但当前队伍积分值尚未取得可信数据。"
                    QualificationMechanism.MIXED -> "该队当前路径没有可核实积分值；混合体系的其他资格节点仍按来源展示。"
                },
                color = RiftMuted,
                fontSize = 7.sp,
                lineHeight = 10.sp
            )
        }

        if (route.updatedThrough.isNotBlank()) {
            Text("SNAPSHOT  ${route.updatedThrough}", color = RiftMuted, fontSize = 7.sp)
        }
        Spacer(Modifier.height(6.dp))

        route.route.forEachIndexed { index, node ->
            val prefix = when (node.state) {
                QualificationNodeState.CONFIRMED -> "✓"
                QualificationNodeState.AVAILABLE -> "→"
                QualificationNodeState.BLOCKED -> "×"
                QualificationNodeState.PENDING -> "…"
            }
            Row(Modifier.fillMaxWidth()) {
                Text(prefix, color = nodeColor(node.state), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.padding(start = 6.dp)) {
                    Text(
                        "${index + 1}. ${node.label} · ${node.state.label}",
                        color = RiftText,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(node.detail, color = RiftMuted, fontSize = 7.sp, lineHeight = 10.sp)
                    Text(
                        "${node.evidence.label} · ${node.source}",
                        color = evidenceColor(node.evidence),
                        fontSize = 6.sp
                    )
                }
            }
            if (index != route.route.lastIndex) Spacer(Modifier.height(5.dp))
        }
    }
}

@Composable
private fun PointCell(title: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(RiftPanelAlt, CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp))
            .padding(6.dp)
    ) {
        Text(title, color = RiftMuted, fontSize = 6.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = RiftCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(detail, color = RiftMuted, fontSize = 6.sp, lineHeight = 9.sp)
    }
}

@Composable
private fun statusColor(status: QualificationTeamState): Color = when (status) {
    QualificationTeamState.LOCKED -> RiftCyan
    QualificationTeamState.CONTENDING -> RiftText
    QualificationTeamState.ELIMINATED -> RiftRed
    QualificationTeamState.PENDING -> RiftMuted
}

@Composable
private fun evidenceColor(evidence: QualificationEvidence): Color = when (evidence) {
    QualificationEvidence.OFFICIAL -> RiftCyan
    QualificationEvidence.PROVIDER -> RiftText
    QualificationEvidence.DERIVED -> RiftMuted
    QualificationEvidence.PENDING -> RiftMuted
}

@Composable
private fun nodeColor(state: QualificationNodeState): Color = when (state) {
    QualificationNodeState.CONFIRMED -> RiftCyan
    QualificationNodeState.AVAILABLE -> RiftText
    QualificationNodeState.BLOCKED -> RiftRed
    QualificationNodeState.PENDING -> RiftMuted
}
