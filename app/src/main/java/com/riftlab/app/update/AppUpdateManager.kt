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
import java.io.FileInputStream
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

private data class UpdateTransport(
    val label: String,
    val accelerated: Boolean,
    val baseUrl: String = ""
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
    private const val GITHUB_MANIFEST_URL =
        "https://github.com/xbu080675-creator/Rlftlab/releases/download/dev-latest/latest.json"
    private const val GITHUB_RELEASE_PATH_PREFIX =
        "/xbu080675-creator/Rlftlab/releases/download/dev-latest/"
    private const val SOURCE_ACCELERATED_PREFIX = "GitHub 更新加速"
    private const val DEV_SIGNER_SHA256 =
        "769d9be3aa3af3fd4bb647bed8ffe4a8f7cfe2e7a9ad4489b260395b13575a24"

    private val directTransport = UpdateTransport("GitHub 直连", accelerated = false)
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
            _state.value = _state.value.copy(
                checking = true,
                error = null,
                status = "正在检查 GitHub DEV 更新…"
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
                status = "正在准备下载 ${current.latestVersionName}…"
            )
            val result = runCatching { downloadWithFallback(current) }
            result.onSuccess { apk ->
                val accelerated = _state.value.sourceLabel.startsWith(SOURCE_ACCELERATED_PREFIX)
                _state.value = _state.value.copy(
                    downloading = false,
                    progressPercent = 100,
                    status = if (accelerated) {
                        "下载与安全校验完成 · GitHub 更新加速连接已释放 · 正在打开系统安装器"
                    } else {
                        "下载与安全校验完成 · 正在打开系统安装器"
                    }
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
        requireOfficialGithubManifest(GITHUB_MANIFEST_URL)
        return try {
            PreferredRelease(fetchGithubManifest(directTransport))
        } catch (directError: Throwable) {
            val accelerators = acceleratorTransports()
            if (accelerators.isEmpty()) throw directError

            var previousError: Throwable = directError
            for (transport in accelerators) {
                _state.value = _state.value.copy(
                    status = "GitHub 直连检查失败 · 尝试 ${transport.label}…"
                )
                try {
                    return PreferredRelease(
                        fetchGithubManifest(transport),
                        "GitHub 直连不可用 · 本次检查已临时启用 ${transport.label}，连接已释放"
                    )
                } catch (acceleratedError: Throwable) {
                    acceleratedError.addSuppressed(previousError)
                    previousError = acceleratedError
                }
            }
            throw previousError
        }
    }

    private fun fetchGithubManifest(transport: UpdateTransport): ReleaseCandidate {
        val root = getJson(
            originalUrl = GITHUB_MANIFEST_URL,
            accept = "application/json",
            failurePrefix = transport.label,
            transport = transport
        )
        val schemaVersion = root.optInt("schemaVersion", 1)
        if (schemaVersion < 1) error("GitHub OTA manifest schema 无效")
        val channel = root.optString("channel", "dev")
        if (channel.isNotBlank() && !channel.equals("dev", ignoreCase = true)) {
            error("GitHub OTA manifest channel 非 dev")
        }

        val versionName = root.optString("versionName").trim()
        val versionCode = root.optInt("versionCode", 0)
        val sha256 = root.optString("sha256").trim().lowercase()
        val apkRef = root.optString("apkUrl").trim()
            .ifBlank { root.optString("apk").trim() }
        val changelog = root.optString("changelog").trim()
        val size = root.optLong("size", 0L).coerceAtLeast(0L)

        if (versionName.isBlank() || versionCode <= 0 || apkRef.isBlank()) {
            error("GitHub OTA manifest 元数据不完整")
        }
        validateSha256(sha256)

        val apkUrl = URL(URL(GITHUB_MANIFEST_URL), apkRef).toString()
        requireOfficialGithubApk(apkUrl)
        return ReleaseCandidate(
            sourceLabel = transport.label,
            versionName = versionName,
            versionCode = versionCode,
            changelog = changelog,
            apkUrl = apkUrl,
            sha256 = sha256,
            size = size
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
        val candidate = ReleaseCandidate(
            sourceLabel = current.sourceLabel,
            versionName = current.latestVersionName,
            versionCode = current.latestVersionCode,
            changelog = current.changelog,
            apkUrl = current.apkUrl,
            sha256 = current.expectedSha256,
            size = current.totalBytes
        )
        requireOfficialGithubApk(candidate.apkUrl)

        val accelerators = acceleratorTransports()
        val preferredAccelerator = accelerators.firstOrNull { it.label == current.sourceLabel }
        val transports = when {
            preferredAccelerator != null -> listOf(preferredAccelerator) +
                accelerators.filterNot { it == preferredAccelerator } + directTransport
            current.sourceLabel.startsWith(SOURCE_ACCELERATED_PREFIX) && accelerators.isNotEmpty() ->
                accelerators + directTransport
            else -> listOf(directTransport) + accelerators
        }

        var firstError: Throwable? = null
        var lastError: Throwable? = null
        transports.forEachIndexed { index, transport ->
            _state.value = _state.value.copy(
                sourceLabel = transport.label,
                status = if (transport.accelerated) {
                    "正在通过 ${transport.label} 下载 ${candidate.versionName}…"
                } else {
                    "正在通过 GitHub 直连下载 ${candidate.versionName}…"
                }
            )
            try {
                return downloadApk(candidate, transport)
            } catch (t: Throwable) {
                if (firstError == null) firstError = t
                lastError = t
                if (index < transports.lastIndex) {
                    _state.value = _state.value.copy(
                        status = "${transport.label} 无响应或失败 · 自动切换下一更新通道…"
                    )
                }
            }
        }

        val failure = lastError ?: error("没有可用的 GitHub 更新传输通道")
        firstError?.let { first ->
            if (first !== failure) failure.addSuppressed(first)
        }
        throw failure
    }

    private fun downloadApk(candidate: ReleaseCandidate, transport: UpdateTransport): File {
        val context = appContext ?: error("AppUpdateManager not initialized")
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val part = File(dir, "RiftLab-update-${candidate.versionCode}.apk.part")
        val out = File(dir, "RiftLab-update.apk")
        if (out.exists()) out.delete()
        if (candidate.size > 0L && part.exists() && part.length() > candidate.size) {
            part.delete()
        }

        var allowResume = true
        while (true) {
            val existing = if (allowResume && part.exists()) part.length() else 0L
            val requestUrl = transportUrl(candidate.apkUrl, transport)
            val connection = URL(requestUrl).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = if (transport.accelerated) 7_000 else 12_000
                connection.readTimeout = if (transport.accelerated) 20_000 else 30_000
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/octet-stream")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("User-Agent", "RiftLab-Updater/${BuildConfig.VERSION_NAME}")
                if (transport.accelerated) {
                    // Request-scoped acceleration only: never install a VPN or system proxy.
                    connection.setRequestProperty("Connection", "close")
                }
                if (existing > 0L) {
                    connection.setRequestProperty("Range", "bytes=$existing-")
                }
                connection.connect()

                val code = connection.responseCode
                if (code == 416 && existing > 0L) {
                    part.delete()
                    allowResume = false
                    continue
                }
                if (code !in 200..299) {
                    error("${transport.label} HTTP $code")
                }
                if (!connection.url.protocol.equals("https", ignoreCase = true)) {
                    error("${transport.label} 重定向到了非 HTTPS 地址")
                }

                val append = existing > 0L && code == HttpURLConnection.HTTP_PARTIAL
                if (append) {
                    val range = connection.getHeaderField("Content-Range").orEmpty()
                    if (!range.startsWith("bytes $existing-")) {
                        part.delete()
                        error("${transport.label} 断点续传范围不一致")
                    }
                } else if (existing > 0L) {
                    // Server ignored Range and returned a full body. Restart locally from byte 0.
                    part.delete()
                }

                val startBytes = if (append) existing else 0L
                val rangedTotal = contentRangeTotal(connection.getHeaderField("Content-Range"))
                val responseLength = connection.contentLengthLong.coerceAtLeast(0L)

                if (candidate.size > 0L && rangedTotal > 0L && rangedTotal != candidate.size) {
                    error("${transport.label} 返回的源文件长度与 OTA manifest 不一致")
                }
                if (candidate.size > 0L && startBytes == 0L && responseLength > 0L &&
                    code == HttpURLConnection.HTTP_OK && responseLength != candidate.size
                ) {
                    error("${transport.label} 返回的文件长度异常")
                }

                val total = when {
                    candidate.size > 0L -> candidate.size
                    rangedTotal > 0L -> rangedTotal
                    responseLength > 0L -> startBytes + responseLength
                    else -> 0L
                }

                connection.inputStream.use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var downloaded = startBytes
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
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
                                status = buildString {
                                    append("正在通过 ${transport.label} 下载 ${candidate.versionName} · $percent%")
                                    if (startBytes > 0L) append(" · 断点续传")
                                }
                            )
                        }
                    }
                }
                break
            } finally {
                connection.disconnect()
            }
        }

        if (candidate.size > 0L && part.length() != candidate.size) {
            error("${transport.label} 下载长度不完整，可在下一通道继续断点续传")
        }

        val actual = sha256Of(part)
        if (!actual.equals(candidate.sha256, ignoreCase = true)) {
            part.delete()
            error("${transport.label} SHA-256 校验失败")
        }

        if (out.exists()) out.delete()
        if (!part.renameTo(out)) {
            part.copyTo(out, overwrite = true)
            part.delete()
        }
        verifyArchiveIdentity(context, out, candidate.versionCode)
        return out
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

    private fun getJson(
        originalUrl: String,
        accept: String,
        failurePrefix: String,
        transport: UpdateTransport
    ): JSONObject {
        val requestUrl = transportUrl(originalUrl, transport)
        val connection = URL(requestUrl).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = if (transport.accelerated) 6_000 else 7_000
            connection.readTimeout = if (transport.accelerated) 10_000 else 12_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.setRequestProperty("User-Agent", "RiftLab-Updater/${BuildConfig.VERSION_NAME}")
            if (transport.accelerated) {
                connection.setRequestProperty("Connection", "close")
            }
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

    private fun transportUrl(originalUrl: String, transport: UpdateTransport): String {
        if (!transport.accelerated) return originalUrl
        requireOfficialGithubSource(originalUrl)
        val base = transport.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) error("GitHub 更新加速未配置")
        requireHttps(base, "GitHub 更新加速地址")
        return "$base/$originalUrl"
    }

    private fun acceleratorTransports(): List<UpdateTransport> {
        return BuildConfig.GITHUB_ACCELERATOR_BASE_URLS
            .split('|')
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()
            .map { base ->
                requireHttps(base, "GitHub 更新加速地址")
                UpdateTransport(
                    label = "$SOURCE_ACCELERATED_PREFIX · ${acceleratorNodeLabel(base)}",
                    accelerated = true,
                    baseUrl = base
                )
            }
    }

    private fun acceleratorNodeLabel(base: String): String {
        return when (runCatching { URL(base).host.lowercase() }.getOrDefault("")) {
            "ghfast.top" -> "GHFast"
            "ghproxy.net" -> "GHProxy.net"
            else -> runCatching { URL(base).host }.getOrDefault("第三方节点").ifBlank { "第三方节点" }
        }
    }

    private fun requireOfficialGithubSource(value: String) {
        val url = URL(value)
        val allowed = url.protocol.equals("https", ignoreCase = true) &&
            url.host.equals("github.com", ignoreCase = true) &&
            (url.path == URL(GITHUB_MANIFEST_URL).path || url.path.startsWith(GITHUB_RELEASE_PATH_PREFIX))
        if (!allowed) error("更新加速只允许访问 RiftLab 官方 GitHub Release 资源")
    }

    private fun requireOfficialGithubManifest(value: String) {
        val url = URL(value)
        val expected = URL(GITHUB_MANIFEST_URL)
        if (!url.protocol.equals("https", ignoreCase = true) ||
            !url.host.equals(expected.host, ignoreCase = true) ||
            url.path != expected.path
        ) {
            error("GitHub OTA manifest 地址无效")
        }
    }

    private fun requireOfficialGithubApk(value: String) {
        val url = URL(value)
        if (!url.protocol.equals("https", ignoreCase = true) ||
            !url.host.equals("github.com", ignoreCase = true) ||
            !url.path.startsWith(GITHUB_RELEASE_PATH_PREFIX) ||
            !url.path.endsWith(".apk", ignoreCase = true)
        ) {
            error("GitHub OTA APK 必须来自 RiftLab 官方 dev-latest Release")
        }
    }

    private fun contentRangeTotal(header: String?): Long = header
        ?.substringAfterLast('/', "")
        ?.trim()
        ?.takeIf { it != "*" }
        ?.toLongOrNull()
        ?: 0L

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
}
