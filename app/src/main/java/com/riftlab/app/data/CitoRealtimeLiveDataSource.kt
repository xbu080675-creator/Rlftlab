package com.riftlab.app.data

import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * One process-wide Cito realtime bus.
 *
 * Every realtime consumer must subscribe here instead of opening a second socket. The bus keeps
 * receive order, provider timestamps when published, event history, reconnect state and transport
 * freshness. Match identity / REST reconciliation stay in domain adapters so a raw provider packet
 * can never select a schedule target by itself.
 */
internal object CitoRealtimeBus {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _bundle = MutableStateFlow(CitoRealtimeBundle())
    val bundle: StateFlow<CitoRealtimeBundle> = _bundle.asStateFlow()

    private val _transport = MutableStateFlow("Cito WebSocket · 待机")
    val transport: StateFlow<String> = _transport.asStateFlow()

    @Synchronized
    fun ensureRunning() {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoffMs = 1_500L
            while (isActive) {
                if (CitoApiConfig.apiKey() == null) {
                    _transport.value = "Cito WebSocket · API Key 未配置"
                    delay(5_000L)
                    continue
                }

                _transport.value = "Cito WebSocket · 正在连接"
                val ended = runCatching {
                    CitoRealtimeSocket().messages().collect { message ->
                        _bundle.value = _bundle.value.acceptSocket(message)
                        _transport.value = "Cito WebSocket · 已连接"
                        backoffMs = 1_500L
                    }
                }
                if (ended.isFailure) {
                    _transport.value = "Cito WebSocket · 断开，${backoffMs / 1000.0}s 后重连"
                } else {
                    _transport.value = "Cito WebSocket · 已关闭，等待重连"
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
            }
        }
    }
}

/**
 * Cito live-domain adapter.
 *
 * WebSocket is the live clock. REST is bootstrap/reconnect/reconciliation only. No undocumented
 * room/subscription message is invented: RiftLab accepts provider push frames as delivered and
 * falls back to the documented REST surface when WSS is unavailable.
 */
internal class CitoRealtimeLiveDataSource : LiveMatchDataSource {
    private val _status = MutableStateFlow(
        LiveSourceStatus(LiveSourcePhase.IDLE, "Cito Realtime · API Key 未配置")
    )
    val status: StateFlow<LiveSourceStatus> = _status.asStateFlow()

