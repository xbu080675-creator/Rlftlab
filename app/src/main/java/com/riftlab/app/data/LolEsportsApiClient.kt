package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import kotlin.math.max

internal object LolEsportsConfig {
    const val API_KEY = "0TvQnueqKa5mxJntVWt0w4LpLfEkrV1Ta8rQBb9Z"
    const val LPL_LEAGUE_ID = "98767991314006698"

    // Tier-one regional + international competitions tracked by RiftLab. IDs are discovered
    // dynamically from Riot getLeagues so league reshuffles do not require an APK update.
    val GLOBAL_MAJOR_LEAGUE_SLUGS = setOf(
        "worlds", "msi", "first-stand", "first_stand", "firststand", "ewc", "esports-world-cup", "americas-cup", "emea-masters",
        "lpl", "lck", "lec", "lcs", "lta", "lta-north", "lta_north", "lta-south", "lta_south", "lcp",
        "cblol", "cblol-brazil", "pcs", "vcs", "ljl", "lla", "lrn", "lrs", "fls",
        "lck-cl", "lck_challengers", "lcp-wild-card", "lpl-development-league", "ldl",
        "greek-legends-league", "lit", "nlc", "esports-balkan-league", "tcl", "hitpoint-masters", "rift-legends", "nacl"
    )
    val GLOBAL_TRACKED_LEAGUE_NAMES = setOf(
        "americas cup", "emea masters", "fls", "lck cl", "lck challengers", "lcp wild card",
        "lpl development league", "greek legends league", "lit", "nlc", "esports balkan league",
        "tcl", "hitpoint masters", "rift legends", "nacl"
    )
    const val PERSISTED_BASE = "https://esports-api.lolesports.com/persisted/gw"
    const val LIVE_BASE = "https://feed.lolesports.com/livestats/v1"
}

internal data class TrackedLeagueRef(
    val id: String,
    val slug: String,
    val name: String
)

internal data class LiveEventRef(
    val eventId: String,
    val matchId: String,
    val startTimeIso: String,
    val teams: List<EsportsTeamRef>
)

internal data class LiveGameRef(
    val gameId: String,
    val gameNumber: Int,
    val state: String,
    val blueTeamId: String,
    val redTeamId: String,
    val teams: List<EsportsTeamRef>
)

internal class LolEsportsApiClient {

