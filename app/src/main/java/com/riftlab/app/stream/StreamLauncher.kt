package com.riftlab.app.stream

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.riftlab.app.overlay.RiftOverlayService

/**
 * Opens the user's preferred viewing app without coupling RiftScreen to the video source.
 * Only known streaming packages are queried; QUERY_ALL_PACKAGES is intentionally avoided.
 */
enum class StreamPlatform(
    val id: String,
    val webUrl: String,
    val packages: List<String>,
    val deepLinks: List<String> = emptyList()
) {
    BILIBILI(
        id = "bilibili",
        webUrl = "https://live.bilibili.com/6",
        packages = listOf("tv.danmaku.bili"),
        deepLinks = listOf("bilibili://live/6")
    ),
    HUYA(
        id = "huya",
        webUrl = "https://www.huya.com/lpl",
        // Google Play / overseas package + common mainland package.
        packages = listOf("com.huya.kiwi", "com.duowan.kiwi")
    )
}

object StreamLauncher {
    private const val PREFS = "riftlab_stream_launcher"
    private const val KEY_PENDING = "pending_action"
    private const val PENDING_OVERLAY_ONLY = "overlay_only"

    fun startOverlay(context: Context): Boolean {
        if (!Settings.canDrawOverlays(context)) {
            savePending(context, PENDING_OVERLAY_ONLY)
            requestOverlayPermission(context)
            return false
        }
        clearPending(context)
        RiftOverlayService.start(context)
        return true
    }

    fun watch(context: Context, platform: StreamPlatform) {
        if (!Settings.canDrawOverlays(context)) {
            savePending(context, platform.id)
            requestOverlayPermission(context)
            return
        }
        clearPending(context)
        RiftOverlayService.start(context)
        openPlatform(context, platform)
    }

    /** Called from MainActivity.onResume after returning from overlay settings. */
    fun resumePendingIfReady(context: Context) {
        if (!Settings.canDrawOverlays(context)) return
        val pending = prefs(context).getString(KEY_PENDING, null) ?: return
        clearPending(context)
        RiftOverlayService.start(context)
        if (pending == PENDING_OVERLAY_ONLY) return
        StreamPlatform.entries.firstOrNull { it.id == pending }?.let { platform ->
            // The Activity has just resumed from Settings. Posting avoids racing its lifecycle.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                openPlatform(context, platform)
            }, 180)
        }
    }

    fun isInstalled(context: Context, platform: StreamPlatform): Boolean =
        platform.packages.any { isPackageInstalled(context, it) }

    private fun openPlatform(context: Context, platform: StreamPlatform) {
        val installedPackages = platform.packages.filter { isPackageInstalled(context, it) }

        // Prefer an app-specific deep link when one is known.
        for (pkg in installedPackages) {
            for (uri in platform.deepLinks) {
                if (tryStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg))) return
            }
        }

        // Then try the canonical HTTPS room while explicitly targeting the installed app.
        for (pkg in installedPackages) {
            if (tryStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(platform.webUrl)).setPackage(pkg))) return
        }

        // If an installed client cannot consume the room URL, opening the correct web room is
        // more useful than dumping the user on the app home page.
        tryStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(platform.webUrl)))
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun requestOverlayPermission(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun savePending(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PENDING, value).apply()
    }

    private fun clearPending(context: Context) {
        prefs(context).edit().remove(KEY_PENDING).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
