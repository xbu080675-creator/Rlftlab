package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

enum class StartingRosterSource {
    TEAM_SOCIAL,
    LEAGUE_SOCIAL,
    OFFICIAL_SITE,
    OTHER_OFFICIAL
}

enum class StartingRosterEvidenceType { TEXT, IMAGE_OCR, CROSS_CONFIRMED }

data class StartingRosterEvidence(
    val matchDateLocal: String,
    val timezone: String,
    val league: String,
    val team: String,
    val opponent: String,
    val starters: List<PlayerCard>,
    val source: StartingRosterSource,
    val platform: String,
    val account: String,
    val publishedAtEpochMs: Long,
    val observedAtEpochMs: Long,
    val evidenceType: StartingRosterEvidenceType,
    val confidence: Float,
    val crossConfirmed: Boolean = false,
    val conflict: Boolean = false
)

/**
 * Global official starting-roster feed.
 *
 * Important design boundary: the Android app does not need to visit X / Instagram / YouTube /
 * Weibo / regional league sites directly. A cloud-side collector normalizes official announcements
 * into one JSON feed. This keeps the China client usable without a VPN and also avoids coupling the
 * APK to changing social-site HTML/API behavior.
 *
 * Origin order intentionally prefers a mainland-friendly mirror, then CDN/GitHub canonical copies.
 * GitHub remains source-of-truth; mirrors are delivery endpoints only.
 */
