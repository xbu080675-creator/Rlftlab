package com.riftlab.app.data

import com.riftlab.app.RiftLabApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Non-Riot international-event adapter backed by a repository mirror.
 *
 * The mirror is generated from explicitly attributed public provider pages. Provider rows never
 * masquerade as Riot-official data. Network refresh is preferred, while a bundled snapshot keeps
 * dev/offline builds usable until the same mirror reaches the production branch/CDN.
 */
internal object InternationalEventMirrorProvider {
    private val endpoints = listOf(
        "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/global/international_events.json",
        "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/global/international_events.json"
    )
    private const val BUNDLED_ASSET = "data/international_events.json"
    private const val TTL_MS = 15L * 60L * 1000L

    @Volatile private var cachedAt = 0L
    @Volatile private var cachedRoot: JSONObject? = null

    suspend fun fetchMatches(): List<ScheduledEsportsMatch> = withContext(Dispatchers.IO) {
        loadRoot()?.let(::parseMatches).orEmpty()
    }

    suspend fun fetchTournaments(): List<EsportsTournamentRef> = withContext(Dispatchers.IO) {
        loadRoot()?.let(::parseTournaments).orEmpty()
    }

    private fun loadRoot(): JSONObject? {
        val now = System.currentTimeMillis()
        cachedRoot?.takeIf { now - cachedAt < TTL_MS }?.let { return it }

        for (endpoint in endpoints) {
            val root = runCatching { getJson(endpoint) }.getOrNull() ?: continue
            if (!valid(root)) continue
            cachedRoot = root
            cachedAt = now
            return root
        }

        val bundled = runCatching {
            RiftLabApplication.appContext.assets.open(BUNDLED_ASSET)
                .bufferedReader()
                .use { JSONObject(it.readText()) }
        }.getOrNull()?.takeIf(::valid)
        if (bundled != null) {
            cachedRoot = bundled
            cachedAt = now
            return bundled
        }

        // Keep the last known good in-memory snapshot if both refresh paths fail.
        return cachedRoot
    }

    private fun valid(root: JSONObject): Boolean =
        root.optInt("schemaVersion", 0) > 0 && (root.optJSONArray("events")?.length() ?: 0) > 0

    private fun parseMatches(root: JSONObject): List<ScheduledEsportsMatch> {
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
                    val startTime = row.optString("scheduledAt")
                    if (startTime.isBlank()) continue
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
                            startTimeIso = startTime,
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

    private fun parseTournaments(root: JSONObject): List<EsportsTournamentRef> {
        val events = root.optJSONArray("events") ?: JSONArray()
        return buildList {
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val eventId = event.optString("id")
                val slug = event.optString("slug").ifBlank { eventId }
                val name = event.optString("name").ifBlank { "International Event" }
                if (eventId.isBlank() || slug.isBlank()) continue

                val dates = buildList {
                    val matches = event.optJSONArray("matches") ?: JSONArray()
                    for (j in 0 until matches.length()) {
                        matches.optJSONObject(j)
                            ?.optString("scheduledAt")
                            ?.take(10)
                            ?.takeIf { it.length == 10 }
                            ?.let(::add)
                    }
                }.sorted()
                if (dates.isEmpty()) continue

                add(
                    EsportsTournamentRef(
                        id = eventId,
                        slug = slug,
                        startDate = dates.first(),
                        endDate = dates.last(),
                        leagueId = eventId,
                        leagueSlug = slug,
                        leagueName = name
                    )
                )
            }
        }.distinctBy { it.id }.sortedBy { it.startDate }
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
