from pathlib import Path


def replace_exact(path, old, new, expected=1):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} matches, got {count}")
    p.write_text(text.replace(old, new))


cito = "app/src/main/java/com/riftlab/app/data/CitoRealtimeLiveDataSource.kt"

replace_exact(
    cito,
    """    fun bestBoardFor(gameId: String): JSONObject? = payloads
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
""",
    """    fun bestBoardFor(gameId: String): JSONObject? {
        if (gameId.isBlank()) return null
        return payloads
            .asSequence()
            .filter { it.kind == CitoRealtimeKind.BOARD || it.kind == CitoRealtimeKind.UNKNOWN }
            .filter { it.gameId == gameId }
            .maxWithOrNull(compareBy<CitoRealtimePayload> { it.orderingEpochMs }.thenBy { it.sequence })
            ?.let { payload -> CitoRealtimeClassifier.extractBoard(payload.payload, gameId) }
    }

    fun supplementsFor(gameId: String): List<JSONObject> {
        if (gameId.isBlank()) return emptyList()
        return payloads
            .asSequence()
            .filter { it.gameId == gameId }
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
    }

    fun socketPayloadsAfter(sequence: Long, gameId: String): List<CitoRealtimePayload> {
        if (gameId.isBlank()) return emptyList()
        return payloads
            .asSequence()
            .filter { it.origin == CitoRealtimeOrigin.WEBSOCKET && it.sequence > sequence }
            .filter { it.gameId == gameId }
            .sortedBy { it.sequence }
            .toList()
    }

    fun latestSocketMessageAt(gameId: String): Long {
        if (gameId.isBlank()) return 0L
        return payloads.asSequence()
            .filter { it.origin == CitoRealtimeOrigin.WEBSOCKET && it.gameId == gameId }
            .maxOfOrNull { it.receivedAtEpochMs } ?: 0L
    }

    fun latestDomainDataAt(gameId: String): Long {
        if (gameId.isBlank()) return 0L
        return payloads.asSequence()
            .filter { it.gameId == gameId }
            .filter { it.kind !in setOf(CitoRealtimeKind.READY, CitoRealtimeKind.HEARTBEAT) }
            .maxOfOrNull { it.receivedAtEpochMs } ?: 0L
    }
""",
)

replace_exact(cito, "capturedAtEpochMs = bundle.lastDomainDataAtEpochMs", "capturedAtEpochMs = bundle.latestDomainDataAt(gameId)", expected=2)
replace_exact(
    cito,
    """                val wsFresh = bundle.lastSocketMessageAtEpochMs > 0L &&
                    now - bundle.lastSocketMessageAtEpochMs <= WS_FRESH_MS
""",
    """                val gameSocketAt = bundle.latestSocketMessageAt(gameId)
                val wsFresh = gameSocketAt > 0L && now - gameSocketAt <= WS_FRESH_MS
""",
)
replace_exact(
    cito,
    """    private fun mergePlayers(
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
""",
    """    private fun mergePlayers(
        basePlayers: List<LivePlayerSnapshot>,
        supplements: List<PlayerSupplement>,
        side: String
    ): List<LivePlayerSnapshot> {
        if (basePlayers.isEmpty()) return basePlayers
        return basePlayers.map { base ->
            var merged = base
            supplements.asSequence()
                .filter { it.side.isBlank() || it.side == side }
                .filter { supplementMatches(base, it) }
                .forEach { extra ->
                    merged = merged.copy(
                        alive = extra.alive ?: merged.alive,
                        currentHealth = extra.currentHealth ?: merged.currentHealth,
                        maxHealth = extra.maxHealth ?: merged.maxHealth,
                        items = extra.items.ifEmpty { merged.items },
                        killParticipation = extra.killParticipation ?: merged.killParticipation,
                        damageShare = extra.damageShare ?: merged.damageShare,
                        wardsPlaced = extra.wardsPlaced ?: merged.wardsPlaced,
                        wardsKilled = extra.wardsKilled ?: merged.wardsKilled
                    )
                }
            merged
        }
    }
""",
)

