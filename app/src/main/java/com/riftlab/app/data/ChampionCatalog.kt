package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Resolves Riot numeric champion ids to localized display names for BP/history UI. */
internal object ChampionCatalog {
    private const val VERSIONS_URL = "https://ddragon.leagueoflegends.com/api/versions.json"
    private val mutex = Mutex()
    @Volatile private var cachedNames: Map<String, String>? = null

    suspend fun decorateDrafts(drafts: List<DraftPickRecord>): List<DraftPickRecord> {
        if (drafts.isEmpty()) return drafts
        val names = loadNames()
        return drafts.map { draft ->
            draft.copy(
                blueBans = draft.blueBans.map { displayName(it, names) },
                redBans = draft.redBans.map { displayName(it, names) },
                bluePicks = draft.bluePicks.map { displayName(it, names) },
                redPicks = draft.redPicks.map { displayName(it, names) }
            )
        }
    }

    suspend fun displayName(raw: String): String = displayName(raw, loadNames())

    private fun displayName(raw: String, names: Map<String, String>): String {
        val token = raw.trim()
        if (token.isBlank()) return token
        if (!token.all(Char::isDigit)) return token
        return names[token] ?: "英雄ID $token"
    }

    private suspend fun loadNames(): Map<String, String> {
        cachedNames?.let { return it }
        return mutex.withLock {
            cachedNames?.let { return@withLock it }
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
                            val id = champion.optString("key")
                            val name = champion.optString("name")
                            if (id.isNotBlank() && name.isNotBlank()) put(id, name)
                        }
                    }
                }.getOrDefault(emptyMap())
            }
            cachedNames = loaded
            loaded
        }
    }

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
