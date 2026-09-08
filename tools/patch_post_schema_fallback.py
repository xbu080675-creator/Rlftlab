from pathlib import Path

p = Path('app/src/main/java/com/riftlab/app/data/LplHistoricalPostMatchResolver.kt')
s = p.read_text(encoding='utf-8')

old = '''        val games = parseGames(data.optJSONArray("matchInfos") ?: JSONArray())
'''
new = '''        val gameArray = firstArray(
            data,
            "matchInfos", "gameInfos", "games", "gameList", "singleGames", "gameInfoList"
        ) ?: findLikelyGameArray(data) ?: JSONArray()
        val games = parseGames(gameArray)
'''
if old not in s:
    raise SystemExit('game array anchor not found')
s = s.replace(old, new, 1)

old = '''        if (finals.isEmpty()) {
            _status.value = "POST · bmid=${ref.bmid} 已定位，但 matchDetail 暂无可用终局 teamInfos"
            return
        }
'''
new = '''        if (finals.isEmpty()) {
            _status.value = "POST · bmid=${ref.bmid} 已定位，但终局解析为空 · games=${games.size} · ${schemaSummary(data)}"
            return
        }
'''
if old not in s:
    raise SystemExit('empty finals anchor not found')
s = s.replace(old, new, 1)

start = s.index('    private fun parseGames(infos: JSONArray): List<ParsedGame> = buildList {')
end = s.index('    private fun finalSnapshotFor(', start)
replacement = r'''    private fun parseGames(infos: JSONArray): List<ParsedGame> = buildList {
        for (i in 0 until infos.length()) {
            val info = infos.optJSONObject(i) ?: continue
            val teamInfos = firstArray(
                info,
                "teamInfos", "teams", "teamInfoList", "gameTeamInfos", "teamList", "teamData"
            ) ?: findLikelyTeamArray(info) ?: JSONArray()

            val teams = buildList {
                for (j in 0 until teamInfos.length()) {
                    val team = teamInfos.optJSONObject(j) ?: continue
                    val id = intAny(team, "teamId", "teamID", "team_id", "id")
                    if (id <= 0) continue
                    val players = firstArray(
                        team,
                        "playerInfos", "players", "playerInfoList", "gamePlayerInfos", "playerList"
                    ) ?: JSONArray()
                    add(
                        TeamState(
                            teamId = id,
                            gold = intAny(team, "golds", "gold", "totalGold", "teamGold", "goldAmount"),
                            kills = intAny(team, "kills", "kill", "totalKills", "killAmount"),
                            towers = intAny(team, "turretAmount", "towers", "tower", "towerAmount", "turretCount"),
                            dragons = intAny(team, "dragonAmount", "dragons", "dragon", "dragonCount"),
                            barons = intAny(team, "baronAmount", "barons", "baron", "baronCount"),
                            players = parsePlayers(players)
                        )
                    )
                }
            }

            // Some TJStats payloads put all players at game level rather than under each team.
            // If the team rows have no player list, attach players by teamId when possible.
            val gamePlayers = firstArray(
                info,
                "playerInfos", "players", "playerInfoList", "gamePlayerInfos", "playerList"
            ) ?: JSONArray()
            val playersByTeam = parsePlayersByTeam(gamePlayers)
            val mergedTeams = teams.map { team ->
                if (team.players.isNotEmpty()) team
                else team.copy(players = playersByTeam[team.teamId].orEmpty())
            }

            add(
                ParsedGame(
                    bo = parseBo(info, i + 1),
                    status = intAny(info, "matchStatus", "status", "gameStatus", "state"),
                    gameTime = intAny(info, "gameTime", "time", "elapsedSeconds", "gameDuration", "duration"),
                    blueTeamId = intAny(info, "blueTeam", "blueTeamId", "blueId", "blueTeamID"),
                    teams = mergedTeams
                )
            )
        }
    }

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
                keys.any { it in setOf("bo", "gameNo", "gameNum", "gameNumber", "gameIndex", "round", "gameStatus", "matchStatus") } ||
                    firstArray(sample, "teamInfos", "teams", "teamInfoList", "gameTeamInfos", "teamList") != null
            }
        }
    }

    private fun findLikelyTeamArray(root: JSONObject): JSONArray? =
        findArrayRecursive(root, maxDepth = 2) { arr ->
            if (arr.length() !in 2..4) false
            else {
                var teamLike = 0
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (intAny(obj, "teamId", "teamID", "team_id") > 0) teamLike++
                }
                teamLike >= 2
            }
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
            val teamId = intAny(p, "teamId", "teamID", "team_id")
            if (teamId <= 0) continue
            val one = JSONArray().put(p)
            val parsed = parsePlayers(one).firstOrNull() ?: continue
            grouped.getOrPut(teamId) { mutableListOf() }.add(parsed)
        }
        return grouped
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

'''
s = s[:start] + replacement + s[end:]

# Broaden player field compatibility without changing the archive model.
s = s.replace('''                    participantId = p.optInt("playerId", i + 1),
                    role = normalizeRole(p.optString("playerLocation")),
                    summonerName = p.optString("playerName").ifBlank { "P${i + 1}" },
                    championId = p.opt("heroId")?.toString().orEmpty(),
''', '''                    participantId = intAny(p, "playerId", "playerID", "id").takeIf { it > 0 } ?: i + 1,
                    role = normalizeRole(stringAny(p, "playerLocation", "position", "role")),
                    summonerName = stringAny(p, "playerName", "summonerName", "name").ifBlank { "P${i + 1}" },
                    championId = stringAny(p, "heroId", "championId", "championID"),
''', 1)

p.write_text(s, encoding='utf-8')
print('patched TJStats post schema compatibility')
