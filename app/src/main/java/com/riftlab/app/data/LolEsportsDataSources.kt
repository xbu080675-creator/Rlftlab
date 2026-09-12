package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

internal class LolEsportsScheduleDataSource(
    private val client: LolEsportsApiClient = LolEsportsApiClient(),
    private val cito: CitoScheduleSupplementProvider = CitoScheduleSupplementProvider()
) : ScheduleDataSource {
    override suspend fun fetchLeagueSchedule(): List<ScheduledEsportsMatch> {
        val riot = client.fetchGlobalSchedule().map(::verifySeriesCompletion)
        val citoRows = runCatching { cito.fetch() }
            .getOrDefault(emptyList())
            .map(::verifySeriesCompletion)
        val internationalRows = runCatching { InternationalEventMirrorProvider.fetchMatches() }
            .getOrDefault(emptyList())
            .map(::verifySeriesCompletion)
        val merged = mergeSchedule(mergeSchedule(riot, citoRows), internationalRows)
        CitoArchiveCoordinator.observe(merged)
        return TeamAssetCatalog.enrichMatches(merged)
    }

    private fun mergeSchedule(
        riot: List<ScheduledEsportsMatch>,
        citoRows: List<ScheduledEsportsMatch>
    ): List<ScheduledEsportsMatch> {
        if (citoRows.isEmpty()) return riot
        val output = riot.toMutableList()
        citoRows.forEach { candidate ->
            val duplicate = output.any { existing -> sameSeries(existing, candidate) }
            if (!duplicate) output += candidate
        }
        return output.sortedBy { parseStart(it.startTimeIso)?.toEpochMilli() ?: Long.MAX_VALUE }
    }

    private fun sameSeries(a: ScheduledEsportsMatch, b: ScheduledEsportsMatch): Boolean {
        if (a.matchId.isNotBlank() && b.matchId.isNotBlank() && a.matchId == b.matchId) return true
        if (a.eventId.isNotBlank() && b.eventId.isNotBlank() && a.eventId == b.eventId) return true
        if (a.bestOf > 0 && b.bestOf > 0 && a.bestOf != b.bestOf) return false

        val aStart = parseStart(a.startTimeIso)
        val bStart = parseStart(b.startTimeIso)
        if (aStart != null && bStart != null) {
            val deltaMs = kotlin.math.abs(aStart.toEpochMilli() - bStart.toEpochMilli())
            if (deltaMs > 90L * 60L * 1000L) return false
        } else {
            val aDay = a.startTimeIso.take(10)
            val bDay = b.startTimeIso.take(10)
            if (aDay.isBlank() || aDay != bDay) return false
        }

        val aTeams = a.teams.take(2).map(::teamAliases)
        val bTeams = b.teams.take(2).map(::teamAliases)
        if (aTeams.size < 2 || bTeams.size < 2 || aTeams.any { it.isEmpty() } || bTeams.any { it.isEmpty() }) return false

        return aTeams.all { left -> bTeams.any { right -> left.intersect(right).isNotEmpty() } } &&
            bTeams.all { right -> aTeams.any { left -> right.intersect(left).isNotEmpty() } }
    }

    private fun teamAliases(team: EsportsTeamRef): Set<String> =
        listOf(team.slug, team.code, team.name)
            .map(::teamToken)
            .filter { it.isNotBlank() }
            .toSet()

    private fun verifySeriesCompletion(match: ScheduledEsportsMatch): ScheduledEsportsMatch {
        val normalized = normalizeState(match.state)
        val claimsCompleted = normalized.contains("complete") || normalized == "finished"
        if (!claimsCompleted) return match

        val requiredWins = if (match.bestOf > 0) match.bestOf / 2 + 1 else 1
        val maxGameWins = match.teams.maxOfOrNull { it.gameWins } ?: 0

        return if (maxGameWins >= requiredWins) match else match.copy(state = "unstarted")
    }

    private fun normalizeState(value: String): String =
        value.lowercase().replace("_", "").replace("-", "").replace(" ", "")

    private fun parseStart(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

    private fun teamToken(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
}

internal class LolEsportsStandingsDataSource(
    private val client: LolEsportsStandingsClient = LolEsportsStandingsClient(),
    private val cito: CitoStandingsSupplementProvider = CitoStandingsSupplementProvider()
) : StandingsDataSource {
    private val tournamentRefs = linkedMapOf<String, EsportsTournamentRef>()

    override suspend fun fetchLeagueTournaments(): List<EsportsTournamentRef> {
        val riot = client.fetchGlobalTournaments()
        val international = runCatching { InternationalEventMirrorProvider.fetchTournaments() }
            .getOrDefault(emptyList())
        val tournaments = (riot + international).distinctBy { it.id }
        tournamentRefs.clear()
        tournaments.forEach { tournamentRefs[it.id] = it }
        return tournaments
    }

    override suspend fun fetchStandings(tournamentId: String): TournamentStandings? {
        if (tournamentId.startsWith("rft-event:")) return null

        val riot = runCatching { client.fetchTournamentStandings(tournamentId) }.getOrNull()
        val hasRiotRows = riot?.stages?.any { stage ->
            stage.sections.any { it.rankings.isNotEmpty() || it.matches.isNotEmpty() }
        } == true
        if (hasRiotRows) return riot
        val tournament = tournamentRefs[tournamentId] ?: return riot
        return runCatching { cito.fetch(tournament) }.getOrNull() ?: riot
    }
}

internal class LolEsportsTeamDataSource(
    private val client: LolEsportsApiClient = LolEsportsApiClient(),
    private val cito: CitoTeamSupplementProvider = CitoTeamSupplementProvider()
) : TeamDataSource {
    override suspend fun fetchTeam(slug: String): EsportsTeamDetails? {
        val riot = runCatching { client.fetchTeamDetails(slug) }.getOrNull()
        val teamRef = riot?.let {
            EsportsTeamRef(
                id = it.id,
                code = it.code,
                name = it.name,
                slug = it.slug,
                imageUrl = it.imageUrl
            )
        } ?: EsportsTeamRef(id = "", code = slug.uppercase(), name = slug, slug = slug)
        val citoDetails = runCatching { cito.fetch(teamRef) }.getOrNull()
        if (riot == null) return citoDetails
        if (citoDetails == null) return riot

        val players = (riot.players + citoDetails.players)
            .distinctBy { it.summonerName.uppercase().replace(Regex("[^A-Z0-9]+"), "") }
        return riot.copy(
            players = players,
            imageUrl = riot.imageUrl.ifBlank { citoDetails.imageUrl },
            staff = riot.staff.ifEmpty { citoDetails.staff },
            management = riot.management.ifEmpty { citoDetails.management },
            socialLinks = riot.socialLinks.ifEmpty { citoDetails.socialLinks }
        )
    }
}

internal class LolEsportsLiveDataSource(
    private val client: LolEsportsApiClient = LolEsportsApiClient()
) : LiveMatchDataSource {

    private val _status = MutableStateFlow(
        LiveSourceStatus(
            phase = LiveSourcePhase.IDLE,
            message = "实时源尚未启动"
        )
    )
    val status: StateFlow<LiveSourceStatus> = _status.asStateFlow()

    override fun observe(matchId: String): Flow<LiveSnapshot> = flow {
        var currentEvent: LiveEventRef? = null
        var knownGames: List<LiveGameRef> = emptyList()
        var currentGame: LiveGameRef? = null
        var currentGameId = ""
        var previous: LiveSnapshot? = null
        var lockedFromSchedule = false
        var observedTargetKey = ""

        while (currentCoroutineContext().isActive) {
            try {
                val registeredTarget = LiveMatchTargetRegistry.snapshot()
                val nextTargetKey = LiveMatchTargetRegistry.key(registeredTarget)
                if (nextTargetKey != observedTargetKey) {
                    observedTargetKey = nextTargetKey
                    currentEvent = null
                    knownGames = emptyList()
                    currentGame = null
                    currentGameId = ""
                    previous = null
                    lockedFromSchedule = false
                }
                if (registeredTarget != null && isExternalProviderTarget(registeredTarget)) {
                    currentEvent = null
                    knownGames = emptyList()
                    currentGame = null
                    currentGameId = ""
                    previous = null
                    lockedFromSchedule = false
                    _status.value = LiveSourceStatus(
                        phase = LiveSourcePhase.WAITING_FOR_MATCH,
                        message = "Riot LiveStats · 当前赛事使用非 Riot Event ID，等待其它实时源",
                        eventId = registeredTarget.eventId,
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    delay(5_000)
                    continue
                }

                if (currentEvent == null) {
                    val scheduled = LiveMatchTargetRegistry.snapshot()
                    if (scheduled != null && scheduled.eventId.isNotBlank()) {
                        currentEvent = LiveEventRef(
                            eventId = scheduled.eventId,
                            matchId = scheduled.matchId,
                            startTimeIso = scheduled.startTimeIso,
                            teams = scheduled.teams
                        )
                        lockedFromSchedule = true
                        _status.value = LiveSourceStatus(
                            phase = LiveSourcePhase.WAITING_FOR_MATCH,
                            message = "已锁定 ${teamLabel(scheduled)}，正在探测 Riot LiveStats",
                            eventId = scheduled.eventId,
                            lastUpdateEpochMs = System.currentTimeMillis()
                        )
                    } else {
                        lockedFromSchedule = false
                        _status.value = LiveSourceStatus(
                            phase = LiveSourcePhase.WAITING_FOR_MATCH,
                            message = "正在等待全球 LoL Esports 实时比赛…",
                            lastUpdateEpochMs = System.currentTimeMillis()
                        )
                        currentEvent = client.findLiveEvent(preferredMatchId = matchId)
                    }

                    knownGames = emptyList()
                    currentGame = null
                    currentGameId = ""
                    previous = null

                    if (currentEvent == null) {
                        delay(5_000)
                        continue
                    }
                }

                val event = currentEvent ?: continue
                if (knownGames.isEmpty()) knownGames = fetchEventGames(event)

                val stateGame = knownGames.firstOrNull { isInProgress(it.state) }
                val newerWindowGame = currentGame?.let { active ->
                    knownGames
                        .filter { it.gameNumber > active.gameNumber }
                        .minByOrNull { it.gameNumber }
                        ?.takeIf { hasAnyLiveFrames(event, it) }
                }

                val discovered = stateGame
                    ?: newerWindowGame
                    ?: currentGame
                    ?: findHighestStartedGame(event, knownGames)

                if (discovered == null) {
                    _status.value = LiveSourceStatus(
                        phase = LiveSourcePhase.WAITING_FOR_MATCH,
                        message = "赛事已锁定，正在用多游标探测 Riot LiveStats 游戏帧",
                        eventId = event.eventId,
                        gameId = "",
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    delay(2_000)
                    if (!lockedFromSchedule) {
                        currentEvent = client.findLiveEvent(preferredMatchId = matchId) ?: event
                    } else {
                        knownGames = fetchEventGames(event)
                    }
                    continue
                }

                if (currentGame == null || discovered.gameId != currentGameId) {
                    currentGame = discovered
                    currentGameId = discovered.gameId
                    previous = null
                }

                val game = currentGame ?: continue
                val raw = fetchLatestSnapshot(event, game, previous)
                val snapshot = raw.copy(targetKey = observedTargetKey)
                val target = LiveMatchTargetRegistry.snapshot()
                if (target == null || !MatchIdentityPolicy.snapshotBelongsTo(snapshot, target)) {
                    currentGame = null
                    currentGameId = ""
                    previous = null
                    knownGames = emptyList()
                    _status.value = LiveSourceStatus(
                        phase = LiveSourcePhase.WAITING_FOR_MATCH,
                        message = "Riot LiveStats · 帧身份与当前赛程不一致，已丢弃并重新解析 EventDetails",
                        eventId = event.eventId,
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                    delay(2_000)
                    continue
                }
                previous = snapshot

                _status.value = LiveSourceStatus(
                    phase = LiveSourcePhase.LIVE,
                    message = "Riot LoL Esports Live · G${snapshot.game}",
                    eventId = event.eventId,
                    gameId = snapshot.gameId,
                    lastUpdateEpochMs = System.currentTimeMillis()
                )
                emit(snapshot)
                delay(3_000)
            } catch (t: Throwable) {
                _status.value = LiveSourceStatus(
                    phase = LiveSourcePhase.ERROR,
                    message = "Riot LiveStats 暂时不可用：${t.message?.take(120) ?: t::class.java.simpleName}",
                    eventId = currentEvent?.eventId.orEmpty(),
                    gameId = currentGameId,
                    lastUpdateEpochMs = System.currentTimeMillis()
                )
                delay(3_000)
                currentEvent = null
                knownGames = emptyList()
                currentGame = null
                currentGameId = ""
                previous = null
                lockedFromSchedule = false
            }
        }
    }

    private suspend fun fetchEventGames(event: LiveEventRef): List<LiveGameRef> {
        val root = getJson(
            "${LolEsportsConfig.PERSISTED_BASE}/getEventDetails?hl=en-US&id=${event.eventId}"
        )
        val match = root.optJSONObject("data")
            ?.optJSONObject("event")
            ?.optJSONObject("match") ?: return emptyList()
        val games = match.optJSONArray("games") ?: JSONArray()

        return buildList {
            for (i in 0 until games.length()) {
                val game = games.optJSONObject(i) ?: continue
                val gameId = game.optString("id")
                if (gameId.isBlank()) continue
                var blueId = ""
                var redId = ""
                val sides = game.optJSONArray("teams") ?: JSONArray()
                for (j in 0 until sides.length()) {
                    val side = sides.optJSONObject(j) ?: continue
                    when (side.optString("side").lowercase()) {
                        "blue" -> blueId = side.optString("id")
                        "red" -> redId = side.optString("id")
                    }
                }
                add(
                    LiveGameRef(
                        gameId = gameId,
                        gameNumber = game.optInt("number", i + 1),
                        state = game.optString("state"),
                        blueTeamId = blueId,
                        redTeamId = redId,
                        teams = event.teams
                    )
                )
            }
        }.sortedBy { it.gameNumber }
    }

    private suspend fun findHighestStartedGame(event: LiveEventRef, games: List<LiveGameRef>): LiveGameRef? {
        for (game in games.sortedByDescending { it.gameNumber }) {
            if (hasAnyLiveFrames(event, game)) return game
        }
        return null
    }

    /**
     * LiveStats window is cursor-sensitive. The old no-cursor discovery probe could report "no
     * frames" while the exact same game became readable as soon as fetchLatestSnapshot supplied a
     * startingTime. Discovery now uses the same wall-clock cursor ladder as the real reader.
     */
    private suspend fun hasAnyLiveFrames(event: LiveEventRef, game: LiveGameRef): Boolean {
        val now = Instant.now()
        val lagsSeconds = longArrayOf(15, 30, 60, 120, 300, 600)
        for (lag in lagsSeconds) {
            val cursorEvent = event.copy(startTimeIso = now.minusSeconds(lag).toString())
            val snapshot = runCatching { client.fetchLiveWindow(cursorEvent, game, null) }.getOrNull()
            if (snapshot != null && isMeaningful(snapshot)) return true
        }
        val fallback = runCatching { client.fetchLiveWindow(event.copy(startTimeIso = ""), game, null) }.getOrNull()
        return fallback != null && isMeaningful(fallback)
    }

    private suspend fun fetchLatestSnapshot(
        event: LiveEventRef,
        game: LiveGameRef,
        previous: LiveSnapshot?
    ): LiveSnapshot {
        var lastError: Throwable? = null
        val now = Instant.now()
        val lagsSeconds = longArrayOf(15, 30, 60, 120, 300, 600)

        for (lag in lagsSeconds) {
            val cursorEvent = event.copy(startTimeIso = now.minusSeconds(lag).toString())
            try {
                val snapshot = client.fetchLiveWindow(cursorEvent, game, previous)
                if (isMeaningful(snapshot)) return snapshot
            } catch (t: Throwable) {
                lastError = t
            }
        }

        try {
            val fallback = client.fetchLiveWindow(event.copy(startTimeIso = ""), game, previous)
            if (isMeaningful(fallback)) return fallback
        } catch (t: Throwable) {
            lastError = t
        }

        throw IOException("LiveStats game ${game.gameNumber} exists but no current meaningful frame yet", lastError)
    }

    private fun isMeaningful(snapshot: LiveSnapshot): Boolean =
        snapshot.blueGold > 0 ||
            snapshot.redGold > 0 ||
            snapshot.blueKills > 0 ||
            snapshot.redKills > 0 ||
            snapshot.bluePlayers.any { it.gold > 0 || it.creepScore > 0 || it.level > 1 } ||
            snapshot.redPlayers.any { it.gold > 0 || it.creepScore > 0 || it.level > 1 }

    private fun isInProgress(value: String): Boolean = value
        .lowercase()
        .replace("_", "")
        .replace("-", "")
        .contains("progress")

    private suspend fun getJson(url: String): JSONObject =
        RiotResilientHttp.getJson(url, connectTimeoutMs = 5_000, readTimeoutMs = 5_000)

    private fun isExternalProviderTarget(match: ScheduledEsportsMatch): Boolean =
        match.eventId.startsWith("provider:") || match.leagueId.startsWith("rft-event:")

    private fun teamLabel(match: ScheduledEsportsMatch): String =
        match.teams.take(2).joinToString(" vs ") { it.code.ifBlank { it.name } }
}