    /**
     * Riot getSchedule is paged. A single response is only a moving window around "now",
     * so the schedule center follows both older/newer page tokens and de-duplicates events.
     */
    suspend fun fetchGlobalSchedule(): List<ScheduledEsportsMatch> {
        val leagues = fetchTrackedLeagues()
        if (leagues.isEmpty()) return emptyList()

        val semaphore = Semaphore(4)
        val results = coroutineScope {
            leagues.map { league ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        league to runCatching { fetchLeagueScheduleWindow(league) }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll()
        }

        return results
            .flatMap { it.second }
            .distinctBy { it.eventId.ifBlank { it.matchId } }
            .sortedWith(
                compareBy<ScheduledEsportsMatch> { parseInstant(it.startTimeIso) ?: Instant.MAX }
                    .thenBy { it.leagueSlug }
                    .thenBy { it.eventId }
            )
    }

    private suspend fun fetchLeagueScheduleWindow(league: TrackedLeagueRef): List<ScheduledEsportsMatch> {
        val pages = mutableListOf<JSONObject>()
        val visitedTokens = mutableSetOf<String>()
        val center = fetchSchedulePage(null, league.id)
        pages += center

        val deepPaged = league.slug.lowercase().replace('_', '-') in setOf(
            "lpl", "lck", "lec", "lcs", "lta", "lta-north", "lta-south", "lcp",
            "worlds", "msi", "first-stand", "firststand", "ewc", "esports-world-cup",
            "americas-cup", "emea-masters"
        )
        if (!deepPaged) {
            return pages.flatMap { parseSchedulePage(it, league) }.distinctBy { it.eventId.ifBlank { it.matchId } }
        }

        // One neighbour page in each direction is enough for the always-on phone refresh.
        // Historical deep collection belongs in the central mirror, not in every five-minute APK poll.
        var older = schedulePageToken(center, "older")
        if (older.isNotBlank() && visitedTokens.add("older:$older")) {
            pages += fetchSchedulePage(older, league.id)
        }
        var newer = schedulePageToken(center, "newer")
        if (newer.isNotBlank() && visitedTokens.add("newer:$newer")) {
            pages += fetchSchedulePage(newer, league.id)
        }

        return pages
            .flatMap { parseSchedulePage(it, league) }
            .distinctBy { it.eventId.ifBlank { it.matchId } }
    }

    // Compatibility alias for older call sites while the app migrates away from LPL-only naming.
    suspend fun fetchLplSchedule(): List<ScheduledEsportsMatch> = fetchGlobalSchedule()

    internal suspend fun fetchTrackedLeagues(): List<TrackedLeagueRef> {
        val discovered = runCatching {
            val root = getJson("${LolEsportsConfig.PERSISTED_BASE}/getLeagues?hl=en-US")
            val leagues = root.optJSONObject("data")?.optJSONArray("leagues") ?: JSONArray()
            buildList {
                for (i in 0 until leagues.length()) {
                    val league = leagues.optJSONObject(i) ?: continue
                    val id = league.optString("id")
                    val slug = league.optString("slug").lowercase()
                    val normalized = slug.replace('_', '-')
                    val normalizedName = league.optString("name").trim().lowercase()
                    val tracked = slug in LolEsportsConfig.GLOBAL_MAJOR_LEAGUE_SLUGS ||
                        normalized in LolEsportsConfig.GLOBAL_MAJOR_LEAGUE_SLUGS ||
                        LolEsportsConfig.GLOBAL_TRACKED_LEAGUE_NAMES.any { trackedName ->
                            normalizedName == trackedName || normalizedName.contains(trackedName)
                        }
                    if (id.isNotBlank() && tracked) {
                        add(
                            TrackedLeagueRef(
                                id = id,
                                slug = slug,
                                name = league.optString("name").ifBlank { slug.uppercase() }
                            )
                        )
                    }
                }
            }.distinctBy { it.id }
        }.getOrDefault(emptyList())

        return discovered.ifEmpty {
            listOf(TrackedLeagueRef(LolEsportsConfig.LPL_LEAGUE_ID, "lpl", "LPL"))
        }
    }

    private suspend fun fetchSchedulePage(pageToken: String?, leagueId: String): JSONObject {
        val url = buildString {
            append("${LolEsportsConfig.PERSISTED_BASE}/getSchedule?hl=en-US")
            if (leagueId.isNotBlank()) {
                append("&leagueId=")
                append(URLEncoder.encode(leagueId, "UTF-8"))
            }
            if (!pageToken.isNullOrBlank()) {
                append("&pageToken=").append(URLEncoder.encode(pageToken, "UTF-8"))
            }
        }
        return getJson(url)
    }

    private fun schedulePageToken(root: JSONObject, direction: String): String =
        root.optJSONObject("data")
            ?.optJSONObject("schedule")
            ?.optJSONObject("pages")
            ?.optString(direction)
            .orEmpty()

    private fun parseSchedulePage(root: JSONObject, trackedLeague: TrackedLeagueRef): List<ScheduledEsportsMatch> {
        val events = root.optJSONObject("data")
            ?.optJSONObject("schedule")
            ?.optJSONArray("events") ?: JSONArray()

        return buildList {
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                if (event.optString("type") != "match") continue

                val league = event.optJSONObject("league")
                val leagueId = league?.optString("id").orEmpty().ifBlank { trackedLeague.id }
                val leagueSlug = league?.optString("slug").orEmpty().ifBlank { trackedLeague.slug }
                val leagueName = league?.optString("name").orEmpty().ifBlank { trackedLeague.name }
                val match = event.optJSONObject("match") ?: continue
                val teams = parseTeams(match.optJSONArray("teams"))
                if (teams.size < 2) continue
                val bestOf = match.optJSONObject("strategy")?.optInt("count", 0) ?: 0
                val rawState = event.optString("state")

                add(
                    ScheduledEsportsMatch(
                        eventId = event.optString("id", match.optString("id")),
                        matchId = match.optString("id"),
                        league = leagueName.ifBlank { "LoL Esports" },
                        blockName = event.optString("blockName", ""),
                        startTimeIso = event.optString("startTime"),
                        state = verifiedScheduleState(rawState, bestOf, teams),
                        bestOf = bestOf,
                        teams = teams,
                        leagueId = leagueId,
                        leagueSlug = leagueSlug
                    )
                )
            }
        }
    }

    /**
     * getSchedule can transiently report a series as completed before it has actually started.
     * Never promote that raw flag to a finished match unless the series result proves it.
     */
    private fun verifiedScheduleState(
        rawState: String,
        bestOf: Int,
        teams: List<EsportsTeamRef>
    ): String {
        val normalized = rawState.lowercase()
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "")
        val claimsCompleted = normalized.contains("complete") || normalized == "finished"
        if (!claimsCompleted) return rawState

