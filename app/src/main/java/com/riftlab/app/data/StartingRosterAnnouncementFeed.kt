package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class StartingRosterAnnouncement(
    val id: String,
    val league: String,
    val team: String,
    val source: String,
    val platform: String,
    val account: String,
    val publishedAt: String,
    val observedAt: String,
    val sourceUrl: String,
    val textSnippet: String,
    val imageCount: Int,
    val imageUrls: List<String>,
    val parsed: Boolean
)

/**
 * Raw official-announcement fallback.
 *
 * A parsing failure must never become an information blackout. The server feed
 * keeps recent official post metadata in `announcements`; every Android device
 * can therefore show "official announcement found / parsing pending" even when
 * neither server OCR nor any on-device AI can recover the five-player lineup.
 */
internal class StartingRosterAnnouncementFeed(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
) {
    private val endpoints = listOf(
        "https://gitee.com/xiaobaiaaa1/Rlftlab/raw/main/data/global/starting_rosters.json",
        "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/global/starting_rosters.json",
        "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/global/starting_rosters.json"
    )

    suspend fun fetchFor(target: ScheduledEsportsMatch): List<StartingRosterAnnouncement> = withContext(Dispatchers.IO) {
        val aliases = target.teams.take(2).flatMap { teamAliases(it) }.toSet()
        val leagueTokens = listOf(target.league, target.leagueSlug, target.leagueId)
            .map(::token)
            .filter(String::isNotBlank)
            .toSet()

        for (url in endpoints) {
            val body = runCatching {
                client.newCall(
                    Request.Builder().url(url)
                        .header("Cache-Control", "no-cache")
                        .header("Accept", "application/json,text/plain;q=0.9,*/*;q=0.1")
                        .build()
                ).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.string()
                }
            }.getOrNull().orEmpty()
            if (body.isBlank()) continue
            val root = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val rows = root.optJSONArray("announcements") ?: continue
            val out = mutableListOf<StartingRosterAnnouncement>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val rowLeague = token(row.optString("league"))
                val rowTeam = token(row.optString("team"))
                val text = row.optString("textSnippet")
                val textToken = token(text)
                val leagueMatch = rowLeague.isBlank() || leagueTokens.any { it == rowLeague || it.contains(rowLeague) || rowLeague.contains(it) }
                if (!leagueMatch) continue
                val teamMatch = rowTeam.isBlank() || rowTeam in aliases || aliases.any { it.length >= 2 && textToken.contains(it) }
                if (!teamMatch) continue
                val imageUrlsJson = row.optJSONArray("imageUrls")
                val imageUrls = buildList {
                    if (imageUrlsJson != null) {
                        for (j in 0 until imageUrlsJson.length()) {
                            imageUrlsJson.optString(j).takeIf { it.startsWith("http") }?.let(::add)
                        }
                    }
                }
                out += StartingRosterAnnouncement(
                    id = row.optString("id"),
                    league = row.optString("league"),
                    team = row.optString("team"),
                    source = row.optString("source"),
                    platform = row.optString("platform"),
                    account = row.optString("account"),
                    publishedAt = row.optString("publishedAt"),
                    observedAt = row.optString("observedAt"),
                    sourceUrl = row.optString("sourceUrl"),
                    textSnippet = text,
                    imageCount = row.optInt("imageCount", imageUrls.size),
                    imageUrls = imageUrls,
                    parsed = row.optString("parseStatus").equals("PARSED", ignoreCase = true)
                )
            }
            if (out.isNotEmpty()) return@withContext out.distinctBy { it.id }.take(8)
        }
        emptyList()
    }

    private fun teamAliases(team: EsportsTeamRef): Set<String> =
        listOf(team.code, team.name, team.slug, team.id).map(::token).filter(String::isNotBlank).toSet()

    private fun token(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9\\p{L}\\p{N}]+"), "")
}
