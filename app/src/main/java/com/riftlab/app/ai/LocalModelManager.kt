package com.riftlab.app.ai

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class LocalModelInstallStatus {
    IDLE,
    DOWNLOADING,
    VERIFYING,
    VERIFIED,
    READY,
    FAILED
}

data class LocalModelInstallState(
    val modelId: String? = null,
    val status: LocalModelInstallStatus = LocalModelInstallStatus.IDLE,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val localPath: String? = null,
    val sha256: String? = null,
    val benchmarkLatencyMs: Long? = null,
    val message: String = ""
)

/**
 * Owns persistent local-model files under filesDir/local_ai_models.
 *
 * Model assets are deliberately outside cacheDir so normal cache cleanup never removes them.
 * Download completion is not readiness: SHA-256 plus a real LiteRT-LM load/inference benchmark are
 * both required before the runtime can be promoted to READY.
 */
object LocalModelManager {
    private const val PREFS = "riftlab_local_model_manager"
    private const val MODELS_DIR = "local_ai_models"

    // Verified LiteRT Community build of Qwen3-0.6B INT4 no-think. Keep this fallback pinned so the
    // first real-device rollout does not depend on a mutable catalog response.
    private const val QWEN3_NO_THINK_ID = "qwen3-0.6b-int4-nothink"
    private const val QWEN3_NO_THINK_URL =
        "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm?download=true"
    private const val QWEN3_NO_THINK_SHA256 =
        "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val mutableState = MutableStateFlow(LocalModelInstallState())
    val state: StateFlow<LocalModelInstallState> = mutableState.asStateFlow()

    fun initialize(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val modelId = prefs.getString("model_id", null)
            val path = prefs.getString("path", null)
            val sha = prefs.getString("sha256", null)
            val benchmarkMs = prefs.getLong("benchmark_ms", -1L).takeIf { it >= 0L }
            val file = path?.let(::File)
            mutableState.value = if (modelId != null && file?.isFile == true && !sha.isNullOrBlank()) {
                LocalModelInstallState(
                    modelId = modelId,
                    status = LocalModelInstallStatus.VERIFIED,
                    downloadedBytes = file.length(),
                    totalBytes = file.length(),
                    localPath = file.absolutePath,
                    sha256 = sha,
                    benchmarkLatencyMs = benchmarkMs,
                    message = "模型文件已验证，等待本次进程真实运行时基准测试"
                )
            } else {
                LocalModelInstallState()
            }
        }
    }

    fun resolvedDownloadMetadata(descriptor: LocalModelDescriptor): Pair<String, String>? {
        val catalogUrl = descriptor.downloadUrl?.trim().orEmpty()
        val catalogSha = descriptor.sha256?.trim()?.lowercase().orEmpty()
        if (catalogUrl.isNotBlank() && catalogSha.length == 64) return catalogUrl to catalogSha
        if (descriptor.id == QWEN3_NO_THINK_ID) return QWEN3_NO_THINK_URL to QWEN3_NO_THINK_SHA256
        return null
    }

    fun installSelected(context: Context, descriptor: LocalModelDescriptor) {
        val metadata = resolvedDownloadMetadata(descriptor)
        if (metadata == null) {
            mutableState.value = LocalModelInstallState(
                modelId = descriptor.id,
                status = LocalModelInstallStatus.FAILED,
                message = "模型目录尚未提供可验证的兼容下载包"
            )
            return
        }
        val (url, expectedSha) = metadata
        if (mutableState.value.status == LocalModelInstallStatus.DOWNLOADING ||
            mutableState.value.status == LocalModelInstallStatus.VERIFYING
        ) return

        val app = context.applicationContext
        scope.launch {
            val modelDir = File(app.filesDir, MODELS_DIR).apply { mkdirs() }
            val finalFile = File(modelDir, "${safeId(descriptor.id)}.litertlm")
            val partFile = File(modelDir, "${safeId(descriptor.id)}.part")
            runCatching {
                mutableState.value = LocalModelInstallState(
                    modelId = descriptor.id,
                    status = LocalModelInstallStatus.DOWNLOADING,
                    message = "正在下载 LiteRT-LM 模型…"
                )

                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("模型下载响应为空")
                    val total = body.contentLength().takeIf { it > 0L }
                    partFile.parentFile?.mkdirs()
                    body.byteStream().use { input ->
                        FileOutputStream(partFile, false).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var downloaded = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                mutableState.value = mutableState.value.copy(
                                    downloadedBytes = downloaded,
                                    totalBytes = total
                                )
                            }
                            output.fd.sync()
                        }
                    }
                }

                mutableState.value = mutableState.value.copy(
                    status = LocalModelInstallStatus.VERIFYING,
                    message = "正在校验 SHA-256…"
                )
                val actualSha = sha256(partFile)
                if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                    partFile.delete()
                    error("SHA-256 校验失败")
                }
                if (finalFile.exists() && !finalFile.delete()) error("无法替换旧模型文件")
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    if (!partFile.delete()) partFile.deleteOnExit()
                }

                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("model_id", descriptor.id)
                    .putString("path", finalFile.absolutePath)
                    .putString("sha256", actualSha)
                    .remove("benchmark_ms")
                    .apply()

                mutableState.value = LocalModelInstallState(
                    modelId = descriptor.id,
                    status = LocalModelInstallStatus.VERIFIED,
                    downloadedBytes = finalFile.length(),
                    totalBytes = finalFile.length(),
                    localPath = finalFile.absolutePath,
                    sha256 = actualSha,
                    message = "下载与校验通过；下一步必须执行真实模型加载与短推理"
                )
            }.onFailure { error ->
                partFile.delete()
                mutableState.value = LocalModelInstallState(
                    modelId = descriptor.id,
                    status = LocalModelInstallStatus.FAILED,
                    message = error.message ?: "模型安装失败"
                )
            }
        }
    }

    /** Runtime layer calls this only after model load + latency/thermal benchmark both pass. */
    fun markReady(modelId: String, localPath: String, benchmarkLatencyMs: Long) {
        val current = mutableState.value
        if (current.modelId != modelId || current.localPath != localPath || current.status != LocalModelInstallStatus.VERIFIED) return
        val app = runCatching { com.riftlab.app.RiftLabApplication.appContext }.getOrNull()
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putLong("benchmark_ms", benchmarkLatencyMs)
            ?.apply()
        mutableState.value = current.copy(
            status = LocalModelInstallStatus.READY,
            benchmarkLatencyMs = benchmarkLatencyMs,
            message = "真实模型加载/短推理通过 · ${benchmarkLatencyMs}ms · 可启用"
        )
    }

    fun removeInstalled(context: Context) {
        val app = context.applicationContext
        scope.launch {
            LiteRtLocalAiRuntime.shutdown()
            val current = mutableState.value
            current.localPath?.let(::File)?.delete()
            File(app.filesDir, MODELS_DIR).listFiles()
                ?.filter { it.name.endsWith(".part") || it.name.endsWith(".litertlm") }
                ?.forEach { it.delete() }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            LocalAiCore.disableModel()
            mutableState.value = LocalModelInstallState(message = "本地模型已删除，已回到规则模式")
        }
    }

    private fun safeId(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
