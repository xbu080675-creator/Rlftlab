from pathlib import Path

# 1) OP.GG: produce a real global terminal series snapshot, retain third-party MVP-point aggregate.
p = Path('app/src/main/java/com/riftlab/app/data/OpggMatchSupplementProvider.kt')
s = p.read_text()
s = s.replace(
'''internal data class OpggMatchSupplement(
    val drafts: List<DraftPickRecord> = emptyList(),
    val gameMvps: List<OfficialMvpRecord> = emptyList(),
    val panels: List<OfficialVoteRecord> = emptyList(),
    val teamImages: Map<String, String> = emptyMap(),
    val status: String = "OP.GG · 未同步"
)''',
'''internal data class OpggMatchSupplement(
    val series: CompletedSeriesSnapshot? = null,
    val seriesMvp: OfficialMvpRecord? = null,
    val drafts: List<DraftPickRecord> = emptyList(),
    val gameMvps: List<OfficialMvpRecord> = emptyList(),
    val panels: List<OfficialVoteRecord> = emptyList(),
    val teamImages: Map<String, String> = emptyMap(),
    val status: String = "OP.GG · 未同步"
)''', 1)
s = s.replace(
'''                teams {
                  side bans
                  team { id name acronym imageUrl imageUrlDarkMode imageUrlLightMode }
                }''',
'''                teams {
                  side bans
                  kills deaths assists towerKills inhibitorKills heraldKills dragonKills elderDrakeKills baronKills goldEarned
                  team { id name acronym imageUrl imageUrlDarkMode imageUrlLightMode }
                }''', 1)
