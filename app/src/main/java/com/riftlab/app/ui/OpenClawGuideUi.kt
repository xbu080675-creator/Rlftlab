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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.riftlab.app.data.RiftClawContract
import com.riftlab.app.data.RiftClawProbe
import kotlinx.coroutines.launch

private const val BOOTSTRAP =
    "curl -fL https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/scripts/riftclaw-bootstrap.sh -o ~/riftclaw-bootstrap.sh && chmod 700 ~/riftclaw-bootstrap.sh && ~/riftclaw-bootstrap.sh"
private const val VERIFY_PLUGIN = "openclaw plugins list | grep -i weibo"
private const val VERIFY_SKILL = "openclaw skills list | grep -i weibo-search"
private const val DEFAULT_MODEL_ENDPOINT = "http://127.0.0.1:18080/v1"

@Composable
fun OpenClawGuideHost(content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        content()
        Button(
            onClick = { open = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
                .height(44.dp),
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
    var copied by remember { mutableStateOf("") }
    var gatewayStatus by remember { mutableStateOf("未检测") }
    var gatewayTesting by remember { mutableStateOf(false) }
    var modelEndpoint by remember { mutableStateOf(DEFAULT_MODEL_ENDPOINT) }
    var modelStatus by remember { mutableStateOf("可选 · 未检测") }
    var modelTesting by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = RiftBg, contentColor = RiftText) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("RiftClaw 外挂部署器", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("只服务微博检索 · localhost-only · deny-by-default", color = RiftMuted, fontSize = 11.sp)
                    }
                    TextButton(onClick = onClose) { Text("关闭") }
                }

                Spacer(Modifier.height(14.dp))
                GuideBlock(
                    title = "它不是通用 Agent",
                    body = "RiftClaw 只给 RiftLab 提供微博首发发现通道。官网/官方源轮询始终保留；龙虾不可用时直接回退，不会为了恢复功能扩大权限。shell、文件读写/删除、插件管理、root/ADB/Shizuku/Magisk 都不属于 RiftLab 能力。"
                )

                StatusBlock(
                    title = "1 / 本地 Gateway",
                    value = gatewayStatus,
                    action = if (gatewayTesting) "检测中…" else "检测 ${RiftClawContract.DEFAULT_GATEWAY}",
                    enabled = !gatewayTesting,
                    onAction = {
                        gatewayTesting = true
                        gatewayStatus = "正在检查回环端口…"
                        scope.launch {
                            val result = RiftClawProbe.probeGateway()
                            gatewayStatus = if (result.reachable) {
                                "已发现本地 Gateway · ${result.detail}"
                            } else {
                                "未连接 · ${result.detail}"
                            }
                            gatewayTesting = false
                        }
                    }
                )

                GuideCommand(
                    title = "2 / 一条命令部署微博专用 RiftClaw",
                    command = BOOTSTRAP,
                    copied = copied
                ) { copied = copy(context, "一键部署", BOOTSTRAP) }
                GuideBlock(
                    title = "部署器会自动处理",
                    body = "检测 OpenClaw → 安装微博插件 → 安装卡住时自动改走持久化 npm pack + tar + --link → plugins.allow 只留微博插件 → 安全输入 AppID/AppSecret → 检查 weibo-search Skill。你刚才踩过的 Extracting 卡死问题已经纳入 fallback。"
                )

                Spacer(Modifier.height(8.dp))
                Text("3 / 模型（可选增强）", color = RiftCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(
                    "微博检索权限与模型分离。模型只允许整理经过清洗的结果，不能新增工具或执行动作。本地 OpenAI-compatible 服务可在这里做预检。",
                    color = RiftText,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = modelEndpoint,
                    onValueChange = { modelEndpoint = it; modelStatus = "可选 · 未检测" },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("本地模型 API") },
                    supportingText = { Text("只接受 localhost / 127.0.0.1 / ::1") }
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    enabled = !modelTesting,
                    onClick = {
                        modelTesting = true
                        modelStatus = "正在请求 /v1/models…"
                        scope.launch {
                            val result = RiftClawProbe.probeOpenAiModel(modelEndpoint)
                            modelStatus = if (result.reachable) {
                                val models = result.modelIds.joinToString().takeIf { it.isNotBlank() }
                                if (models == null) "模型 API 可用" else "模型 API 可用 · $models"
                            } else {
                                "模型不可用 · ${result.detail}"
                            }
                            modelTesting = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RiftPanelAlt, contentColor = RiftText)
                ) { Text(if (modelTesting) "检测中…" else "检测本地模型") }
                Spacer(Modifier.height(5.dp))
                Text(modelStatus, color = RiftMuted, fontSize = 11.sp)
                Text("建议：插件较多时至少 16K context；32K 会明显增加 KV Cache 内存。", color = RiftMuted, fontSize = 10.sp)

                GuideBlock(
                    title = "4 / 防提示注入",
                    body = "RiftLab 只发送日期、赛区、双方队名和 starting_roster 这类结构化字段，不把任意用户提示交给龙虾。微博正文、评论、OCR、智搜摘要全部视为不可信数据，先经过程序级 sanitizer，再进入任何可选模型。搜索结果仍必须经过官方来源、日期、对阵和 5+5 首发校验。"
                )

                GuideCommand("5 / 故障排查 · 插件", VERIFY_PLUGIN, copied) {
                    copied = copy(context, "验证微博插件", VERIFY_PLUGIN)
                }
                GuideCommand("6 / 故障排查 · Skill", VERIFY_SKILL, copied) {
                    copied = copy(context, "验证微博搜索 Skill", VERIFY_SKILL)
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    if (copied.isBlank()) "部署时只需要复制第 2 步；其余命令仅用于故障排查。" else "已复制：$copied",
                    color = RiftCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun StatusBlock(
    title: String,
    value: String,
    action: String,
    enabled: Boolean,
    onAction: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(RiftPanelAlt, CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
            .padding(12.dp)
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
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(RiftPanelAlt, CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
            .padding(12.dp)
    ) {
        Text(title, color = RiftCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(body, color = RiftText, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun GuideCommand(title: String, command: String, copied: String, onCopy: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(RiftPanelAlt, CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
            .clickable(onClick = onCopy)
            .padding(12.dp)
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
