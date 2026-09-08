package com.riftlab.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.riftlab.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class AppUpdateState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val available: Boolean = false,
    val latestVersionName: String = "",
    val latestVersionCode: Int = 0,
    val changelog: String = "",
    val apkUrl: String = "",
    val expectedSha256: String = "",
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val status: String = "尚未检查更新",
    val error: String? = null
)

internal object AppUpdateManager {
    private const val RELEASE_API = "https://api.github.com/repos/xbu080675-creator/Rlftlab/releases/tags/dev-latest"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null

    private val _state = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun checkForUpdates() {
        if (_state.value.checking) return
        scope.launch {
            _state.value = _state.value.copy(checking = true, error = null, status = "正在检查 GitHub DEV 更新…")
            val result = runCatching { fetchRelease() }
            _state.value = result.fold(
                onSuccess = { it.copy(checking = false) },
                onFailure = { t ->
                    _state.value.copy(
                        checking = false,
                        error = t.message ?: t::class.java.simpleName,
                        status = "检查更新失败 · ${t.message?.take(120) ?: t::class.java.simpleName}"
                    )
                }
            )
        }
    }

    fun downloadAndInstall() {
        val context = appContext ?: return
        val current = _state.value
        if (!current.available || current.apkUrl.isBlank() || current.downloading) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = current.copy(status = "需要先允许 RiftLab 安装未知应用")
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }

        scope.launch {
            _state.value = current.copy(
                downloading = true,
                progressPercent = 0,
                downloadedBytes = 0,
                totalBytes = 0,
                error = null,
                status = "正在下载 ${current.latestVersionName}…"
            )
            val result = runCatching { downloadApk(current.apkUrl, current.expectedSha256) }
            result.onSuccess { apk ->
                _state.value = _state.value.copy(
                    downloading = false,
                    progressPercent = 100,
                    status = "下载并校验完成 · 正在打开系统安装器"
                )
                withContext(Dispatchers.Main) { launchInstaller(context, apk) }
            }.onFailure { t ->
                _state.value = _state.value.copy(
                    downloading = false,
                    error = t.message ?: t::class.java.simpleName,
                    status = "更新下载失败 · ${t.message?.take(120) ?: t::class.java.simpleName}"
                )
            }
        }
    }

    private fun fetchRelease(): AppUpdateState {
        val root = getJson(RELEASE_API)
        val body = root.optString("body")
        val versionName = lineValue(body, "versionName")
        val versionCode = lineValue(body, "versionCode").toIntOrNull() ?: 0
        val sha256 = lineValue(body, "sha256").lowercase()
        val changelog = body.substringAfter("changelog=", "").trim().ifBlank { root.optString("name") }

        val assets = root.optJSONArray("assets")
        var apkUrl = ""
        var total = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    total = asset.optLong("size", 0L)
                    break
                }
            }
        }
        if (versionCode <= 0 || versionName.isBlank() || apkUrl.isBlank()) {
            error("DEV release metadata incomplete")
        }

        val available = versionCode > BuildConfig.VERSION_CODE
        return AppUpdateState(
            available = available,
            latestVersionName = versionName,
            latestVersionCode = versionCode,
            changelog = changelog,
            apkUrl = apkUrl,
            expectedSha256 = sha256,
            totalBytes = total,
            status = if (available) {
                "发现新版本 $versionName · versionCode $versionCode"
            } else {
                "当前已是最新 DEV 版本 · ${BuildConfig.VERSION_NAME}"
            }
        )
    }

    private fun downloadApk(url: String, expectedSha256: String): File {
        val context = appContext ?: error("AppUpdateManager not initialized")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "RiftLab-update.apk")
        if (out.exists()) out.delete()

        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 12_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("User-Agent", "RiftLab-Updater/${BuildConfig.VERSION_NAME}")
            connection.connect()
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.coerceAtLeast(0L)
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                FileOutputStream(out).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        val percent = if (total > 0) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
                        _state.value = _state.value.copy(
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            progressPercent = percent,
                            status = "正在下载 ${_state.value.latestVersionName} · $percent%"
                        )
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256.isNotBlank() && !actual.equals(expectedSha256, ignoreCase = true)) {
                out.delete()
                error("SHA-256 校验失败")
            }
            return out
        } finally {
            connection.disconnect()
        }
    }

    private fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "RiftLab-Updater/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            if (code !in 200..299) error("GitHub HTTP $code")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun lineValue(body: String, key: String): String = body.lineSequence()
        .firstOrNull { it.trim().startsWith("$key=") }
        ?.substringAfter('=')
        ?.trim()
        .orEmpty()
}