s = s.replace(
'''        val drafts = mutableListOf<DraftPickRecord>()
        val mvps = mutableListOf<OfficialMvpRecord>()
        val panels = mutableListOf<OfficialVoteRecord>()
        val maxSets = match.bestOf.takeIf { it > 0 } ?: 5
''',
'''        val drafts = mutableListOf<DraftPickRecord>()
        val mvps = mutableListOf<OfficialMvpRecord>()
        val panels = mutableListOf<OfficialVoteRecord>()
        val finals = mutableListOf<LiveSnapshot>()
        val seriesMvpPoints = linkedMapOf<String, Double>()
        val seriesMvpMeta = linkedMapOf<String, OfficialMvpRecord>()
        val maxSets = match.bestOf.takeIf { it > 0 } ?: 5
''', 1)
s = s.replace(
'''            val blueBans = mutableListOf<String>()
            val redBans = mutableListOf<String>()
            val teams = game.optJSONArray("teams") ?: JSONArray()
''',
'''            val blueBans = mutableListOf<String>()
            val redBans = mutableListOf<String>()
            var blueCode = ""
            var redCode = ""
            var blueGold = 0
            var redGold = 0
            var blueKills = 0
            var redKills = 0
            var blueTowers = 0
            var redTowers = 0
            var blueDragons = 0
            var redDragons = 0
            var blueBarons = 0
            var redBarons = 0
            val teams = game.optJSONArray("teams") ?: JSONArray()
''', 1)
s = s.replace(
'''                when (side) {
                    "blue" -> blueBans += bans
                    "red" -> redBans += bans
                }
''',
'''                when (side) {
                    "blue" -> {
                        blueBans += bans
                        blueCode = code
                        blueGold = row.optInt("goldEarned", 0)
                        blueKills = row.optInt("kills", 0)
                        blueTowers = row.optInt("towerKills", 0)
                        blueDragons = row.optInt("dragonKills", 0) + row.optInt("elderDrakeKills", 0)
                        blueBarons = row.optInt("baronKills", 0)
                    }
                    "red" -> {
                        redBans += bans
                        redCode = code
                        redGold = row.optInt("goldEarned", 0)
                        redKills = row.optInt("kills", 0)
                        redTowers = row.optInt("towerKills", 0)
                        redDragons = row.optInt("dragonKills", 0) + row.optInt("elderDrakeKills", 0)
                        redBarons = row.optInt("baronKills", 0)
                    }
                }
''', 1)
s = s.replace(
'''                if (point > 0.0) {
                    ratings += Rating(
                        name = playerName,
                        team = teamCode,
                        role = row.optString("position").ifBlank { player.optString("position") },
                        point = point
                    )
                }
''',
'''                if (point > 0.0) {
                    val role = row.optString("position").ifBlank { player.optString("position") }
                    ratings += Rating(
                        name = playerName,
                        team = teamCode,
                        role = role,
                        point = point
                    )
                    val ratingKey = "${token(playerName)}|${token(teamCode)}"
                    seriesMvpPoints[ratingKey] = (seriesMvpPoints[ratingKey] ?: 0.0) + point
                    seriesMvpMeta[ratingKey] = OfficialMvpRecord(
                        game = null,
                        playerName = playerName,
                        team = teamCode,
                        role = role,
                        source = "OP.GG · 系列赛 MVP Point 累计（第三方评分，非官方奖项）"
                    )
                }
''', 1)
needle = '''            if (sortedRatings.isNotEmpty()) {
                panels += OfficialVoteRecord(
                    title = "G$set · OP.GG MVP POINT",
                    options = sortedRatings.take(5).map { rating ->
                        VoteOptionRecord(
                            label = "${rating.name}${rating.team.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
                            votes = rating.point.roundToLong()
                        )
                    },
                    totalVotes = null,
                    source = "OP.GG · MVP Point（第三方评分，不是官方投票）"
                )
            }
'''
replacement = needle + '''
            val elapsed = normalizeGameLengthSeconds(game.optLong("length", 0L))
            val terminalMeaningful = blueGold > 0 || redGold > 0 || blueKills > 0 || redKills > 0 ||
                blueTowers > 0 || redTowers > 0 || blueDragons > 0 || redDragons > 0 || blueBarons > 0 || redBarons > 0
            if (game.optBoolean("finished", false) && blueCode.isNotBlank() && redCode.isNotBlank() && terminalMeaningful) {
                finals += LiveSnapshot(
                    game = set,
                    elapsedSeconds = elapsed,
                    blue = blueCode,
                    red = redCode,
                    blueGold = blueGold,
                    redGold = redGold,
                    blueKills = blueKills,
                    redKills = redKills,
                    blueTowers = blueTowers,
                    redTowers = redTowers,
                    blueDragons = blueDragons,
                    redDragons = redDragons,
                    latestEvent = "OP.GG 终局快照 · G$set",
                    blueBarons = blueBarons,
                    redBarons = redBarons,
                    source = "OP.GG Esports · gameByMatch FINAL · third-party",
                    gameId = "opgg:${game.opt("id")?.toString().orEmpty()}"
                )
            }
'''
if needle not in s: raise SystemExit('OPGG ratings block not found')
s = s.replace(needle, replacement, 1)
old_return = '''        OpggMatchSupplement(
            drafts = drafts,
            gameMvps = mvps,
            panels = panels,
            teamImages = images,
            status = "OP.GG · match=$matchId · BP ${drafts.size} 局 · MVP ${mvps.size} 局"
        )
'''
new_return = '''        val left = match.teams.getOrNull(0)
        val right = match.teams.getOrNull(1)
        val scoreA = left?.let { scoreForTeam(opggMatch, it) } ?: 0
        val scoreB = right?.let { scoreForTeam(opggMatch, it) } ?: 0
        val series = if (left != null && right != null && finals.isNotEmpty()) {
            CompletedSeriesSnapshot(
                matchKey = "OPGG:$matchId",
                teamA = left.code.ifBlank { left.name },
                teamB = right.code.ifBlank { right.name },
                scoreA = scoreA.takeIf { it > 0 || scoreB > 0 } ?: left.gameWins,
                scoreB = scoreB.takeIf { it > 0 || scoreA > 0 } ?: right.gameWins,
                games = finals.sortedBy { it.game },
                seriesFinished = opggMatch.optString("status").contains("finish", ignoreCase = true) ||
                    scoreA > 0 || scoreB > 0,
                source = "OP.GG Esports · gameByMatch FINAL · third-party"
            )
        } else null
        val seriesMvp = seriesMvpPoints.maxByOrNull { it.value }?.key?.let(seriesMvpMeta::get)

        OpggMatchSupplement(
            series = series,
            seriesMvp = seriesMvp,
            drafts = drafts,
            gameMvps = mvps,
            panels = panels,
            teamImages = images,
            status = "OP.GG · match=$matchId · FINAL ${finals.size} 局 · BP ${drafts.size} 局 · MVP Point ${mvps.size} 局"
        )
'''
if old_return not in s: raise SystemExit('OPGG return block not found')
s = s.replace(old_return, new_return, 1)
insert_before = '''    /** Latest actually-played five for one team, with role/name/image for Riot-roster gaps. */
'''
helpers = '''    private fun scoreForTeam(opggMatch: JSONObject, team: EsportsTeamRef): Int {
        val home = opggMatch.optJSONObject("homeTeam")
        val away = opggMatch.optJSONObject("awayTeam")
        return when {
            home != null && teamMatches(home, team) -> opggMatch.optInt("homeScore", team.gameWins)
            away != null && teamMatches(away, team) -> opggMatch.optInt("awayScore", team.gameWins)
            else -> team.gameWins
        }
    }

    private fun normalizeGameLengthSeconds(raw: Long): Int = when {
        raw <= 0L -> 0
        raw > 100_000L -> (raw / 1000L).toInt()
        else -> raw.toInt()
    }

'''
if insert_before not in s: raise SystemExit('OPGG helper insertion point not found')
s = s.replace(insert_before, helpers + insert_before, 1)
p.write_text(s)

