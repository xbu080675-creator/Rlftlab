package com.riftlab.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.riftlab.app.BuildConfig
import com.riftlab.app.update.AppUpdateManager

@Composable
internal fun UpdateCenterDialog(onClose: () -> Unit) {
    val state by AppUpdateManager.state.collectAsState()
    LaunchedEffect(Unit) { AppUpdateManager.checkForUpdates() }

    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth()
                .background(RiftPanel, CutCornerShape(topEnd = 18.dp, bottomStart = 12.dp))
                .border(1.dp, if (state.available) RiftCyan.copy(alpha = 0.6f) else RiftLine, CutCornerShape(topEnd = 18.dp, bottomStart = 12.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("RIFTLAB UPDATE", color = RiftCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("当前版本", color = RiftMuted, fontSize = 9.sp)
            Text("${BuildConfig.VERSION_NAME} · code ${BuildConfig.VERSION_CODE}", fontSize = 18.sp, fontWeight = FontWeight.Bold)

            if (state.latestVersionName.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text("最新 DEV", color = RiftMuted, fontSize = 9.sp)
                Text("${state.latestVersionName} · code ${state.latestVersionCode}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }

            if (state.sourceLabel.isNotBlank()) {
                Text("更新通道 · ${state.sourceLabel}", color = RiftMuted, fontSize = 9.sp)
                when {
                    state.sourceLabel == "Gitee 国内 OTA" -> Text(
                        "中国大陆优先更新源 · 失败会自动校验同版本并回退 GitHub 自适应更新通道",
                        color = RiftMuted,
                        fontSize = 8.sp,
                        lineHeight = 12.sp
                    )
                    state.sourceLabel.startsWith("GitHub 更新加速") -> Text(
                        "仅作用于本次 RiftLab GitHub 更新请求 · 非 VPN / 非系统代理 · 节点失败会自动切换",
                        color = RiftMuted,
                        fontSize = 8.sp,
                        lineHeight = 12.sp
                    )
                    state.sourceLabel == "GitHub 直连" -> Text(
                        "GitHub 官方兜底通道 · APK 仍执行 SHA-256、包名、版本号与固定签名校验",
                        color = RiftMuted,
                        fontSize = 8.sp,
                        lineHeight = 12.sp
                    )
                }
            }
            Text(state.status, color = if (state.available) RiftCyan else RiftMuted, fontSize = 11.sp, lineHeight = 16.sp)

            if (state.changelog.isNotBlank()) {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("更新内容", color = RiftMuted, fontSize = 9.sp)
                    Spacer(Modifier.height(5.dp))
                    Text(state.changelog, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }

            if (state.downloading) {
                val sizeText = if (state.totalBytes > 0) {
                    "%.1f MB / %.1f MB".format(state.downloadedBytes / 1048576f, state.totalBytes / 1048576f)
                } else {
                    "%.1f MB".format(state.downloadedBytes / 1048576f)
                }
                Text("${state.progressPercent}% · $sizeText", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }

            state.error?.let { Text(it, color = RiftRed, fontSize = 9.sp) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = AppUpdateManager::checkForUpdates,
                    enabled = !state.checking && !state.downloading,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = RiftPanelAlt, contentColor = RiftText),
                    shape = CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp)
                ) {
                    Text(if (state.checking) "检查中" else "检查更新", fontSize = 10.sp)
                }
                Button(
                    onClick = AppUpdateManager::downloadAndInstall,
                    enabled = state.available && !state.downloading,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = RiftCyan, contentColor = RiftBg),
                    shape = CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp)
                ) {
                    Text(if (state.downloading) "下载中" else "下载并安装", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = RiftPanelAlt, contentColor = RiftMuted),
                shape = CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp)
            ) {
                Text("关闭", fontSize = 10.sp)
            }
        }
    }
}
