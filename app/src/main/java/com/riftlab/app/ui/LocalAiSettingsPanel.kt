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
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.ai.LocalAiCore
import com.riftlab.app.ai.LocalAiTier
import com.riftlab.app.ai.LocalModelRecommendation

@Composable
internal fun LocalAiSettingsPanel() {
    val context = LocalContext.current
    val state by LocalAiCore.state.collectAsState()
    val profile = state.profile

    Column {
        Text(
            "LOCAL AI · 本地智能辅助",
            color = RiftCyan,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "规则层始终可用；本地模型永远是可选增强。不会因为推荐就自动下载，也不会因为设备够强就自动启用。",
            color = RiftMuted,
            fontSize = 11.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        state.enabled -> "本地模型辅助已启用"
                        state.modelReady -> "模型已就绪 · 当前未启用"
                        else -> "规则模式 · 无需模型"
                    },
                    color = RiftText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    if (state.modelReady) "AI 只做场景理解与关键点排序，不改写比赛事实。"
                    else "所有基础观赛能力保持可用；模型未安装时不会影响 RiftLab。",
                    color = RiftMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
            Switch(
                checked = state.enabled,
                onCheckedChange = { LocalAiCore.setEnabled(it) },
                enabled = state.modelReady
            )
        }

        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.fillMaxWidth()
                .background(RiftPanelAlt.copy(alpha = 0.72f), CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
                .border(1.dp, RiftLine.copy(alpha = 0.55f), CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("DEVICE PROFILE / 设备检测", color = RiftText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(
                        profile?.tier?.label() ?: "检测中…",
                        color = RiftCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Button(onClick = { LocalAiCore.refreshProfile(context) }) { Text("重新检测") }
            }
            if (profile != null) {
                Spacer(Modifier.height(8.dp))
                Text(profile.reason, color = RiftMuted, fontSize = 11.sp, lineHeight = 16.sp)
                Spacer(Modifier.height(5.dp))
                Text(
                    "RAM ${formatGiB(profile.totalRamBytes)} · 可用 ${formatGiB(profile.availableRamBytes)} · 存储 ${formatGiB(profile.availableStorageBytes)}",
                    color = RiftMuted,
                    fontSize = 11.sp
                )
                Text(
                    "CPU ${profile.cpuCores} 核 · ABI ${profile.supportedAbis.joinToString()}${profile.thermalStatus?.let { " · THERMAL $it" } ?: ""}",
                    color = RiftMuted,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("MODEL RECOMMENDATIONS / 模型推荐", color = RiftText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Spacer(Modifier.height(7.dp))
        if (state.recommendations.isEmpty()) {
            Text("等待设备检测结果…", color = RiftMuted, fontSize = 11.sp)
        } else {
            state.recommendations.forEach { recommendation ->
                ModelRecommendationCard(
                    recommendation = recommendation,
                    selected = state.selectedModelId == recommendation.model.id,
                    onSelect = {
                        LocalAiCore.selectModel(
                            if (state.selectedModelId == recommendation.model.id) null else recommendation.model.id
                        )
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Text(
            "下载策略：只展示适合本机的模型；下载后必须经过 SHA 校验和本机短基准测试，达不到实时延迟/持续温控要求就降级到规则模式。模型资产与普通缓存分离，清理缓存不会删除模型。",
            color = RiftMuted,
            fontSize = 11.sp,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun ModelRecommendationCard(
    recommendation: LocalModelRecommendation,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val model = recommendation.model
    val badge = when {
        recommendation.recommended -> "推荐"
        recommendation.runnable -> "可运行"
        else -> "不推荐"
    }
    Column(
        Modifier.fillMaxWidth()
            .background(RiftPanel.copy(alpha = 0.7f), CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
            .border(
                1.dp,
                if (selected) RiftCyan else RiftLine.copy(alpha = 0.55f),
                CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp)
            )
            .clickable(enabled = recommendation.runnable, onClick = onSelect)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(model.displayName, color = RiftText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "$badge · ${model.runtime} · ${model.quantization}${if (model.noThink) " · NO-THINK" else ""}",
                    color = if (recommendation.recommended) RiftCyan else RiftMuted,
                    fontSize = 11.sp
                )
            }
            Text(if (selected) "已选择" else badge, color = if (selected) RiftCyan else RiftMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(recommendation.reason, color = RiftMuted, fontSize = 11.sp, lineHeight = 16.sp)
        Text("模型体积约 ${formatMiB(model.approximateBytes)}", color = RiftMuted, fontSize = 11.sp)
        if (selected && !model.downloadUrl.isNullOrBlank()) {
            Text("下载源已就绪", color = RiftCyan, fontSize = 11.sp)
        } else if (selected) {
            Text("等待模型目录提供兼容下载包；不会下载错误格式的权重。", color = RiftMuted, fontSize = 11.sp)
        }
    }
}

private fun LocalAiTier.label(): String = when (this) {
    LocalAiTier.TIER_0_RULES -> "TIER 0 · RULES ONLY"
    LocalAiTier.TIER_1_LITE -> "TIER 1 · LITE"
    LocalAiTier.TIER_2_SLM -> "TIER 2 · SMALL LOCAL MODEL"
    LocalAiTier.TIER_3_HIGH -> "TIER 3 · HIGH PERFORMANCE"
    LocalAiTier.TIER_4_EXPERIMENTAL -> "TIER 4 · EXPERIMENTAL"
}

private fun formatGiB(bytes: Long): String = "%.1f GB".format(bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
private fun formatMiB(bytes: Long): String = "%.0f MB".format(bytes.toDouble() / (1024.0 * 1024.0))
