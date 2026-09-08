package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
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
    const val PERSISTED_BASE = "https://esports-api.lolesports.com/persisted/gw"
    const val LIVE_BASE = "https://feed.lolesports.com/livestats/v1"
}

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
    suspend fun fetchLplSchedule(): List<ScheduledEsportsMatch> {
        val pages = mutableListOf<JSONObject>()
        val visitedTokens = mutableSetOf<String>()

        val center = fetchSchedulePage(null)
        pages += center

        var older = schedulePageToken(center, "older")
        repeat(3) {
            if (older.isBlank() || !visitedTokens.add("older:$older")) return@repeat
            val page = fetchSchedulePage(older)
            pages += page
            older = schedulePageToken(page, "older")
        }

        var newer = schedulePageToken(center, "newer")
        repeat(3) {
            if (newer.isBlank() || !visitedTokens.add("newer:$newer")) return@repeat
            val page = fetchSchedulePage(newer)
            pages += page
            newer = schedulePageToken(page, "newer")
        }

        return pages
            .flatMap(::parseSchedulePage)
            .distinctBy { it.eventId.ifBlank { it.matchId } }
            .sortedWith(compareBy<ScheduledEsportsMatch> { parseInstant(it.startTimeIso) ?: Instant.MAX }
                .thenBy { it.eventId })
    }

    private suspend fun fetchSchedulePage(pageToken: String?): JSONObject {
        val url = buildString {
            append("${LolEsportsConfig.PERSISTED_BASE}/getSchedule?hl=en-US")
            append("&leagueId=${LolEsportsConfig.LPL_LEAGUE_ID}")
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

    private fun parseSchedulePage(root: JSONObject): List<ScheduledEsportsMatch> {
        val events = root.optJSONObject("data")
            ?.optJSONObject("schedule")
            ?.optJSONArray("events") ?: JSONArray()

        return buildList {
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                if (event.optString("type") != "match") continue

                val league = event.optJSONObject("league")
                val leagueId = league?.optString("id").orEmpty()
                val leagueSlug = league?.optString("slug").orEmpty()
                if (leagueId.isNotBlank() && leagueId != LolEsportsConfig.LPL_LEAGUE_ID && leagueSlug != "lpl") continue

                val match = event.optJSONObject("match") ?: continue
                val teams = parseTeams(match.optJSONArray("teams"))
                if (teams.size < 2) continue

                add(
                    ScheduledEsportsMatch(
                        eventId = event.optString("id", match.optString("id")),
                        matchId = match.optString("id"),
                        league = league?.optString("name", "LPL") ?: "LPL",
                        blockName = event.optString("blockName", ""),
                        startTimeIso = event.optString("startTime"),
                        state = event.optString("state"),
                        bestOf = match.optJSONObject("strategy")?.optInt("count", 0) ?: 0,
                        teams = teams
                    )
                )
            }
        }
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
            if (item.optString("slug").equals(slug, ignoreCase = true)) {
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
                add(
                    EsportsPlayerRef(
                        id = player.optString("id"),
                        summonerName = summoner,
                        role = normalizeRole(player.optString("role")),
                        imageUrl = player.optString("image")
                    )
                )
            }
        }.sortedBy { roleOrder(it.role) }

        return EsportsTeamDetails(
            id = team.optString("id"),
            slug = team.optString("slug", slug),
            code = team.optString("code").ifBlank { team.optString("name").take(4).uppercase() },
            name = team.optString("name"),
            players = players
        )
    }

    suspend fun findLiveLplEvent(
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
                val league = event.optJSONObject("league")
                val leagueId = league?.optString("id").orEmpty()
                val leagueSlug = league?.optString("slug").orEmpty()
                if (leagueId != LolEsportsConfig.LPL_LEAGUE_ID && leagueSlug != "lpl") continue

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

    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("x-api-key", LolEsportsConfig.API_KEY)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RiftLab/1.0 Android")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code from LoL Esports: ${body.take(180)}")
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
