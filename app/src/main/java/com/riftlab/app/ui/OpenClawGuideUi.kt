package com.riftlab.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.riftlab.app.data.ProviderCredentialStore
import com.riftlab.app.data.RiftClawClient
import com.riftlab.app.data.RiftClawContract
import com.riftlab.app.data.RiftClawProbe
import kotlinx.coroutines.launch

private const val BOOTSTRAP =
    "curl -fL https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/scripts/riftclaw-bootstrap.sh -o ~/riftclaw-bootstrap.sh && chmod 700 ~/riftclaw-bootstrap.sh && ~/riftclaw-bootstrap.sh"
private const val START_RIFTCLAW = "~/.local/share/riftclaw/start.sh"
private const val VERIFY_PLUGIN = "openclaw --profile riftclaw plugins list | grep -i weibo"
private const val VERIFY_SKILL = "openclaw --profile riftclaw skills list | grep -i weibo-search"
private const val DEFAULT_MODEL_ENDPOINT = "http://127.0.0.1:18080/v1"

@Composable
fun OpenClawGuideHost(content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        content()
        Button(
            onClick = { open = true },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).height(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RiftPanelAlt, contentColor = RiftText),
            shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
        ) {
            Icon(Icons.Default.Pets, null, tint = RiftCyan)
            Spacer(Modifier.width(7.dp))
            Text("RiftClaw", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    if (open) RiftClawDeployerDialog(onClose = { open = false })
}

@Composable
private fun RiftClawDeployerDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paired by ProviderCredentialStore.riftClawPaired.collectAsState()
    var copied by remember { mutableStateOf("") }
    var bridgeStatus by remember { mutableStateOf("未检测") }
    var bridgeTesting by remember { mutableStateOf(false) }
    var pairingToken by remember { mutableStateOf("") }
    var pairingStatus by remember { mutableStateOf(if (paired) "已保存配对码" else "尚未配对") }
    var modelEndpoint by remember { mutableStateOf(DEFAULT_MODEL_ENDPOINT) }
    var modelStatus by remember { mutableStateOf("可选 · 未检测") }
    var modelTesting by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = RiftBg, contentColor = RiftText) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("RiftClaw 外挂部署器", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("只服务微博检索 · 独立 profile · 双层 localhost 隔离", color = RiftMuted, fontSize = 11.sp)
                    }
                    TextButton(onClick = onClose) { Text("关闭") }
                }

                Spacer(Modifier.height(14.dp))
                GuideBlock(
                    "它不是通用 Agent",
                    "RiftClaw 只给 RiftLab 提供微博首发发现通道。官网/官方源轮询始终保留。部署器创建独立 riftclaw profile，不碰你平时的 OpenClaw；RiftLab 只连接 127.0.0.1:18790 的窄权限 Bridge，真正的 OpenClaw Gateway 在 127.0.0.1:18791，operator token 永远不交给 RiftLab。"
                )

                GuideCommand("1 / 一条命令部署", BOOTSTRAP, copied) {
                    copied = copy(context, "一键部署", BOOTSTRAP)
                }
                GuideBlock(
                    "部署器自动吃掉这些坑",
                    "自动安装微博插件；Extracting 卡死时切换 npm pack + tar + 持久化 --link；plugins.allow 只留微博插件；tools.allow 只留 weibo_search；关闭微博私信频道；生成独立 Gateway token 与 RiftClaw 配对码；安装 18790 Bridge。"
                )
                GuideCommand("2 / 启动专用 RiftClaw", START_RIFTCLAW, copied) {
                    copied = copy(context, "启动 RiftClaw", START_RIFTCLAW)
                }

                StatusBlock(
                    title = "3 / 检测窄权限 Bridge",
                    value = bridgeStatus,
                    action = if (bridgeTesting) "检测中…" else "检测 ${RiftClawContract.DEFAULT_ENDPOINT}",
                    enabled = !bridgeTesting,
                    onAction = {
                        bridgeTesting = true
                        bridgeStatus = "正在检查 18790…"
                        scope.launch {
                            val result = RiftClawClient.status()
                            bridgeStatus = result.fold(
                                onSuccess = { if (it.ready) "Bridge 已就绪 · 仅 weibo_search" else "Bridge 在线但未 ready" },
                                onFailure = { "未连接 · ${it.message ?: it::class.java.simpleName}" }
                            )
                            bridgeTesting = false
                        }
                    }
                )

                Spacer(Modifier.height(8.dp))
                Text("4 / 配对 RiftLab", color = RiftCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    "部署完成后终端只显示一次 RiftLab 配对码。它只能访问 18790 Bridge，不是 OpenClaw operator token。",
                    color = RiftText, fontSize = 12.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(7.dp))
                OutlinedTextField(
                    value = pairingToken,
                    onValueChange = { pairingToken = it; pairingStatus = "尚未保存" },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (paired) "RiftClaw 配对码 · 已配置" else "RiftClaw 配对码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            runCatching { ProviderCredentialStore.saveRiftClawBridgeToken(pairingToken) }
                                .onSuccess { pairingToken = ""; pairingStatus = "配对码已使用 Android Keystore 加密保存" }
                                .onFailure { pairingStatus = it.message ?: "保存失败" }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RiftPanelAlt, contentColor = RiftText)
                    ) { Text("保存配对码") }
                    if (paired) {
                        TextButton(onClick = {
                            ProviderCredentialStore.clearRiftClawBridgeToken()
                            pairingToken = ""
                            pairingStatus = "配对已清除"
                        }) { Text("清除") }
                    }
                }
                Text(pairingStatus, color = RiftMuted, fontSize = 10.sp)

                GuideBlock(
                    "5 / 微博搜索不需要模型",
                    "这一版已经改成 Bridge 直接调用 OpenClaw 的 weibo_search 工具，不经过 Agent 推理，因此不会再因为 API Key、8K context、模型加载 503 让首发检索失效。模型仅作为可选的结果整理层，关闭模型也不影响微博搜索。"
                )

                Text("可选 / 本地模型预检", color = RiftCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = modelEndpoint,
                    onValueChange = { modelEndpoint = it; modelStatus = "可选 · 未检测" },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("本地 OpenAI-compatible API") },
                    supportingText = { Text("只接受 localhost / 127.0.0.1 / ::1") }
                )
                Button(
                    enabled = !modelTesting,
                    onClick = {
                        modelTesting = true
                        modelStatus = "正在请求 /v1/models…"
                        scope.launch {
                            val result = RiftClawProbe.probeOpenAiModel(modelEndpoint)
                            modelStatus = if (result.reachable) {
                                result.modelIds.joinToString().takeIf { it.isNotBlank() }?.let { "模型 API 可用 · $it" } ?: "模型 API 可用"
                            } else "模型不可用 · ${result.detail}"
                            modelTesting = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RiftPanelAlt, contentColor = RiftText)
                ) { Text(if (modelTesting) "检测中…" else "检测本地模型") }
                Text(modelStatus, color = RiftMuted, fontSize = 10.sp)

                GuideBlock(
                    "防提示注入",
                    "RiftLab 只发送日期、赛区、双方队名和 starting_roster 等结构化字段；Bridge 自己拼搜索词。微博正文、评论、OCR、智搜摘要都是不可信数据，Bridge 和 APP 各清洗一次。任何文本都无权新增工具、修改策略、读密钥、执行命令。最终仍需官方来源 + 日期 + 对阵 + 5+5 校验才能发布首发。"
                )

                GuideCommand("故障排查 · 插件", VERIFY_PLUGIN, copied) { copied = copy(context, "验证微博插件", VERIFY_PLUGIN) }
                GuideCommand("故障排查 · Skill", VERIFY_SKILL, copied) { copied = copy(context, "验证微博搜索 Skill", VERIFY_SKILL) }

                Spacer(Modifier.height(10.dp))
                Text(
                    if (copied.isBlank()) "正常用户只需要：部署 → 启动 → 填配对码。" else "已复制：$copied",
                    color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun StatusBlock(title: String, value: String, action: String, enabled: Boolean, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background(RiftPanelAlt, CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp)).padding(12.dp)
    ) {
        Text(title, color = RiftCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(value, color = RiftText, fontSize = 12.sp)
        Spacer(Modifier.height(7.dp))
        TextButton(enabled = enabled, onClick = onAction) { Text(action) }
    }
}

@Composable
private fun GuideBlock(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background(RiftPanelAlt, CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp)).padding(12.dp)
    ) {
        Text(title, color = RiftCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(body, color = RiftText, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun GuideCommand(title: String, command: String, copied: String, onCopy: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background(RiftPanelAlt, CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
            .clickable(onClick = onCopy).padding(12.dp)
    ) {
        Text(title, color = RiftCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(command, color = RiftText, fontSize = 11.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(5.dp))
        Text(if (copied == title) "已复制" else "点这里复制", color = RiftMuted, fontSize = 10.sp)
    }
}

private fun copy(context: Context, label: String, value: String): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("RiftClaw", value))
    return label
}
