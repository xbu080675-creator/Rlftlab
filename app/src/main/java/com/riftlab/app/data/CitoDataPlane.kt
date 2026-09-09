package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

internal object CitoProviderState {
    private val _status = MutableStateFlow("Cito · API Key 未配置")
    val status: StateFlow<String> = _status.asStateFlow()

    fun update(message: String) {
        _status.value = message
    }
}

internal object CitoHttpClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun getJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        val key = CitoApiConfig.apiKey() ?: return@withContext null
        val request = Request.Builder()
            .url(url)
            .header(CitoApiConfig.API_KEY_HEADER, key)
            .header("Accept", "application/json")
            .header("User-Agent", "RiftLab-Android")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CitoHttpException(response.code, text.take(200))
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    fun websocketClient(): OkHttpClient = client
}

internal class CitoHttpException(val code: Int, detail: String) :
    IllegalStateException("Cito HTTP $code${if (detail.isBlank()) "" else " · $detail"}")

/**
 * Stores the raw provider payload alongside normalized MatchState frames. This preserves fields
 * that the current LiveSnapshot schema does not expose yet (items, wards, damage-share, etc.).
 */
internal object CitoRawArchive {
    private const val MAX_FILE_BYTES = 16L * 1024L * 1024L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    fun append(matchKey: String, gameId: String, kind: String, payload: JSONObject) {
        if (matchKey.isBlank() && gameId.isBlank()) return
        val line = JSONObject()
            .put("capturedAtEpochMs", System.currentTimeMillis())
            .put("provider", "Cito API")
            .put("kind", kind)
            .put("matchKey", matchKey)
            .put("gameId", gameId)
            .put("payload", payload)
            .toString() + "\n"
        scope.launch {
            runCatching {
                val dir = File(
                    com.riftlab.app.RiftLabApplication.appContext.filesDir,
                    "provider_raw/cito/${safe(matchKey.ifBlank { "unknown-match" })}"
                ).apply { mkdirs() }
                val file = File(dir, "${safe(gameId.ifBlank { "series" })}.jsonl")
                synchronized(lock) {
                    if (file.exists() && file.length() >= MAX_FILE_BYTES) {
                        val old = File(dir, file.nameWithoutExtension + ".previous.jsonl")
                        old.delete()
                        file.renameTo(old)
                    }
                    file.appendText(line)
                }
            }
        }
    }

    private fun safe(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .take(96)
        .ifBlank { "unknown" }
}

internal class CitoScheduleSupplementProvider {
    private var cacheAt = 0L
    private var cache = emptyList<ScheduledEsportsMatch>()

    suspend fun fetch(): List<ScheduledEsportsMatch> {
        if (CitoApiConfig.apiKey() == null) return emptyList()
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - cacheAt < CACHE_MS) return cache

        val today = runCatching { CitoHttpClient.getJson(CitoApiConfig.scheduleTodayUrl()) }.getOrNull()
        val upcoming = runCatching { CitoHttpClient.getJson(CitoApiConfig.scheduleUpcomingUrl()) }.getOrNull()
        val output = mutableListOf<ScheduledEsportsMatch>()
        if (today != null) output += CitoJson.parseSchedule(today)
        if (upcoming != null) output += CitoJson.parseSchedule(upcoming)

        val deduped = output
            .distinctBy { CitoJson.scheduleIdentity(it) }
            .sortedBy { CitoJson.epoch(it.startTimeIso) }
        if (deduped.isNotEmpty()) {
            cache = TeamAssetCatalog.enrichMatches(deduped)
            cacheAt = now
            CitoProviderState.update("Cito REST · 赛程补充 ${cache.size} 场")
        }
        return cache
    }

    companion object { private const val CACHE_MS = 30 * 60 * 1000L }
}

internal class CitoTeamSupplementProvider {
    private data class CacheEntry(val at: Long, val value: EsportsTeamDetails?)
    private val cache = linkedMapOf<String, CacheEntry>()