    override fun observe(matchId: String): Flow<LiveSnapshot> = flow {
        CitoRealtimeBus.ensureRunning()

        var observedTargetKey = ""
        var citoMatchId = ""
        var gameId = ""
        var gameNumber = 1
        var lastEmission = ""
        var lastArchivedSocketSequence = 0L
        var nextSeriesRefreshAt = 0L
        var nextRestReconcileAt = 0L
        var restOverlay = CitoRealtimeBundle()

        while (currentCoroutineContext().isActive) {
            if (CitoApiConfig.apiKey() == null) {
                restOverlay = CitoRealtimeBundle()
                _status.value = LiveSourceStatus(LiveSourcePhase.IDLE, "Cito Realtime · API Key 未配置")
                delay(5_000L)
                continue
            }

            val target = LiveMatchTargetRegistry.snapshot()
            if (target == null) {
                _status.value = LiveSourceStatus(
                    LiveSourcePhase.WAITING_FOR_MATCH,
                    "Cito Realtime · 等待赛事目标",
                    lastUpdateEpochMs = System.currentTimeMillis()
                )
                delay(2_000L)
                continue
            }

            val targetKey = LiveMatchTargetRegistry.key(target)
            if (targetKey != observedTargetKey) {
                observedTargetKey = targetKey
                citoMatchId = ""
                gameId = ""
                gameNumber = 1
                lastEmission = ""
                lastArchivedSocketSequence = 0L
                nextSeriesRefreshAt = 0L
                nextRestReconcileAt = 0L
                restOverlay = CitoRealtimeBundle()
                SideSelectionStore.load(target)
            }

            val matchKey = MatchLifecycleArchive.keyFor(target)
            try {
                if (citoMatchId.isBlank()) {
                    citoMatchId = resolveCitoMatchId(target)
                    if (citoMatchId.isBlank()) {
                        _status.value = LiveSourceStatus(
                            LiveSourcePhase.WAITING_FOR_MATCH,
                            "Cito Realtime · 当前赛事尚未进入 live 列表",
                            eventId = target.eventId,
                            lastUpdateEpochMs = System.currentTimeMillis()
                        )
                        delay(DISCOVERY_MS)
                        continue
                    }
                }

                val now = System.currentTimeMillis()
                if (gameId.isBlank() || now >= nextSeriesRefreshAt) {
                    val series = runCatching {
                        CitoHttpClient.getJson(CitoApiConfig.liveSeriesUrl(citoMatchId))
                    }.getOrNull()
                    if (series != null) {
                        CitoRawArchive.append(matchKey, "", "realtime-series-bootstrap", series)
                        val context = CitoJson.parseSeriesContext(series, target)
                        if (context.gameId.isNotBlank() && context.gameId != gameId) {
                            gameId = context.gameId
                            restOverlay = CitoRealtimeBundle()
                            nextRestReconcileAt = 0L
                            lastEmission = ""
                            lastArchivedSocketSequence = 0L
                        }
                        if (context.gameNumber > 0) gameNumber = context.gameNumber
                    }
                    nextSeriesRefreshAt = now + SERIES_REFRESH_MS
                }

                var bundle = CitoRealtimeBus.bundle.value.merge(restOverlay)
                val newSocketPayloads = bundle.socketPayloadsAfter(lastArchivedSocketSequence, gameId)
                newSocketPayloads.forEach { pushed ->
                    lastArchivedSocketSequence = maxOf(lastArchivedSocketSequence, pushed.sequence)
                    CitoRawArchive.append(
                        matchKey,
                        pushed.gameId.ifBlank { gameId },
                        "realtime-websocket-${pushed.kind.name.lowercase()}",
                        pushed.payload
                    )
                }

                var transport = "WebSocket"
                val boardPayload = bundle.bestBoardFor(gameId)
                var snapshot = boardPayload?.let { payload ->
                    CitoJson.parseLiveBoard(payload, target, gameNumber)
                }
                if (snapshot != null) {
                    snapshot = CitoLiveFusion.alignVerifiedSides(snapshot, boardPayload, target, gameNumber)
                    snapshot = CitoLiveFusion.enrichSnapshot(
                        snapshot = snapshot,
                        payloads = bundle.supplementsFor(gameId),
                        capturedAtEpochMs = bundle.lastDomainDataAtEpochMs
                    )
                }

                val wsFresh = bundle.lastSocketMessageAtEpochMs > 0L &&
                    now - bundle.lastSocketMessageAtEpochMs <= WS_FRESH_MS
                val needsRest = gameId.isNotBlank() && (
                    snapshot == null || !CitoJson.meaningful(snapshot) || !wsFresh || now >= nextRestReconcileAt
                )
                if (needsRest) {
                    val reconciled = reconcileRest(matchKey, gameId)
                    if (reconciled.payloads.isNotEmpty()) {
                        restOverlay = restOverlay.merge(reconciled)
                        bundle = CitoRealtimeBus.bundle.value.merge(restOverlay)
                        val reconciledBoard = bundle.bestBoardFor(gameId)
                        snapshot = reconciledBoard?.let { CitoJson.parseLiveBoard(it, target, gameNumber) }
                        if (snapshot != null) {
                            snapshot = CitoLiveFusion.alignVerifiedSides(snapshot, reconciledBoard, target, gameNumber)
                            snapshot = CitoLiveFusion.enrichSnapshot(
                                snapshot = snapshot,
                                payloads = bundle.supplementsFor(gameId),
                                capturedAtEpochMs = bundle.lastDomainDataAtEpochMs
                            )
                        }
                    }
                    transport = if (wsFresh) "WebSocket + REST reconcile" else "REST reconnect fallback"
                    nextRestReconcileAt = now + if (wsFresh) REST_SAFETY_MS else REST_FALLBACK_MS
                }

                if (snapshot != null && CitoJson.meaningful(snapshot)) {
                    val fixed = snapshot.copy(
                        gameId = snapshot.gameId.ifBlank { gameId },
                        targetKey = targetKey,
                        source = "Cito API · $transport"
                    )
                    if (gameId.isBlank() && fixed.gameId.isNotBlank()) gameId = fixed.gameId
                    val key = fingerprint(fixed)
                    if (key != lastEmission) {
                        lastEmission = key
                        _status.value = LiveSourceStatus(
                            phase = LiveSourcePhase.LIVE,
                            message = "Cito Realtime · $transport · G${fixed.game}",
                            eventId = target.eventId,
                            gameId = fixed.gameId,
                            lastUpdateEpochMs = System.currentTimeMillis()
                        )
                        emit(fixed)
                    }
                } else {
                    _status.value = LiveSourceStatus(
                        phase = LiveSourcePhase.WAITING_FOR_MATCH,
                        message = if (wsFresh) {
                            "Cito Realtime · WSS 已连接，等待可归一化比赛帧"
                        } else {
                            "Cito Realtime · ${CitoRealtimeBus.transport.value} · REST 同步兜底"
                        },
                        eventId = target.eventId,
                        gameId = gameId,
                        lastUpdateEpochMs = System.currentTimeMillis()
                    )
                }

                delay(if (wsFresh) WS_LOOP_MS else REST_LOOP_MS)
            } catch (t: Throwable) {
                _status.value = LiveSourceStatus(
                    phase = LiveSourcePhase.ERROR,
                    message = "Cito Realtime 暂不可用：${t.message?.take(120) ?: t::class.java.simpleName}",
                    eventId = target.eventId,
                    gameId = gameId,
                    lastUpdateEpochMs = System.currentTimeMillis()
                )
                citoMatchId = ""
                gameId = ""
                gameNumber = 1
                nextSeriesRefreshAt = 0L
                nextRestReconcileAt = 0L
                restOverlay = CitoRealtimeBundle()
                delay(5_000L)
            }
        }
    }

