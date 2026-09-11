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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.SideSelectionStore

@Composable
internal fun SideSelectionPrePanel() {
    val target by MatchSessionStore.targetMatch.collectAsState()
    val state by SideSelectionStore.state.collectAsState()

    LaunchedEffect(target?.eventId, target?.matchId) {
        target?.let { SideSelectionStore.load(it) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "SIDE SELECTION / 选边与一抢",
            color = RiftMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
        Column(
            Modifier.fillMaxWidth()
                .background(RiftPanel, CutCornerShape(topEnd = 18.dp, bottomStart = 10.dp))
                .border(1.dp, if (state.records.isNotEmpty()) RiftCyan.copy(alpha = 0.38f) else RiftLine, CutCornerShape(topEnd = 18.dp, bottomStart = 10.dp))
                .padding(16.dp)
        ) {
            if (state.records.isEmpty()) {
                Text(
                    if (state.loading) "正在同步选边信息…" else state.status,
                    color = if (state.loading) RiftCyan else RiftMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "RiftLab 不会根据对阵顺序猜蓝红方；Riot EventDetails 公布后才显示。",
                    color = RiftMuted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
            } else {
                state.records.forEachIndexed { index, row ->
                    if (index > 0) Spacer(Modifier.height(9.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("G${row.game}", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
                        Column(Modifier.weight(1f)) {
                            Text("蓝色方 · ${row.blueTeam}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text("FIRST PICK / 一抢 · ${row.firstPickTeam}", color = RiftCyan, fontSize = 11.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("红色方 · ${row.redTeam}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.height(9.dp))
                Text("来源 · ${state.records.first().source}", color = RiftMuted, fontSize = 11.sp)
                Text(
                    "注：蓝色方拥有标准 BP 的 First Pick；当前源若未单独给出“选边权归属”，RiftLab 不把蓝色方直接写成“拥有选边权的队伍”。",
                    color = RiftMuted,
                    fontSize = 11.sp,
                    lineHeight = 13.sp
                )
            }
        }
    }
}