# 2) Match detail: use OP.GG terminal series globally and expose its labelled aggregate MVP fallback.
p = Path('app/src/main/java/com/riftlab/app/data/MatchDetailRepository.kt')
s = p.read_text()
old = '''            val enrichedMatch = runCatching { enrichTeamImages(matchWithRiotAssets, opgg.teamImages) }
                .getOrDefault(matchWithRiotAssets)

            val officialGameMvps = awards.gameMvps.map { it.withDisplaySource(enrichedMatch) }
'''
new = '''            val enrichedMatch = runCatching { enrichTeamImages(matchWithRiotAssets, opgg.teamImages) }
                .getOrDefault(matchWithRiotAssets)
            val opggSeries = opgg.series?.normalizeDetailRoles()?.let { alignSeriesToMatch(it, enrichedMatch) }
            val detailSeries = resolved ?: opggSeries

            val officialGameMvps = awards.gameMvps.map { it.withDisplaySource(enrichedMatch) }
'''
if old not in s: raise SystemExit('detail enriched block not found')
s = s.replace(old, new, 1)
s = s.replace('''                series = resolved,
                seriesMvp = awards.seriesMvp?.withDisplaySource(enrichedMatch),
''','''                series = detailSeries,
                seriesMvp = (awards.seriesMvp ?: opgg.seriesMvp)?.withDisplaySource(enrichedMatch),
''',1)
s = s.replace('''                    resolved != null -> "已加载 ${resolved.games.size} 局终局数据 · ${awards.status} · ${draftResult.status} · ${opgg.status}"
''','''                    detailSeries != null -> "已加载 ${detailSeries.games.size} 局终局数据 · ${awards.status} · ${draftResult.status} · ${opgg.status}"
''',1)
p.write_text(s)

