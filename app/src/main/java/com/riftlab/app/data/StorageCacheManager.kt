package com.riftlab.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * RiftLab cache accounting/cleanup.
 *
 * Only disposable cache roots are touched. Persistent match archives, encrypted provider keys,
 * user settings and downloaded local-AI models under filesDir are deliberately excluded.
 */
data class RiftCacheSnapshot(
    val internalBytes: Long,
    val externalBytes: Long,
    val totalBytes: Long,
    val fileCount: Int
)

data class RiftCacheClearResult(
    val beforeBytes: Long,
    val afterBytes: Long,
    val freedBytes: Long,
    val deletedFiles: Int
)

object StorageCacheManager {
    suspend fun inspect(context: Context): RiftCacheSnapshot = withContext(Dispatchers.IO) {
        inspectBlocking(context.applicationContext)
    }

    suspend fun clearDisposableCache(context: Context): RiftCacheClearResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val before = inspectBlocking(app)
        var deleted = 0
        cacheRoots(app).forEach { root ->
            root.listFiles()?.forEach { child ->
                deleted += deleteRecursivelyCount(child)
            }
        }
        val after = inspectBlocking(app)
        RiftCacheClearResult(
            beforeBytes = before.totalBytes,
            afterBytes = after.totalBytes,
            freedBytes = (before.totalBytes - after.totalBytes).coerceAtLeast(0L),
            deletedFiles = deleted
        )
    }

    /**
     * Automatic pressure guard. It trims oldest disposable files until cache is under targetBytes.
     * This never runs against filesDir, so local model files and persistent archives are preserved.
     */
    suspend fun trimIfNeeded(
        context: Context,
        triggerBytes: Long = 768L * MIB,
        targetBytes: Long = 384L * MIB
    ): RiftCacheClearResult? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val before = inspectBlocking(app)
        if (before.totalBytes < triggerBytes) return@withContext null

        val files = cacheRoots(app)
            .flatMap { root -> root.walkTopDown().filter { it.isFile }.toList() }
            .sortedBy { it.lastModified() }
            .toMutableList()

        var remaining = before.totalBytes
        var deleted = 0
        for (file in files) {
            if (remaining <= targetBytes) break
            val length = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                remaining = (remaining - length).coerceAtLeast(0L)
                deleted++
            }
        }
        cacheRoots(app).forEach(::deleteEmptyDirectories)
        val after = inspectBlocking(app)
        RiftCacheClearResult(before.totalBytes, after.totalBytes, before.totalBytes - after.totalBytes, deleted)
    }

    fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L)
        return when {
            value >= GIB -> "%.2f GB".format(value.toDouble() / GIB)
            value >= MIB -> "%.1f MB".format(value.toDouble() / MIB)
            value >= KIB -> "%.1f KB".format(value.toDouble() / KIB)
            else -> "$value B"
        }
    }

    private fun inspectBlocking(context: Context): RiftCacheSnapshot {
        val internal = dirSize(context.cacheDir)
        val external = context.externalCacheDir?.let(::dirSize) ?: 0L
        val count = cacheRoots(context).sumOf { root ->
            if (!root.exists()) 0 else root.walkTopDown().count { it.isFile() }
        }
        return RiftCacheSnapshot(internal, external, internal + external, count)
    }

    private fun cacheRoots(context: Context): List<File> = buildList {
        add(context.cacheDir)
        context.externalCacheDir?.let { add(it) }
    }.distinctBy { file ->
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
    }

    private fun dirSize(root: File): Long =
        if (!root.exists()) 0L else root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun deleteRecursivelyCount(file: File): Int {
        if (!file.exists()) return 0
        if (file.isFile) return if (runCatching { file.delete() }.getOrDefault(false)) 1 else 0
        var count = 0
        file.listFiles()?.forEach { count += deleteRecursivelyCount(it) }
        runCatching { file.delete() }
        return count
    }

    private fun deleteEmptyDirectories(root: File) {
        if (!root.exists() || !root.isDirectory) return
        root.listFiles()?.filter { it.isDirectory }?.forEach(::deleteEmptyDirectories)
        if (root.listFiles().isNullOrEmpty()) runCatching { root.delete() }
    }

    private const val KIB = 1024L
    private const val MIB = 1024L * KIB
    private const val GIB = 1024L * MIB
}
