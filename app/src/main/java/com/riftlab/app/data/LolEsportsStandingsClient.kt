package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal class LolEsportsStandingsClient {

    suspend fun fetchLplTournaments(): List<EsportsTournamentRef> {
        val root = getJson(
            "${LolEsportsConfig.PERSISTED_BASE}/getTournamentsForLeague?hl=en-US&leagueId=${LolEsportsConfig.LPL_LEAGUE_ID}"
        )
        val leagues = root.optJSONObject("data")?.optJSONArray("leagues") ?: JSONArray()
        val result = buildList {
            for (i in 0 until leagues.length()) {
                val league = leagues.optJSONObject(i) ?: continue
                val tournaments = league.optJSONArray("tournaments") ?: JSONArray()
                for (j in 0 until tournaments.length()) {
                    val tournament = tournaments.optJSONObject(j) ?: continue
                    val id = tournament.optString("id")
                    if (id.isBlank()) continue
                    add(
                        EsportsTournamentRef(
                            id = id,
                            slug = tournament.optString("slug"),
                            startDate = tournament.optString("startDate"),
                            endDate = tournament.optString("endDate")
                        )
                    )
                }
            }
        }
        return result.distinctBy { it.id }.sortedBy { it.startDate }
    }

    suspend fun fetchTournamentStandings(tournamentId: String): TournamentStandings? {
        if (tournamentId.isBlank()) return null
        val encoded = URLEncoder.encode(tournamentId, "UTF-8")
        val root = getJson(
            "${LolEsportsConfig.PERSISTED_BASE}/getStandings?hl=en-US&tournamentId=$encoded"
        )
        val blocks = root.optJSONObject("data")?.optJSONArray("standings") ?: JSONArray()
        val stages = buildList {
            for (i in 0 until blocks.length()) {
                val block = blocks.optJSONObject(i) ?: continue
                val stageArray = block.optJSONArray("stages") ?: JSONArray()
                for (j in 0 until stageArray.length()) {
                    val stage = stageArray.optJSONObject(j) ?: continue
                    add(parseStage(stage))
                }
            }
        }.distinctBy { it.id.ifBlank { it.slug.ifBlank { it.name } } }

        return TournamentStandings(
            tournamentId = tournamentId,
            stages = stages
        )
    }

    private fun parseStage(stage: JSONObject): StandingStage {
        val sectionsJson = stage.optJSONArray("sections") ?: JSONArray()
        val sections = buildList {
            for (i in 0 until sectionsJson.length()) {
                val section = sectionsJson.optJSONObject(i) ?: continue
                add(parseSection(section))
            }
        }
        return StandingStage(
            id = stage.optString("id"),
            name = stage.optString("name"),
            slug = stage.optString("slug"),
            type = if (stage.isNull("type")) "" else stage.optString("type"),
            sections = sections
        )
    }

    private fun parseSection(section: JSONObject): StandingSection {
        val rankingsJson = section.optJSONArray("rankings") ?: JSONArray()
        val rankings = buildList {
            for (i in 0 until rankingsJson.length()) {
                val ranking = rankingsJson.optJSONObject(i) ?: continue
                val ordinal = ranking.optInt("ordinal", i + 1)
                val teams = ranking.optJSONArray("teams") ?: JSONArray()
                for (j in 0 until teams.length()) {
                    val teamJson = teams.optJSONObject(j) ?: continue
                    val team = parseStandingTeamRef(teamJson)
                    val record = teamJson.optJSONObject("record") ?: JSONObject()
                    val wins = record.optInt("wins", 0)
                    val losses = record.optInt("losses", 0)
                    add(
                        StandingTeam(
                            ordinal = ordinal,
                            team = team,
                            wins = wins,
                            losses = losses,
                            // Tournament/group standing points shown by the Riot standings surface.
                            // This is deliberately NOT annual Championship Points for Worlds qualification.
                            points = wins
                        )
                    )
                }
            }
        }

        val matchesJson = section.optJSONArray("matches") ?: JSONArray()
        val matches = buildList {
            for (i in 0 until matchesJson.length()) {
                val match = matchesJson.optJSONObject(i) ?: continue
                val id = match.optString("id")
                if (id.isBlank()) continue
                add(
                    StandingBracketMatch(
                        id = id,
                        state = match.optString("state"),
                        previousMatchIds = stringList(match.optJSONArray("previousMatchIds")),
                        teams = parseTeams(match.optJSONArray("teams"))
                    )
                )
            }
        }

        return StandingSection(
            name = section.optString("name"),
            rankings = rankings.sortedWith(compareBy<StandingTeam> { it.ordinal }.thenByDescending { it.points ?: it.wins }),
            matches = matches
        )
    }

    private fun parseStandingTeamRef(team: JSONObject): EsportsTeamRef {
        val name = team.optString("name")
        val code = team.optString("code").ifBlank {
            name.filter { it.isLetterOrDigit() }.take(4).uppercase()
        }
        val record = team.optJSONObject("record") ?: JSONObject()
        val result = team.optJSONObject("result") ?: JSONObject()
        return EsportsTeamRef(
            id = team.optString("id"),
            code = code,
            name = name.ifBlank { code },
            slug = team.optString("slug"),
            imageUrl = team.optString("image"),
            gameWins = result.optInt("gameWins", 0),
            outcome = result.optString("outcome"),
            recordWins = record.optInt("wins", 0),
            recordLosses = record.optInt("losses", 0)
        )
    }

    private fun parseTeams(array: JSONArray?): List<EsportsTeamRef> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val team = array.optJSONObject(i) ?: continue
                add(parseStandingTeamRef(team))
            }
        }
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i)
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private suspend fun getJson(url: String): JSONObject =
        RiotResilientHttp.getJson(url, connectTimeoutMs = 8_000, readTimeoutMs = 8_000)
}
