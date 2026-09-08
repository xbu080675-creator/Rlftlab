package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Resolves a player portrait from Riot getTeams and caches it for visual match-detail cards. */
internal object PlayerPortraitResolver {
    private val mutex = Mutex()
    private val portraitCache = linkedMapOf<String, String>()
    private val teamPayloadCache = linkedMapOf<String, JSONObject?>()

    suspend fun resolve(
        match: ScheduledEsportsMatch,
        playerName: String,
        teamHint: String = ""
    ): String {
        val targetName = playerToken(playerName)
        if (targetName.isBlank()) return ""
        val cacheKey = "${teamToken(teamHint)}|$targetName"
        mutex.withLock { portraitCache[cacheKey]?.let { return it } }

        val orderedTeams = match.teams.sortedByDescending { team ->
            if (teamHint.isNotBlank() && teamMatchesHint(team, teamHint)) 1 else 0
        }
        for (team in orderedTeams) {
            val payload = loadTeamPayload(team) ?: continue
            val players = payload.optJSONArray("players") ?: JSONArray()
            for (i in 0 until players.length()) {
                val player = players.optJSONObject(i) ?: continue
                val nick = player.optString("summonerName")
                    .ifBlank { player.optString("nickName") }
                    .ifBlank { player.optString("name") }
                val token = playerToken(nick)
                if (token.isBlank()) continue
                val matches = token == targetName || token.endsWith(targetName) || targetName.endsWith(token)
                if (!matches) continue
                val image = firstValidAsset(
                    player.opt("image"),
                    player.opt("imageUrl"),
                    player.opt("imageUrlDarkMode"),
                    player.opt("imageUrlLightMode"),
                    player.opt("portrait"),
                    player.opt("portraitUrl")
                )
                if (image.isNotBlank()) {
                    mutex.withLock { portraitCache[cacheKey] = image }
                    return image
                }
            }
        }
        mutex.withLock { portraitCache[cacheKey] = "" }
        return ""
    }

    private suspend fun loadTeamPayload(team: EsportsTeamRef): JSONObject? {
        val lookups = listOf(team.slug, slugify(team.name), team.code, team.id, team.name)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        for (lookup in lookups) {
            val key = lookup.uppercase()
            val cached = mutex.withLock { if (teamPayloadCache.containsKey(key)) teamPayloadCache[key] else null }
            if (cached != null) return cached

            val payload = runCatching { fetchTeam(lookup, team) }.getOrNull()
            mutex.withLock { teamPayloadCache[key] = payload }
            if (payload != null) return payload
        }
        return null
    }

    private suspend fun fetchTeam(lookup: String, expected: EsportsTeamRef): JSONObject? = withContext(Dispatchers.IO) {
        val url = "${LolEsportsConfig.PERSISTED_BASE}/getTeams?hl=en-US&id=${URLEncoder.encode(lookup, Charsets.UTF_8.name())}"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
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
            if (code !in 200..299 || body.isBlank()) return@withContext null
            val teams = JSONObject(body).optJSONObject("data")?.optJSONArray("teams") ?: return@withContext null
            var first: JSONObject? = null
            for (i in 0 until teams.length()) {
                val item = teams.optJSONObject(i) ?: continue
                if (first == null) first = item
                if (teamMatches(item, expected)) return@withContext item
            }
            first
        } finally {
            connection.disconnect()
        }
    }

    private fun teamMatches(item: JSONObject, expected: EsportsTeamRef): Boolean {
        val candidates = listOf(item.optString("id"), item.optString("slug"), item.optString("code"), item.optString("name"))
            .map(::teamToken).filter { it.isNotBlank() }
        val targets = listOf(expected.id, expected.slug, expected.code, expected.name)
            .map(::teamToken).filter { it.isNotBlank() }
        return candidates.any { a -> targets.any { b -> a == b || (a.length >= 3 && b.contains(a)) || (b.length >= 3 && a.contains(b)) } }
    }

    private fun teamMatchesHint(team: EsportsTeamRef, hint: String): Boolean {
        val target = teamToken(hint)
        return listOf(team.id, team.code, team.name, team.slug)
            .map(::teamToken)
            .any { it.isNotBlank() && (it == target || it.contains(target) || target.contains(it)) }
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

    private fun playerToken(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

    private fun teamToken(value: String): String = playerToken(value)
    private fun slugify(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
