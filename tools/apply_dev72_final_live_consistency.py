from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"missing patch anchor in {path}: {old[:120]!r}")
    if text.count(old) != 1:
        raise SystemExit(f"patch anchor not unique in {path}: {text.count(old)} matches")
    p.write_text(text.replace(old, new, 1))


# 1) Provider-neutral player/team identity + stable schedule target identity.
replace_once(
    "app/src/main/java/com/riftlab/app/data/Models.kt",
    '''data class LivePlayerSnapshot(
    val participantId: Int,
    val role: String,
    val summonerName: String,
    val championId: String,
    val level: Int,
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    val creepScore: Int,
    val gold: Int
)''',
    '''data class LivePlayerSnapshot(
    val participantId: Int,
    val role: String,
    val summonerName: String,
    val championId: String,
    val level: Int,
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    val creepScore: Int,
    val gold: Int,
    val teamId: String = "",
    val side: String = ""
)'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/Models.kt",
    '''    val source: String = "unknown",
    val gameId: String = ""
) {''',
    '''    val source: String = "unknown",
    val gameId: String = "",
    // Stable schedule-series identity. Provider-local gameId values must never define a real game.
    val targetKey: String = ""
) {'''
)

# 2) The global router stamps every chosen frame with the current schedule identity.
replace_once(
    "app/src/main/java/com/riftlab/app/data/LplOfficialLiveDataSource.kt",
    '''                                    chosen = candidate.copy(
                                        source = "${candidate.source} · Router=${best.name}"
                                    )''',
    '''                                    chosen = candidate.copy(
                                        source = "${candidate.source} · Router=${best.name}",
                                        targetKey = LiveMatchTargetRegistry.key(LiveMatchTargetRegistry.snapshot())
                                    )'''
)

# 3) Provider failover is not a game transition. Only Gx or schedule target changes complete a timeline.
replace_once(
    "app/src/main/java/com/riftlab/app/data/MatchTimelineCapture.kt",
    '''                    val switchedGame = previous != null && (
                        previous.game != snapshot.game ||
                            (previous.gameId.isNotBlank() && snapshot.gameId.isNotBlank() && previous.gameId != snapshot.gameId)
                        )''',
    '''                    val switchedGame = previous != null && (
                        previous.game != snapshot.game ||
                            (
                                previous.targetKey.isNotBlank() && snapshot.targetKey.isNotBlank() &&
                                    previous.targetKey != snapshot.targetKey
                                )
                        )'''
)

# 4) Timeline identity is schedule target + Gx. Late attachment is CAPTURE START, not a fake GAME START.
replace_once(
    "app/src/main/java/com/riftlab/app/data/MatchTimelineStore.kt",
    '''            val derived = if (previous == null) {
                listOf(
                    MatchTimelineEvent(
                        seconds = 0,
                        type = TimelineEventType.GAME_START,
                        title = "GAME START",
                        detail = "G${snapshot.game} · ${snapshot.blue} vs ${snapshot.red}",
                        evidence = TimelineEventEvidence.LOCAL_CAPTURE,
                        source = snapshot.source
                    )
                )
            } else {
                deriveEvents(previous, snapshot)
            }''',
    '''            val derived = if (previous == null) {
                val nearOpening = snapshot.elapsedSeconds in 0..30
                listOf(
                    MatchTimelineEvent(
                        seconds = if (nearOpening) 0 else snapshot.elapsedSeconds,
                        type = TimelineEventType.GAME_START,
                        title = if (nearOpening) "GAME START" else "CAPTURE START",
                        detail = if (nearOpening) {
                            "G${snapshot.game} · ${snapshot.blue} vs ${snapshot.red} · RiftLab 在开局窗口接入"
                        } else {
                            "RiftLab 于 ${formatClock(snapshot.elapsedSeconds)} 接入本局实时流；这是本机采集起点，不代表比赛在此刻开始。"
                        },
                        evidence = TimelineEventEvidence.LOCAL_CAPTURE,
                        source = snapshot.source
                    )
                )
            } else {
                deriveEvents(previous, snapshot)
            }'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/MatchTimelineStore.kt",
    '''    private fun timelineKey(snapshot: LiveSnapshot): String = snapshot.gameId.trim().ifBlank {
        "${token(snapshot.blue)}_${token(snapshot.red)}_G${snapshot.game}"
    }''',
    '''    private fun timelineKey(snapshot: LiveSnapshot): String =
        snapshot.targetKey.trim().takeIf { it.isNotBlank() }?.let { "$it:G${snapshot.game}" }
            ?: snapshot.gameId.trim().ifBlank {
                "${token(snapshot.blue)}_${token(snapshot.red)}_G${snapshot.game}"
            }'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/MatchTimelineStore.kt",
    '''        .put("source", snapshot.source)
        .put("gameId", snapshot.gameId)
        .put("bluePlayers", playersToJson(snapshot.bluePlayers))''',
    '''        .put("source", snapshot.source)
        .put("gameId", snapshot.gameId)
        .put("targetKey", snapshot.targetKey)
        .put("bluePlayers", playersToJson(snapshot.bluePlayers))'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/MatchTimelineStore.kt",
    '''        source = root.optString("source"),
        gameId = root.optString("gameId")
    )''',
    '''        source = root.optString("source"),
        gameId = root.optString("gameId"),
        targetKey = root.optString("targetKey")
    )'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/MatchTimelineStore.kt",
    '''                    .put("creepScore", player.creepScore)
                    .put("gold", player.gold)''',
    '''                    .put("creepScore", player.creepScore)
                    .put("gold", player.gold)
                    .put("teamId", player.teamId)
                    .put("side", player.side)'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/MatchTimelineStore.kt",
    '''                    creepScore = player.optInt("creepScore"),
                    gold = player.optInt("gold")
                )''',
    '''                    creepScore = player.optInt("creepScore"),
                    gold = player.optInt("gold"),
                    teamId = player.optString("teamId"),
                    side = player.optString("side")
                )'''
)