        val requiredWins = if (bestOf > 0) bestOf / 2 + 1 else 1
        val maxGameWins = teams.maxOfOrNull { it.gameWins } ?: 0
        val winnerByOutcome = teams.any {
            it.outcome.equals("win", ignoreCase = true) ||
                it.outcome.equals("winner", ignoreCase = true)
        }

        return if (maxGameWins >= requiredWins || winnerByOutcome) rawState else "unstarted"
    }

    suspend fun fetchTeamDetails(slug: String): EsportsTeamDetails? {
        if (slug.isBlank()) return null
        val encoded = URLEncoder.encode(slug, "UTF-8")
        val root = getJson("${LolEsportsConfig.PERSISTED_BASE}/getTeams?hl=en-US&id=$encoded")
        val teams = root.optJSONObject("data")?.optJSONArray("teams") ?: return null
        if (teams.length() == 0) return null

        var selected: JSONObject? = null
        for (i in 0 until teams.length()) {
            val item = teams.optJSONObject(i) ?: continue
            if (
                item.optString("id") == slug ||
                item.optString("slug").equals(slug, ignoreCase = true)
            ) {
                selected = item
                break
            }
            if (selected == null) selected = item
        }
        val team = selected ?: return null
        val playersJson = team.optJSONArray("players") ?: JSONArray()
        val players = buildList {
            for (i in 0 until playersJson.length()) {
                val player = playersJson.optJSONObject(i) ?: continue
                val summoner = player.optString("summonerName")
                if (summoner.isBlank()) continue
                val playerImage = EsportsAssetCache.normalize(player.optString("image"))
                    .ifBlank { EsportsAssetCache.normalize(player.optString("imageUrl")) }
                    .ifBlank { EsportsAssetCache.normalize(player.optString("portraitUrl")) }
                if (playerImage.isNotBlank()) {
                    EsportsAssetCache.putPlayer(summoner, team.optString("code"), playerImage)
                }
                add(
                    EsportsPlayerRef(
                        id = player.optString("id"),
                        summonerName = summoner,
                        role = normalizeRole(player.optString("role")),
                        imageUrl = playerImage,
                        firstName = player.optString("firstName"),
                        lastName = player.optString("lastName")
                    )
                )
            }
        }.sortedBy { roleOrder(it.role) }

        val teamCode = team.optString("code").ifBlank { team.optString("name").take(4).uppercase() }
        val teamImage = EsportsAssetCache.normalize(team.optString("image"))
            .ifBlank { EsportsAssetCache.normalize(team.optString("imageUrl")) }
            .ifBlank { EsportsAssetCache.normalize(team.optString("imageUrlDarkMode")) }
            .ifBlank { EsportsAssetCache.normalize(team.optString("imageUrlLightMode")) }
        if (teamImage.isNotBlank()) {
            EsportsAssetCache.putTeam(teamImage, team.optString("id"), team.optString("slug"), teamCode, team.optString("name"))
        }
        return EsportsTeamDetails(
            id = team.optString("id"),
            slug = team.optString("slug", slug),
            code = teamCode,
            name = team.optString("name"),
            imageUrl = teamImage,
            players = players
        )
    }

    suspend fun findLiveEvent(
        preferredMatchId: String = "",
        preferredTeamCodes: Set<String> = emptySet()
    ): LiveEventRef? {
        val root = getJson("${LolEsportsConfig.PERSISTED_BASE}/getLive?hl=en-US")
        val events = root.optJSONObject("data")
            ?.optJSONObject("schedule")
            ?.optJSONArray("events") ?: return null

        val candidates = buildList {
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val match = event.optJSONObject("match") ?: continue
                val teams = parseTeams(match.optJSONArray("teams"))
                val eventId = event.optString("id")
                if (eventId.isBlank()) continue
                add(
                    LiveEventRef(
                        eventId = eventId,
                        matchId = match.optString("id"),
                        startTimeIso = event.optString("startTime"),
                        teams = teams
                    )
                )
            }
        }

        if (candidates.isEmpty()) return null

        if (preferredMatchId.isNotBlank()) {
            candidates.firstOrNull {
                it.matchId == preferredMatchId || it.eventId == preferredMatchId
            }?.let { return it }
        }

        if (preferredTeamCodes.isNotEmpty()) {
            candidates.firstOrNull { event ->
                val codes = event.teams.map { it.code.uppercase() }.toSet()
                preferredTeamCodes.all { it.uppercase() in codes }
            }?.let { return it }
        }

        return candidates.first()
    }

    suspend fun findLiveLplEvent(
        preferredMatchId: String = "",
        preferredTeamCodes: Set<String> = emptySet()
    ): LiveEventRef? = findLiveEvent(preferredMatchId, preferredTeamCodes)

    suspend fun fetchLiveGame(event: LiveEventRef): LiveGameRef? {
        val root = getJson(
            "${LolEsportsConfig.PERSISTED_BASE}/getEventDetails?hl=en-US&id=${event.eventId}"
        )
        val match = root.optJSONObject("data")
            ?.optJSONObject("event")
            ?.optJSONObject("match") ?: return null

        val teams = parseTeams(match.optJSONArray("teams"))
        val games = match.optJSONArray("games") ?: return null

        for (i in 0 until games.length()) {
            val game = games.optJSONObject(i) ?: continue
            val normalized = game.optString("state")
                .lowercase()
                .replace("_", "")
                .replace("-", "")
            if (!normalized.contains("progress")) continue
            return parseGame(game, teams)
        }
        return null
    }

    suspend fun fetchLiveWindow(
        event: LiveEventRef,
        game: LiveGameRef,
        previous: LiveSnapshot?
    ): LiveSnapshot {
        val startingTime = event.startTimeIso.takeIf { it.isNotBlank() }
        val url = buildString {
            append("${LolEsportsConfig.LIVE_BASE}/window/${game.gameId}")
            if (startingTime != null) {
                append("?startingTime=").append(URLEncoder.encode(startingTime, "UTF-8"))
            }
        }
        val root = getJson(url)
        val frames = root.optJSONArray("frames") ?: throw IOException("Live window has no frames")
        if (frames.length() == 0) throw IOException("Live window frames are empty")

        val latest = frames.optJSONObject(frames.length() - 1) ?: throw IOException("Missing latest live frame")
        val first = frames.optJSONObject(0)
        val metadata = root.optJSONObject("gameMetadata") ?: JSONObject()

        val blueMeta = metadata.optJSONObject("blueTeamMetadata") ?: JSONObject()
        val redMeta = metadata.optJSONObject("redTeamMetadata") ?: JSONObject()
        val blueTeamId = blueMeta.optString("esportsTeamId", game.blueTeamId)
        val redTeamId = redMeta.optString("esportsTeamId", game.redTeamId)
        val teamById = game.teams.associateBy { it.id }

        val blueCode = teamById[blueTeamId]?.code?.ifBlank { null }
            ?: event.teams.firstOrNull { it.id == blueTeamId }?.code
            ?: "BLUE"
        val redCode = teamById[redTeamId]?.code?.ifBlank { null }
            ?: event.teams.firstOrNull { it.id == redTeamId }?.code
            ?: "RED"

        val blue = latest.optJSONObject("blueTeam") ?: JSONObject()
        val red = latest.optJSONObject("redTeam") ?: JSONObject()

        val elapsed = deriveElapsedSeconds(first, latest, previous, game.gameId)
        val blueDragons = countDragons(blue)
        val redDragons = countDragons(red)
        val blueBarons = blue.optInt("barons", 0)
        val redBarons = red.optInt("barons", 0)

        val snapshot = LiveSnapshot(
            game = max(1, game.gameNumber),
            elapsedSeconds = elapsed,
            blue = blueCode,
            red = redCode,
            blueGold = blue.optInt("totalGold", 0),
            redGold = red.optInt("totalGold", 0),
            blueKills = blue.optInt("totalKills", 0),
            redKills = red.optInt("totalKills", 0),
            blueTowers = blue.optInt("towers", 0),
            redTowers = red.optInt("towers", 0),
            blueDragons = blueDragons,
            redDragons = redDragons,
            latestEvent = "",
            blueBarons = blueBarons,
            redBarons = redBarons,
            bluePlayers = parsePlayers(blueMeta, blue),
            redPlayers = parsePlayers(redMeta, red),
            source = "Riot LoL Esports Live",
            gameId = game.gameId
        )

        return snapshot.copy(latestEvent = detectEvent(previous, snapshot))
    }

    private fun parseGame(game: JSONObject, teams: List<EsportsTeamRef>): LiveGameRef? {
        val gameId = game.optString("id")
        if (gameId.isBlank()) return null
        val gameTeams = game.optJSONArray("teams") ?: JSONArray()
        var blueId = ""
        var redId = ""
        for (i in 0 until gameTeams.length()) {
            val team = gameTeams.optJSONObject(i) ?: continue
            when (team.optString("side").lowercase()) {
                "blue" -> blueId = team.optString("id")
                "red" -> redId = team.optString("id")
            }
        }
        return LiveGameRef(
            gameId = gameId,
            gameNumber = game.optInt("number", 1),
            state = game.optString("state"),
            blueTeamId = blueId,
            redTeamId = redId,
            teams = teams
        )
    }

    private fun parseTeams(array: JSONArray?): List<EsportsTeamRef> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val team = array.optJSONObject(i) ?: continue
                val name = team.optString("name")
                val code = team.optString("code").ifBlank {
                    name.filter { it.isLetterOrDigit() }.take(4).uppercase()
                }
                val result = team.optJSONObject("result") ?: JSONObject()
                val record = team.optJSONObject("record") ?: JSONObject()
                add(
                    EsportsTeamRef(
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
                )
            }
        }
    }

    private fun parsePlayers(metadata: JSONObject, frameTeam: JSONObject): List<LivePlayerSnapshot> {
        val metaArray = metadata.optJSONArray("participantMetadata") ?: JSONArray()
        val metaById = mutableMapOf<Int, JSONObject>()
        for (i in 0 until metaArray.length()) {
            val item = metaArray.optJSONObject(i) ?: continue
            metaById[item.optInt("participantId", -1)] = item
        }

        val participants = frameTeam.optJSONArray("participants") ?: JSONArray()
        return buildList {
            for (i in 0 until participants.length()) {
                val player = participants.optJSONObject(i) ?: continue
                val id = player.optInt("participantId", -1)
                val meta = metaById[id]
                add(
                    LivePlayerSnapshot(
                        participantId = id,
                        role = meta?.optString("role").orEmpty(),
                        summonerName = meta?.optString("summonerName").orEmpty(),
                        championId = meta?.optString("championId").orEmpty(),
                        level = player.optInt("level", 0),
                        kills = player.optInt("kills", 0),
                        deaths = player.optInt("deaths", 0),
                        assists = player.optInt("assists", 0),
                        creepScore = player.optInt("creepScore", 0),
                        gold = player.optInt("totalGold", 0)
                    )
                )
            }
        }
    }

    private fun countDragons(team: JSONObject): Int {
        val array = team.optJSONArray("dragons")
        return array?.length() ?: team.optInt("dragons", 0)
    }

    private fun deriveElapsedSeconds(
        first: JSONObject?,
        latest: JSONObject,
        previous: LiveSnapshot?,
        gameId: String
    ): Int {
        val firstTs = parseInstant(first?.optString("rfc460Timestamp").orEmpty())
        val latestTs = parseInstant(latest.optString("rfc460Timestamp"))
        if (firstTs != null && latestTs != null) {
            val diff = latestTs.epochSecond - firstTs.epochSecond
            if (diff in 0..7200) return diff.toInt()
        }
        if (previous != null && previous.gameId == gameId) return previous.elapsedSeconds + 3
        return 0
    }

    private fun detectEvent(previous: LiveSnapshot?, current: LiveSnapshot): String {
        if (previous == null || previous.gameId != current.gameId) {
            return "Riot Live · GAME ${current.game} 实时数据已接入"
        }
        return when {
            current.blueBarons > previous.blueBarons -> "${current.blue} 获得男爵"
            current.redBarons > previous.redBarons -> "${current.red} 获得男爵"
            current.blueDragons > previous.blueDragons -> "${current.blue} 获得小龙"
            current.redDragons > previous.redDragons -> "${current.red} 获得小龙"
            current.blueTowers > previous.blueTowers -> "${current.blue} 摧毁防御塔"
            current.redTowers > previous.redTowers -> "${current.red} 摧毁防御塔"
            current.blueKills > previous.blueKills -> "${current.blue} 完成击杀"
            current.redKills > previous.redKills -> "${current.red} 完成击杀"
            else -> "Riot Live · 实时数据已同步"
        }
    }

    private fun normalizeRole(value: String): String = when (value.lowercase()) {
        "top" -> "TOP"
        "jungle", "jun" -> "JUG"
        "mid", "middle" -> "MID"
        "bottom", "bot", "adc" -> "BOT"
        "support", "sup" -> "SUP"
        else -> value.uppercase().ifBlank { "—" }
    }

    private fun roleOrder(role: String): Int = when (role) {
        "TOP" -> 0
        "JUG" -> 1
        "MID" -> 2
        "BOT" -> 3
        "SUP" -> 4
        else -> 99
    }

    private fun parseInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

    private suspend fun getJson(url: String): JSONObject =
        RiotResilientHttp.getJson(url, connectTimeoutMs = 8_000, readTimeoutMs = 8_000)
}