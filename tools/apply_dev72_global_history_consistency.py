from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"missing anchor in {path}: {old[:120]!r}")
    if text.count(old) != 1:
        raise SystemExit(f"non-unique anchor in {path}: {text.count(old)}")
    p.write_text(text.replace(old, new, 1))


path = "app/src/main/java/com/riftlab/app/data/RiotLiveStatsHistoryResolver.kt"
replace_once(
    path,
    '''    fun ensure(match: ScheduledEsportsMatch, game: Int) {
        if (game <= 0 || match.eventId.isBlank()) return
        val phase = MatchSessionStore.schedulePhase(match)''',
    '''    fun ensure(match: ScheduledEsportsMatch, game: Int) {
        if (game <= 0 || match.eventId.isBlank()) return
        if (match.eventId.startsWith("provider:") || match.leagueId.startsWith("rft-event:")) {
            val key = stateKey(match, game)
            publish(
                RiotHistoryBackfillState(
                    key = key,
                    game = game,
                    phase = RiotHistoryPhase.UNAVAILABLE,
                    message = "该国际赛事使用非 Riot Event ID；RiftLab 不伪绑定 Riot LiveStats 历史流"
                )
            )
            return
        }
        val phase = MatchSessionStore.schedulePhase(match)'''
)
replace_once(
    path,
    '''            bluePlayers = parsePlayers(blueMeta, blueFrame),
            redPlayers = parsePlayers(redMeta, redFrame),
            source = "Riot LoL Esports LiveStats · verified window",
            gameId = gameId
        )''',
    '''            bluePlayers = parsePlayers(blueMeta, blueFrame, blueId, "BLUE"),
            redPlayers = parsePlayers(redMeta, redFrame, redId, "RED"),
            source = "Riot LoL Esports LiveStats · verified window",
            gameId = gameId,
            targetKey = LiveMatchTargetRegistry.key(match)
        )'''
)
replace_once(
    path,
    '''    private fun parsePlayers(metadata: JSONObject, teamFrame: JSONObject): List<LivePlayerSnapshot> {''',
    '''    private fun parsePlayers(
        metadata: JSONObject,
        teamFrame: JSONObject,
        teamId: String,
        side: String
    ): List<LivePlayerSnapshot> {'''
)
replace_once(
    path,
    '''                        assists = row.optInt("assists", 0),
                        creepScore = row.optInt("creepScore", 0),
                        gold = row.optInt("totalGold", 0)
                    )''',
    '''                        assists = row.optInt("assists", 0),
                        creepScore = row.optInt("creepScore", 0),
                        gold = row.optInt("totalGold", 0),
                        teamId = teamId,
                        side = side
                    )'''
)

archive = "app/src/main/java/com/riftlab/app/data/MatchLifecycleArchive.kt"
replace_once(
    archive,
    '''        .put("bestOf", match.bestOf)
        .put("teams", JSONArray().apply { match.teams.forEach { put(teamToJson(it)) } })''',
    '''        .put("bestOf", match.bestOf)
        .put("leagueId", match.leagueId)
        .put("leagueSlug", match.leagueSlug)
        .put("teams", JSONArray().apply { match.teams.forEach { put(teamToJson(it)) } })'''
)
replace_once(
    archive,
    '''            bestOf = root.optInt("bestOf"),
            teams = teams
        )''',
    '''            bestOf = root.optInt("bestOf"),
            teams = teams,
            leagueId = root.optString("leagueId"),
            leagueSlug = root.optString("leagueSlug")
        )'''
)
replace_once(
    archive,
    '''        .put("source", snapshot.source)
        .put("gameId", snapshot.gameId)
        .put("bluePlayers", playersToJson(snapshot.bluePlayers))''',
    '''        .put("source", snapshot.source)
        .put("gameId", snapshot.gameId)
        .put("targetKey", snapshot.targetKey)
        .put("bluePlayers", playersToJson(snapshot.bluePlayers))'''
)
replace_once(
    archive,
    '''        source = root.optString("source"),
        gameId = root.optString("gameId")
    )''',
    '''        source = root.optString("source"),
        gameId = root.optString("gameId"),
        targetKey = root.optString("targetKey")
    )'''
)
replace_once(
    archive,
    '''                    .put("creepScore", player.creepScore)
                    .put("gold", player.gold)''',
    '''                    .put("creepScore", player.creepScore)
                    .put("gold", player.gold)
                    .put("teamId", player.teamId)
                    .put("side", player.side)'''
)
replace_once(
    archive,
    '''                    creepScore = root.optInt("creepScore"),
                    gold = root.optInt("gold")
                )''',
    '''                    creepScore = root.optInt("creepScore"),
                    gold = root.optInt("gold"),
                    teamId = root.optString("teamId"),
                    side = root.optString("side")
                )'''
)

print("dev72 global Riot history and lifecycle archive consistency applied")
