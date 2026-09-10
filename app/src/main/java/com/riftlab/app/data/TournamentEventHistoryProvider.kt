package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * dev.71 historical tournament hydrator.
 *
 * The current moving getSchedule window is not a historical database. For an opened Tournament
 * Edition we therefore ask Riot's tournament-scoped getCompletedEvents endpoint, then use real
 * game IDs from EventDetails to read LiveStats gameMetadata.patchVersion. This gives old editions
 * a real event/participant/patch source instead of leaving the archive dependent on whatever
 * happens to still be present in today's schedule pages.
 *
 * Awards remain provenance-preserving: only rows already present in the verified awards mirror are
 * promoted. No KDA/damage based MVP guessing is performed here.
 */
internal data class TournamentEventHistorySnapshot(
    val completedSeries: List<ScheduledEsportsMatch> = emptyList(),
    val patchVersions: List<String> = emptyList(),
    val verifiedAwards: List<OfficialMvpRecord> = emptyList(),
    val completedEventsSource: String = "",
    val patchSource: String = "",
    val awardsSource: String = "",
    val diagnostics: List<String> = emptyList()
)

internal class TournamentEventHistoryProvider {
    private val globalAwardsProvider = GlobalVerifiedAwardsProvider()

    suspend fun fetch(edition: TournamentEditionArchiveRecord): TournamentEventHistorySnapshot =
        withContext(Dispatchers.IO) {
            val diagnostics = mutableListOf<String>()

            val completed = runCatching { fetchCompletedEvents(edition) }
                .onFailure { diagnostics += "Riot Completed Events: ${shortError(it)}" }
                .getOrDefault(emptyList())

            val patches = if (completed.isEmpty()) {
                emptyList()
            } else {
                runCatching { fetchPatchVersions(completed) }
                    .onFailure { diagnostics += "Riot LiveStats patch: ${shortError(it)}" }
                    .getOrDefault(emptyList())
            }

            val awards = if (completed.isEmpty()) {
                emptyList()
            } else {
                runCatching { fetchVerifiedAwards(completed) }
                    .onFailure { diagnostics += "Verified Awards: ${shortError(it)}" }
                    .getOrDefault(emptyList())
            }

            TournamentEventHistorySnapshot(
                completedSeries = completed,
                patchVersions = patches,
                verifiedAwards = awards,
                completedEventsSource = if (completed.isNotEmpty()) {
                    "${RiotResilientHttp.sourceLabel()} · getCompletedEvents"
                } else "",
                patchSource = if (patches.isNotEmpty()) {
                    "Riot LoL Esports LiveStats · gameMetadata.patchVersion"
                } else "",
                awardsSource = if (awards.isNotEmpty()) {
                    "RiftLab Verified Awards Mirror"
                } else "",
                diagnostics = diagnostics
            )
        }

    private suspend fun fetchCompletedEvents(
        edition: TournamentEditionArchiveRecord
    ): List<ScheduledEsportsMatch> {
        if (edition.tournamentId.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(edition.tournamentId, "UTF-8")
        val root = RiotResilientHttp.getJson(
            "${LolEsportsConfig.PERSISTED_BASE}/getCompletedEvents?hl=en-US&tournamentId=$encoded",
            connectTimeoutMs = 8_000,
            readTimeoutMs = 10_000
        )
        val events = root.optJSONObject("data")
            ?.optJSONObject("schedule")
            ?.optJSONArray("events")
            ?: JSONArray()

        return buildList {
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                if (event.optString("type").isNotBlank() && !event.optString("type").equals("match", true)) continue
                val match = event.optJSONObject("match") ?: continue
                val teams = parseTeams(match.optJSONArray("teams") ?: event.optJSONArray("teams"))
                if (teams.size < 2) continue

                val league = event.optJSONObject("league")
                val eventId = event.optString("id").ifBlank { match.optString("id") }
                val matchId = match.optString("id").ifBlank { eventId }
                if (eventId.isBlank() && matchId.isBlank()) continue

                add(
                    ScheduledEsportsMatch(
                        eventId = eventId,
                        matchId = matchId,
                        league = league?.optString("name").orEmpty().ifBlank { edition.leagueName.ifBlank { edition.leagueSlug.uppercase() } },
                        blockName = event.optString("blockName"),
                        startTimeIso = event.optString("startTime"),
                        state = event.optString("state").ifBlank { "completed" },
                        bestOf = match.optJSONObject("strategy")?.optInt("count", 0) ?: 0,
                        teams = teams,
                        leagueId = league?.optString("id").orEmpty().ifBlank { edition.leagueId },
                        leagueSlug = league?.optString("slug").orEmpty().ifBlank { edition.leagueSlug }
                    )
                )
            }
        }
            .distinctBy { it.eventId.ifBlank { it.matchId } }
            .sortedBy { it.startTimeIso }
    }