internal class StartingRosterFeed(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        val FEED_URLS = listOf(
            // Read-only distribution mirror for users without overseas network access.
            "https://gitee.com/xiaobaiaaa1/Rlftlab/raw/main/data/global/starting_rosters.json",
            // Public CDN fallback. Availability in mainland networks can vary by carrier.
            "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/global/starting_rosters.json",
            // Canonical source-of-truth fallback.
            "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/global/starting_rosters.json"
        )

        private val coreRoles = listOf("TOP", "JUG", "MID", "BOT", "SUP")
    }

    suspend fun fetchFor(target: ScheduledEsportsMatch): Map<String, StartingRosterEvidence> = withContext(Dispatchers.IO) {
        if (target.teams.size < 2) return@withContext emptyMap()
        val body = fetchFirstAvailable() ?: return@withContext emptyMap()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext emptyMap()
        val rows = root.optJSONArray("evidence") ?: return@withContext emptyMap()
        val leftAliases = aliases(target.teams[0])
        val rightAliases = aliases(target.teams[1])
        val accepted = mutableListOf<StartingRosterEvidence>()

        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val timezone = row.optString("timezone").ifBlank { defaultTimezone(target) }
            val matchDate = row.optString("matchDateLocal").ifBlank {
                // Backward compatibility for the first LPL seed.
                row.optString("matchDateChina")
            }
            val targetDate = localDate(target.startTimeIso, timezone) ?: continue
            if (matchDate != targetDate) continue

            val rowLeague = row.optString("league")
            if (rowLeague.isNotBlank() && !sameLeague(rowLeague, target)) continue

            val teamToken = token(row.optString("team"))
            val opponentToken = token(row.optString("opponent"))
            val isPair = (teamToken in leftAliases && opponentToken in rightAliases) ||
                (teamToken in rightAliases && opponentToken in leftAliases)
            if (!isPair) continue

            val startersJson = row.optJSONArray("starters") ?: continue
            val starters = mutableListOf<PlayerCard>()
            for (j in 0 until startersJson.length()) {
                val p = startersJson.optJSONObject(j) ?: continue
                val role = normalizeRole(p.optString("role")) ?: continue
                val id = p.optString("id").trim()
                if (id.isBlank()) continue
                starters += PlayerCard(
                    role = role,
                    id = id,
                    rank = "首发已确认",
                    recent = row.optString("account")
                )
            }
            if (starters.size != 5 || starters.map { it.role }.toSet() != coreRoles.toSet()) continue

            val source = parseSource(row.optString("source")) ?: continue
            val evidenceType = runCatching {
                StartingRosterEvidenceType.valueOf(row.optString("evidenceType"))
            }.getOrNull() ?: continue
            val published = runCatching { Instant.parse(row.optString("publishedAt")).toEpochMilli() }.getOrNull() ?: continue
            val observed = runCatching { Instant.parse(row.optString("observedAt")).toEpochMilli() }.getOrDefault(published)

            accepted += StartingRosterEvidence(
                matchDateLocal = targetDate,
                timezone = timezone,
                league = rowLeague.ifBlank { target.league },
                team = row.optString("team"),
                opponent = row.optString("opponent"),
                starters = starters.sortedBy { coreRoles.indexOf(it.role) },
                source = source,
                platform = row.optString("platform").ifBlank { legacyPlatform(row.optString("source")) },
                account = row.optString("account"),
                publishedAtEpochMs = published,
                observedAtEpochMs = observed,
                evidenceType = evidenceType,
                confidence = row.optDouble("confidence", 1.0).toFloat().coerceIn(0f, 1f),
                crossConfirmed = row.optBoolean("crossConfirmed", evidenceType == StartingRosterEvidenceType.CROSS_CONFIRMED)
            )
        }

        resolveByTeam(accepted)
    }

    private fun fetchFirstAvailable(): String? {
        for (url in FEED_URLS) {
            val body = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.string()?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
            if (!body.isNullOrBlank()) return body
        }
        return null
    }

    private fun resolveByTeam(rows: List<StartingRosterEvidence>): Map<String, StartingRosterEvidence> {
        return rows.groupBy { token(it.team) }.mapValues { (_, candidates) ->
            val bestRank = candidates.maxOfOrNull { sourceRank(it.source) } ?: 0
            val finalists = candidates.filter { sourceRank(it.source) == bestRank }
            val distinctLineups = finalists.map { lineupKey(it) }.distinct()

            if (distinctLineups.size > 1) {
                // Two same-trust official sources disagree. Never silently overwrite one with another.
                finalists.maxByOrNull { it.publishedAtEpochMs }!!.copy(conflict = true)
            } else {
                val winner = finalists.maxByOrNull { it.publishedAtEpochMs }!!
                winner.copy(crossConfirmed = winner.crossConfirmed || candidates.count { lineupKey(it) == lineupKey(winner) } >= 2)
            }
        }
    }

    private fun sourceRank(source: StartingRosterSource): Int = when (source) {
        StartingRosterSource.TEAM_SOCIAL,
        StartingRosterSource.LEAGUE_SOCIAL,
        StartingRosterSource.OFFICIAL_SITE -> 3
        StartingRosterSource.OTHER_OFFICIAL -> 2
    }

    private fun parseSource(raw: String): StartingRosterSource? = when (raw.uppercase()) {
        "TEAM_SOCIAL", "TEAM_WEIBO" -> StartingRosterSource.TEAM_SOCIAL
        "LEAGUE_SOCIAL", "LPL_WEIBO" -> StartingRosterSource.LEAGUE_SOCIAL
        "OFFICIAL_SITE" -> StartingRosterSource.OFFICIAL_SITE
        "OTHER_OFFICIAL" -> StartingRosterSource.OTHER_OFFICIAL
        else -> null
    }

    private fun legacyPlatform(raw: String): String = when (raw.uppercase()) {
        "TEAM_WEIBO", "LPL_WEIBO" -> "WEIBO"
        else -> "OFFICIAL"
    }

    private fun normalizeRole(raw: String): String? = when (raw.uppercase()) {
        "TOP", "上单" -> "TOP"
        "JUG", "JUNGLE", "JGL", "打野" -> "JUG"
        "MID", "中单" -> "MID"
        "BOT", "ADC", "BOTTOM", "AD", "下路" -> "BOT"
        "SUP", "SUPPORT", "辅助" -> "SUP"
        else -> null
    }

    private fun lineupKey(evidence: StartingRosterEvidence): String =
        evidence.starters.joinToString("|") { "${it.role}:${token(it.id)}" }

    private fun aliases(team: EsportsTeamRef): Set<String> =
        listOf(team.code, team.name, team.slug, team.id).map(::token).filter(String::isNotBlank).toSet()

    private fun token(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9\\p{L}\\p{N}]+"), "")

    private fun sameLeague(rowLeague: String, target: ScheduledEsportsMatch): Boolean {
        val row = token(rowLeague)
        val candidates = listOf(target.league, target.leagueSlug, target.leagueId).map(::token)
        return candidates.any { it.isNotBlank() && (it == row || it.contains(row) || row.contains(it)) }
    }

    private fun defaultTimezone(target: ScheduledEsportsMatch): String {
        val league = token("${target.league} ${target.leagueSlug}")
        return when {
            league.contains("LPL") -> "Asia/Shanghai"
            league.contains("LCK") -> "Asia/Seoul"
            league.contains("LCP") || league.contains("PCS") -> "Asia/Taipei"
            league.contains("LJL") -> "Asia/Tokyo"
            league.contains("LEC") -> "Europe/Berlin"
            league.contains("LCS") || league.contains("LTA") -> "America/Los_Angeles"
            else -> "UTC"
        }
    }

    private fun localDate(iso: String, timezone: String): String? = runCatching {
        DateTimeFormatter.ISO_LOCAL_DATE
            .withZone(ZoneId.of(timezone))
            .format(Instant.parse(iso))
    }.getOrNull()
}
