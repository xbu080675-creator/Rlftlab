package com.riftlab.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.imageLoader
import com.riftlab.app.data.RiftCacheSnapshot
import com.riftlab.app.data.StorageCacheManager
import kotlinx.coroutines.launch

@Composable
internal fun CacheSettingsPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<RiftCacheSnapshot?>(null) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            snapshot = StorageCacheManager.inspect(context)
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "STORAGE · CACHE",
            color = RiftCyan,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            snapshot?.let {
                "当前可清理缓存 ${StorageCacheManager.formatBytes(it.totalBytes)} · ${it.fileCount} 个文件"
            } ?: "正在统计缓存…",
            color = RiftMuted,
            fontSize = 11.sp,
            lineHeight = 17.sp
        )
        Text(
            "只清理图片、更新包/断点和其他临时文件；比赛档案、API Key、设置与已下载本地 AI 模型不会被删除。缓存超过约 768 MB 时会自动回收到约 384 MB。",
            color = RiftMuted,
            fontSize = 11.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !working,
                onClick = {
                    working = true
                    message = "正在清理…"
                    scope.launch {
                        // Coil owns the active image-cache journal; clear it through Coil first,
                        // then remove the remaining disposable roots.
                        runCatching { context.imageLoader.diskCache?.clear() }
                        val result = StorageCacheManager.clearDisposableCache(context)
                        snapshot = StorageCacheManager.inspect(context)
                        message = "已释放 ${StorageCacheManager.formatBytes(result.freedBytes)}"
                        working = false
                    }
                }
            ) { Text(if (working) "清理中" else "清理缓存") }
            TextButton(enabled = !working, onClick = { refresh() }) { Text("重新统计") }
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(message, color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}
