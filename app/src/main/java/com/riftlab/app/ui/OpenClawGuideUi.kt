package com.riftlab.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

private const val INSTALL_PLUGIN = "openclaw plugins install @wecode-ai/weibo-openclaw-plugin"
private const val SET_APP_ID = "openclaw config set 'channels.weibo.appId' '你的AppID'"
private const val SET_APP_SECRET = "openclaw config set 'channels.weibo.appSecret' '你的AppSecret'"
private const val START_GATEWAY = "openclaw gateway"

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
            Text("养龙虾向导", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    if (open) OpenClawGuideDialog(onClose = { open = false })
}

@Composable
private fun OpenClawGuideDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf("") }

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
                        Text("养龙虾向导", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("OPENCLAW + 微博龙虾 · 本机增强首发通道", color = RiftMuted, fontSize = 11.sp)
                    }
                    TextButton(onClick = onClose) { Text("关闭") }
                }

                Spacer(Modifier.height(14.dp))
                GuideBlock(
                    title = "0 / 先理解它",
                    body = "RiftLab 的官网轮询始终保留。OpenClaw 只是本机增强通道：微博官号先发时，可以比官网更早拿到首发。没有龙虾也能正常用，只是可能晚几分钟。"
                )
                GuideBlock(
                    title = "1 / 准备 OpenClaw Gateway",
                    body = "推荐在电脑/Linux/WSL2 上运行 Gateway。Android/Termux 属于实验路线，但能在手机本机运行。无论哪种方式，都不要用 root 身份启动 Gateway，也不要给它 su、Magisk、Shizuku 或 ADB shell 权限。"
                )
                GuideCommand("2 / 安装微博插件", INSTALL_PLUGIN, copied) { copied = copy(context, INSTALL_PLUGIN) }
                GuideBlock(
                    title = "3 / 获取微博龙虾凭证",
                    body = "微博登录 → 搜索并关注“微博龙虾助手” → 点击“连接龙虾” → 获取 AppID 和 AppSecret。凭证只配置给你自己的 OpenClaw，不要发到聊天、截图、GitHub 或日志里。"
                )
                GuideCommand("4 / 配置 AppID", SET_APP_ID, copied) { copied = copy(context, SET_APP_ID) }
                GuideCommand("5 / 配置 AppSecret", SET_APP_SECRET, copied) { copied = copy(context, SET_APP_SECRET) }
                GuideCommand("6 / 启动 Gateway", START_GATEWAY, copied) { copied = copy(context, START_GATEWAY) }
                GuideBlock(
                    title = "7 / RiftLab 安全边界",
                    body = "RiftLab 只允许回环地址上的 OpenClaw，并且只开放 Gateway 健康检查和微博搜索。shell、exec、文件写入/删除、插件安装、root/su/ADB/Shizuku/Magisk 一律默认拒绝。微博正文也按不可信输入处理。"
                )
                GuideBlock(
                    title = "8 / 数据优先级",
                    body = "本地龙虾命中且通过日期、对阵、官方账号和 5+5 首发校验后可立即显示；官网轮询继续后台运行，官网随后命中后升级为交叉确认。"
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (copied.isBlank()) "点击任意命令即可复制" else "已复制：$copied",
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
        Text(if (copied == title) "已复制" else "点这里复制命令", color = RiftMuted, fontSize = 10.sp)
    }
}

private fun copy(context: Context, value: String): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("RiftLab OpenClaw", value))
    return when (value) {
        INSTALL_PLUGIN -> "安装微博插件"
        SET_APP_ID -> "配置 AppID"
        SET_APP_SECRET -> "配置 AppSecret"
        START_GATEWAY -> "启动 Gateway"
        else -> "命令"
    }
}
