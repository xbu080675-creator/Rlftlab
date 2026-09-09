package com.riftlab.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
    val sourceLabel: String = "",
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val status: String = "尚未检查更新",
    val error: String? = null
)

private data class ReleaseCandidate(
    val sourceLabel: String,
    val versionName: String,
    val versionCode: Int,
    val changelog: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long
)

private data class PreferredRelease(
    val candidate: ReleaseCandidate,
    val note: String = ""
)

internal object AppUpdateManager {
    private const val RELEASE_API =
        "https://api.github.com/repos/xbu080675-creator/Rlftlab/releases/tags/dev-latest"
    private const val SOURCE_CN = "国内 OTA 镜像"
    private const val SOURCE_GITHUB = "GitHub DEV 备用源"
    private const val DEV_SIGNER_SHA256 =
        "769d9be3aa3af3fd4bb647bed8ffe4a8f7cfe2e7a9ad4489b260395b13575a24"

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
            val hasCn = BuildConfig.OTA_CN_MANIFEST_URL.isNotBlank()
            _state.value = _state.value.copy(
                checking = true,
                error = null,
                status = if (hasCn) {
                    "正在检查国内 OTA 镜像…"
                } else {
                    "国内 OTA 镜像尚未配置 · 正在检查 GitHub 备用源…"
                }
            )
            val result = runCatching { fetchPreferredRelease() }
            _state.value = result.fold(
                onSuccess = { preferred ->
                    candidateToState(preferred.candidate, preferred.note).copy(checking = false)
                },
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
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
                error = null,
                status = "正在通过 ${current.sourceLabel.ifBlank { "OTA" }} 下载 ${current.latestVersionName}…"
            )
            val result = runCatching { downloadWithFallback(current) }
            result.onSuccess { apk ->
                _state.value = _state.value.copy(
                    downloading = false,
                    progressPercent = 100,
                    status = "下载与安全校验完成 · 正在打开系统安装器"
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

    private fun fetchPreferredRelease(): PreferredRelease {
        val cnManifest = BuildConfig.OTA_CN_MANIFEST_URL.trim()
        if (cnManifest.isBlank()) {
            return PreferredRelease(fetchGithubRelease(), "国内镜像待配置")
        }

        return try {
            PreferredRelease(fetchDomesticManifest(cnManifest))
        } catch (_: Throwable) {
            PreferredRelease(
                fetchGithubRelease(),
                "国内镜像不可用，已自动切换 GitHub"
            )
        }
    }

    private fun fetchDomesticManifest(manifestUrl: String): ReleaseCandidate {
        requireHttps(manifestUrl, "国内 OTA manifest")
        val root = getJson(manifestUrl, "application/json", "国内镜像")
        val schemaVersion = root.optInt("schemaVersion", 1)
        if (schemaVersion < 1) error("国内镜像 manifest schema 无效")
        val channel = root.optString("channel", "dev")
        if (channel.isNotBlank() && !channel.equals("dev", ignoreCase = true)) {
            error("国内镜像 channel 非 dev")
        }

        val versionName = root.optString("versionName").trim()
        val versionCode = root.optInt("versionCode", 0)
        val sha256 = root.optString("sha256").trim().lowercase()
        val apkRef = root.optString("apkUrl").trim()
            .ifBlank { root.optString("apk").trim() }
        val changelog = root.optString("changelog").trim()
        val size = root.optLong("size", 0L).coerceAtLeast(0L)

        if (versionName.isBlank() || versionCode <= 0 || apkRef.isBlank()) {
            error("国内镜像 manifest 元数据不完整")
        }
        validateSha256(sha256)

        val apkUrl = URL(URL(manifestUrl), apkRef).toString()
        requireHttps(apkUrl, "国内 OTA APK")
        return ReleaseCandidate(
            sourceLabel = SOURCE_CN,
            versionName = versionName,
            versionCode = versionCode,
            changelog = changelog,
            apkUrl = apkUrl,
            sha256 = sha256,
            size = size
        )
    }

    private fun fetchGithubRelease(): ReleaseCandidate {
        val root = getJson(RELEASE_API, "application/vnd.github+json", "GitHub")
        val body = root.optString("body")
        val versionName = lineValue(body, "versionName")
        val versionCode = lineValue(body, "versionCode").toIntOrNull() ?: 0
        val sha256 = lineValue(body, "sha256").lowercase()
        val changelog = body.substringAfter("changelog=", "")
            .trim()
            .ifBlank { root.optString("name") }

        val assets = root.optJSONArray("assets")
        var apkUrl = ""
        var total = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url").trim()
                    total = asset.optLong("size", 0L).coerceAtLeast(0L)
                    break
                }
            }
        }
        if (versionCode <= 0 || versionName.isBlank() || apkUrl.isBlank()) {
            error("GitHub DEV release metadata incomplete")
        }
        validateSha256(sha256)
        requireHttps(apkUrl, "GitHub OTA APK")
        return ReleaseCandidate(
            sourceLabel = SOURCE_GITHUB,
            versionName = versionName,
            versionCode = versionCode,
            changelog = changelog,
            apkUrl = apkUrl,
            sha256 = sha256,
            size = total
        )
    }

    private fun candidateToState(candidate: ReleaseCandidate, note: String): AppUpdateState {
        val available = candidate.versionCode > BuildConfig.VERSION_CODE
        val prefix = listOf(note, candidate.sourceLabel)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        return AppUpdateState(
            available = available,
            latestVersionName = candidate.versionName,
            latestVersionCode = candidate.versionCode,
            changelog = candidate.changelog,
            apkUrl = candidate.apkUrl,
            expectedSha256 = candidate.sha256,
            sourceLabel = candidate.sourceLabel,
            totalBytes = candidate.size,
            status = if (available) {
                "$prefix · 发现新版本 ${candidate.versionName} · code ${candidate.versionCode}"
            } else {
                "$prefix · 当前已是最新 DEV 版本 · ${BuildConfig.VERSION_NAME}"
            }
        )
    }

    private fun downloadWithFallback(current: AppUpdateState): File {
        val primary = ReleaseCandidate(
            sourceLabel = current.sourceLabel,
            versionName = current.latestVersionName,
            versionCode = current.latestVersionCode,
            changelog = current.changelog,
            apkUrl = current.apkUrl,
            sha256 = current.expectedSha256,
            size = current.totalBytes
        )

        return try {
            downloadApk(primary)
        } catch (primaryError: Throwable) {
            if (primary.sourceLabel != SOURCE_CN) throw primaryError

            _state.value = _state.value.copy(
                progressPercent = 0,
                downloadedBytes = 0,
                status = "国内镜像下载失败 · 正在切换 GitHub 备用源…"
            )
            val fallback = fetchGithubRelease()
            if (fallback.versionCode != primary.versionCode ||
                !fallback.sha256.equals(primary.sha256, ignoreCase = true)
            ) {
                error("国内镜像与 GitHub 备用源版本或 SHA-256 不一致，请重新检查更新")
            }
            _state.value = _state.value.copy(
                sourceLabel = fallback.sourceLabel,
                apkUrl = fallback.apkUrl,
                totalBytes = fallback.size,
                status = "已切换 ${fallback.sourceLabel} · 正在继续下载…"
            )
            try {
                downloadApk(fallback)
            } catch (fallbackError: Throwable) {
                fallbackError.addSuppressed(primaryError)
                throw fallbackError
            }
        }
    }

    private fun downloadApk(candidate: ReleaseCandidate): File {
        val context = appContext ?: error("AppUpdateManager not initialized")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "RiftLab-update.apk")
        if (out.exists()) out.delete()

        val connection = URL(candidate.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 12_000
            connection.readTimeout = 35_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("User-Agent", "RiftLab-Updater/${BuildConfig.VERSION_NAME}")
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("${candidate.sourceLabel} HTTP ${connection.responseCode}")
            }
            if (!connection.url.protocol.equals("https", ignoreCase = true)) {
                error("${candidate.sourceLabel} 重定向到了非 HTTPS 地址")
            }

            val responseTotal = connection.contentLengthLong.coerceAtLeast(0L)
            val total = if (responseTotal > 0L) responseTotal else candidate.size
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
                        val percent = if (total > 0L) {
                            ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        _state.value = _state.value.copy(
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            progressPercent = percent,
                            status = "正在通过 ${candidate.sourceLabel} 下载 ${candidate.versionName} · $percent%"
                        )
                    }
                }
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(candidate.sha256, ignoreCase = true)) {
                out.delete()
                error("${candidate.sourceLabel} SHA-256 校验失败")
            }
            verifyArchiveIdentity(context, out, candidate.versionCode)
            return out
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyArchiveIdentity(context: Context, apk: File, expectedVersionCode: Int) {
        val info = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES
        ) ?: run {
            apk.delete()
            error("APK 元数据读取失败")
        }
        if (info.packageName != context.packageName) {
            apk.delete()
            error("APK 包名校验失败")
        }
        if (info.longVersionCode != expectedVersionCode.toLong()) {
            apk.delete()
            error("APK versionCode 与 OTA manifest 不一致")
        }
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            ?: run {
                apk.delete()
                error("APK 签名读取失败")
            }
        val signerSha = MessageDigest.getInstance("SHA-256")
            .digest(signer)
            .joinToString("") { "%02x".format(it) }
        if (!signerSha.equals(DEV_SIGNER_SHA256, ignoreCase = true)) {
            apk.delete()
            error("APK 开发签名校验失败")
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

    private fun getJson(url: String, accept: String, failurePrefix: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.setRequestProperty("User-Agent", "RiftLab-Updater/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            if (code !in 200..299) error("$failurePrefix HTTP $code")
            if (!connection.url.protocol.equals("https", ignoreCase = true)) {
                error("$failurePrefix 重定向到了非 HTTPS 地址")
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun validateSha256(value: String) {
        if (!Regex("^[0-9a-f]{64}$").matches(value)) {
            error("OTA SHA-256 缺失或格式无效")
        }
    }

    private fun requireHttps(value: String, label: String) {
        if (!value.startsWith("https://", ignoreCase = true)) {
            error("$label 必须使用 HTTPS")
        }
    }

    private fun lineValue(body: String, key: String): String = body.lineSequence()
        .firstOrNull { it.trim().startsWith("$key=") }
        ?.substringAfter('=')
        ?.trim()
        .orEmpty()
}
