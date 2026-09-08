from pathlib import Path

p = Path('app/src/main/java/com/riftlab/app/data/LplHistoricalPostMatchResolver.kt')
s = p.read_text(encoding='utf-8')

# Accept meaningful per-game terminal payloads whenever the series itself is known finished,
# even if TJStats omits or changes per-game status after the series ends.
old = '''        val finals = games
            .filter { game -> (game.status == 3 || seriesStatus == 3) && isMeaningful(game.teams) }
            .mapNotNull { game ->
'''
new = '''        val seriesFinished = seriesStatus == 3 || ref.matchStatus == 3 || isSeriesWon(match.bestOf, scoreA, scoreB)
        val finals = games
            .filter { game -> isMeaningful(game.teams) && (game.status == 3 || seriesFinished) }
            .mapNotNull { game ->
'''
if old in s:
    s = s.replace(old, new, 1)

old = '''            seriesFinished = seriesStatus == 3 || isSeriesWon(match.bestOf, scoreA, scoreB),
'''
new = '''            seriesFinished = seriesFinished,
'''
if old in s:
    s = s.replace(old, new, 1)

old = '''            _status.value = "POST · bmid=${ref.bmid} 已定位，但终局解析为空 · games=${games.size} · ${schemaSummary(data)}"
'''
new = '''            _status.value = "POST · bmid=${ref.bmid} 已定位，但终局解析为空 · games=${games.size} · ${parsedGameSummary(gameArray, games)} · ${schemaSummary(data)}"
'''
if old in s:
    s = s.replace(old, new, 1)

