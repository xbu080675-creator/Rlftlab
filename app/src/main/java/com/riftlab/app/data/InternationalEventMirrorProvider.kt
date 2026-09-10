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
 *
 * Upstream may expose the same event through more than one numeric event id. RiftLab therefore uses
 * the provider event slug as its stable tournament identity and keeps the individual provider match
 * ids only as Series identities. Unknown short codes remain blank rather than being synthesized.
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

        return cachedRoot
    }

    private fun valid(root: JSONObject): Boolean =
        root.optInt("schemaVersion", 0) > 0 && (root.optJSONArray("events")?.length() ?: 0) > 0

    private fun parseMatches(root: JSONObject): List<ScheduledEsportsMatch> {
        val events = root.optJSONArray("events") ?: JSONArray()
        return buildList {
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val upstreamEventId = event.optString("id")
                val eventName = event.optString("name").ifBlank { "International Event" }
                val eventSlug = event.optString("slug").ifBlank { upstreamEventId }
                if (eventSlug.isBlank()) continue
                val stableEventId = stableEventId(eventSlug)
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
                            val code = team.optString("code")
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
                    val upstreamMatchId = row.optString("providerMatchId")
                        .ifBlank { row.optString("id") }
                        .ifBlank { "$upstreamEventId-$j" }
                    val stableMatchId = "provider:$upstreamMatchId"
                    add(
                        ScheduledEsportsMatch(
                            eventId = stableMatchId,
                            matchId = stableMatchId,
                            league = eventName,
                            blockName = row.optString("stage").ifBlank { "$provider · Provider" },
                            startTimeIso = startTime,
                            state = row.optString("state").ifBlank { "unstarted" },
                            bestOf = row.optInt("bestOf", 0),
                            teams = teams,
                            leagueId = stableEventId,
                            leagueSlug = eventSlug
                        )
                    )
                }
            }
        }.distinctBy { it.matchId }.sortedBy { it.startTimeIso }
    }

    private fun parseTournaments(root: JSONObject): List<EsportsTournamentRef> {
        val events = root.optJSONArray("events") ?: JSONArray()
        val grouped = linkedMapOf<String, MutableList<JSONObject>>()
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val slug = event.optString("slug").ifBlank { event.optString("id") }
            if (slug.isBlank()) continue
            grouped.getOrPut(slug) { mutableListOf() }.add(event)
        }

        return grouped.mapNotNull { (slug, variants) ->
            val name = variants.asSequence()
                .map { it.optString("name") }
                .firstOrNull { it.isNotBlank() }
                ?: "International Event"
            val dates = buildList {
                variants.forEach { event ->
                    val matches = event.optJSONArray("matches") ?: JSONArray()
                    for (j in 0 until matches.length()) {
                        matches.optJSONObject(j)
                            ?.optString("scheduledAt")
                            ?.take(10)
                            ?.takeIf { it.length == 10 }
                            ?.let(::add)
                    }
                }
            }.distinct().sorted()
            if (dates.isEmpty()) return@mapNotNull null

            val eventId = stableEventId(slug)
            EsportsTournamentRef(
                id = eventId,
                slug = slug,
                startDate = dates.first(),
                endDate = dates.last(),
                leagueId = eventId,
                leagueSlug = slug,
                leagueName = name
            )
        }.sortedBy { it.startDate }
    }

    private fun stableEventId(slug: String): String =
        "rft-event:" + slug.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

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