# 3) Match detail G tabs: a completed global series must enumerate games even before an archive exists.
p = Path('app/src/main/java/com/riftlab/app/ui/MatchDetailUi.kt')
s = p.read_text()
old = '''    val gameNumbers = remember(series?.games, live?.game) {
        buildList {
            series?.games.orEmpty().map { it.game }.filter { it > 0 }.distinct().sorted().forEach(::add)
            live?.game?.takeIf { it > 0 && it !in this }?.let(::add)
        }.sorted()
    }
'''
new = '''    val playedGamesFromScore = if (phase == ScheduleMatchPhase.COMPLETED) {
        match.teams.take(2).sumOf { it.gameWins }.coerceAtMost(match.bestOf.takeIf { it > 0 } ?: 7)
    } else 0
    val gameNumbers = remember(series?.games, live?.game, state.drafts, state.gameMvps, playedGamesFromScore) {
        buildList {
            series?.games.orEmpty().map { it.game }.filter { it > 0 }.distinct().sorted().forEach(::add)
            state.drafts.map { it.game }.filter { it > 0 && it !in this }.forEach(::add)
            state.gameMvps.mapNotNull { it.game }.filter { it > 0 && it !in this }.forEach(::add)
            if (playedGamesFromScore > 0) (1..playedGamesFromScore).filter { it !in this }.forEach(::add)
            live?.game?.takeIf { it > 0 && it !in this }?.let(::add)
        }.distinct().sorted()
    }
'''
if old not in s: raise SystemExit('detail gameNumbers block not found')
s = s.replace(old, new, 1)
p.write_text(s)

# 4) Operator view: consume the selected match's global terminal series and unblock history backfill game IDs.
p = Path('app/src/main/java/com/riftlab/app/ui/MatchOperationsContent.kt')
s = p.read_text()
old = '''    val finalSeries = completedSeries?.takeIf { series -> seriesMatches(series.teamA, series.teamB, match) }
    val games = remember(record?.games, record?.finalGames, finalSeries?.games, live?.game) {
        buildList {
            record?.games?.keys.orEmpty().filter { it > 0 }.forEach(::add)
            record?.finalGames?.keys.orEmpty().filter { it > 0 && it !in this }.forEach(::add)
            finalSeries?.games.orEmpty().map { it.game }.filter { it > 0 && it !in this }.forEach(::add)
            live?.game?.takeIf { it > 0 && it !in this }?.let(::add)
        }.distinct().sorted()
    }
'''
new = '''    val finalSeries = completedSeries?.takeIf { series -> seriesMatches(series.teamA, series.teamB, match) }
        ?: detail.series?.takeIf { series -> seriesMatches(series.teamA, series.teamB, match) }
    val playedGamesFromScore = if (phase == ScheduleMatchPhase.COMPLETED) {
        match.teams.take(2).sumOf { it.gameWins }.coerceAtMost(match.bestOf.takeIf { it > 0 } ?: 7)
    } else 0
    val games = remember(
        record?.games, record?.finalGames, finalSeries?.games, live?.game,
        detail.drafts, detail.gameMvps, playedGamesFromScore
    ) {
        buildList {
            record?.games?.keys.orEmpty().filter { it > 0 }.forEach(::add)
            record?.finalGames?.keys.orEmpty().filter { it > 0 && it !in this }.forEach(::add)
            finalSeries?.games.orEmpty().map { it.game }.filter { it > 0 && it !in this }.forEach(::add)
            detail.drafts.map { it.game }.filter { it > 0 && it !in this }.forEach(::add)
            detail.gameMvps.mapNotNull { it.game }.filter { it > 0 && it !in this }.forEach(::add)
            if (playedGamesFromScore > 0) (1..playedGamesFromScore).filter { it !in this }.forEach(::add)
            live?.game?.takeIf { it > 0 && it !in this }?.let(::add)
        }.distinct().sorted()
    }
'''
if old not in s: raise SystemExit('operations games block not found')
s = s.replace(old, new, 1)
p.write_text(s)

# 5) Version bump.
p = Path('app/build.gradle.kts')
s = p.read_text()
s = s.replace('versionCode = 53', 'versionCode = 54', 1)
s = s.replace('versionName = "1.0.0-dev.53"', 'versionName = "1.0.0-dev.54"', 1)
s += '\n// dev.54: global completed-series operator archive from OP.GG terminal data; unblock Riot/OP.GG history backfill game enumeration; labelled global MVP Point fallback.\n'
p.write_text(s)