start = s.index('    private fun parseGames(infos: JSONArray): List<ParsedGame> = buildList {')
end = s.index('    private fun finalSnapshotFor(', start)
replacement = r'''    private fun parseGames(infos: JSONArray): List<ParsedGame> = buildList {
        for (i in 0 until infos.length()) {
            val info = infos.optJSONObject(i) ?: continue
            val directArray = firstArray(
                info,
                "teamInfos", "teams", "teamInfoList", "gameTeamInfos", "teamList", "teamData",
                "battleTeams", "teamStats", "teamDetails"
            ) ?: findLikelyTeamArray(info)

            val parsed = mutableListOf<TeamState>()
            if (directArray != null) {
                for (j in 0 until directArray.length()) {
                    val row = directArray.optJSONObject(j) ?: continue
                    parseTeamState(row)?.let(parsed::add)
                }
            }

            // Terminal matchDetail may wrap each team in separate blue/red or A/B objects instead
            // of a 2-row teamInfos array. Recursively collect those objects when needed.
            if (parsed.map { it.teamId }.distinct().size < 2) {
                findLikelyTeamObjects(info).mapNotNull(::parseTeamState).forEach(parsed::add)
            }

            val gamePlayers = firstArray(
                info,
                "playerInfos", "players", "playerInfoList", "gamePlayerInfos", "playerList",
                "battlePlayers", "playerStats", "playerDetails"
            ) ?: findLikelyPlayerArray(info) ?: JSONArray()
            val playersByTeam = parsePlayersByTeam(gamePlayers)

            val mergedTeams = parsed
                .groupBy { it.teamId }
                .mapNotNull { (_, rows) -> rows.maxByOrNull(::teamRichness) }
                .map { team ->
                    if (team.players.isNotEmpty()) team
                    else team.copy(players = playersByTeam[team.teamId].orEmpty())
                }
                .map { team ->
                    // If terminal team totals are absent but player finals are present, derive safe
                    // additive metrics. Do not derive towers/dragons/barons from players.
                    if (team.players.isEmpty()) team else team.copy(
                        gold = team.gold.takeIf { it > 0 } ?: team.players.sumOf { it.gold },
                        kills = team.kills.takeIf { it > 0 } ?: team.players.sumOf { it.kills }
                    )
                }

            add(
                ParsedGame(
                    bo = parseBo(info, i + 1),
                    status = intAny(info, "matchStatus", "status", "gameStatus", "state", "gameState"),
                    gameTime = secondsAny(info, "gameTime", "time", "elapsedSeconds", "gameDuration", "duration"),
                    blueTeamId = intAny(info, "blueTeam", "blueTeamId", "blueId", "blueTeamID", "blueIdNum"),
                    teams = mergedTeams
                )
            )
        }
    }

    private fun parseTeamState(raw: JSONObject): TeamState? {
        val id = intAny(raw, "teamId", "teamID", "team_id", "id").takeIf { it > 0 }
            ?: deepTeamId(raw)
        if (id <= 0) return null

        val playerArray = firstArray(
            raw,
            "playerInfos", "players", "playerInfoList", "gamePlayerInfos", "playerList",
            "battlePlayers", "playerStats", "playerDetails"
        ) ?: findLikelyPlayerArray(raw) ?: JSONArray()
        val players = parsePlayers(playerArray)

        val gold = teamMetric(raw, "golds", "gold", "totalGold", "teamGold", "goldAmount", "total_gold")
        val kills = teamMetric(raw, "kills", "kill", "totalKills", "killAmount", "killCount")
        val towers = teamMetric(raw, "turretAmount", "towers", "tower", "towerAmount", "turretCount", "towerCount")
        val dragons = teamMetric(raw, "dragonAmount", "dragons", "dragon", "dragonCount")
        val barons = teamMetric(raw, "baronAmount", "barons", "baron", "baronCount", "nashorCount")

        return TeamState(
            teamId = id,
            gold = gold.takeIf { it > 0 } ?: players.sumOf { it.gold },
            kills = kills.takeIf { it > 0 } ?: players.sumOf { it.kills },
            towers = towers,
            dragons = dragons,
            barons = barons,
            players = players
        )
    }

    private fun teamMetric(raw: JSONObject, vararg keys: String): Int {
        intAny(raw, *keys).takeIf { it != 0 }?.let { return it }
        for (container in listOf("battleDetail", "stats", "teamStats", "detail", "otherDetail", "gameData", "teamData", "battleData")) {
            val obj = raw.optJSONObject(container) ?: continue
            intAny(obj, *keys).takeIf { it != 0 }?.let { return it }
        }
        return 0
    }

    private fun teamRichness(team: TeamState): Int =
        (if (team.gold > 0) 4 else 0) +
            (if (team.kills > 0) 2 else 0) +
            (if (team.towers > 0 || team.dragons > 0 || team.barons > 0) 2 else 0) +
            team.players.size

    private fun firstArray(obj: JSONObject, vararg keys: String): JSONArray? {
        for (key in keys) {
            val arr = obj.optJSONArray(key)
            if (arr != null && arr.length() > 0) return arr
        }
        return null
    }

    private fun findLikelyGameArray(root: JSONObject): JSONArray? {
        val preferred = listOf("matchInfos", "gameInfos", "games", "gameList", "singleGames", "gameInfoList")
        firstArray(root, *preferred.toTypedArray())?.let { return it }
        return findArrayRecursive(root, maxDepth = 3) { arr ->
            if (arr.length() == 0) false
            else {
                val sample = arr.optJSONObject(0) ?: return@findArrayRecursive false
                val keys = sample.keys().asSequence().toSet()
                keys.any { it in setOf("bo", "gameNo", "gameNum", "gameNumber", "gameIndex", "round", "gameStatus", "matchStatus", "gameState") } ||
                    firstArray(sample, "teamInfos", "teams", "teamInfoList", "gameTeamInfos", "teamList") != null
            }
        }
    }

    private fun findLikelyTeamArray(root: JSONObject): JSONArray? =
        findArrayRecursive(root, maxDepth = 3) { arr ->
            if (arr.length() !in 2..6) false
            else {
                var teamLike = 0
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (deepTeamId(obj) > 0) teamLike++
                }
                teamLike >= 2
            }
        }

    private fun findLikelyPlayerArray(root: JSONObject): JSONArray? =
        findArrayRecursive(root, maxDepth = 3) { arr ->
            if (arr.length() < 5) false
            else {
                var playerLike = 0
                for (i in 0 until minOf(arr.length(), 12)) {
                    val p = arr.optJSONObject(i) ?: continue
                    if (
                        intAny(p, "playerId", "playerID") > 0 ||
                        stringAny(p, "playerName", "summonerName", "name").isNotBlank() ||
                        stringAny(p, "heroId", "championId").isNotBlank()
                    ) playerLike++
                }
                playerLike >= minOf(5, arr.length())
            }
        }

    private fun findLikelyTeamObjects(root: JSONObject): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        fun walk(obj: JSONObject, depth: Int) {
            if (depth > 4) return
            val directId = intAny(obj, "teamId", "teamID", "team_id")
            val hasTeamSignal = directId > 0 && (
                teamMetric(obj, "gold", "golds", "totalGold", "kills", "totalKills", "towers", "turretAmount") != 0 ||
                    findLikelyPlayerArray(obj) != null ||
                    obj.has("playerInfos") || obj.has("players")
                )
            if (hasTeamSignal) out += obj

            val keys = obj.keys()
            while (keys.hasNext()) {
                when (val value = obj.opt(keys.next())) {
                    is JSONObject -> walk(value, depth + 1)
                    is JSONArray -> for (i in 0 until value.length()) value.optJSONObject(i)?.let { walk(it, depth + 1) }
                }
            }
        }
        walk(root, 0)
        return out
            .groupBy { deepTeamId(it) }
            .filterKeys { it > 0 }
            .values
            .mapNotNull { rows -> rows.maxByOrNull { row -> objectRichness(row) } }
    }

    private fun objectRichness(obj: JSONObject): Int {
        var score = 0
        if (intAny(obj, "teamId", "teamID", "team_id") > 0) score += 4
        if (teamMetric(obj, "gold", "golds", "totalGold") > 0) score += 4
        if (teamMetric(obj, "kills", "totalKills") > 0) score += 2
        score += (findLikelyPlayerArray(obj)?.length() ?: 0).coerceAtMost(10)
        return score
    }

    private fun deepTeamId(root: JSONObject): Int {
        intAny(root, "teamId", "teamID", "team_id").takeIf { it > 0 }?.let { return it }
        for (container in listOf("teamInfo", "team", "basic", "teamBasicInfo", "gameTeamInfo", "detail")) {
            val obj = root.optJSONObject(container) ?: continue
            intAny(obj, "teamId", "teamID", "team_id", "id").takeIf { it > 0 }?.let { return it }
        }
        return 0
    }

    private fun findArrayRecursive(
        root: JSONObject,
        maxDepth: Int,
        predicate: (JSONArray) -> Boolean
    ): JSONArray? {
        fun walkObject(obj: JSONObject, depth: Int): JSONArray? {
            if (depth > maxDepth) return null
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when (val value = obj.opt(key)) {
                    is JSONArray -> {
                        if (predicate(value)) return value
                        for (i in 0 until value.length()) {
                            val child = value.optJSONObject(i) ?: continue
                            walkObject(child, depth + 1)?.let { return it }
                        }
                    }
                    is JSONObject -> walkObject(value, depth + 1)?.let { return it }
                }
            }
            return null
        }
        return walkObject(root, 0)
    }

    private fun parsePlayersByTeam(array: JSONArray): Map<Int, List<LivePlayerSnapshot>> {
        val grouped = linkedMapOf<Int, MutableList<LivePlayerSnapshot>>()
        for (i in 0 until array.length()) {
            val p = array.optJSONObject(i) ?: continue
            val teamId = intAny(p, "teamId", "teamID", "team_id").takeIf { it > 0 }
                ?: deepTeamId(p)
            if (teamId <= 0) continue
            val one = JSONArray().put(p)
            val parsed = parsePlayers(one).firstOrNull() ?: continue
            grouped.getOrPut(teamId) { mutableListOf() }.add(parsed)
        }
        return grouped
    }

    private fun parsedGameSummary(raw: JSONArray, games: List<ParsedGame>): String {
        val parsed = games.take(5).joinToString(",") { g ->
            "G${g.bo}:s${g.status}:t${g.gameTime}:teams${g.teams.size}:m${g.teams.sumOf { it.gold }}"
        }
        val shapes = mutableListOf<String>()
        for (i in 0 until minOf(raw.length(), 3)) {
            val obj = raw.optJSONObject(i) ?: continue
            val keys = obj.keys().asSequence().take(10).joinToString("/")
            val arrays = mutableListOf<String>()
            val objects = mutableListOf<String>()
            val ids = mutableListOf<String>()
            val it = obj.keys()
            while (it.hasNext()) {
                val key = it.next()
                when (val value = obj.opt(key)) {
                    is JSONArray -> if (arrays.size < 5) arrays += "$key:${value.length()}"
                    is JSONObject -> if (objects.size < 5) objects += key
                    is Number, is String -> if (
                        key.contains("id", true) || key.contains("game", true) || key.contains("match", true)
                    ) {
                        if (ids.size < 6) ids += "$key=${value.toString().take(18)}"
                    }
                }
            }
            shapes += "R${i + 1}{k=$keys;a=${arrays.joinToString("/")};o=${objects.joinToString("/")};id=${ids.joinToString("/")}}"
        }
        return "parsed=[$parsed] raw=[${shapes.joinToString(";")}]"
    }

    private fun schemaSummary(data: JSONObject): String {
        val keys = data.keys().asSequence().take(12).joinToString(",")
        val arrays = mutableListOf<String>()
        val it = data.keys()
        while (it.hasNext() && arrays.size < 8) {
            val key = it.next()
            val arr = data.optJSONArray(key) ?: continue
            arrays += "$key:${arr.length()}"
        }
        return "keys=[$keys]" + if (arrays.isNotEmpty()) " arrays=[${arrays.joinToString(",")}]" else ""
    }

    private fun secondsAny(obj: JSONObject, vararg keys: String): Int {
        for (key in keys) {
            if (!obj.has(key)) continue
            when (val raw = obj.opt(key)) {
                is Number -> return raw.toInt()
                is String -> {
                    raw.toDoubleOrNull()?.toInt()?.let { return it }
                    val parts = raw.trim().split(":")
                    if (parts.size == 2) {
                        val m = parts[0].toIntOrNull()
                        val sec = parts[1].toIntOrNull()
                        if (m != null && sec != null) return m * 60 + sec
                    }
                }
            }
        }
        return 0
    }

'''
s = s[:start] + replacement + s[end:]

