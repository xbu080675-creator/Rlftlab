package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Riot Schedule often omits team.image; resolve the canonical team asset through getTeams. */
internal class RiotTeamAssetProvider {
    private val cache = linkedMapOf<String, String>()

    suspend fun resolve(team: EsportsTeamRef): String = withContext(Dispatchers.IO) {
        validAssetUrl(team.imageUrl)?.let { return@withContext it }
        val keys = listOf(team.slug, slugify(team.name), team.code, team.id)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val cacheKey = keys.firstOrNull().orEmpty().uppercase()
        cache[cacheKey]?.let { return@withContext it }

        for (lookup in keys) {
            val root = runCatching {
                getJson("${LolEsportsConfig.PERSISTED_BASE}/getTeams?hl=en-US&id=${enc(lookup)}")
            }.getOrNull() ?: continue
            val teams = root.optJSONObject("data")?.optJSONArray("teams") ?: JSONArray()
            val selected = selectTeam(teams, team) ?: continue
            val image = firstValidAsset(
                selected.opt("image"),
                selected.opt("imageUrl"),
                selected.opt("imageUrlDarkMode"),
                selected.opt("imageUrlLightMode")
            )
            if (image.isNotBlank()) {
                if (cacheKey.isNotBlank()) cache[cacheKey] = image
                return@withContext image
            }
        }
        ""
    }

    private fun selectTeam(array: JSONArray, target: EsportsTeamRef): JSONObject? {
        var first: JSONObject? = null
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (first == null) first = item
            if (matches(item, target)) return item
        }
        return first
    }

    private fun matches(item: JSONObject, target: EsportsTeamRef): Boolean {
        val candidates = listOf(item.optString("id"), item.optString("slug"), item.optString("code"), item.optString("name"))
            .map(::token).filter { it.isNotBlank() }
        val expected = listOf(target.id, target.slug, target.code, target.name)
            .map(::token).filter { it.isNotBlank() }
        return candidates.any { a -> expected.any { b -> a == b || (a.length >= 3 && b.contains(a)) || (b.length >= 3 && a.contains(b)) } }
    }

    private fun firstValidAsset(vararg values: Any?): String =
        values.asSequence().mapNotNull(::validAssetUrl).firstOrNull().orEmpty()

    private fun validAssetUrl(raw: Any?): String? {
        if (raw == null || raw == JSONObject.NULL) return null
        val value = raw.toString().trim()
        if (value.isBlank() || value.equals("null", true) || value.equals("undefined", true)) return null
        return when {
            value.startsWith("https://", true) || value.startsWith("http://", true) -> value
            value.startsWith("//") -> "https:$value"
            else -> null
        }
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 6_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("x-api-key", LolEsportsConfig.API_KEY)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "RiftLab/1.0 Android")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Riot getTeams HTTP $code")
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun token(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
    private fun slugify(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