    private suspend fun reconcileRest(matchKey: String, gameId: String): CitoRealtimeBundle {
        if (gameId.isBlank()) return CitoRealtimeBundle()
        val capturedAt = System.currentTimeMillis()
        val payloads = buildList {
            runCatching { CitoHttpClient.getJson(CitoApiConfig.liveBoardUrl(gameId)) }.getOrNull()?.let {
                CitoRawArchive.append(matchKey, gameId, "realtime-rest-board", it)
                add(CitoRealtimePayload.rest(CitoRealtimeKind.BOARD, gameId, it, capturedAt))
            }
            runCatching { CitoHttpClient.getJson(CitoApiConfig.liveMapUrl(gameId)) }.getOrNull()?.let {
                CitoRawArchive.append(matchKey, gameId, "realtime-rest-map", it)
                add(CitoRealtimePayload.rest(CitoRealtimeKind.MAP, gameId, it, capturedAt))
            }
            runCatching { CitoHttpClient.getJson(CitoApiConfig.liveDetailsUrl(gameId)) }.getOrNull()?.let {
                CitoRawArchive.append(matchKey, gameId, "realtime-rest-details", it)
                add(CitoRealtimePayload.rest(CitoRealtimeKind.DETAILS, gameId, it, capturedAt))
            }
            runCatching { CitoHttpClient.getJson(CitoApiConfig.liveEventsUrl(gameId)) }.getOrNull()?.let {
                CitoRawArchive.append(matchKey, gameId, "realtime-rest-events", it)
                add(CitoRealtimePayload.rest(CitoRealtimeKind.EVENTS, gameId, it, capturedAt))
            }
        }
        return CitoRealtimeBundle(
            payloads = payloads,
            lastSocketMessageAtEpochMs = 0L,
            lastDomainDataAtEpochMs = capturedAt
        )
    }