    suspend fun fetch(team: EsportsTeamRef): EsportsTeamDetails? {
        if (CitoApiConfig.apiKey() == null) return null
        val key = CitoJson.token(team.slug.ifBlank { team.code.ifBlank { team.name } })
        val cached = cache[key]
        if (cached != null && System.currentTimeMillis() - cached.at < CACHE_MS) return cached.value

        val candidates = listOf(
            team.slug,
            team.code.lowercase(),
            team.name.lowercase().replace(' ', '-')
        ).filter { it.isNotBlank() }.distinct()

        var value: EsportsTeamDetails? = null
        for (slug in candidates) {
            val root = runCatching {
                CitoHttpClient.getJson(CitoApiConfig.teamRosterHistoryUrl(slug))
            }.getOrNull() ?: continue
            CitoRawArchive.append("team-${team.code.ifBlank { team.name }}", "", "roster-history", root)
            value = CitoJson.parseTeamRoster(team, root)
            if (value?.players?.isNotEmpty() == true) break
        }
        cache[key] = CacheEntry(System.currentTimeMillis(), value)
        return value
    }

    companion object { private const val CACHE_MS = 60 * 60 * 1000L }
}

internal class CitoStandingsSupplementProvider {
    private data class CacheEntry(val at: Long, val value: TournamentStandings?)
    private val cache = linkedMapOf<String, CacheEntry>()

    suspend fun fetch(tournament: EsportsTournamentRef): TournamentStandings? {
        if (CitoApiConfig.apiKey() == null) return null
        val league = tournament.leagueSlug.ifBlank { tournament.leagueId }.ifBlank { return null }
        val key = "$league|${tournament.id}"
        val cached = cache[key]
        if (cached != null && System.currentTimeMillis() - cached.at < CACHE_MS) return cached.value

        val root = runCatching {
            CitoHttpClient.getJson(CitoApiConfig.leagueStandingsUrl(league))
        }.getOrNull()
        val value = root?.let { CitoJson.parseStandings(tournament, it) }
        if (root != null) CitoRawArchive.append("standings-$league", "", "standings", root)
        cache[key] = CacheEntry(System.currentTimeMillis(), value)
        return value
    }

    companion object { private const val CACHE_MS = 30 * 60 * 1000L }
}

/**
 * Cito participates in the same LiveMatchDataSource contract as Riot/Tencent.
 * WSS is opportunistic; REST continues at a deliberately conservative cadence when WSS is absent.
 */
internal class CitoLiveDataSource : LiveMatchDataSource {
    private val _status = MutableStateFlow(
        LiveSourceStatus(LiveSourcePhase.IDLE, "Cito · API Key 未配置")
    )
    val status: StateFlow<LiveSourceStatus> = _status.asStateFlow()