# 5) Riot LiveStats is the global official source: preserve side/team metadata in every player row.
replace_once(
    "app/src/main/java/com/riftlab/app/data/LolEsportsApiClient.kt",
    '''            bluePlayers = parsePlayers(blueMeta, blue),
            redPlayers = parsePlayers(redMeta, red),''',
    '''            bluePlayers = parsePlayers(blueMeta, blue, blueTeamId, "BLUE"),
            redPlayers = parsePlayers(redMeta, red, redTeamId, "RED"),'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/LolEsportsApiClient.kt",
    '''    private fun parsePlayers(metadata: JSONObject, frameTeam: JSONObject): List<LivePlayerSnapshot> {''',
    '''    private fun parsePlayers(
        metadata: JSONObject,
        frameTeam: JSONObject,
        teamId: String,
        side: String
    ): List<LivePlayerSnapshot> {'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/LolEsportsApiClient.kt",
    '''                        assists = player.optInt("assists", 0),
                        creepScore = player.optInt("creepScore", 0),
                        gold = player.optInt("totalGold", 0)
                    )''',
    '''                        assists = player.optInt("assists", 0),
                        creepScore = player.optInt("creepScore", 0),
                        gold = player.optInt("totalGold", 0),
                        teamId = teamId,
                        side = side
                    )'''
)

# Do not degrade to an LPL-only schedule when Riot league discovery fails. Other global providers
# remain available and a later refresh can recover Riot discovery.
replace_once(
    "app/src/main/java/com/riftlab/app/data/LolEsportsApiClient.kt",
    '''        return discovered.ifEmpty {
            listOf(TrackedLeagueRef(LolEsportsConfig.LPL_LEAGUE_ID, "lpl", "LPL"))
        }''',
    '''        return discovered'''
)

# 6) Cito is global: keep explicit team/side when supplied, otherwise use participant-side fallback.
replace_once(
    "app/src/main/java/com/riftlab/app/data/CitoDataPlane.kt",
    '''        for (i in 0 until playerArray.length()) {
            val p = playerArray.optJSONObject(i) ?: continue
            val player = LivePlayerSnapshot(''',
    '''        for (i in 0 until playerArray.length()) {
            val p = playerArray.optJSONObject(i) ?: continue
            val rawSide = firstString(p, "side", "team").lowercase()
            val side = when (rawSide) {
                "blue", "left", "teama" -> "BLUE"
                "red", "right", "teamb" -> "RED"
                else -> ""
            }
            val player = LivePlayerSnapshot('''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/CitoDataPlane.kt",
    '''                assists = firstInt(p, "assists"),
                creepScore = firstInt(p, "creepScore", "cs"),
                gold = firstInt(p, "totalGold", "gold")
            )
            when (firstString(p, "side", "team").lowercase()) {
                "blue", "left", "teama" -> bluePlayers += player
                "red", "right", "teamb" -> redPlayers += player
                else -> if (player.participantId <= 5) bluePlayers += player else redPlayers += player
            }''',
    '''                assists = firstInt(p, "assists"),
                creepScore = firstInt(p, "creepScore", "cs"),
                gold = firstInt(p, "totalGold", "gold"),
                teamId = firstString(p, "teamId", "team_id", "esportsTeamId"),
                side = side
            )
            when (player.side) {
                "BLUE" -> bluePlayers += player
                "RED" -> redPlayers += player
                else -> if (player.participantId <= 5) {
                    bluePlayers += player.copy(side = "BLUE")
                } else {
                    redPlayers += player.copy(side = "RED")
                }
            }'''
)

# 7) LPL Comm is a regional supplement, but must obey the same provider-neutral model.
replace_once(
    "app/src/main/java/com/riftlab/app/data/LplCommRealtimeDataSource.kt",
    '''                assists = intAnyDeep(p, "assists", "assist"),
                creepScore = intAnyDeep(p, "minionKilled", "creepScore", "cs", "creepsKilled"),
                gold = intAnyDeep(p, "golds", "gold", "totalGold")
            )''',
    '''                assists = intAnyDeep(p, "assists", "assist"),
                creepScore = intAnyDeep(p, "minionKilled", "creepScore", "cs", "creepsKilled"),
                gold = intAnyDeep(p, "golds", "gold", "totalGold"),
                teamId = intAny(p, "teamId", "teamID", "team_id").takeIf { it > 0 }?.toString().orEmpty(),
                side = normalizeSide(stringAny(p, "side", "camp", "teamSide", "color"))
            )'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/LplCommRealtimeDataSource.kt",
    '''    // Player-team metadata is not part of LivePlayerSnapshot yet. These helpers intentionally
    // return neutral values; once a player model carries team/side, the router can split exactly.
    // Until then, playerInfo is optional and team-level realtime is still authoritative.
    private fun playerTeamId(player: LivePlayerSnapshot): Int = 0
    private fun playerSide(player: LivePlayerSnapshot): String = ""''',
    '''    private fun playerTeamId(player: LivePlayerSnapshot): Int = player.teamId.toIntOrNull() ?: 0
    private fun playerSide(player: LivePlayerSnapshot): String = player.side'''
)

print("dev72 final global live consistency patch applied")
