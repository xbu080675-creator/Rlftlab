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

enum class StartingRosterSource { TEAM_WEIBO, LPL_WEIBO, OFFICIAL_SITE, OTHER_OFFICIAL }
enum class StartingRosterEvidenceType { TEXT, IMAGE_OCR, CROSS_CONFIRMED }

data class StartingRosterEvidence(
    val matchDateChina: String,
    val team: String,
    val opponent: String,
    val starters: List<PlayerCard>,
    val source: StartingRosterSource,
    val account: String,
    val publishedAtEpochMs: Long,
    val observedAtEpochMs: Long,
    val evidenceType: StartingRosterEvidenceType,
    val confidence: Float
)

/**
 * Fast starting-roster lane for LPL. Website publication is NOT a gate.
 *
 * Team/LPL official social posts can win the race; the website is a later confirmation source.
 * The mirror is normalized outside the APK so text-first extraction and image OCR can evolve without
 * shipping a new app. Invalid/mismatched evidence is ignored rather than guessed.
 */
internal class LplStartingRosterFeed(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val FEED_URL = "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/lpl/starting_rosters.json"
        private val coreRoles = listOf("TOP", "JUG", "MID", "BOT", "SUP")
    }

    suspend fun fetchFor(target: ScheduledEsportsMatch): Map<String, StartingRosterEvidence> = withContext(Dispatchers.IO) {
        if (!isLpl(target) || target.teams.size < 2) return@withContext emptyMap()
        val targetDate = chinaDate(target.startTimeIso) ?: return@withContext emptyMap()
        val request = Request.Builder().url(FEED_URL).header("Cache-Control", "no-cache").build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyMap()
            response.body?.string().orEmpty()
        }
        if (body.isBlank()) return@withContext emptyMap()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext emptyMap()
        val rows = root.optJSONArray("evidence") ?: return@withContext emptyMap()
        val leftAliases = aliases(target.teams[0])
        val rightAliases = aliases(target.teams[1])
        val accepted = mutableListOf<StartingRosterEvidence>()

        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            if (row.optString("matchDateChina") != targetDate) continue
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
                starters += PlayerCard(role = role, id = id, rank = "首发已确认", recent = row.optString("account"))
            }
            if (starters.size != 5 || starters.map { it.role }.toSet() != coreRoles.toSet()) continue
            val source = runCatching { StartingRosterSource.valueOf(row.optString("source")) }.getOrNull() ?: continue
            val evidenceType = runCatching { StartingRosterEvidenceType.valueOf(row.optString("evidenceType")) }.getOrNull() ?: continue
            val published = runCatching { Instant.parse(row.optString("publishedAt")).toEpochMilli() }.getOrNull() ?: continue
            val observed = runCatching { Instant.parse(row.optString("observedAt")).toEpochMilli() }.getOrDefault(published)
            accepted += StartingRosterEvidence(
                matchDateChina = targetDate,
                team = row.optString("team"),
                opponent = row.optString("opponent"),
                starters = starters.sortedBy { coreRoles.indexOf(it.role) },
                source = source,
                account = row.optString("account"),
                publishedAtEpochMs = published,
                observedAtEpochMs = observed,
                evidenceType = evidenceType,
                confidence = row.optDouble("confidence", 1.0).toFloat().coerceIn(0f, 1f)
            )
        }

        accepted.groupBy { token(it.team) }.mapValues { (_, candidates) ->
            candidates.maxWithOrNull(compareBy<StartingRosterEvidence> { sourceRank(it.source) }.thenBy { it.publishedAtEpochMs })!!
        }
    }

    private fun sourceRank(source: StartingRosterSource): Int = when (source) {
        StartingRosterSource.OFFICIAL_SITE -> 4
        StartingRosterSource.LPL_WEIBO -> 3
        StartingRosterSource.TEAM_WEIBO -> 2
        StartingRosterSource.OTHER_OFFICIAL -> 1
    }

    private fun normalizeRole(raw: String): String? = when (raw.uppercase()) {
        "TOP" -> "TOP"
        "JUG", "JUNGLE" -> "JUG"
        "MID" -> "MID"
        "BOT", "ADC", "BOTTOM" -> "BOT"
        "SUP", "SUPPORT" -> "SUP"
        else -> null
    }

    private fun aliases(team: EsportsTeamRef): Set<String> = listOf(team.code, team.name, team.slug, team.id).map(::token).filter(String::isNotBlank).toSet()
    private fun token(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
    private fun isLpl(target: ScheduledEsportsMatch): Boolean = token(target.league).contains("LPL") || token(target.leagueSlug).contains("LPL") || target.league.uppercase().contains("PRO LEAGUE")
    private fun chinaDate(iso: String): String? = runCatching {
        DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.of("Asia/Shanghai")).format(Instant.parse(iso))
    }.getOrNull()
}