    override fun observe(matchId: String): Flow<LiveSnapshot> = flow {
        var citoMatchId = ""
        var gameId = ""
        var gameNumber = 1
        var lastEmission = ""
        var lastWsPayload: JSONObject? = null
        val wsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var wsJob = wsScope.launch { }

        while (currentCoroutineContext().isActive) {
            if (CitoApiConfig.apiKey() == null) {
                if (wsJob.isActive) wsJob.cancel()
                _status.value = LiveSourceStatus(LiveSourcePhase.IDLE, "Cito · API Key 未配置")
                delay(5_000)
                continue
            }

            if (!wsJob.isActive) {
                wsJob = wsScope.launch {
                    runCatching {
                        CitoWebSocketPump().messages().collect { message -> lastWsPayload = message }
                    }
                }
            }

            try {
                val target = LiveMatchTargetRegistry.snapshot()
                if (target == null) {
                    _status.value = LiveSourceStatus(LiveSourcePhase.WAITING_FOR_MATCH, "Cito · 等待赛事目标")
                    delay(5_000)
                    continue
                }

                val matchKey = MatchLifecycleArchive.keyFor(target)
                if (citoMatchId.isBlank()) {
                    citoMatchId = resolveCitoMatchId(target)
                    if (citoMatchId.isBlank()) {
                        _status.value = LiveSourceStatus(
                            LiveSourcePhase.WAITING_FOR_MATCH,
                            "Cito · 当前赛事尚未进入 live 列表",
                            eventId = target.eventId,
                            lastUpdateEpochMs = System.currentTimeMillis()
                        )
                        delay(DISCOVERY_MS)
                        continue
                    }
                }

                val series = runCatching {
                    CitoHttpClient.getJson(CitoApiConfig.liveSeriesUrl(citoMatchId))
                }.getOrNull()
                if (series != null) {
                    CitoRawArchive.append(matchKey, "", "live-series", series)
                    val context = CitoJson.parseSeriesContext(series, target)
                    if (context.gameId.isNotBlank()) gameId = context.gameId
                    if (context.gameNumber > 0) gameNumber = context.gameNumber
                }

                var snapshot: LiveSnapshot? = null
                var transport = "REST fallback"
                val ws = lastWsPayload
                if (ws != null) {
                    val candidate = CitoJson.parseLiveBoard(ws, target, gameNumber)
                    if (candidate != null && CitoJson.meaningful(candidate) &&
                        (gameId.isBlank() || candidate.gameId.isBlank() || candidate.gameId == gameId)
                    ) {
                        snapshot = candidate
                        transport = "WebSocket"
                        CitoRawArchive.append(matchKey, candidate.gameId.ifBlank { gameId }, "websocket", ws)
                    }
                }

                if (snapshot == null && gameId.isNotBlank()) {
                    val board = CitoHttpClient.getJson(CitoApiConfig.liveBoardUrl(gameId))
                    if (board != null) {
                        CitoRawArchive.append(matchKey, gameId, "live-board", board)
                        snapshot = CitoJson.parseLiveBoard(board, target, gameNumber)
                    }
                }

                if (snapshot != null && CitoJson.meaningful(snapshot)) {
                    val fixed = if (snapshot.gameId.isBlank()) snapshot.copy(gameId = gameId) else snapshot
                    val key = "${fixed.gameId}|${fixed.elapsedSeconds}|${fixed.blueGold}|${fixed.redGold}|${fixed.blueKills}|${fixed.redKills}"
                    if (key != lastEmission) {
                        lastEmission = key
                        _status.value = LiveSourceStatus(
                            LiveSourcePhase.LIVE,
                            "Cito $transport · G${fixed.game}",
                            eventId = target.eventId,
                            gameId = fixed.gameId,
                            lastUpdateEpochMs = System.currentTimeMillis()
                        )
                        emit(fixed)
                    }
                } else {
                    _status.value = LiveSourceStatus(
                        LiveSourcePhase.WAITING_FOR_MATCH,
                        "Cito · 已锁定赛事，等待有效状态帧",
                        eventId = target.eventId,
                        gameId = gameId,
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                }

                delay(if (transport == "WebSocket") WS_SAFETY_REST_MS else REST_POLL_MS)
            } catch (t: Throwable) {
                _status.value = LiveSourceStatus(
                    LiveSourcePhase.ERROR,
                    "Cito 暂不可用：${t.message?.take(100) ?: t::class.java.simpleName}",
                    eventId = LiveMatchTargetRegistry.snapshot()?.eventId.orEmpty(),
                    gameId = gameId,
                    lastUpdateEpochMs = System.currentTimeMillis()
                )
                citoMatchId = ""
                gameId = ""
                gameNumber = 1
                delay(8_000)
            }
        }
        wsJob.cancel()
    }

    private suspend fun resolveCitoMatchId(target: ScheduledEsportsMatch): String {
        val ids = listOf(target.matchId, target.eventId).filter { it.isNotBlank() }.distinct()
        for (id in ids) {
            val coverage = runCatching { CitoHttpClient.getJson(CitoApiConfig.coverageUrl(id)) }.getOrNull()
                ?: continue
            val c = coverage.optJSONObject("coverage")
            if (c?.optBoolean("schedule_metadata", false) == true ||
                c?.optBoolean("live_row", false) == true ||
                c?.optBoolean("numeric_live_state", false) == true
            ) return id
        }

        val root = runCatching { CitoHttpClient.getJson(CitoApiConfig.liveMatchesUrl()) }.getOrNull()
            ?: return ""
        val wanted = target.teams.take(2)
            .map { CitoJson.token(it.code.ifBlank { it.name }) }
            .filter { it.isNotBlank() }
            .toSet()
        val live = CitoJson.arrayFrom(root, "data", "matches")
        for (i in 0 until live.length()) {
            val item = live.optJSONObject(i) ?: continue
            if (wanted.size == 2 && CitoJson.teamTokens(item) == wanted) {
                return item.optString("matchId").ifBlank { item.optString("id") }
            }
        }
        return ""
    }

    companion object {
        private const val DISCOVERY_MS = 12_000L
        private const val REST_POLL_MS = 8_000L
        private const val WS_SAFETY_REST_MS = 25_000L
    }
}

internal class CitoWebSocketPump {
    fun messages(): Flow<JSONObject> = callbackFlow {
        val key = CitoApiConfig.apiKey()
        if (key == null) {
            close()
            return@callbackFlow
        }

        val request = Request.Builder()
            .url(CitoApiConfig.LIVE_WEBSOCKET_URL)
            .header(CitoApiConfig.API_KEY_HEADER, key)
            .header("User-Agent", "RiftLab-Android")
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                CitoProviderState.update("Cito WebSocket · 已连接")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val payload = runCatching { JSONObject(text) }.getOrNull() ?: return
                trySend(payload)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                CitoProviderState.update("Cito WebSocket · 不可用，REST 自动接管")
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                CitoProviderState.update("Cito WebSocket · 已关闭，REST 自动接管")
                close()
            }
        }
        val socket = CitoHttpClient.websocketClient().newWebSocket(request, listener)
        awaitClose { socket.cancel() }
    }
}

