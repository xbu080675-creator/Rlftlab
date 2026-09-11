package com.riftlab.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.CitoApiConfig
import com.riftlab.app.data.ProviderCredentialStore
import com.riftlab.app.overlay.DraftHudSimulation
import com.riftlab.app.overlay.TacticalHudSimulation
import com.riftlab.app.stream.StreamLauncher

@Composable
internal fun RealtimeSourceSettingsDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val citoConfigured by ProviderCredentialStore.citoConfigured.collectAsState()
    val tachioConfigured by ProviderCredentialStore.tachioConfigured.collectAsState()
    val draftSim by DraftHudSimulation.state.collectAsState()
    val tacticalSim by TacticalHudSimulation.state.collectAsState()

    var citoDraft by remember { mutableStateOf("") }
    var tachioDraft by remember { mutableStateOf("") }
    var revealCito by remember { mutableStateOf(false) }
    var revealTachio by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Column {
                Text("实时数据源 / API KEYS", fontWeight = FontWeight.Bold)
                Text(
                    "GLOBAL LIVE PROVIDER SETTINGS",
                    color = RiftMuted,
                    fontSize = 11.sp
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "CITO API · LOL REST + WEBSOCKET",
                    color = RiftCyan,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (citoConfigured) {
                        "Cito API Key 已配置。RiftLab 后续可直接复用同一个 Key 访问 LoL REST，并在订阅包含 Live WebSockets 时建立 WSS。"
                    } else {
                        "预留 Cito API 接口。购买 One Game / Pro 等 LoL 套餐后，把 Dashboard 生成的 API Key 填在这里即可。"
                    },
                    color = RiftMuted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(8.dp))
                Text("REST  ${CitoApiConfig.REST_BASE_URL}", color = RiftMuted, fontSize = 11.sp)
                Text("WSS   ${CitoApiConfig.LIVE_WEBSOCKET_URL}", color = RiftMuted, fontSize = 11.sp)
                Text("AUTH  ${CitoApiConfig.API_KEY_HEADER}", color = RiftMuted, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = citoDraft,
                    onValueChange = { citoDraft = it; statusText = "" },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Cito API Key") },
                    placeholder = { Text(if (citoConfigured) "••••••••  已配置" else "粘贴 Cito API Key") },
                    singleLine = true,
                    visualTransformation = if (revealCito) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { revealCito = !revealCito }) {
                            Text(if (revealCito) "隐藏" else "显示")
                        }
                    }
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val value = citoDraft.trim()
                            if (value.isNotEmpty()) {
                                ProviderCredentialStore.saveCitoApiKey(value)
                                citoDraft = ""
                                revealCito = false
                                statusText = "Cito API Key 已加密保存"
                            } else {
                                statusText = if (citoConfigured) "现有 Cito Key 保持不变" else "请先填写 Cito API Key"
                            }
                        }
                    ) { Text("保存 Cito") }
                    if (citoConfigured) {
                        TextButton(
                            onClick = {
                                ProviderCredentialStore.clearCitoApiKey()
                                citoDraft = ""
                                statusText = "Cito API Key 已清除"
                            }
                        ) { Text("清除") }
                    }
                }

                Spacer(Modifier.height(22.dp))
                Text(
                    "TACHIO SPORTS · WEBSOCKET",
                    color = RiftCyan,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (tachioConfigured) {
                        "Tachio API Key 已保存。留空不会覆盖现有 Key；输入新 Key 后保存即可替换。"
                    } else {
                        "保留 Tachio Sports 凭据位，后续作为另一条全球实时 Provider 使用。"
                    },
                    color = RiftMuted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = tachioDraft,
                    onValueChange = { tachioDraft = it; statusText = "" },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tachio Sports API Key") },
                    placeholder = { Text(if (tachioConfigured) "••••••••  已配置" else "粘贴 API Key") },
                    singleLine = true,
                    visualTransformation = if (revealTachio) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { revealTachio = !revealTachio }) {
                            Text(if (revealTachio) "隐藏" else "显示")
                        }
                    }
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val value = tachioDraft.trim()
                            if (value.isNotEmpty()) {
                                ProviderCredentialStore.saveTachioApiKey(value)
                                tachioDraft = ""
                                revealTachio = false
                                statusText = "Tachio API Key 已加密保存"
                            } else {
                                statusText = if (tachioConfigured) "现有 Tachio Key 保持不变" else "请先填写 Tachio API Key"
                            }
                        }
                    ) { Text("保存 Tachio") }
                    if (tachioConfigured) {
                        TextButton(
                            onClick = {
                                ProviderCredentialStore.clearTachioApiKey()
                                tachioDraft = ""
                                statusText = "Tachio API Key 已清除"
                            }
                        ) { Text("清除") }
                    }
                }

                Spacer(Modifier.height(22.dp))
                Text(
                    "RIFTSCREEN · DRAFT HUD SIMULATOR",
                    color = RiftCyan,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "在正式 BP WebSocket 接入前，用本地脚本模拟 10 次 Pick、选手英雄胜率、对位形成与 Counter/Lane Edge 弹层。全屏 HUD 本身完全触摸穿透，只有屏幕右侧 RIFT 控制条可操作；下方约 40% 默认留给直播官方 BP 包装。所有百分比均标记 SIMULATION，不会写入真实赛事档案。",
                    color = RiftMuted,
                    fontSize = 11.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (draftSim.active) "SIM ${draftSim.step}/${draftSim.totalSteps} · ${draftSim.message}" else "SIM 待机",
                    color = if (draftSim.active) RiftCyan else RiftMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            if (StreamLauncher.startOverlay(context)) {
                                DraftHudSimulation.startAuto()
                                statusText = "BP 自动模拟已启动 · 现在切到直播/视频 APP 查看全屏 HUD"
                            } else {
                                statusText = "请先授予悬浮窗权限，返回后再点一次 BP 自动模拟"
                            }
                        }
                    ) { Text("自动模拟") }
                    TextButton(
                        onClick = {
                            if (StreamLauncher.startOverlay(context)) {
                                DraftHudSimulation.startManual()
                                statusText = "BP 手动模拟已准备 · 切到直播后用右侧 RIFT 控制条逐手推进"
                            } else {
                                statusText = "请先授予悬浮窗权限"
                            }
                        }
                    ) { Text("手动") }
                    if (draftSim.active) {
                        TextButton(
                            onClick = {
                                DraftHudSimulation.stop()
                                statusText = "BP 模拟已停止"
                            }
                        ) { Text("停止") }
                    }
                }

                Spacer(Modifier.height(22.dp))
                Text(
                    "RIFTSCREEN · LIVE HUD SIMULATOR",
                    color = RiftCyan,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "手机实测用假数据脚本：GLOBAL → FIGHT → GLOBAL。只看覆盖位置、信息密度和切换节奏，不写入真实比赛数据。",
                    color = RiftMuted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (tacticalSim.active) "SIM ${tacticalSim.step}/${tacticalSim.totalSteps} · ${tacticalSim.phase.name}" else "SIM 待机",
                    color = if (tacticalSim.active) RiftCyan else RiftMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            if (StreamLauncher.startHudSimulation(context)) {
                                statusText = "赛中 HUD 模拟已启动 · 切到直播/视频 APP 看 GLOBAL/FIGHT 自动切换"
                            } else {
                                statusText = "请先授予悬浮窗权限；返回后 HUD 模拟会直接启动"
                            }
                        }
                    ) { Text("赛中 HUD 模拟") }
                    if (tacticalSim.active) {
                        TextButton(
                            onClick = {
                                TacticalHudSimulation.stop()
                                statusText = "赛中 HUD 模拟已停止"
                            }
                        ) { Text("停止") }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "所有 Provider Key 均使用 Android Keystore AES-GCM 加密，仅保存在本机；不会写入源码、GitHub、日志或比赛归档。",
                    color = RiftMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                if (statusText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(statusText, color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("关闭") }
        }
    )
}