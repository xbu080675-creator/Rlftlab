package com.riftlab.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
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
    val sourceUrl: String = "",
    val crossConfirmed: Boolean = false,
    val conflict: Boolean = false
)

data class StartingRosterFetchResult(
    val evidence: Map<String, StartingRosterEvidence> = emptyMap(),
    val endpointLabel: String = "NONE",
    val endpointUrl: String = "",
    val usedLastGood: Boolean = false,
    val checkedEndpoints: Int = 0,
    val diagnostics: String = ""
)

/**
 * Global official starting-roster feed.
 *
 * Android never crawls social HTML directly. Upstream collectors/OCR normalize official announcements
 * into one small JSON feed. The phone validates every delivery endpoint before trusting it, keeps
 * trying when a mirror is stale for the current match, and persists the last known-good matching
 * payload so a temporary network outage cannot erase an already confirmed official roster.
 */
internal class StartingRosterFeed(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private data class Endpoint(val label: String, val url: String)

        private val ENDPOINTS = listOf(
            Endpoint("GITEE", "https://gitee.com/xiaobaiaaa1/Rlftlab/raw/main/data/global/starting_rosters.json"),
            Endpoint("JSDELIVR", "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/global/starting_rosters.json"),
            Endpoint("GITHUB_RAW", "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/global/starting_rosters.json")
        )

        val FEED_URLS: List<String> = ENDPOINTS.map { it.url }
        private val coreRoles = listOf("TOP", "JUG", "MID", "BOT", "SUP")
        private const val MAX_SCHEMA_VERSION = 3
    }

    private data class Candidate(
        val label: String,
        val url: String,
        val body: String,
        val evidence: Map<String, StartingRosterEvidence>,
        val updatedAtEpochMs: Long
    )

    private val appContext = context.applicationContext
    private val lastGoodFile: File = File(appContext.filesDir, "starting_roster/last_good.json")
    private val unhealthyUntil = mutableMapOf<String, Long>()

    suspend fun fetchFor(target: ScheduledEsportsMatch): StartingRosterFetchResult = withContext(Dispatchers.IO) {
        if (target.teams.size < 2) return@withContext StartingRosterFetchResult(diagnostics = "target_missing_teams")

        val diagnostics = mutableListOf<String>()
        val candidates = mutableListOf<Candidate>()
        var checked = 0
        val now = System.currentTimeMillis()

        for (endpoint in ENDPOINTS) {
            val blockedUntil = unhealthyUntil[endpoint.url] ?: 0L
            if (blockedUntil > now) {
                diagnostics += "${endpoint.label}:cooldown"
                continue
            }
            checked++
            val body = fetchBody(endpoint, diagnostics) ?: continue
            val root = validateRoot(body, endpoint.label, diagnostics) ?: continue
            val evidence = parseEvidence(root, target)
            val updatedAt = parseInstant(root.optString("updatedAt")) ?: 0L
            diagnostics += "${endpoint.label}:ok:${evidence.size}/2"
            candidates += Candidate(endpoint.label, endpoint.url, body, evidence, updatedAt)

            // Two teams is the maximum useful match coverage. Once a healthy endpoint has both,
            // later mirrors cannot improve match coverage; stop wasting network time.
            if (evidence.size >= 2 && evidence.values.none { it.conflict }) break
        }

        val bestNetwork = candidates
            .filter { it.evidence.isNotEmpty() }
            .maxWithOrNull(
                compareBy<Candidate> { it.evidence.size }
                    .thenBy { it.evidence.values.maxOfOrNull(StartingRosterEvidence::observedAtEpochMs) ?: 0L }
                    .thenBy { it.updatedAtEpochMs }
            )

        if (bestNetwork != null) {
            persistLastGood(bestNetwork.body)
            return@withContext StartingRosterFetchResult(
                evidence = bestNetwork.evidence,
                endpointLabel = bestNetwork.label,
                endpointUrl = bestNetwork.url,
                usedLastGood = false,
                checkedEndpoints = checked,
                diagnostics = diagnostics.joinToString(" · ")
            )
        }

        val cachedBody = runCatching { lastGoodFile.takeIf(File::isFile)?.readText() }.getOrNull()
        if (!cachedBody.isNullOrBlank()) {
            val cachedRoot = validateRoot(cachedBody, "LAST_GOOD", diagnostics)
            val cachedEvidence = cachedRoot?.let { parseEvidence(it, target) }.orEmpty()
            if (cachedEvidence.isNotEmpty()) {
                diagnostics += "LAST_GOOD:hit:${cachedEvidence.size}/2"
                return@withContext StartingRosterFetchResult(
                    evidence = cachedEvidence,
                    endpointLabel = "LAST_GOOD",
                    usedLastGood = true,
                    checkedEndpoints = checked,
                    diagnostics = diagnostics.joinToString(" · ")
                )
            }
        }

        val validButNoMatch = candidates.isNotEmpty()
        StartingRosterFetchResult(
            endpointLabel = if (validButNoMatch) "VALID_NO_MATCH" else "NO_VALID_SOURCE",
            checkedEndpoints = checked,
            diagnostics = diagnostics.joinToString(" · ").ifBlank { "no_endpoint_attempted" }
        )
    }

    private fun fetchBody(endpoint: Endpoint, diagnostics: MutableList<String>): String? {
        return runCatching {
            val request = Request.Builder()
                .url(endpoint.url)
                .header("Cache-Control", "no-cache")
                .header("Accept", "application/json,text/plain;q=0.9,*/*;q=0.1")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    diagnostics += "${endpoint.label}:http_${response.code}"
                    unhealthyUntil[endpoint.url] = System.currentTimeMillis() + 3 * 60_000L
                    return@use null
                }
                response.body?.string()?.takeIf { it.isNotBlank() }
            }
        }.onFailure { error ->
            diagnostics += "${endpoint.label}:${error::class.java.simpleName}"
            unhealthyUntil[endpoint.url] = System.currentTimeMillis() + 3 * 60_000L
        }.getOrNull()
    }

    private fun validateRoot(body: String, label: String, diagnostics: MutableList<String>): JSONObject? {
        val root = runCatching { JSONObject(body) }.getOrNull()
        if (root == null) {
            diagnostics += "$label:invalid_json"
            return null
        }
        val schema = root.optInt("schemaVersion", -1)
        if (schema !in 1..MAX_SCHEMA_VERSION) {
            diagnostics += "$label:bad_schema_$schema"
            return null
        }
        if (root.optJSONArray("evidence") == null) {
            diagnostics += "$label:no_evidence_array"
            return null
        }
        return root
    }

    private fun parseEvidence(root: JSONObject, target: ScheduledEsportsMatch): Map<String, StartingRosterEvidence> {
        val rows = root.optJSONArray("evidence") ?: return emptyMap()
        val leftAliases = aliases(target.teams[0])
        val rightAliases = aliases(target.teams[1])
        val accepted = mutableListOf<StartingRosterEvidence>()

        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val timezone = row.optString("timezone").ifBlank { defaultTimezone(target) }
            val matchDate = row.optString("matchDateLocal").ifBlank { row.optString("matchDateChina") }
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
                val player = startersJson.optJSONObject(j) ?: continue
                val role = normalizeRole(player.optString("role")) ?: continue
                val id = player.optString("id").trim()
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
            val published = parseInstant(row.optString("publishedAt")) ?: continue
            val observed = parseInstant(row.optString("observedAt")) ?: published

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
                sourceUrl = row.optString("sourceUrl"),
                crossConfirmed = row.optBoolean("crossConfirmed", evidenceType == StartingRosterEvidenceType.CROSS_CONFIRMED)
            )
        }

        return resolveByTeam(accepted)
    }

    private fun persistLastGood(body: String) {
        runCatching {
            lastGoodFile.parentFile?.mkdirs()
            val temp = File(lastGoodFile.parentFile, "${lastGoodFile.name}.tmp")
            temp.writeText(body)
            if (!temp.renameTo(lastGoodFile)) {
                lastGoodFile.writeText(body)
                temp.delete()
            }
        }
    }

    private fun resolveByTeam(rows: List<StartingRosterEvidence>): Map<String, StartingRosterEvidence> {
        return rows.groupBy { token(it.team) }.mapValues { (_, candidates) ->
            val bestRank = candidates.maxOfOrNull { sourceRank(it.source) } ?: 0
            val finalists = candidates.filter { sourceRank(it.source) == bestRank }
            val distinctLineups = finalists.map(::lineupKey).distinct()
            if (distinctLineups.size > 1) {
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

    private fun parseInstant(raw: String): Long? = runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
}