internal data class CitoSeriesContext(val gameId: String, val gameNumber: Int)

/**
 * Completed matches are opportunistically backfilled from Cito for every league. The coordinator
 * does not replace Riot/OP.GG archives; it merges additional terminal truth and preserves raw JSON.
 */
internal object CitoArchiveCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val attemptedAt = linkedMapOf<String, Long>()

    fun observe(matches: List<ScheduledEsportsMatch>) {
        if (CitoApiConfig.apiKey() == null) return
        val now = System.currentTimeMillis()
        matches
            .filter { CitoJson.completed(it) }
            .sortedByDescending { CitoJson.epoch(it.startTimeIso) }
            .take(12)
            .forEach { match ->
                val key = MatchLifecycleArchive.keyFor(match)
                if (now - (attemptedAt[key] ?: 0L) < RETRY_MS) return@forEach
                attemptedAt[key] = now
                scope.launch { runCatching { backfill(match) } }
            }
    }

    private suspend fun backfill(match: ScheduledEsportsMatch) {
        val matchId = match.matchId.ifBlank { match.eventId }.ifBlank { return }
        val matchKey = MatchLifecycleArchive.keyFor(match)
        val gamesRoot = CitoHttpClient.getJson(CitoApiConfig.matchGamesUrl(matchId)) ?: return
        CitoRawArchive.append(matchKey, "", "match-games", gamesRoot)
        val games = CitoJson.arrayFrom(gamesRoot, "data", "games")
        if (games.length() == 0) return

        val snapshots = mutableListOf<LiveSnapshot>()
        for (i in 0 until games.length()) {
            val game = games.optJSONObject(i) ?: continue
            val gameId = game.optString("gameId").ifBlank { game.optString("id") }
            if (gameId.isBlank()) continue
            val post = runCatching { CitoHttpClient.getJson(CitoApiConfig.gamePostgameUrl(gameId)) }.getOrNull()
            val stats = runCatching { CitoHttpClient.getJson(CitoApiConfig.gameStatsUrl(gameId)) }.getOrNull()
            val timeline = runCatching { CitoHttpClient.getJson(CitoApiConfig.gameTimelineUrl(gameId)) }.getOrNull()
            if (post != null) CitoRawArchive.append(matchKey, gameId, "postgame", post)
            if (stats != null) CitoRawArchive.append(matchKey, gameId, "player-stats", stats)
            if (timeline != null) CitoRawArchive.append(matchKey, gameId, "timeline", timeline)
            CitoJson.parseCompletedGame(post ?: stats ?: game, match, i + 1, gameId)?.let { snapshots += it }
        }
        if (snapshots.isEmpty()) return

        val a = match.teams.getOrNull(0)
        val b = match.teams.getOrNull(1)
        val scoreA = a?.gameWins ?: 0
        val scoreB = b?.gameWins ?: 0
        val series = CompletedSeriesSnapshot(
            matchKey = matchKey,
            teamA = a?.code?.ifBlank { a.name }.orEmpty(),
            teamB = b?.code?.ifBlank { b.name }.orEmpty(),
            scoreA = scoreA,
            scoreB = scoreB,
            games = snapshots,
            seriesFinished = true,
            source = "Cito API · postgame"
        )
        MatchLifecycleArchive.observeCompletedSeries(match, series)
        CompletedGameArchive.publishSeries(series)
        CitoProviderState.update("Cito REST · 已归档 ${match.teams.take(2).joinToString(" vs ") { it.code.ifBlank { it.name } }}")
    }

    companion object { private const val RETRY_MS = 6 * 60 * 60 * 1000L }
}

