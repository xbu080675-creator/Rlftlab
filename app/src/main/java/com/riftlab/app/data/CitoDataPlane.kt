package com.riftlab.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlin.math.max

/**
 * Cito is an optional first-class provider. No key means no traffic and no feature breakage.
 * The rest of RiftLab continues to run on Riot / league official / OP.GG sources.
 */
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
            .header("User-Agent", "RiftLab/${BuildInfo.versionLabel}")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CitoHttpException(response.code, body.take(240))
            }
            if (body.isBlank()) return@withContext JSONObject()
            JSONObject(body)
        }
    }

    fun websocketClient(): OkHttpClient = client
}

internal class CitoHttpException(val code: Int, detail: String) :
    IllegalStateException("Cito HTTP $code${if (detail.isBlank()) "" else " · $detail"}")

/** Keeps Cito-only fields for future sandbox work instead of throwing them away at normalization. */
internal object CitoRawArchive {
    private const val MAX_FILE_BYTES = 16L * 1024L * 1024L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    fun append(matchKey: String, gameId: String, kind: String, payload: JSONObject) {
        if (matchKey.isBlank() && gameId.isBlank()) return
        val safeMatch = safe(matchKey.ifBlank { "unknown-match" })
        val safeGame = safe(gameId.ifBlank { "series" })
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
                val dir = File(com.riftlab.app.RiftLabApplication.appContext.filesDir, "provider_raw/cito/$safeMatch")
                    .apply { mkdirs() }
                val file = File(dir, "$safeGame.jsonl")
                synchronized(lock) {
                    if (file.exists() && file.length() > MAX_FILE_BYTES) {
                        val rotated = File(dir, "$safeGame.previous.jsonl")
                        rotated.delete()
                        file.renameTo(rotated)
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

/** Cito schedule supplement; cached aggressively because a 10K plan should not be wasted on idle UI. */
internal class CitoScheduleSupplementProvider {
    private var cacheAt = 0L
    private var cache: List<ScheduledEsportsMatch> = emptyList()

    suspend fun fetch(): List<ScheduledEsportsMatch> {
        if (CitoApiConfig.apiKey() == null) return emptyList()
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - cacheAt < CACHE_MS) return cache

        val today = runCatching { CitoHttpClient.getJson(CitoApiConfig.scheduleTodayUrl()) }.getOrNull()
        val upcoming = runCatching { CitoHttpClient.getJson(CitoApiConfig.scheduleUpcomingUrl()) }.getOrNull()
        val parsed = buildList {
            today?.let { addAll(CitoJson.parseSchedule(it)) }
            upcoming?.let { addAll(CitoJson.parseSchedule(it)) }
        }.distinctBy(::scheduleIdentity).sortedBy { CitoJson.epoch(it.startTimeIso) }
        if (parsed.isNotEmpty()) {
            cache = TeamAssetCatalog.enrichMatches(parsed)
            cacheAt = now
            CitoProviderState.update("Cito REST · 赛程补充 ${cache.size} 场")
        }
        return cache
    }

    private fun scheduleIdentity(match: ScheduledEsportsMatch): String = buildString {
        append(match.matchId.ifBlank { match.eventId })
        append('|')
        append(match.teams.take(2).map { CitoJson.token(it.code.ifBlank { it.name }) }.sorted().joinToString("-"))
        append('|')
        append(match.startTimeIso.take(10))
    }

    companion object { private const val CACHE_MS = 30 * 60 * 1000L }
}

internal class CitoTeamSupplementProvider {
    private data class Entry(val at: Long, val details: EsportsTeamDetails?)
    private val cache = linkedMapOf<String, Entry>()

    suspend fun fetch(team: EsportsTeamRef): EsportsTeamDetails? {
        if (CitoApiConfig.apiKey() == null) return null
        val key = CitoJson.token(team.slug.ifBlank { team.code.ifBlank { team.name } })
        cache[key]?.takeIf { System.currentTimeMillis() - it.at < CACHE_MS }?.let { return it.details }

        val slugCandidates = listOf(team.slug, team.code.lowercase(), team.name.lowercase().replace(' ', '-'))
            .filter { it.isNotBlank() }
            .distinct()
        var result: EsportsTeamDetails? = null
        for (slug in slugCandidates) {
            val root = runCatching { CitoHttpClient.getJson(CitoApiConfig.teamRosterHistoryUrl(slug)) }.getOrNull() ?: continue
            CitoRawArchive.append("team-${CitoJson.token(team.code.ifBlank { team.name })}", "", "roster-history", root)
            result = CitoJson.parseTeamRoster(team, root)
            if (result?.players?.isNotEmpty() == true) break
        }
        cache[key] = Entry(System.currentTimeMillis(), result)
        return result
    }

    companion object { private const val CACHE_MS = 60 * 60 * 1000L }
}

internal class CitoStandingsSupplementProvider {
    private data class Entry(val at: Long, val standings: TournamentStandings?)
    private val cache = linkedMapOf<String, Entry>()

    suspend fun fetch(tournament: EsportsTournamentRef): TournamentStandings? {
        if (CitoApiConfig.apiKey() == null) return null
        val league = tournament.leagueSlug.ifBlank { tournament.leagueId }.ifBlank { return null }
        val key = "$league|${tournament.id}"
        cache[key]?.takeIf { System.currentTimeMillis() - it.at < CACHE_MS }?.let { return it.standings }
        val root = runCatching { CitoHttpClient.getJson(CitoApiConfig.leagueStandingsUrl(league)) }.getOrNull()
        val standings = root?.let { CitoJson.parseStandings(tournament, it) }
        if (root != null) CitoRawArchive.append("standings-$league", "", "standings", root)
        cache[key] = Entry(System.currentTimeMillis(), standings)
        return standings
    }

    companion object { private const val CACHE_MS = 30 * 60 * 1000L }
}

/**
 * Cito live provider.
 * - WSS is attempted when the paid add-on actually accepts the connection.
 * - REST remains a quota-aware fallback (8s cadence, not 2s) because Riot is already the primary feed.
 * - Any Cito-only fields are mirrored to CitoRawArchive before normalization.
 */
internal class CitoLiveDataSource : LiveMatchDataSource {
    private val _status = MutableStateFlow(
        LiveSourceStatus(LiveSourcePhase.IDLE, "Cito · API Key 未配置")
    )
    val status: StateFlow<LiveSourceStatus> = _status.asStateFlow()

    override fun observe(matchId: String): Flow<LiveSnapshot> = flow {
        var currentCitoMatchId = ""
        var currentGameId = ""
        var gameNumber = 0
        var lastSnapshotKey = ""
        var websocket: CitoWebSocketPump? = null
        var websocketJob: Job? = null
        val wsLatest = MutableStateFlow<JSONObject?>(null)

        while (currentCoroutineContext().isActive) {
            val key = CitoApiConfig.apiKey()
            if (key == null) {
                websocketJob?.cancel(); websocketJob = null; websocket = null
                _status.value = LiveSourceStatus(LiveSourcePhase.IDLE, "Cito · API Key 未配置")
                delay(5_000)
                continue
            }

            try {
                if (websocket == null) {
                    websocket = CitoWebSocketPump()
                    websocketJob = CoroutineScope(currentCoroutineContext()).launch {
                        websocket!!.messages().collect { wsLatest.value = it }
                    }
                }

                val target = LiveMatchTargetRegistry.snapshot()
                if (target == null) {
                    _status.value = LiveSourceStatus(LiveSourcePhase.WAITING_FOR_MATCH, "Cito · 等待赛事目标")
                    delay(5_000)
                    continue
                }

                val matchKey = MatchLifecycleArchive.keyFor(target)
                if (currentCitoMatchId.isBlank()) {
                    currentCitoMatchId = resolveCitoMatchId(target)
                    if (currentCitoMatchId.isBlank()) {
                        _status.value = LiveSourceStatus(
                            LiveSourcePhase.WAITING_FOR_MATCH,
                            "Cito · 当前赛事尚未出现在 live 列表",
                            eventId = target.eventId,
                            lastUpdateEpochMs = System.currentTimeMillis()
                        )
                        delay(DISCOVERY_POLL_MS)
                        continue
                    }
                }

                val series = runCatching { CitoHttpClient.getJson(CitoApiConfig.liveSeriesUrl(currentCitoMatchId)) }.getOrNull()
                if (series != null) {
                    CitoRawArchive.append(matchKey, "", "live-series", series)
                    val context = CitoJson.parseSeriesContext(series, target)
                    if (context.gameId.isNotBlank()) currentGameId = context.gameId
                    if (context.gameNumber > 0) gameNumber = context.gameNumber
                }

                val wsRoot = wsLatest.value
                val wsSnapshot = wsRoot?.let { raw ->
                    CitoJson.findBoardPayload(raw)?.let { board ->
                        val wsGameId = board.optString("gameId", currentGameId)
                        if (currentGameId.isBlank() || wsGameId == currentGameId) {
                            CitoRawArchive.append(matchKey, wsGameId, "websocket", raw)
                            CitoJson.parseLiveBoard(raw, target, max(gameNumber, 1))
                        } else null
                    }
                }

                val snapshot = wsSnapshot ?: if (currentGameId.isNotBlank()) {
                    val board = CitoHttpClient.getJson(CitoApiConfig.liveBoardUrl(currentGameId))
                    if (board != null) {
                        CitoRawArchive.append(matchKey, currentGameId, "live-board", board)
                        CitoJson.parseLiveBoard(board, target, max(gameNumber, 1))
                    } else null
                } else null

                if (snapshot != null && CitoJson.meaningful(snapshot)) {
                    val emissionKey = "${snapshot.gameId}|${snapshot.elapsedSeconds}|${snapshot.blueGold}|${snapshot.redGold}|${snapshot.blueKills}|${snapshot.redKills}"
                    if (emissionKey != lastSnapshotKey) {
                        lastSnapshotKey = emissionKey
                        _status.value = LiveSourceStatus(
                            LiveSourcePhase.LIVE,
                            "Cito ${if (wsSnapshot != null) "WebSocket" else "REST fallback"} · G${snapshot.game}",
                            eventId = target.eventId,
                            gameId = snapshot.gameId,
                            lastUpdateEpochMs = System.currentTimeMillis()
                        )
                        emit(snapshot)
                    }
                } else {
                    _status.value = LiveSourceStatus(
                        LiveSourcePhase.WAITING_FOR_MATCH,
                        "Cito · 已锁定赛事，等待有效实时状态",
                        eventId = target.eventId,
                        gameId = currentGameId,
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                }
                delay(if (wsSnapshot != null) WS_REST_SAFETY_POLL_MS else REST_POLL_MS)
            } catch (t: Throwable) {
                _status.value = LiveSourceStatus(
                    LiveSourcePhase.ERROR,
                    "Cito 暂不可用：${t.message?.take(100) ?: t::class.java.simpleName}",
                    eventId = LiveMatchTargetRegistry.snapshot()?.eventId.orEmpty(),
                    gameId = currentGameId,
                    lastUpdateEpochMs = System.currentTimeMillis()
                )
                currentCitoMatchId = ""
                currentGameId = ""
                gameNumber = 0
                delay(8_000)
            }
        }
    }

    private suspend fun resolveCitoMatchId(target: ScheduledEsportsMatch): String {
        val candidates = listOf(target.matchId, target.eventId).filter { it.isNotBlank() }
        for (id in candidates) {
            val coverage = runCatching { CitoHttpClient.getJson(CitoApiConfig.coverageUrl(id)) }.getOrNull() ?: continue
            val coverageNode = coverage.optJSONObject("coverage")
            val usable = coverageNode?.optBoolean("schedule_metadata", false) == true ||
                coverageNode?.optBoolean("live_row", false) == true ||
                coverageNode?.optBoolean("numeric_live_state", false) == true
            if (usable) return id
        }

        val root = runCatching { CitoHttpClient.getJson(CitoApiConfig.liveMatchesUrl()) }.getOrNull() ?: return ""
        val wanted = target.teams.take(2).map { CitoJson.token(it.code.ifBlank { it.name }) }.toSet()
        return CitoJson.arrayFrom(root, "data", "matches").firstNotNullOfOrNull { item ->
            val obj = item as? JSONObject ?: return@firstNotNullOfOrNull null
            val parsedTeams = CitoJson.teamTokens(obj)
            if (wanted.size == 2 && parsedTeams == wanted) {
                obj.optString("matchId").ifBlank { obj.optString("id") }
            } else null
        }.orEmpty()
    }

    companion object {
        private const val DISCOVERY_POLL_MS = 12_000L
        private const val REST_POLL_MS = 8_000L
        private const val WS_REST_SAFETY_POLL_MS = 25_000L
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
            .header("User-Agent", "RiftLab/${BuildInfo.versionLabel}")
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                CitoProviderState.update("Cito WebSocket · 已连接")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JSONObject(text) }.getOrNull()?.let { trySend(it) }
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

/** Background post-match ingest for every completed competition, not just LPL. */
internal object CitoArchiveCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val attemptedAt = linkedMapOf<String, Long>()

    fun observe(matches: List<ScheduledEsportsMatch>) {
        if (CitoApiConfig.apiKey() == null) return
        val now = System.currentTimeMillis()
        matches.asSequence()
            .filter { CitoJson.completed(it) }
            .sortedByDescending { CitoJson.epoch(it.startTimeIso) }
            .take(12)
            .forEach { match ->
                val key = MatchLifecycleArchive.keyFor(match)
                val last = attemptedAt[key] ?: 0L
                if (now - last < RETRY_MS) return@forEach
                attemptedAt[key] = now
                scope.launch { runCatching { backfill(match) } }
            }
    }

    private suspend fun backfill(match: ScheduledEsportsMatch) {
        val matchId = match.matchId.ifBlank { match.eventId }.ifBlank { return }
        val matchKey = MatchLifecycleArchive.keyFor(match)
        val gamesRoot = CitoHttpClient.getJson(CitoApiConfig.matchGamesUrl(matchId)) ?: return
        CitoRawArchive.append(matchKey, "", "match-games", gamesRoot)
        val gamesArray = CitoJson.arrayFrom(gamesRoot, "data", "games")
        if (gamesArray.isEmpty()) return

        val snapshots = mutableListOf<LiveSnapshot>()
        gamesArray.forEachIndexed { index, raw ->
            val game = raw as? JSONObject ?: return@forEachIndexed
            val gameId = game.optString("gameId").ifBlank { game.optString("id") }.ifBlank { return@forEachIndexed }
            val post = runCatching { CitoHttpClient.getJson(CitoApiConfig.gamePostgameUrl(gameId)) }.getOrNull()
            val stats = runCatching { CitoHttpClient.getJson(CitoApiConfig.gameStatsUrl(gameId)) }.getOrNull()
            post?.let { CitoRawArchive.append(matchKey, gameId, "postgame", it) }
            stats?.let { CitoRawArchive.append(matchKey, gameId, "player-stats", it) }
            val snapshot = CitoJson.parseCompletedGame(post ?: stats ?: game, match, index + 1, gameId)
            if (snapshot != null) snapshots += snapshot
        }
        if (snapshots.isEmpty()) return

        val a = match.teams.getOrNull(0)
        val b = match.teams.getOrNull(1)
        val series = CompletedSeriesSnapshot(
            matchKey = matchKey,
            teamA = a?.code?.ifBlank { a.name }.orEmpty(),
            teamB = b?.code?.ifBlank { b.name }.orEmpty(),
            scoreA = a?.gameWins ?: snapshots.count { it.blue == (a?.code ?: a?.name) && it.blueGold > it.redGold },
            scoreB = b?.gameWins ?: snapshots.count { it.red == (b?.code ?: b?.name) && it.redGold > it.blueGold },
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

private object BuildInfo {
    val versionLabel: String get() = "android"
}

internal object CitoJson {
    fun parseSchedule(root: JSONObject): List<ScheduledEsportsMatch> {
        val array = arrayFrom(root, "data", "matches", "schedule")
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                parseScheduledMatch(obj)?.let(::add)
            }
        }
    }

    private fun parseScheduledMatch(obj: JSONObject): ScheduledEsportsMatch? {
        val teams = parseTeams(obj)
        if (teams.size < 2) return null
        val matchId = obj.optString("matchId").ifBlank { obj.optString("id") }
        val eventId = obj.optString("eventId").ifBlank { matchId }
        val leagueObj = obj.optJSONObject("league")
        val league = when (val raw = obj.opt("league")) {
            is String -> raw
            else -> leagueObj?.optString("name").orEmpty()
                .ifBlank { leagueObj?.optString("slug").orEmpty() }
        }
        val start = firstString(obj, "startTime", "startTimeIso", "scheduledAt", "date", "startDate")
        return ScheduledEsportsMatch(
            eventId = eventId,
            matchId = matchId,
            league = league.ifBlank { obj.optString("leagueName", "LoL Esports") },
            blockName = firstString(obj, "blockName", "stage", "round", "phase"),
            startTimeIso = normalizeInstant(start),
            state = firstString(obj, "state", "status").ifBlank { "unstarted" },
            bestOf = firstInt(obj, "bestOf", "bo", "best_of").takeIf { it > 0 } ?: 1,
            teams = teams,
            leagueId = leagueObj?.optString("id").orEmpty().ifBlank { obj.optString("leagueId") },
            leagueSlug = leagueObj?.optString("slug").orEmpty().ifBlank { obj.optString("leagueSlug") }
        )
    }

    fun parseTeamRoster(team: EsportsTeamRef, root: JSONObject): EsportsTeamDetails? {
        val data = root.optJSONObject("data") ?: root
        val rosterArrays = listOf("roster", "players", "members", "history")
            .mapNotNull { data.optJSONArray(it) }
        val players = mutableListOf<EsportsPlayerRef>()
        rosterArrays.forEach { array ->
            for (i in 0 until array.length()) {
                val raw = array.optJSONObject(i) ?: continue
                val nested = raw.optJSONObject("player") ?: raw
                val name = firstString(nested, "summonerName", "playerName", "name", "handle")
                if (name.isBlank()) continue
                val active = if (raw.has("endedAt")) raw.isNull("endedAt") || raw.optString("endedAt").isBlank() else true
                if (!active && players.size >= 5) continue
                players += EsportsPlayerRef(
                    id = firstString(nested, "playerId", "id"),
                    summonerName = name,
                    role = normalizeRole(firstString(raw, "role", "position").ifBlank { firstString(nested, "role", "position") }),
                    imageUrl = firstString(nested, "imageUrl", "image", "photo"),
                    firstName = firstString(nested, "firstName", "first_name"),
                    lastName = firstString(nested, "lastName", "last_name")
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
        if (rankings.isEmpty()) return null
        val rows = buildList {
            for (i in 0 until rankings.length()) {
                val row = rankings.optJSONObject(i) ?: continue
                val teamObj = row.optJSONObject("team") ?: row
                val name = firstString(teamObj, "name", "teamName")
                val code = firstString(teamObj, "code", "acronym", "shortName").ifBlank { name }
                if (name.isBlank() && code.isBlank()) continue
                add(
                    StandingTeam(
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
                )
            }
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
        } else (target.teams.take(2).sumOf { it.gameWins } + 1)
        return CitoSeriesContext(gameId, explicit ?: inferred.coerceAtLeast(1))
    }

    fun parseLiveBoard(root: JSONObject, target: ScheduledEsportsMatch, gameNumber: Int): LiveSnapshot? {
        val data = findBoardPayload(root) ?: return null
        val blue = data.optJSONObject("blueTeam") ?: data.optJSONObject("blue") ?: JSONObject()
        val red = data.optJSONObject("redTeam") ?: data.optJSONObject("red") ?: JSONObject()
        val players = data.optJSONArray("players") ?: JSONArray()
        val bluePlayers = mutableListOf<LivePlayerSnapshot>()
        val redPlayers = mutableListOf<LivePlayerSnapshot>()
        for (i in 0 until players.length()) {
            val p = players.optJSONObject(i) ?: continue
            val snapshot = LivePlayerSnapshot(
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
                "blue", "left", "teama" -> bluePlayers += snapshot
                "red", "right", "teamb" -> redPlayers += snapshot
                else -> if (snapshot.participantId <= 5) bluePlayers += snapshot else redPlayers += snapshot
            }
        }
        val teamA = target.teams.getOrNull(0)?.code?.ifBlank { target.teams.getOrNull(0)?.name.orEmpty() }.orEmpty()
        val teamB = target.teams.getOrNull(1)?.code?.ifBlank { target.teams.getOrNull(1)?.name.orEmpty() }.orEmpty()
        val elapsed = firstInt(data, "elapsedSeconds", "gameTime", "gameTimeSeconds", "seconds")
        return LiveSnapshot(
            game = gameNumber.coerceAtLeast(1),
            elapsedSeconds = elapsed.coerceAtLeast(0),
            blue = firstString(blue, "code", "name").ifBlank { teamA },
            red = firstString(red, "code", "name").ifBlank { teamB },
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

    fun parseCompletedGame(root: JSONObject, match: ScheduledEsportsMatch, gameNumber: Int, gameId: String): LiveSnapshot? {
        parseLiveBoard(root, match, gameNumber)?.let { parsed ->
            if (meaningful(parsed)) return parsed.copy(gameId = parsed.gameId.ifBlank { gameId }, latestEvent = "Cito POSTGAME")
        }
        val data = root.optJSONObject("data") ?: root
        val blue = data.optJSONObject("blueTeam") ?: data.optJSONObject("blue")
        val red = data.optJSONObject("redTeam") ?: data.optJSONObject("red")
        if (blue == null || red == null) return null
        return parseLiveBoard(
            JSONObject().put("data", JSONObject(data.toString()).put("gameId", gameId)),
            match,
            gameNumber
        )?.copy(latestEvent = "Cito POSTGAME")
    }

    fun findBoardPayload(root: JSONObject): JSONObject? {
        val data = root.optJSONObject("data")
        if (data?.has("blueTeam") == true || data?.has("redTeam") == true) return data
        if (root.has("blueTeam") || root.has("redTeam")) return root
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
            return buildList {
                for (i in 0 until direct.length()) {
                    direct.optJSONObject(i)?.let { parseTeam(it) }?.let(::add)
                }
            }
        }
        val blue = obj.optJSONObject("blueTeam") ?: obj.optJSONObject("teamA") ?: obj.optJSONObject("leftTeam")
        val red = obj.optJSONObject("redTeam") ?: obj.optJSONObject("teamB") ?: obj.optJSONObject("rightTeam")
        return listOfNotNull(blue?.let(::parseTeam), red?.let(::parseTeam))
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
        keys.forEach { key ->
            root.optJSONArray(key)?.let { return it }
            root.optJSONObject("data")?.optJSONArray(key)?.let { return it }
        }
        val data = root.opt("data")
        return if (data is JSONArray) data else JSONArray()
    }

    fun firstString(obj: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            val value = obj.opt(key)
            if (value is String && value.isNotBlank()) return value
            if (value != null && value != JSONObject.NULL && value !is JSONObject && value !is JSONArray) {
                val text = value.toString()
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }

    fun firstInt(obj: JSONObject, vararg keys: String): Int {
        keys.forEach { key ->
            if (!obj.has(key) || obj.isNull(key)) return@forEach
            val value = obj.opt(key)
            when (value) {
                is Number -> return value.toInt()
                is String -> value.toDoubleOrNull()?.toInt()?.let { return it }
            }
        }
        return 0
    }

    private fun dragonCount(team: JSONObject): Int {
        val raw = team.opt("dragons")
        return when (raw) {
            is JSONArray -> raw.length()
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull() ?: 0
            else -> firstInt(team, "dragonKills")
        }
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

    fun normalizeRole(value: String): String = when (token(value)) {
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
