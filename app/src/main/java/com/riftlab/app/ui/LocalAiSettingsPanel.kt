package com.riftlab.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TextButton
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
import com.riftlab.app.ai.LiteRtLocalAiRuntime
import com.riftlab.app.ai.LocalModelInstallStatus
import com.riftlab.app.ai.LocalModelManager
import com.riftlab.app.ai.LocalModelRecommendation

@Composable
internal fun LocalAiSettingsPanel() {
    val context = LocalContext.current
    val state by LocalAiCore.state.collectAsState()
    val installState by LocalModelManager.state.collectAsState()
    val runtimeState by LiteRtLocalAiRuntime.state.collectAsState()
    val profile = state.profile
    val selectedRecommendation = state.recommendations.firstOrNull { it.model.id == state.selectedModelId }
    val selectedModel = selectedRecommendation?.model
    val downloadMetadataReady = selectedModel?.let { LocalModelManager.resolvedDownloadMetadata(it) != null } == true

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

        if (selectedModel != null) {
            Column(
                Modifier.fillMaxWidth()
                    .background(RiftPanelAlt.copy(alpha = 0.65f), CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
                    .border(1.dp, RiftLine.copy(alpha = 0.55f), CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
                    .padding(12.dp)
            ) {
                Text("MODEL MANAGER / 模型管理", color = RiftText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(5.dp))
                val statusForSelected = installState.modelId == selectedModel.id
                val statusText = if (statusForSelected) {
                    when (installState.status) {
                        LocalModelInstallStatus.IDLE -> "未安装"
                        LocalModelInstallStatus.DOWNLOADING -> "下载中 ${progressLabel(installState.downloadedBytes, installState.totalBytes)}"
                        LocalModelInstallStatus.VERIFYING -> "正在校验 SHA-256"
                        LocalModelInstallStatus.VERIFIED -> "文件校验通过 · 等待本机基准测试"
                        LocalModelInstallStatus.READY -> "基准测试通过 · 可启用"
                        LocalModelInstallStatus.FAILED -> "安装失败"
                    }
                } else {
                    "未安装"
                }
                Text(statusText, color = if (statusForSelected && installState.status != LocalModelInstallStatus.FAILED) RiftCyan else RiftMuted, fontSize = 11.sp)
                if (statusForSelected && installState.message.isNotBlank()) {
                    Text(installState.message, color = RiftMuted, fontSize = 11.sp, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val busy = statusForSelected && (
                        installState.status == LocalModelInstallStatus.DOWNLOADING ||
                            installState.status == LocalModelInstallStatus.VERIFYING
                        )
                    Button(
                        enabled = downloadMetadataReady && !busy && !(statusForSelected && installState.status in setOf(LocalModelInstallStatus.VERIFIED, LocalModelInstallStatus.READY)),
                        onClick = { LocalModelManager.installSelected(context, selectedModel) }
                    ) {
                        Text(if (busy) "处理中" else if (statusForSelected && installState.status == LocalModelInstallStatus.VERIFIED) "重新校验下载" else "下载并校验")
                    }
                    if (statusForSelected && installState.localPath != null) {
                        TextButton(onClick = { LocalModelManager.removeInstalled(context) }) { Text("删除模型") }
                    }
                }
                if (statusForSelected && installState.status == LocalModelInstallStatus.VERIFIED) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = !runtimeState.busy,
                        onClick = { LiteRtLocalAiRuntime.benchmarkVerifiedModel(context, selectedModel) }
                    ) { Text(if (runtimeState.busy) "基准测试中" else "运行真机基准") }
                    Text(
                        "会真实加载模型并完成一次短推理；延迟和热状态不过门槛就继续规则模式。",
                        color = RiftMuted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
                if (statusForSelected && installState.status == LocalModelInstallStatus.READY) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "READY · 真机推理 ${installState.benchmarkLatencyMs?.let { "${it}ms" } ?: "已通过"} · 请手动打开总开关",
                        color = RiftCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (runtimeState.modelId == selectedModel.id && runtimeState.message.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(runtimeState.message, color = RiftMuted, fontSize = 11.sp, lineHeight = 16.sp)
                }
                if (!downloadMetadataReady) {
                    Text(
                        "当前目录还没有发布经过验证的 Android 运行时模型包与 SHA-256，因此下载按钮保持锁定；不会拿普通权重文件冒充可运行包。",
                        color = RiftMuted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Text(
            "下载策略：只展示适合本机的模型；模型包先写入 filesDir/local_ai_models，完成 SHA-256 校验后仍不会直接启用，必须再通过真实运行时加载、短延迟基准和热状态检查。失败或不达标立即回到规则模式。模型资产与普通缓存分离，清理缓存不会删除模型。",
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
    }
}

private fun LocalAiTier.label(): String = when (this) {
    LocalAiTier.TIER_0_RULES -> "TIER 0 · RULES ONLY"
    LocalAiTier.TIER_1_LITE -> "TIER 1 · LITE"
    LocalAiTier.TIER_2_SLM -> "TIER 2 · SMALL LOCAL MODEL"
    LocalAiTier.TIER_3_HIGH -> "TIER 3 · HIGH PERFORMANCE"
    LocalAiTier.TIER_4_EXPERIMENTAL -> "TIER 4 · EXPERIMENTAL"
}

private fun progressLabel(downloaded: Long, total: Long?): String {
    if (total == null || total <= 0L) return formatMiB(downloaded)
    val percent = (downloaded * 100L / total).coerceIn(0L, 100L)
    return "$percent% · ${formatMiB(downloaded)} / ${formatMiB(total)}"
}

private fun formatGiB(bytes: Long): String = "%.1f GB".format(bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
private fun formatMiB(bytes: Long): String = "%.0f MB".format(bytes.toDouble() / (1024.0 * 1024.0))