internal object CitoJson {
    fun parseSchedule(root: JSONObject): List<ScheduledEsportsMatch> {
        val array = arrayFrom(root, "data", "matches", "schedule")
        val output = mutableListOf<ScheduledEsportsMatch>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            parseScheduledMatch(obj)?.let { output += it }
        }
        return output
    }

    fun scheduleIdentity(match: ScheduledEsportsMatch): String {
        val teams = match.teams.take(2)
            .map { token(it.code.ifBlank { it.name }) }
            .sorted()
            .joinToString("-")
        return "${match.matchId.ifBlank { match.eventId }}|$teams|${match.startTimeIso.take(10)}"
    }

    private fun parseScheduledMatch(obj: JSONObject): ScheduledEsportsMatch? {
        val teams = parseTeams(obj)
        if (teams.size < 2) return null
        val matchId = obj.optString("matchId").ifBlank { obj.optString("id") }
        val eventId = obj.optString("eventId").ifBlank { matchId }
        val leagueObj = obj.optJSONObject("league")
        val rawLeague = obj.opt("league")
        val league = if (rawLeague is String) rawLeague else
            leagueObj?.optString("name").orEmpty().ifBlank { leagueObj?.optString("slug").orEmpty() }
        return ScheduledEsportsMatch(
            eventId = eventId,
            matchId = matchId,
            league = league.ifBlank { obj.optString("leagueName", "LoL Esports") },
            blockName = firstString(obj, "blockName", "stage", "round", "phase"),
            startTimeIso = normalizeInstant(firstString(obj, "startTime", "startTimeIso", "scheduledAt", "date", "startDate")),
            state = firstString(obj, "state", "status").ifBlank { "unstarted" },
            bestOf = firstInt(obj, "bestOf", "bo", "best_of").takeIf { it > 0 } ?: 1,
            teams = teams,
            leagueId = leagueObj?.optString("id").orEmpty().ifBlank { obj.optString("leagueId") },
            leagueSlug = leagueObj?.optString("slug").orEmpty().ifBlank { obj.optString("leagueSlug") }
        )
    }

    fun parseTeamRoster(team: EsportsTeamRef, root: JSONObject): EsportsTeamDetails? {
        val data = root.optJSONObject("data") ?: root
        val arrays = mutableListOf<JSONArray>()
        for (key in listOf("roster", "players", "members", "history")) {
            data.optJSONArray(key)?.let { arrays += it }
        }
        val players = mutableListOf<EsportsPlayerRef>()
        for (array in arrays) {
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val p = row.optJSONObject("player") ?: row
                val name = firstString(p, "summonerName", "playerName", "name", "handle")
                if (name.isBlank()) continue
                val active = if (row.has("endedAt")) row.isNull("endedAt") || row.optString("endedAt").isBlank() else true
                if (!active && players.size >= 5) continue
                players += EsportsPlayerRef(
                    id = firstString(p, "playerId", "id"),
                    summonerName = name,
                    role = normalizeRole(firstString(row, "role", "position").ifBlank { firstString(p, "role", "position") }),
                    imageUrl = firstString(p, "imageUrl", "image", "photo"),
                    firstName = firstString(p, "firstName", "first_name"),
                    lastName = firstString(p, "lastName", "last_name")
                )
            }
        }
        val unique = players.distinctBy { token(it.summonerName) }
        if (unique.isEmpty()) return null
        return EsportsTeamDetails(
            id = team.id,
            slug = team.slug,
            code = team.code,
            name = team.name,
            imageUrl = team.imageUrl,
            players = unique
        )
    }

    fun parseStandings(tournament: EsportsTournamentRef, root: JSONObject): TournamentStandings? {
        val data = root.optJSONObject("data") ?: root
        val rankings = arrayFrom(data, "rankings", "standings", "teams")
        if (rankings.length() == 0) return null
        val rows = mutableListOf<StandingTeam>()
        for (i in 0 until rankings.length()) {
            val row = rankings.optJSONObject(i) ?: continue
            val teamObj = row.optJSONObject("team") ?: row
            val name = firstString(teamObj, "name", "teamName")
            val code = firstString(teamObj, "code", "acronym", "shortName").ifBlank { name }
            if (name.isBlank() && code.isBlank()) continue
            rows += StandingTeam(
                ordinal = firstInt(row, "ordinal", "rank", "position").takeIf { it > 0 } ?: i + 1,
                team = EsportsTeamRef(
                    id = firstString(teamObj, "id", "teamId"),
                    code = code,
                    name = name.ifBlank { code },
                    slug = firstString(teamObj, "slug"),
                    imageUrl = firstString(teamObj, "imageUrl", "image", "logo")
                ),
                wins = firstInt(row, "wins", "win", "seriesWins"),
                losses = firstInt(row, "losses", "loss", "seriesLosses"),
                points = firstInt(row, "points", "score").takeIf { it != 0 }
            )
        }
        if (rows.isEmpty()) return null
        return TournamentStandings(
            tournamentId = tournament.id,
            stages = listOf(
                StandingStage(
                    id = "cito-${tournament.id}",
                    name = tournament.leagueName.ifBlank { tournament.leagueSlug.uppercase() },
                    slug = "cito",
                    type = "ranking",
                    sections = listOf(StandingSection("Cito Standings", rows, emptyList()))
                )
            )
        )
    }

    fun parseSeriesContext(root: JSONObject, target: ScheduledEsportsMatch): CitoSeriesContext {
        val data = root.optJSONObject("data") ?: root
        val current = data.optJSONObject("currentGame") ?: data.optJSONObject("game")
        val gameId = firstString(data, "currentGameId", "gameId").ifBlank {
            current?.let { firstString(it, "gameId", "id") }.orEmpty()
        }
        val explicit = firstInt(data, "currentGameNumber", "gameNumber").takeIf { it > 0 }
            ?: current?.let { firstInt(it, "number", "gameNumber").takeIf { n -> n > 0 } }
        val score = data.optJSONObject("score")
        val inferred = if (score != null) {
            firstInt(score, "blue", "teamA", "left") + firstInt(score, "red", "teamB", "right") + 1
        } else target.teams.take(2).sumOf { it.gameWins } + 1
        return CitoSeriesContext(gameId, explicit ?: inferred.coerceAtLeast(1))
    }

    fun parseLiveBoard(root: JSONObject, target: ScheduledEsportsMatch, gameNumber: Int): LiveSnapshot? {
        val data = findBoardPayload(root) ?: return null
        val blue = data.optJSONObject("blueTeam") ?: data.optJSONObject("blue") ?: JSONObject()
        val red = data.optJSONObject("redTeam") ?: data.optJSONObject("red") ?: JSONObject()
        val playerArray = data.optJSONArray("players") ?: JSONArray()
        val bluePlayers = mutableListOf<LivePlayerSnapshot>()
        val redPlayers = mutableListOf<LivePlayerSnapshot>()
        for (i in 0 until playerArray.length()) {
            val p = playerArray.optJSONObject(i) ?: continue
            val player = LivePlayerSnapshot(
                participantId = firstInt(p, "participantId", "participant_id").takeIf { it > 0 } ?: i + 1,
                role = normalizeRole(firstString(p, "role", "position")),
                summonerName = firstString(p, "summonerName", "playerName", "name"),
                championId = firstString(p, "championId", "champion"),
                level = firstInt(p, "level"),
                kills = firstInt(p, "kills"),
                deaths = firstInt(p, "deaths"),
                assists = firstInt(p, "assists"),
                creepScore = firstInt(p, "creepScore", "cs"),
                gold = firstInt(p, "totalGold", "gold")
            )
            when (firstString(p, "side", "team").lowercase()) {
                "blue", "left", "teama" -> bluePlayers += player
                "red", "right", "teamb" -> redPlayers += player
                else -> if (player.participantId <= 5) bluePlayers += player else redPlayers += player
            }
        }

        val a = target.teams.getOrNull(0)
        val b = target.teams.getOrNull(1)
        return LiveSnapshot(
            game = gameNumber.coerceAtLeast(1),
            elapsedSeconds = firstInt(data, "elapsedSeconds", "gameTime", "gameTimeSeconds", "seconds").coerceAtLeast(0),
            blue = firstString(blue, "code", "name").ifBlank { a?.code?.ifBlank { a.name }.orEmpty() },
            red = firstString(red, "code", "name").ifBlank { b?.code?.ifBlank { b.name }.orEmpty() },
            blueGold = firstInt(blue, "totalGold", "gold"),
            redGold = firstInt(red, "totalGold", "gold"),
            blueKills = firstInt(blue, "totalKills", "kills"),
            redKills = firstInt(red, "totalKills", "kills"),
            blueTowers = firstInt(blue, "towers", "towerKills"),
            redTowers = firstInt(red, "towers", "towerKills"),
            blueDragons = dragonCount(blue),
            redDragons = dragonCount(red),
            latestEvent = "Cito · ${firstString(data, "state", "status").ifBlank { "live" }}",
            blueBarons = firstInt(blue, "barons", "baronKills"),
            redBarons = firstInt(red, "barons", "baronKills"),
            blueXp = firstInt(blue, "totalXp", "xp"),
            redXp = firstInt(red, "totalXp", "xp"),
            bluePlayers = bluePlayers,
            redPlayers = redPlayers,
            source = "Cito API",
            gameId = firstString(data, "gameId", "id")
        )
    }

    fun parseCompletedGame(
        root: JSONObject,
        match: ScheduledEsportsMatch,
        gameNumber: Int,
        gameId: String
    ): LiveSnapshot? {
        val parsed = parseLiveBoard(root, match, gameNumber) ?: return null
        if (!meaningful(parsed)) return null
        return parsed.copy(gameId = parsed.gameId.ifBlank { gameId }, latestEvent = "Cito POSTGAME")
    }

    private fun findBoardPayload(root: JSONObject): JSONObject? {
        val data = root.optJSONObject("data")
        if (data != null && (data.has("blueTeam") || data.has("redTeam") || data.has("players"))) return data
        if (root.has("blueTeam") || root.has("redTeam") || root.has("players")) return root
        val nested = data?.optJSONObject("board") ?: data?.optJSONObject("state") ?: root.optJSONObject("board")
        return nested?.takeIf { it.has("blueTeam") || it.has("redTeam") || it.has("players") }
    }

    fun teamTokens(obj: JSONObject): Set<String> = parseTeams(obj)
        .map { token(it.code.ifBlank { it.name }) }
        .filter { it.isNotBlank() }
        .toSet()

    private fun parseTeams(obj: JSONObject): List<EsportsTeamRef> {
        val direct = obj.optJSONArray("teams")
        if (direct != null && direct.length() >= 2) {
            val output = mutableListOf<EsportsTeamRef>()
            for (i in 0 until direct.length()) direct.optJSONObject(i)?.let { output += parseTeam(it) }
            return output
        }
        val blue = obj.optJSONObject("blueTeam") ?: obj.optJSONObject("teamA") ?: obj.optJSONObject("leftTeam")
        val red = obj.optJSONObject("redTeam") ?: obj.optJSONObject("teamB") ?: obj.optJSONObject("rightTeam")
        return listOfNotNull(blue?.let { parseTeam(it) }, red?.let { parseTeam(it) })
    }

    private fun parseTeam(obj: JSONObject): EsportsTeamRef {
        val record = obj.optJSONObject("record")
        return EsportsTeamRef(
            id = firstString(obj, "id", "teamId"),
            code = firstString(obj, "code", "acronym", "shortName"),
            name = firstString(obj, "name", "teamName"),
            slug = firstString(obj, "slug"),
            imageUrl = firstString(obj, "imageUrl", "logo", "image"),
            gameWins = firstInt(obj, "gameWins", "winsInMatch", "score"),
            outcome = firstString(obj, "outcome", "result"),
            recordWins = record?.let { firstInt(it, "wins") } ?: firstInt(obj, "recordWins"),
            recordLosses = record?.let { firstInt(it, "losses") } ?: firstInt(obj, "recordLosses")
        )
    }

    fun arrayFrom(root: JSONObject, vararg keys: String): JSONArray {
        for (key in keys) {
            root.optJSONArray(key)?.let { return it }
            root.optJSONObject("data")?.optJSONArray(key)?.let { return it }
        }
        val data = root.opt("data")
        return if (data is JSONArray) data else JSONArray()
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.opt(key)
            if (value is String && value.isNotBlank()) return value
            if (value != null && value != JSONObject.NULL && value !is JSONObject && value !is JSONArray) {
                val text = value.toString()
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }

    private fun firstInt(obj: JSONObject, vararg keys: String): Int {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            when (val value = obj.opt(key)) {
                is Number -> return value.toInt()
                is String -> value.toDoubleOrNull()?.toInt()?.let { return it }
            }
        }
        return 0
    }

    private fun dragonCount(team: JSONObject): Int = when (val raw = team.opt("dragons")) {
        is JSONArray -> raw.length()
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull() ?: 0
        else -> firstInt(team, "dragonKills")
    }

    fun meaningful(snapshot: LiveSnapshot): Boolean =
        snapshot.blueGold > 0 || snapshot.redGold > 0 ||
            snapshot.blueKills > 0 || snapshot.redKills > 0 ||
            snapshot.blueTowers > 0 || snapshot.redTowers > 0 ||
            snapshot.bluePlayers.any { it.gold > 0 || it.creepScore > 0 || it.level > 1 } ||
            snapshot.redPlayers.any { it.gold > 0 || it.creepScore > 0 || it.level > 1 }

    fun completed(match: ScheduledEsportsMatch): Boolean {
        val state = match.state.lowercase().replace("_", "").replace("-", "").replace(" ", "")
        if (state.contains("complete") || state == "finished") return true
        val required = if (match.bestOf > 0) match.bestOf / 2 + 1 else 1
        return (match.teams.maxOfOrNull { it.gameWins } ?: 0) >= required
    }

    private fun normalizeRole(value: String): String = when (token(value)) {
        "TOP", "TOPLANE" -> "TOP"
        "JUG", "JUNGLE", "JUNGLER" -> "JUG"
        "MID", "MIDDLE", "MIDLANE" -> "MID"
        "BOT", "BOTTOM", "ADC", "AD", "CARRY" -> "BOT"
        "SUP", "SUPPORT" -> "SUP"
        else -> value.uppercase()
    }

    fun token(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

    fun epoch(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(Long.MAX_VALUE)

    private fun normalizeInstant(value: String): String {
        if (value.isBlank()) return ""
        return runCatching { Instant.parse(value).toString() }.getOrElse { value }
    }
}
