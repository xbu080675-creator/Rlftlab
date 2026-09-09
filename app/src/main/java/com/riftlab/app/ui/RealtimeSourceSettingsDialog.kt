package com.riftlab.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.ProviderCredentialStore

@Composable
internal fun RealtimeSourceSettingsDialog(onClose: () -> Unit) {
    val tachioConfigured by ProviderCredentialStore.tachioConfigured.collectAsState()
    var tachioDraft by remember { mutableStateOf("") }
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
                    fontSize = 10.sp
                )
            }
        },
        text = {
            Column {
                Text(
                    "TACHIO SPORTS · WEBSOCKET",
                    color = RiftCyan,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (tachioConfigured) {
                        "API Key 已保存。留空不会覆盖现有 Key；输入新 Key 后保存即可替换。"
                    } else {
                        "填写 Tachio Sports API Key 后，RiftLab 的全球实时总线可以直接读取它。"
                    },
                    color = RiftMuted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(12.dp))
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
                Spacer(Modifier.height(8.dp))
                Text(
                    "Key 使用 Android Keystore 加密后仅保存在本机；不会写进源码、GitHub、日志或比赛归档。",
                    color = RiftMuted,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
                if (statusText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(statusText, color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                if (tachioConfigured) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            ProviderCredentialStore.clearTachioApiKey()
                            tachioDraft = ""
                            statusText = "Tachio API Key 已清除"
                        }
                    ) {
                        Text("清除已保存的 Key")
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClose) { Text("关闭") }
                Button(
                    onClick = {
                        val value = tachioDraft.trim()
                        if (value.isNotEmpty()) {
                            ProviderCredentialStore.saveTachioApiKey(value)
                            tachioDraft = ""
                            revealTachio = false
                            statusText = "Tachio API Key 已加密保存"
                        } else {
                            statusText = if (tachioConfigured) "现有 Key 保持不变" else "请先填写 API Key"
                        }
                    }
                ) {
                    Text("保存")
                }
            }
        }
    )
}
