package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves short-lived, public playback URLs for an already verified Bilibili official VOD.
 *
 * This layer never downloads or persists video bytes. It asks Bilibili for the current playback
 * descriptors, keeps them only in a short in-memory cache and lets the native player stream the
 * media directly from Bilibili's CDN. Login challenges / risk-control responses are not bypassed.
 */
data class BilibiliPlaybackSegment(
    val primaryUrl: String,
    val backupUrls: List<String> = emptyList(),
    val durationMs: Long = 0L
)

data class BilibiliNativePlaybackSource(
    val bvid: String,
    val cid: Long,
    val quality: Int,
    val format: String,
    val segments: List<BilibiliPlaybackSegment>,
    val resolvedAtEpochMs: Long = System.currentTimeMillis()
)

object BilibiliNativePlaybackResolver {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"

    private const val API_BASE = "https://api.bilibili.com"
    private const val CACHE_TTL_MS = 10L * 60L * 1000L
    private val cache = ConcurrentHashMap<String, BilibiliNativePlaybackSource>()

    suspend fun resolve(
        vod: BilibiliMatchVod,
        part: BilibiliVodPart,
        forceRefresh: Boolean = false
    ): BilibiliNativePlaybackSource = withContext(Dispatchers.IO) {
        val key = "${vod.bvid}|${part.cid}"
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cache[key]?.takeIf { now - it.resolvedAtEpochMs < CACHE_TTL_MS }?.let { return@withContext it }
        }

        // fnval=0 asks for the progressive descriptor. This keeps audio/video together so the
        // native player can play it without reconstructing Bilibili's DASH representation.
        val endpoint = "$API_BASE/x/player/playurl" +
            "?bvid=${enc(vod.bvid)}&cid=${part.cid}" +
            "&qn=80&fnver=0&fnval=0&fourk=0&platform=html5&high_quality=1"
        val root = getJson(endpoint)
        val apiCode = root.optInt("code", -1)
        if (apiCode != 0) {
            throw IOException("Bilibili playurl code=$apiCode · ${root.optString("message")}")
        }
        val data = root.optJSONObject("data")
            ?: throw IOException("Bilibili playurl missing data")
        val durl = data.optJSONArray("durl")
            ?: throw IOException("Bilibili 当前未返回可供原生播放器使用的 progressive 播放源")

        val segments = buildList {
            for (i in 0 until durl.length()) {
                val item = durl.optJSONObject(i) ?: continue
                val primary = normalizeUrl(item.optString("url"))
                if (primary.isBlank()) continue
                val backups = buildList {
                    val array = item.optJSONArray("backup_url")
                    if (array != null) {
                        for (j in 0 until array.length()) {
                            normalizeUrl(array.optString(j)).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }.distinct()
                add(
                    BilibiliPlaybackSegment(
                        primaryUrl = primary,
                        backupUrls = backups,
                        durationMs = item.optLong("length", 0L).coerceAtLeast(0L)
                    )
                )
            }
        }
        if (segments.isEmpty()) throw IOException("Bilibili playurl 没有可播放分段")

        BilibiliNativePlaybackSource(
            bvid = vod.bvid,
            cid = part.cid,
            quality = data.optInt("quality", 0),
            format = data.optString("format").ifBlank { "progressive" },
            segments = segments,
            resolvedAtEpochMs = now
        ).also { cache[key] = it }
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 12_000
        connection.readTimeout = 12_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
        connection.setRequestProperty("Referer", "https://www.bilibili.com/")
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IOException("Bilibili HTTP $status · ${text.take(140)}")
            }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeUrl(value: String): String = when {
        value.startsWith("//") -> "https:$value"
        else -> value.trim()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
