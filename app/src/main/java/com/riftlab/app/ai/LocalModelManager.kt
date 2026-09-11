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
    val message: String = ""
)

/**
 * Owns persistent local-model files under filesDir/local_ai_models.
 *
 * Model assets are deliberately outside cacheDir so normal cache cleanup never removes them.
 * A model is never considered usable merely because the download completed: the catalog must provide
 * both a concrete package URL and expected SHA-256, the file must verify, and a runtime-specific
 * benchmark/backend installer must subsequently promote it to READY.
 */
object LocalModelManager {
    private const val PREFS = "riftlab_local_model_manager"
    private const val MODELS_DIR = "local_ai_models"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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
            val file = path?.let(::File)
            mutableState.value = if (modelId != null && file?.isFile == true && !sha.isNullOrBlank()) {
                LocalModelInstallState(
                    modelId = modelId,
                    status = LocalModelInstallStatus.VERIFIED,
                    downloadedBytes = file.length(),
                    totalBytes = file.length(),
                    localPath = file.absolutePath,
                    sha256 = sha,
                    message = "模型文件已验证，等待运行时基准测试"
                )
            } else {
                LocalModelInstallState()
            }
        }
    }

    fun installSelected(context: Context, descriptor: LocalModelDescriptor) {
        val url = descriptor.downloadUrl?.trim().orEmpty()
        val expectedSha = descriptor.sha256?.trim()?.lowercase().orEmpty()
        if (url.isBlank() || expectedSha.length != 64) {
            mutableState.value = LocalModelInstallState(
                modelId = descriptor.id,
                status = LocalModelInstallStatus.FAILED,
                message = "模型目录尚未提供可验证的兼容下载包"
            )
            return
        }
        if (mutableState.value.status == LocalModelInstallStatus.DOWNLOADING ||
            mutableState.value.status == LocalModelInstallStatus.VERIFYING
        ) return

        val app = context.applicationContext
        scope.launch {
            val modelDir = File(app.filesDir, MODELS_DIR).apply { mkdirs() }
            val finalFile = File(modelDir, "${safeId(descriptor.id)}.bin")
            val partFile = File(modelDir, "${safeId(descriptor.id)}.part")
            runCatching {
                mutableState.value = LocalModelInstallState(
                    modelId = descriptor.id,
                    status = LocalModelInstallStatus.DOWNLOADING,
                    message = "正在下载模型…"
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
                    .apply()

                mutableState.value = LocalModelInstallState(
                    modelId = descriptor.id,
                    status = LocalModelInstallStatus.VERIFIED,
                    downloadedBytes = finalFile.length(),
                    totalBytes = finalFile.length(),
                    localPath = finalFile.absolutePath,
                    sha256 = actualSha,
                    message = "下载与校验通过，等待本机短基准测试"
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
    fun markReady(modelId: String, localPath: String) {
        val current = mutableState.value
        if (current.modelId != modelId || current.localPath != localPath || current.status != LocalModelInstallStatus.VERIFIED) return
        mutableState.value = current.copy(
            status = LocalModelInstallStatus.READY,
            message = "本机基准测试通过，可启用本地智能辅助"
        )
    }

    fun removeInstalled(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val current = mutableState.value
            current.localPath?.let(::File)?.delete()
            File(app.filesDir, MODELS_DIR).listFiles()
                ?.filter { it.name.endsWith(".part") }
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