    private suspend fun fetchPatchVersions(series: List<ScheduledEsportsMatch>): List<String> {
        if (series.isEmpty()) return emptyList()
        val samples = sampleSeries(series)
        val patches = linkedSetOf<String>()

        for (match in samples) {
            val eventId = match.eventId.ifBlank { match.matchId }
            if (eventId.isBlank()) continue
            val eventRoot = runCatching {
                RiotResilientHttp.getJson(
                    "${LolEsportsConfig.PERSISTED_BASE}/getEventDetails?hl=en-US&id=${URLEncoder.encode(eventId, "UTF-8")}",
                    connectTimeoutMs = 7_000,
                    readTimeoutMs = 8_000
                )
            }.getOrNull() ?: continue

            val games = eventRoot.optJSONObject("data")
                ?.optJSONObject("event")
                ?.optJSONObject("match")
                ?.optJSONArray("games")
                ?: continue

            var gameId = ""
            for (i in 0 until games.length()) {
                val candidate = games.optJSONObject(i)?.optString("id").orEmpty()
                if (candidate.isNotBlank()) {
                    gameId = candidate
                    break
                }
            }
            if (gameId.isBlank()) continue

            val window = runCatching {
                RiotResilientHttp.getJson(
                    "${LolEsportsConfig.LIVE_BASE}/window/${URLEncoder.encode(gameId, "UTF-8")}",
                    connectTimeoutMs = 7_000,
                    readTimeoutMs = 8_000
                )
            }.getOrNull() ?: continue

            val patch = normalizePatch(window.optJSONObject("gameMetadata")?.optString("patchVersion").orEmpty())
            if (patch.isNotBlank()) patches += patch
        }
        return patches.toList()
    }

    private suspend fun fetchVerifiedAwards(
        series: List<ScheduledEsportsMatch>
    ): List<OfficialMvpRecord> {
        val result = mutableListOf<OfficialMvpRecord>()
        for (match in series) {
            val awards = runCatching { globalAwardsProvider.fetch(match) }.getOrNull() ?: continue
            awards.seriesMvp?.let(result::add)
            result += awards.gameMvps
        }
        return result
            .filter { it.playerName.isNotBlank() }
            .distinctBy { "${it.game ?: 0}|${token(it.playerName)}|${token(it.team)}|${token(it.source)}" }
    }

    private fun sampleSeries(series: List<ScheduledEsportsMatch>): List<ScheduledEsportsMatch> {
        if (series.size <= 4) return series
        val indices = linkedSetOf(0, series.size / 3, (series.size * 2) / 3, series.lastIndex)
        return indices.mapNotNull(series::getOrNull)
    }

    private fun parseTeams(array: JSONArray?): List<EsportsTeamRef> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val name = row.optString("name")
                val code = row.optString("code").ifBlank {
                    name.filter { it.isLetterOrDigit() }.take(4).uppercase()
                }
                val result = row.optJSONObject("result") ?: JSONObject()
                val record = row.optJSONObject("record") ?: JSONObject()
                val image = EsportsAssetCache.normalize(row.optString("image"))
                if (image.isNotBlank()) {
                    EsportsAssetCache.putTeam(image, row.optString("id"), row.optString("slug"), code, name)
                }
                add(
                    EsportsTeamRef(
                        id = row.optString("id"),
                        code = code,
                        name = name.ifBlank { code },
                        slug = row.optString("slug"),
                        imageUrl = image,
                        gameWins = result.optInt("gameWins", 0),
                        outcome = result.optString("outcome"),
                        recordWins = record.optInt("wins", 0),
                        recordLosses = record.optInt("losses", 0)
                    )
                )
            }
        }
    }

    private fun normalizePatch(raw: String): String {
        val match = Regex("(?<!\\d)(\\d{1,2})\\.(\\d{1,2})(?!\\d)").find(raw.trim()) ?: return ""
        return "${match.groupValues[1]}.${match.groupValues[2]}"
    }

    private fun token(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

    private fun shortError(error: Throwable): String =
        (error.message ?: error::class.java.simpleName).replace('\n', ' ').take(120)
}
