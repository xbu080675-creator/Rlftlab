package com.riftlab.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.Base64

/**
 * Resilient transport for Riot's persisted LoL Esports gateway.
 *
 * Direct Riot remains the primary source. If the mobile network cannot reach
 * esports-api.lolesports.com, RiftLab falls back to a GitHub-hosted snapshot that is fetched by
 * GitHub Actions directly from Riot. The mirror is never used for LiveStats frames.
 */
internal object RiotResilientHttp {
    @Volatile private var persistedMirrorActive = false
    @Volatile private var mirrorUpdatedAt = ""

    suspend fun getJson(
        url: String,
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 8_000
    ): JSONObject = withContext(Dispatchers.IO) {
        try {
            val root = directGet(url, connectTimeoutMs, readTimeoutMs)
            if (url.startsWith(LolEsportsConfig.PERSISTED_BASE)) {
                persistedMirrorActive = false
            }
            root
        } catch (directError: Throwable) {
            if (!url.startsWith(LolEsportsConfig.PERSISTED_BASE)) throw directError
            val mirrored = RiotPersistedMirror.resolve(url)
            if (mirrored != null) {
                persistedMirrorActive = true
                mirrorUpdatedAt = RiotPersistedMirror.updatedAt()
                mirrored
            } else {
                throw IOException(
                    "Riot direct unavailable and mirror has no matching snapshot: ${directError.message}",
                    directError
                )
            }
        }
    }

    fun sourceLabel(): String = if (persistedMirrorActive) {
        val stamp = mirrorUpdatedAt
            .takeIf { it.isNotBlank() }
            ?.substringBefore('T')
            ?.let { " · $it" }
            .orEmpty()
        "RiftLab Riot Mirror$stamp"
    } else {
        "Riot LoL Esports"
    }

    private fun directGet(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("x-api-key", LolEsportsConfig.API_KEY)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RiftLab/1.0 Android")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}: ${body.take(180)}")
            if (body.isBlank()) throw IOException("Empty response from ${URL(url).host}")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}

internal object RiotPersistedMirror {
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val GITHUB_CONTENTS =
        "https://api.github.com/repos/xbu080675-creator/Rlftlab/contents/data/lpl/riot_persisted_mirror.json?ref=main"
    private const val RAW =
        "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/lpl/riot_persisted_mirror.json"
    private const val JSDELIVR =
        "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/lpl/riot_persisted_mirror.json"

    @Volatile private var appContext: Context? = null
    @Volatile private var cachedAt = 0L
    @Volatile private var cachedRoot: JSONObject? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun updatedAt(): String = cachedRoot?.optString("updatedAt").orEmpty()

    fun resolve(originalUrl: String): JSONObject? {
        val root = loadRoot() ?: return null
        val uri = runCatching { URI(originalUrl) }.getOrNull() ?: return null
        val operation = uri.path.substringAfterLast('/')
        val query = parseQuery(uri.rawQuery.orEmpty())

        return when (operation) {
            "getSchedule" -> {
                val token = query["pageToken"].orEmpty()
                val pages = root.optJSONArray("schedulePages") ?: return null
                for (i in 0 until pages.length()) {
                    val row = pages.optJSONObject(i) ?: continue
                    if (row.optString("pageToken") == token) {
                        return row.optJSONObject("payload")
                    }
                }
                null
            }
            "getTournamentsForLeague" -> root.optJSONObject("tournaments")
            "getStandings" -> {
                val tournamentId = query["tournamentId"].orEmpty()
                root.optJSONObject("standingsByTournament")?.optJSONObject(tournamentId)
            }
            "getTeams" -> {
                val requested = normalizeLookup(query["id"].orEmpty())
                val canonical = root.optJSONObject("teamLookup")?.optString(requested).orEmpty()
                root.optJSONObject("teamDetails")?.optJSONObject(canonical)
            }
            "getLive" -> root.optJSONObject("live")?.takeIf { it.length() > 0 }
            "getEventDetails" -> {
                val eventId = query["id"].orEmpty()
                root.optJSONObject("eventDetailsByEvent")?.optJSONObject(eventId)
            }
            else -> null
        }
    }

    private fun loadRoot(): JSONObject? {
        val now = System.currentTimeMillis()
        cachedRoot?.takeIf { now - cachedAt < CACHE_TTL_MS }?.let { return it }

        val network = sequenceOf(::fetchFromGitHubContents, { fetchPlain(RAW) }, { fetchPlain(JSDELIVR) })
            .mapNotNull { loader -> runCatching { loader() }.getOrNull() }
            .firstOrNull(::validate)
        if (network != null) {
            cachedRoot = network
            cachedAt = now
            persist(network)
            return network
        }

        val disk = readDisk()?.takeIf(::validate)
        if (disk != null) {
            cachedRoot = disk
            cachedAt = now
            return disk
        }

        val bundled = readBundled()?.takeIf(::validate)
        if (bundled != null) {
            cachedRoot = bundled
            cachedAt = now
            return bundled
        }
        return cachedRoot
    }

    /** A schedule-only mirror is still valuable; team Dynamic Data can operate independently. */
    private fun validate(root: JSONObject): Boolean =
        root.optInt("schemaVersion", 0) == 1 &&
            root.optJSONArray("schedulePages")?.length()?.let { it > 0 } == true

    private fun fetchFromGitHubContents(): JSONObject {
        val wrapper = fetchJson(GITHUB_CONTENTS)
        val encoded = wrapper.optString("content").replace("\n", "")
        if (encoded.isBlank()) throw IOException("GitHub contents response has no content")
        val decoded = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        return JSONObject(decoded)
    }

    private fun fetchPlain(url: String): JSONObject = JSONObject(fetchText(url))

    private fun fetchJson(url: String): JSONObject = JSONObject(fetchText(url))

    private fun fetchText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json, application/json")
            setRequestProperty("User-Agent", "RiftLab-RiotMirror/1.0 Android")
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("mirror HTTP $code")
            if (body.isBlank()) throw IOException("empty mirror response")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun persist(root: JSONObject) {
        val context = appContext ?: return
        runCatching {
            val file = File(context.filesDir, "riot_persisted_mirror.json")
            file.writeText(root.toString(), Charsets.UTF_8)
        }
    }

    private fun readDisk(): JSONObject? {
        val context = appContext ?: return null
        return runCatching {
            val file = File(context.filesDir, "riot_persisted_mirror.json")
            if (!file.exists()) null else JSONObject(file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun readBundled(): JSONObject? {
        val context = appContext ?: return null
        return runCatching {
            context.assets.open("riot_persisted_mirror.json").bufferedReader().use { JSONObject(it.readText()) }
        }.getOrNull()
    }

    private fun parseQuery(raw: String): Map<String, String> = raw
        .split('&')
        .mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val parts = pair.split('=', limit = 2)
            val key = URLDecoder.decode(parts[0], "UTF-8")
            val value = URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            key to value
        }
        .toMap()

    private fun normalizeLookup(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }
}