timeline = "app/src/main/java/com/riftlab/app/data/MatchTimelineStore.kt"
replace_exact(timeline, '.put("targetKey", snapshot.targetKey)\n        .put("bluePlayers", playersToJson(snapshot.bluePlayers))', '.put("targetKey", snapshot.targetKey)\n        .put("supplementUpdatedAtEpochMs", snapshot.supplementUpdatedAtEpochMs)\n        .put("bluePlayers", playersToJson(snapshot.bluePlayers))')
replace_exact(timeline, '        targetKey = root.optString("targetKey")\n    )', '        targetKey = root.optString("targetKey"),\n        supplementUpdatedAtEpochMs = root.optLong("supplementUpdatedAtEpochMs", 0L)\n    )')
replace_exact(
    timeline,
    """                    .put("teamId", player.teamId)
                    .put("side", player.side)
""",
    """                    .put("teamId", player.teamId)
                    .put("side", player.side)
                    .apply {
                        player.alive?.let { put("alive", it) }
                        player.currentHealth?.let { put("currentHealth", it) }
                        player.maxHealth?.let { put("maxHealth", it) }
                        if (player.items.isNotEmpty()) {
                            put("items", JSONArray().apply { player.items.forEach { item -> put(item) } })
                        }
                        player.killParticipation?.let { put("killParticipation", it) }
                        player.damageShare?.let { put("damageShare", it) }
                        player.wardsPlaced?.let { put("wardsPlaced", it) }
                        player.wardsKilled?.let { put("wardsKilled", it) }
                    }
""",
)
replace_exact(
    timeline,
    """                    teamId = player.optString("teamId"),
                    side = player.optString("side")
""",
    """                    teamId = player.optString("teamId"),
                    side = player.optString("side"),
                    alive = if (player.has("alive") && !player.isNull("alive")) player.optBoolean("alive") else null,
                    currentHealth = if (player.has("currentHealth") && !player.isNull("currentHealth")) player.optInt("currentHealth") else null,
                    maxHealth = if (player.has("maxHealth") && !player.isNull("maxHealth")) player.optInt("maxHealth") else null,
                    items = buildList {
                        val itemArray = player.optJSONArray("items") ?: JSONArray()
                        for (j in 0 until itemArray.length()) itemArray.optString(j).takeIf { it.isNotBlank() }?.let(::add)
                    },
                    killParticipation = if (player.has("killParticipation") && !player.isNull("killParticipation")) player.optDouble("killParticipation") else null,
                    damageShare = if (player.has("damageShare") && !player.isNull("damageShare")) player.optDouble("damageShare") else null,
                    wardsPlaced = if (player.has("wardsPlaced") && !player.isNull("wardsPlaced")) player.optInt("wardsPlaced") else null,
                    wardsKilled = if (player.has("wardsKilled") && !player.isNull("wardsKilled")) player.optInt("wardsKilled") else null
""",
)

lifecycle = "app/src/main/java/com/riftlab/app/data/MatchLifecycleArchive.kt"
replace_exact(lifecycle, '.put("targetKey", snapshot.targetKey)\n        .put("bluePlayers", playersToJson(snapshot.bluePlayers))', '.put("targetKey", snapshot.targetKey)\n        .put("supplementUpdatedAtEpochMs", snapshot.supplementUpdatedAtEpochMs)\n        .put("bluePlayers", playersToJson(snapshot.bluePlayers))')
replace_exact(lifecycle, '        targetKey = root.optString("targetKey")\n    )', '        targetKey = root.optString("targetKey"),\n        supplementUpdatedAtEpochMs = root.optLong("supplementUpdatedAtEpochMs", 0L)\n    )')
replace_exact(
    lifecycle,
    """                    .put("teamId", player.teamId)
                    .put("side", player.side)
""",
    """                    .put("teamId", player.teamId)
                    .put("side", player.side)
                    .apply {
                        player.alive?.let { put("alive", it) }
                        player.currentHealth?.let { put("currentHealth", it) }
                        player.maxHealth?.let { put("maxHealth", it) }
                        if (player.items.isNotEmpty()) {
                            put("items", JSONArray().apply { player.items.forEach { item -> put(item) } })
                        }
                        player.killParticipation?.let { put("killParticipation", it) }
                        player.damageShare?.let { put("damageShare", it) }
                        player.wardsPlaced?.let { put("wardsPlaced", it) }
                        player.wardsKilled?.let { put("wardsKilled", it) }
                    }
""",
)
replace_exact(
    lifecycle,
    """                    teamId = root.optString("teamId"),
                    side = root.optString("side")
""",
    """                    teamId = root.optString("teamId"),
                    side = root.optString("side"),
                    alive = if (root.has("alive") && !root.isNull("alive")) root.optBoolean("alive") else null,
                    currentHealth = if (root.has("currentHealth") && !root.isNull("currentHealth")) root.optInt("currentHealth") else null,
                    maxHealth = if (root.has("maxHealth") && !root.isNull("maxHealth")) root.optInt("maxHealth") else null,
                    items = buildList {
                        val itemArray = root.optJSONArray("items") ?: JSONArray()
                        for (j in 0 until itemArray.length()) itemArray.optString(j).takeIf { it.isNotBlank() }?.let(::add)
                    },
                    killParticipation = if (root.has("killParticipation") && !root.isNull("killParticipation")) root.optDouble("killParticipation") else null,
                    damageShare = if (root.has("damageShare") && !root.isNull("damageShare")) root.optDouble("damageShare") else null,
                    wardsPlaced = if (root.has("wardsPlaced") && !root.isNull("wardsPlaced")) root.optInt("wardsPlaced") else null,
                    wardsKilled = if (root.has("wardsKilled") && !root.isNull("wardsKilled")) root.optInt("wardsKilled") else null
""",
)

Path("DEV_CURRENT_CHANGELOG.txt").write_text(
    "dev.80：Cito realtime fabric + 新 Global/Fight HUD 手机模拟。Cito WebSocket 进入共享实时总线，Riot/Tencent/TJStats/OP.GG 等现有多源链路继续保留并按能力融合；新增 nullable 的 HP/Alive/Items/KP/Damage/Wards 战斗补充字段，缺失即未知，不用 0 冒充。实时总线按 gameId 严格隔离，未绑定比赛的 WSS payload 不进入当前比赛；freshness/REST reconciliation 改为逐 game 跟踪，避免别场流量把陈旧帧续命。Map/Details/Events 的选手补充按时间顺序逐层合并，新的战斗上下文与 supplementUpdatedAtEpochMs 同时写入 Timeline/Lifecycle 本地归档。BP 最终锁定后自动撤 HUD；新增只用于实机布局验证的 Global→Fight→Global Tactical HUD simulation，不写入真实比赛、Timeline 或 Archive。版本 1.0.0-dev.80 / versionCode 80。\n"
)