# Make player terminal parsing tolerant of direct and nested stat fields.
start = s.index('    private fun parsePlayers(array: JSONArray): List<LivePlayerSnapshot> = buildList {')
end = s.index('    private fun parseBo(', start)
replacement = r'''    private fun parsePlayers(array: JSONArray): List<LivePlayerSnapshot> = buildList {
        for (i in 0 until array.length()) {
            val p = array.optJSONObject(i) ?: continue
            val battle = p.optJSONObject("battleDetail") ?: p.optJSONObject("stats") ?: JSONObject()
            val other = p.optJSONObject("otherDetail") ?: p.optJSONObject("detail") ?: JSONObject()
            val kills = intAny(p, "kills", "kill", "killCount").takeIf { it != 0 }
                ?: intAny(battle, "kills", "kill", "killCount")
            val deaths = intAny(p, "deaths", "death", "deathCount").takeIf { it != 0 }
                ?: intAny(battle, "deaths", "death", "deathCount")
            val assists = intAny(p, "assists", "assist", "assistCount").takeIf { it != 0 }
                ?: intAny(battle, "assists", "assist", "assistCount")
            val gold = intAny(p, "golds", "gold", "totalGold").takeIf { it > 0 }
                ?: intAny(other, "golds", "gold", "totalGold")
            val cs = intAny(p, "minionKilled", "creepScore", "cs", "creepsKilled").takeIf { it > 0 }
                ?: intAny(other, "minionKilled", "creepScore", "cs", "creepsKilled")
            val level = intAny(p, "level", "heroLevel").takeIf { it > 0 }
                ?: intAny(other, "level", "heroLevel")
            add(
                LivePlayerSnapshot(
                    participantId = intAny(p, "playerId", "playerID", "id").takeIf { it > 0 } ?: i + 1,
                    role = normalizeRole(stringAny(p, "playerLocation", "position", "role")),
                    summonerName = stringAny(p, "playerName", "summonerName", "name").ifBlank { "P${i + 1}" },
                    championId = stringAny(p, "heroId", "championId", "championID"),
                    level = level,
                    kills = kills,
                    deaths = deaths,
                    assists = assists,
                    creepScore = cs,
                    gold = gold
                )
            )
        }
    }

'''
s = s[:start] + replacement + s[end:]

p.write_text(s, encoding='utf-8')
print('deepened TJStats terminal parser + nested diagnostics')
