package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Non-Riot international-event schedule adapter.
 *
 * The repository mirror is produced by a scheduled data job from explicitly attributed public
 * providers. Provider rows never masquerade as Riot-official data, and a mirror failure simply
 * leaves those events unavailable instead of synthesizing results.
 */
internal object InternationalEventMirrorProvider {
    private val endpoints = listOf(
        "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/global/international_events.json",
        "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/global/international_events.json"
    )

    @Volatile private var cachedAt = 0L
    @Volatile private var cachedMatches: List<ScheduledEsportsMatch> = emptyList()
    private const val TTL_MS = 15L * 60L * 1000L

    suspend fun fetchMatches(): List<ScheduledEsportsMatch> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedMatches.isNotEmpty() && now - cachedAt < TTL_MS) return@withContext cachedMatches

        for (endpoint in endpoints) {
            val root = runCatching { getJson(endpoint) }.getOrNull() ?: continue
            val parsed = parse(root)
            if (parsed.isNotEmpty()) {
                cachedMatches = parsed
                cachedAt = now
                return@withContext parsed
            }
        }
        cachedMatches
    }

    private fun parse(root: JSONObject): List<ScheduledEsportsMatch> {
        if (root.optInt("schemaVersion", 0) <= 0) return emptyList()
        val events = root.optJSONArray("events") ?: JSONArray()
        return buildList {
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val eventId = event.optString("id")
                val eventName = event.optString("name").ifBlank { "International Event" }
                val eventSlug = event.optString("slug").ifBlank { eventId }
                val provider = event.optString("source").ifBlank { "Verified Provider" }
                val matches = event.optJSONArray("matches") ?: JSONArray()
                for (j in 0 until matches.length()) {
                    val row = matches.optJSONObject(j) ?: continue
                    val teamsJson = row.optJSONArray("teams") ?: JSONArray()
                    val teams = buildList {
                        for (k in 0 until teamsJson.length()) {
                            val team = teamsJson.optJSONObject(k) ?: continue
                            val name = team.optString("name")
                            val code = team.optString("code").ifBlank { name.take(8) }
                            if (name.isBlank() && code.isBlank()) continue
                            add(
                                EsportsTeamRef(
                                    id = team.optString("id"),
                                    code = code,
                                    name = name.ifBlank { code },
                                    slug = team.optString("slug"),
                                    imageUrl = team.optString("imageUrl"),
                                    gameWins = team.optInt("wins", 0),
                                    outcome = team.optString("outcome")
                                )
                            )
                        }
                    }
                    if (teams.size < 2) continue
                    val id = row.optString("id").ifBlank { "$eventId-$j" }
                    add(
                        ScheduledEsportsMatch(
                            eventId = "provider:$id",
                            matchId = "provider:$id",
                            league = eventName,
                            blockName = row.optString("stage").ifBlank { "$provider · Provider" },
                            startTimeIso = row.optString("scheduledAt"),
                            state = row.optString("state").ifBlank { "unstarted" },
                            bestOf = row.optInt("bestOf", 0),
                            teams = teams,
                            leagueId = eventId,
                            leagueSlug = eventSlug
                        )
                    )
                }
            }
        }.distinctBy { it.matchId }.sortedBy { it.startTimeIso }
    }

    private fun getJson(endpoint: String): JSONObject {
        val bucket = System.currentTimeMillis() / 300_000L
        val connection = URL("$endpoint?riftlabInternational=$bucket").openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 5_000
            connection.readTimeout = 7_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("User-Agent", "RiftLab-InternationalMirror/1")
            if (connection.responseCode !in 200..299) error("international mirror HTTP ${connection.responseCode}")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