    private suspend fun resolveCitoMatchId(target: ScheduledEsportsMatch): String {
        val ids = listOf(target.matchId, target.eventId).filter { it.isNotBlank() }.distinct()
        for (id in ids) {
            val coverage = runCatching { CitoHttpClient.getJson(CitoApiConfig.coverageUrl(id)) }.getOrNull()
                ?: continue
            val c = coverage.optJSONObject("coverage") ?: coverage.optJSONObject("data")?.optJSONObject("coverage")
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

    private fun fingerprint(snapshot: LiveSnapshot): String = buildString {
        append(snapshot.gameId).append('|').append(snapshot.elapsedSeconds)
        append('|').append(snapshot.blue).append('|').append(snapshot.red)
        append('|').append(snapshot.blueGold).append('|').append(snapshot.redGold)
        append('|').append(snapshot.blueKills).append('|').append(snapshot.redKills)
        append('|').append(snapshot.supplementUpdatedAtEpochMs)
        (snapshot.bluePlayers + snapshot.redPlayers).forEach { player ->
            append('|').append(player.participantId)
            append(':').append(player.alive)
            append(':').append(player.currentHealth ?: -1)
            append(':').append(player.items.joinToString(","))
        }
    }

    companion object {
        private const val DISCOVERY_MS = 10_000L
        private const val SERIES_REFRESH_MS = 12_000L
        private const val WS_FRESH_MS = 12_000L
        private const val WS_LOOP_MS = 350L
        private const val REST_LOOP_MS = 2_000L
        private const val REST_SAFETY_MS = 30_000L
        private const val REST_FALLBACK_MS = 5_000L
    }
}

internal enum class CitoRealtimeKind {
    BOARD,
    MAP,
    DETAILS,
    EVENTS,
    DRAFT,
    SERIES,
    READY,
    HEARTBEAT,
    UNKNOWN
}

internal enum class CitoRealtimeOrigin { WEBSOCKET, REST }

internal object CitoRealtimeOrder {
    private val sequence = AtomicLong(0L)
    fun next(): Long = sequence.incrementAndGet()
}

internal data class CitoRealtimePayload(
    val kind: CitoRealtimeKind,
    val gameId: String,
    val payload: JSONObject,
    val receivedAtEpochMs: Long,
    val origin: CitoRealtimeOrigin,
    val sequence: Long,
    val providerEpochMs: Long? = null
) {
    val orderingEpochMs: Long get() = providerEpochMs ?: receivedAtEpochMs

    companion object {
        fun rest(kind: CitoRealtimeKind, gameId: String, payload: JSONObject, capturedAt: Long): CitoRealtimePayload =
            CitoRealtimePayload(
                kind = kind,
                gameId = gameId,
                payload = payload,
                receivedAtEpochMs = capturedAt,
                origin = CitoRealtimeOrigin.REST,
                sequence = CitoRealtimeOrder.next(),
                providerEpochMs = CitoRealtimeClassifier.providerEpochMs(payload)
            )
    }
}

internal data class CitoRealtimeBundle(
    val payloads: List<CitoRealtimePayload> = emptyList(),
    val lastSocketMessageAtEpochMs: Long = 0L,
    val lastDomainDataAtEpochMs: Long = 0L
) {
    fun acceptSocket(message: JSONObject): CitoRealtimeBundle {
        val payload = CitoRealtimeClassifier.classify(message, CitoRealtimeOrigin.WEBSOCKET)
        val domainDataTime = if (payload.kind in setOf(CitoRealtimeKind.READY, CitoRealtimeKind.HEARTBEAT)) {
            lastDomainDataAtEpochMs
        } else {
            maxOf(lastDomainDataAtEpochMs, payload.receivedAtEpochMs)
        }
        return copy(
            payloads = mergePayloads(payloads + payload),
            lastSocketMessageAtEpochMs = payload.receivedAtEpochMs,
            lastDomainDataAtEpochMs = domainDataTime
        )
    }

    fun merge(other: CitoRealtimeBundle): CitoRealtimeBundle = copy(
        payloads = mergePayloads(payloads + other.payloads),
        lastSocketMessageAtEpochMs = maxOf(lastSocketMessageAtEpochMs, other.lastSocketMessageAtEpochMs),
        lastDomainDataAtEpochMs = maxOf(lastDomainDataAtEpochMs, other.lastDomainDataAtEpochMs)
    )

    fun bestBoardFor(gameId: String): JSONObject? = payloads
        .asSequence()
        .filter { it.kind == CitoRealtimeKind.BOARD || it.kind == CitoRealtimeKind.UNKNOWN }
        .filter { gameId.isBlank() || it.gameId.isBlank() || it.gameId == gameId }
        .maxWithOrNull(compareBy<CitoRealtimePayload> { it.orderingEpochMs }.thenBy { it.sequence })
        ?.let { payload -> CitoRealtimeClassifier.extractBoard(payload.payload, payload.gameId.ifBlank { gameId }) }

    fun supplementsFor(gameId: String): List<JSONObject> = payloads
        .asSequence()
        .filter { gameId.isBlank() || it.gameId.isBlank() || it.gameId == gameId }
        .filter {
            it.kind in setOf(
                CitoRealtimeKind.BOARD,
                CitoRealtimeKind.MAP,
                CitoRealtimeKind.DETAILS,
                CitoRealtimeKind.EVENTS
            )
        }
        .sortedWith(compareBy<CitoRealtimePayload> { it.orderingEpochMs }.thenBy { it.sequence })
        .map { it.payload }
        .toList()

    fun socketPayloadsAfter(sequence: Long, gameId: String): List<CitoRealtimePayload> = payloads
        .asSequence()
        .filter { it.origin == CitoRealtimeOrigin.WEBSOCKET && it.sequence > sequence }
        .filter { gameId.isBlank() || it.gameId.isBlank() || it.gameId == gameId }
        .sortedBy { it.sequence }
        .toList()

    private fun mergePayloads(rows: List<CitoRealtimePayload>): List<CitoRealtimePayload> {
        val eventKinds = setOf(CitoRealtimeKind.EVENTS, CitoRealtimeKind.DRAFT)
        val events = rows
            .asSequence()
            .filter { it.kind in eventKinds }
            .distinctBy { "${it.origin}|${it.gameId}|${it.providerEpochMs}|${it.payload}" }
            .sortedBy { it.sequence }
            .takeLast(48)
            .toList()
        val states = rows
            .asSequence()
            .filter { it.kind !in eventKinds }
            .groupBy { Triple(it.kind, it.gameId, it.origin) }
            .mapNotNull { (_, grouped) ->
                grouped.maxWithOrNull(compareBy<CitoRealtimePayload> { it.orderingEpochMs }.thenBy { it.sequence })
            }
        return (states + events)
            .sortedBy { it.sequence }
            .takeLast(64)
    }
}

internal object CitoRealtimeClassifier {
    fun classify(message: JSONObject, origin: CitoRealtimeOrigin): CitoRealtimePayload {
        val now = System.currentTimeMillis()
        val type = firstString(message, "type", "event", "kind", "channel").lowercase()
        val data = unwrap(message)
        val gameId = firstString(data, "gameId", "game_id", "id").ifBlank {
            firstString(message, "gameId", "game_id")
        }
        val kind = when {
            type == "ready" || type.endsWith(".ready") -> CitoRealtimeKind.READY
            type.contains("ping") || type.contains("pong") || type.contains("heartbeat") -> CitoRealtimeKind.HEARTBEAT
            type.contains("pick_locked") || type.contains("ban_locked") || type.contains("draft") -> CitoRealtimeKind.DRAFT
            type.contains("player_down") || type.contains("kill") || type.contains("objective") ||
                type.contains("tower_destroyed") || type.contains("gold_swing") || type.contains("map_delta") ||
                type.contains("event") -> CitoRealtimeKind.EVENTS
            type.contains("series") || data.has("currentGameId") -> CitoRealtimeKind.SERIES
            type.contains("map") || hasHealthPayload(data) -> CitoRealtimeKind.MAP
            type.contains("detail") || type.contains("item") || type.contains("ward") || type.contains("damage") -> CitoRealtimeKind.DETAILS
            hasBoardPayload(data) -> CitoRealtimeKind.BOARD
            else -> CitoRealtimeKind.UNKNOWN
        }
        return CitoRealtimePayload(
            kind = kind,
            gameId = gameId,
            payload = message,
            receivedAtEpochMs = now,
            origin = origin,
            sequence = CitoRealtimeOrder.next(),
            providerEpochMs = providerEpochMs(message)
        )
    }

    fun providerEpochMs(root: JSONObject): Long? {
        val candidates = listOf(root, root.optJSONObject("data"), root.optJSONObject("payload"), root.optJSONObject("state"))
            .filterNotNull()
        val keys = listOf("frameTimestamp", "timestamp", "eventTimestamp", "updatedAt", "createdAt", "ts")
        for (obj in candidates) {
            for (key in keys) {
                if (!obj.has(key) || obj.isNull(key)) continue
                when (val raw = obj.opt(key)) {
                    is Number -> {
                        val value = raw.toLong()
                        return if (value in 1_000_000_000L..9_999_999_999L) value * 1000L else value
                    }
                    is String -> {
                        raw.toLongOrNull()?.let { value ->
                            return if (value in 1_000_000_000L..9_999_999_999L) value * 1000L else value
                        }
                        runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()?.let { return it }
                    }
                }
            }
        }
        return null
    }

    fun extractBoard(root: JSONObject, gameIdHint: String): JSONObject {
        val found = findBoard(root, 0) ?: return root
        if (gameIdHint.isBlank() || found.has("gameId") || found.optJSONObject("data") != null) return found
        return JSONObject(found.toString()).apply { put("gameId", gameIdHint) }
    }

    private fun findBoard(obj: JSONObject, depth: Int): JSONObject? {
        if (depth > 4) return null
        if (hasBoardPayload(obj)) return obj
        val keys = obj.keys()
        while (keys.hasNext()) {
            when (val child = obj.opt(keys.next())) {
                is JSONObject -> findBoard(child, depth + 1)?.let { return it }
                is JSONArray -> {
                    for (i in 0 until child.length()) {
                        child.optJSONObject(i)?.let { nested ->
                            findBoard(nested, depth + 1)?.let { return it }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun unwrap(root: JSONObject): JSONObject =
        root.optJSONObject("data") ?: root.optJSONObject("payload") ?: root.optJSONObject("state") ?: root

    private fun hasBoardPayload(data: JSONObject): Boolean =
        data.has("blueTeam") || data.has("redTeam") || data.has("players") || data.has("board")

    private fun hasHealthPayload(data: JSONObject): Boolean {
        if (data.has("currentHealth") || data.has("health") || data.has("alive")) return true
        val players = data.optJSONArray("players") ?: return false
        for (i in 0 until players.length()) {
            val row = players.optJSONObject(i) ?: continue
            if (row.has("currentHealth") || row.has("health") || row.has("alive")) return true
        }
        return false
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.opt(key)
            if (value is String && value.isNotBlank()) return value
            if (value != null && value != JSONObject.NULL && value !is JSONObject && value !is JSONArray) {
                return value.toString()
            }
        }
        return ""
    }
}

internal class CitoRealtimeSocket {
    fun messages(): Flow<JSONObject> = callbackFlow {
        val key = CitoApiConfig.apiKey()
        if (key == null) {
            close()
            return@callbackFlow
        }

        val client = CitoHttpClient.websocketClient().newBuilder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(CitoApiConfig.LIVE_WEBSOCKET_URL)
            .header(CitoApiConfig.API_KEY_HEADER, key)
            .header("User-Agent", "RiftLab-Android/dev.80")
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                CitoProviderState.update("Cito WebSocket · realtime fabric 已连接")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val payload = runCatching { JSONObject(text) }.getOrNull() ?: return
                trySend(payload)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                CitoProviderState.update("Cito WebSocket · 断开，REST 进入重连兜底")
                close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                CitoProviderState.update("Cito WebSocket · 已关闭，等待自动重连")
                close()
            }
        }
        val socket = client.newWebSocket(request, listener)
        awaitClose { socket.cancel() }
    }
}

/** Adds Cito-only combat context without replacing canonical scoreboard truth from Riot/Tencent. */
internal object CitoLiveFusion {
    fun alignVerifiedSides(
        snapshot: LiveSnapshot,
        boardPayload: JSONObject?,
        target: ScheduledEsportsMatch,
        gameNumber: Int
    ): LiveSnapshot {
        SideSelectionStore.state.value.records.firstOrNull { it.game == gameNumber }?.let { record ->
            return snapshot.copy(blue = record.blueTeam, red = record.redTeam)
        }

        val board = boardPayload?.let { CitoRealtimeClassifier.extractBoard(it, snapshot.gameId) }
        val blueObj = board?.optJSONObject("blueTeam") ?: board?.optJSONObject("blue")
        val redObj = board?.optJSONObject("redTeam") ?: board?.optJSONObject("red")
        val blueDirect = resolveExplicitTeam(blueObj, snapshot.bluePlayers, target)
        val redDirect = resolveExplicitTeam(redObj, snapshot.redPlayers, target)
        return snapshot.copy(
            blue = blueDirect.ifBlank { "BLUE" },
            red = redDirect.ifBlank { "RED" }
        )
    }

    fun enrichSnapshot(
        snapshot: LiveSnapshot,
        payloads: List<JSONObject>,
        capturedAtEpochMs: Long
    ): LiveSnapshot {
        if (payloads.isEmpty()) return snapshot
        val supplements = payloads.flatMap(::collectPlayerRows)
        if (supplements.isEmpty()) {
            return snapshot.copy(supplementUpdatedAtEpochMs = maxOf(snapshot.supplementUpdatedAtEpochMs, capturedAtEpochMs))
        }
        return snapshot.copy(
            bluePlayers = mergePlayers(snapshot.bluePlayers, supplements, "BLUE"),
            redPlayers = mergePlayers(snapshot.redPlayers, supplements, "RED"),
            supplementUpdatedAtEpochMs = maxOf(snapshot.supplementUpdatedAtEpochMs, capturedAtEpochMs)
        )
    }

    fun mergeSupplement(primary: LiveSnapshot, cito: LiveSnapshot): LiveSnapshot {
        if (primary.game != cito.game) return primary
        if (!sameTeams(primary, cito)) return primary
        return primary.copy(
            bluePlayers = mergeSnapshotPlayers(primary.bluePlayers, cito.bluePlayers),
            redPlayers = mergeSnapshotPlayers(primary.redPlayers, cito.redPlayers),
            supplementUpdatedAtEpochMs = maxOf(primary.supplementUpdatedAtEpochMs, cito.supplementUpdatedAtEpochMs),
            source = if (primary.source.contains("Cito", ignoreCase = true)) {
                primary.source
            } else {
                "${primary.source} · + Cito WSS combat supplement"
            }
        )
    }

    private fun resolveExplicitTeam(
        teamObj: JSONObject?,
        players: List<LivePlayerSnapshot>,
        target: ScheduledEsportsMatch
    ): String {
        if (teamObj != null) {
            val direct = firstString(teamObj, "code", "name", "teamName", "acronym")
            if (direct.isNotBlank()) return direct
            val id = firstString(teamObj, "id", "teamId", "team_id", "esportsTeamId")
            target.teams.firstOrNull { id.isNotBlank() && it.id == id }?.let { team ->
                return team.code.ifBlank { team.name }
            }
        }
        val playerTeamIds = players.map { it.teamId }.filter { it.isNotBlank() }.distinct()
        if (playerTeamIds.size == 1) {
            target.teams.firstOrNull { it.id == playerTeamIds.single() }?.let { team ->
                return team.code.ifBlank { team.name }
            }
        }
        return ""
    }

    private fun mergeSnapshotPlayers(
        basePlayers: List<LivePlayerSnapshot>,
        supplementPlayers: List<LivePlayerSnapshot>
    ): List<LivePlayerSnapshot> {
        if (basePlayers.isEmpty()) return supplementPlayers
        if (supplementPlayers.isEmpty()) return basePlayers
        return basePlayers.map { base ->
            val extra = supplementPlayers.firstOrNull { matches(base, it) } ?: return@map base
            base.copy(
                alive = extra.alive ?: base.alive,
                currentHealth = extra.currentHealth ?: base.currentHealth,
                maxHealth = extra.maxHealth ?: base.maxHealth,
                items = extra.items.ifEmpty { base.items },
                killParticipation = extra.killParticipation ?: base.killParticipation,
                damageShare = extra.damageShare ?: base.damageShare,
                wardsPlaced = extra.wardsPlaced ?: base.wardsPlaced,
                wardsKilled = extra.wardsKilled ?: base.wardsKilled
            )
        }
    }

    private fun mergePlayers(
        basePlayers: List<LivePlayerSnapshot>,
        supplements: List<PlayerSupplement>,
        side: String
    ): List<LivePlayerSnapshot> {
        if (basePlayers.isEmpty()) return basePlayers
        return basePlayers.map { base ->
            val extra = supplements
                .asSequence()
                .filter { it.side.isBlank() || it.side == side }
                .firstOrNull { supplementMatches(base, it) }
                ?: return@map base
            base.copy(
                alive = extra.alive ?: base.alive,
                currentHealth = extra.currentHealth ?: base.currentHealth,
                maxHealth = extra.maxHealth ?: base.maxHealth,
                items = extra.items.ifEmpty { base.items },
                killParticipation = extra.killParticipation ?: base.killParticipation,
                damageShare = extra.damageShare ?: base.damageShare,
                wardsPlaced = extra.wardsPlaced ?: base.wardsPlaced,
                wardsKilled = extra.wardsKilled ?: base.wardsKilled
            )
        }
    }

    private data class PlayerSupplement(
        val participantId: Int?,
        val name: String,
        val role: String,
        val side: String,
        val alive: Boolean?,
        val currentHealth: Int?,
        val maxHealth: Int?,
        val items: List<String>,
        val killParticipation: Double?,
        val damageShare: Double?,
        val wardsPlaced: Int?,
        val wardsKilled: Int?
    )

    private fun collectPlayerRows(root: JSONObject): List<PlayerSupplement> {
        val rows = mutableListOf<Pair<JSONObject, String>>()
        collectFromObject(root, "", rows, depth = 0)
        return rows.mapNotNull { (row, sideHint) -> parseSupplement(row, sideHint) }
    }

    private fun collectFromObject(
        obj: JSONObject,
        sideHint: String,
        output: MutableList<Pair<JSONObject, String>>,
        depth: Int
    ) {
        if (depth > 5) return
        val participantLike = obj.has("participantId") || obj.has("summonerName") || obj.has("playerName") ||
            obj.has("currentHealth") || obj.has("alive") || obj.has("items")
        if (participantLike) output += obj to sideHint

        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val nextSide = when (key.lowercase()) {
                "blue", "blueteam", "blueplayers" -> "BLUE"
                "red", "redteam", "redplayers" -> "RED"
                else -> sideHint
            }
            when (val value = obj.opt(key)) {
                is JSONObject -> collectFromObject(value, nextSide, output, depth + 1)
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val child = value.optJSONObject(i) ?: continue
                        collectFromObject(child, nextSide, output, depth + 1)
                    }
                }
            }
        }
    }

    private fun parseSupplement(row: JSONObject, sideHint: String): PlayerSupplement? {
        val id = nullableInt(row, "participantId", "participant_id", "playerId")
        val name = firstString(row, "summonerName", "playerName", "name")
        val role = normalizeRole(firstString(row, "role", "position"))
        val side = normalizeSide(firstString(row, "side", "team")).ifBlank { sideHint }
        if (id == null && name.isBlank() && role.isBlank()) return null

        val currentHealth = nullableInt(row, "currentHealth", "health", "hp")
        val maxHealth = nullableInt(row, "maxHealth", "maximumHealth", "maxHp")
        val explicitAlive = nullableBoolean(row, "alive", "isAlive")
        val alive = explicitAlive ?: currentHealth?.let { it > 0 }
        return PlayerSupplement(
            participantId = id,
            name = name,
            role = role,
            side = side,
            alive = alive,
            currentHealth = currentHealth,
            maxHealth = maxHealth,
            items = parseItems(row.opt("items")),
            killParticipation = nullableDouble(row, "killParticipation", "kill_participation", "kp"),
            damageShare = nullableDouble(row, "championDamageShare", "damageShare", "damage_share"),
            wardsPlaced = nullableInt(row, "wardsPlaced", "wards_placed", "wards"),
            wardsKilled = nullableInt(row, "wardsKilled", "wards_killed", "wardsCleared")
        )
    }

    private fun supplementMatches(base: LivePlayerSnapshot, extra: PlayerSupplement): Boolean {
        if (extra.participantId != null && base.participantId > 0 && extra.participantId == base.participantId) return true
        if (extra.name.isNotBlank() && token(extra.name) == token(base.summonerName)) return true
        return extra.role.isNotBlank() && extra.role == normalizeRole(base.role)
    }

    private fun matches(a: LivePlayerSnapshot, b: LivePlayerSnapshot): Boolean {
        if (a.participantId > 0 && b.participantId > 0 && a.participantId == b.participantId) return true
        if (a.summonerName.isNotBlank() && b.summonerName.isNotBlank() && token(a.summonerName) == token(b.summonerName)) return true
        return normalizeRole(a.role).isNotBlank() && normalizeRole(a.role) == normalizeRole(b.role)
    }

    private fun sameTeams(a: LiveSnapshot, b: LiveSnapshot): Boolean {
        val aTeams = setOf(token(a.blue), token(a.red)).filter { it.isNotBlank() }.toSet()
        val bTeams = setOf(token(b.blue), token(b.red)).filter { it.isNotBlank() }.toSet()
        return aTeams.size == 2 && aTeams == bTeams
    }

    private fun parseItems(raw: Any?): List<String> = when (raw) {
        is JSONArray -> buildList {
            for (i in 0 until raw.length()) {
                when (val value = raw.opt(i)) {
                    is Number -> add(value.toInt().toString())
                    is String -> if (value.isNotBlank()) add(value)
                    is JSONObject -> firstString(value, "itemId", "id", "item").takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
        is String -> raw.split(',', '|', ';').map(String::trim).filter(String::isNotBlank)
        else -> emptyList()
    }

    private fun nullableInt(obj: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            when (val raw = obj.opt(key)) {
                is Number -> return raw.toInt()
                is String -> raw.toDoubleOrNull()?.toInt()?.let { return it }
            }
        }
        return null
    }

    private fun nullableDouble(obj: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            when (val raw = obj.opt(key)) {
                is Number -> return raw.toDouble()
                is String -> raw.removeSuffix("%").toDoubleOrNull()?.let { value ->
                    return if (raw.endsWith("%")) value / 100.0 else value
                }
            }
        }
        return null
    }

    private fun nullableBoolean(obj: JSONObject, vararg keys: String): Boolean? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            when (val raw = obj.opt(key)) {
                is Boolean -> return raw
                is Number -> return raw.toInt() != 0
                is String -> when (raw.trim().lowercase()) {
                    "true", "1", "alive", "up" -> return true
                    "false", "0", "dead", "down" -> return false
                }
            }
        }
        return null
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val raw = obj.opt(key)
            if (raw is String && raw.isNotBlank()) return raw
            if (raw != null && raw != JSONObject.NULL && raw !is JSONObject && raw !is JSONArray) {
                val text = raw.toString()
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }

    private fun normalizeSide(raw: String): String = when (token(raw)) {
        "BLUE", "LEFT", "TEAMA" -> "BLUE"
        "RED", "RIGHT", "TEAMB" -> "RED"
        else -> ""
    }

    private fun normalizeRole(raw: String): String = when (token(raw)) {
        "TOP", "TOPLANE" -> "TOP"
        "JUG", "JUNGLE", "JUNGLER" -> "JUG"
        "MID", "MIDDLE", "MIDLANE" -> "MID"
        "BOT", "BOTTOM", "ADC", "AD", "CARRY" -> "BOT"
        "SUP", "SUPPORT" -> "SUP"
        else -> raw.uppercase()
    }

    private fun token(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
}
