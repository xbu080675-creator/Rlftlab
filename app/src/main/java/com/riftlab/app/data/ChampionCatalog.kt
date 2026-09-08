package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Resolves Riot champion ids/names to localized labels and Data Dragon artwork. */
internal object ChampionCatalog {
    private const val VERSIONS_URL = "https://ddragon.leagueoflegends.com/api/versions.json"
    private val mutex = Mutex()

    private data class ChampionVisual(
        val numericId: String,
        val displayName: String,
        val imageFile: String,
        val version: String
    )

    @Volatile private var cachedVisuals: Map<String, ChampionVisual>? = null

    suspend fun decorateDrafts(drafts: List<DraftPickRecord>): List<DraftPickRecord> {
        if (drafts.isEmpty()) return drafts
        val visuals = loadVisuals()
        return drafts.map { draft ->
            draft.copy(
                blueBans = draft.blueBans.map { displayName(it, visuals) },
                redBans = draft.redBans.map { displayName(it, visuals) },
                bluePicks = draft.bluePicks.map { displayName(it, visuals) },
                redPicks = draft.redPicks.map { displayName(it, visuals) }
            )
        }
    }

    suspend fun displayName(raw: String): String = displayName(raw, loadVisuals())

    suspend fun iconUrl(raw: String): String {
        val token = raw.trim()
        if (token.isBlank()) return ""
        val visual = loadVisuals()[normalize(token)] ?: return ""
        return "https://ddragon.leagueoflegends.com/cdn/${visual.version}/img/champion/${visual.imageFile}"
    }

    private fun displayName(raw: String, visuals: Map<String, ChampionVisual>): String {
        val token = raw.trim()
        if (token.isBlank()) return token
        val visual = visuals[normalize(token)]
        return visual?.displayName ?: if (token.all(Char::isDigit)) "英雄ID $token" else token
    }

    private suspend fun loadVisuals(): Map<String, ChampionVisual> {
        cachedVisuals?.let { return it }
        return mutex.withLock {
            cachedVisuals?.let { return@withLock it }
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val versions = JSONArray(getText(VERSIONS_URL))
                    val version = versions.optString(0).ifBlank { error("Data Dragon version missing") }
                    val root = JSONObject(
                        getText("https://ddragon.leagueoflegends.com/cdn/$version/data/zh_CN/champion.json")
                    )
                    val data = root.optJSONObject("data") ?: JSONObject()
                    buildMap {
                        val keys = data.keys()
                        while (keys.hasNext()) {
                            val champion = data.optJSONObject(keys.next()) ?: continue
                            val numericId = champion.optString("key")
                            val displayName = champion.optString("name")
                            val internalId = champion.optString("id")
                            val imageFile = champion.optJSONObject("image")?.optString("full")
                                .orEmpty().ifBlank { "$internalId.png" }
                            if (numericId.isBlank() || displayName.isBlank() || internalId.isBlank()) continue
                            val visual = ChampionVisual(
                                numericId = numericId,
                                displayName = displayName,
                                imageFile = imageFile,
                                version = version
                            )
                            for (key in listOf(numericId, displayName, internalId, imageFile.removeSuffix(".png"))) {
                                put(normalize(key), visual)
                            }
                        }
                    }
                }.getOrDefault(emptyMap())
            }
            cachedVisuals = loaded
            loaded
        }
    }

    private fun normalize(value: String): String =
        value.trim().uppercase().replace(Regex("[^A-Z0-9\\u4E00-\\u9FFF]+"), "")

    private fun getText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "RiftLab-Android/1.0")
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
