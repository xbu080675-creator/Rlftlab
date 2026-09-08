from pathlib import Path
import re

root = Path('.')

# 1) Upgrade the archive from one volatile final frame to a series-aware observable archive.
archive = root / 'app/src/main/java/com/riftlab/app/data/CompletedGameArchive.kt'
archive.write_text('''package com.riftlab.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CompletedSeriesSnapshot(
    val matchKey: String,
    val teamA: String,
    val teamB: String,
    val scoreA: Int,
    val scoreB: Int,
    val games: List<LiveSnapshot>,
    val seriesFinished: Boolean,
    val source: String,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    val winner: String
        get() = when {
            scoreA > scoreB -> teamA
            scoreB > scoreA -> teamB
            else -> "—"
        }
}

/**
 * Post-match archive. It is intentionally separated from the live surface.
 *
 * A completed game can be reconstructed from Tencent/TJStats after the game has ended, so the
 * post tab does not depend on RiftLab having been open for the final live frame.
 */
object CompletedGameArchive {
    private val _latest = MutableStateFlow<LiveSnapshot?>(null)
    val latest: StateFlow<LiveSnapshot?> = _latest.asStateFlow()

    private val _series = MutableStateFlow<CompletedSeriesSnapshot?>(null)
    val series: StateFlow<CompletedSeriesSnapshot?> = _series.asStateFlow()

    fun publish(snapshot: LiveSnapshot) {
        if (snapshot.game <= 0) return
        _latest.value = snapshot.copy(
            latestEvent = "G${snapshot.game} 已结束 · ${snapshot.latestEvent}"
        )
    }

    fun publishSeries(snapshot: CompletedSeriesSnapshot) {
        if (snapshot.games.isEmpty()) return
        val normalizedGames = snapshot.games
            .filter { it.game > 0 }
            .distinctBy { it.game }
            .sortedBy { it.game }
        if (normalizedGames.isEmpty()) return

        val normalized = snapshot.copy(games = normalizedGames)
        _series.value = normalized
        publish(normalizedGames.last())
    }
}
''', encoding='utf-8')

# 2) Backfill completed small games from matchDetail on every poll, including app launches after
#    the game/series has already ended.
source = root / 'app/src/main/java/com/riftlab/app/data/LplCurrentGameLiveDataSource.kt'
text = source.read_text(encoding='utf-8')
needle = '                val games = parseGames(data.optJSONArray("matchInfos") ?: JSONArray())\n'
insert = needle + '''\n                // Post-match backfill is independent from the live frame cache. As soon as\n                // matchDetail contains finished games, rebuild their final snapshots and publish\n                // them to the post surface. This also works when RiftLab is opened after a game.\n                publishCompletedSeries(\n                    active = active,\n                    seriesStatus = seriesStatus,\n                    scoreA = scoreA,\n                    scoreB = scoreB,\n                    teamAId = teamAId,\n                    teamBId = teamBId,\n                    teamAName = teamAName,\n                    teamBName = teamBName,\n                    games = games\n                )\n'''
if 'publishCompletedSeries(' not in text:
    if needle not in text:
        raise SystemExit('LplCurrentGameLiveDataSource: games parse anchor not found')
    text = text.replace(needle, insert, 1)

helper_anchor = '    private fun parseGames(infos: JSONArray): List<ParsedGame> = buildList {\n'
helper = '''    private fun publishCompletedSeries(\n        active: MatchRef,\n        seriesStatus: Int,\n        scoreA: Int,\n        scoreB: Int,\n        teamAId: Int,\n        teamBId: Int,\n        teamAName: String,\n        teamBName: String,\n        games: List<ParsedGame>\n    ) {\n        val finalGames = games\n            .filter { game -> (game.status == 3 || seriesStatus == 3) && isMeaningful(game.teams) }\n            .mapNotNull { game ->\n                finalSnapshotFor(\n                    game = game,\n                    bmid = active.bmid,\n                    teamAId = teamAId,\n                    teamBId = teamBId,\n                    teamAName = teamAName,\n                    teamBName = teamBName\n                )\n            }\n            .sortedBy { it.game }\n\n        if (finalGames.isEmpty()) return\n\n        CompletedGameArchive.publishSeries(\n            CompletedSeriesSnapshot(\n                matchKey = "TJ:${active.bmid}",\n                teamA = teamAName,\n                teamB = teamBName,\n                scoreA = scoreA,\n                scoreB = scoreB,\n                games = finalGames,\n                seriesFinished = seriesStatus == 3,\n                source = "LPL Official · TJStats matchDetail FINAL"\n            )\n        )\n    }\n\n    private fun finalSnapshotFor(\n        game: ParsedGame,\n        bmid: String,\n        teamAId: Int,\n        teamBId: Int,\n        teamAName: String,\n        teamBName: String\n    ): LiveSnapshot? {\n        val blueId = game.blueTeamId.takeIf { it > 0 } ?: teamAId\n        val redId = when (blueId) {\n            teamAId -> teamBId\n            teamBId -> teamAId\n            else -> teamBId\n        }\n        val byId = game.teams.associateBy { it.teamId }\n        val blue = byId[blueId] ?: return null\n        val red = byId[redId] ?: game.teams.firstOrNull { it.teamId != blue.teamId } ?: return null\n        if (!isMeaningful(listOf(blue, red))) return null\n\n        val blueName = when (blueId) {\n            teamAId -> teamAName\n            teamBId -> teamBName\n            else -> "BLUE"\n        }\n        val redName = when (redId) {\n            teamAId -> teamAName\n            teamBId -> teamBName\n            else -> "RED"\n        }\n\n        return LiveSnapshot(\n            game = game.bo,\n            elapsedSeconds = game.gameTime.coerceAtLeast(0),\n            blue = blueName,\n            red = redName,\n            blueGold = blue.gold,\n            redGold = red.gold,\n            blueKills = blue.kills,\n            redKills = red.kills,\n            blueTowers = blue.towers,\n            redTowers = red.towers,\n            blueDragons = blue.dragons,\n            redDragons = red.dragons,\n            blueBarons = blue.barons,\n            redBarons = red.barons,\n            bluePlayers = blue.players,\n            redPlayers = red.players,\n            latestEvent = "FINAL · G${game.bo}",\n            source = "LPL Official · TJStats matchDetail FINAL",\n            gameId = "TJ:$bmid:G${game.bo}"\n        )\n    }\n\n'''
if 'private fun publishCompletedSeries(' not in text:
    if helper_anchor not in text:
        raise SystemExit('LplCurrentGameLiveDataSource: helper anchor not found')
    text = text.replace(helper_anchor, helper + helper_anchor, 1)
source.write_text(text, encoding='utf-8')

# 3) Expose the series StateFlow to Compose.
store = root / 'app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt'
text = store.read_text(encoding='utf-8')
anchor = '    val completedGame: StateFlow<LiveSnapshot?> = CompletedGameArchive.latest\n'
replacement = anchor + '    val completedSeries: StateFlow<CompletedSeriesSnapshot?> = CompletedGameArchive.series\n'
if 'val completedSeries:' not in text:
    if anchor not in text:
        raise SystemExit('MatchSessionStore: completedGame anchor not found')
    text = text.replace(anchor, replacement, 1)
store.write_text(text, encoding='utf-8')

# 4) Make the post UI reactive and series-aware.
ui = root / 'app/src/main/java/com/riftlab/app/ui/RiftLabApp.kt'
text = ui.read_text(encoding='utf-8')
new_post = r'''@Composable
private fun PostScreen() {
    val series by MatchSessionStore.completedSeries.collectAsState()
    val latest by MatchSessionStore.completedGame.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val resolved = series
        if (resolved == null) {
            item {
                Panel(accent = false) {
                    Text("POST MATCH · SYNCING", color = RiftMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("正在同步赛后数据", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "赛后数据会从 LPL/TJStats 终局记录重新构建，不要求比赛时一直打开 RiftLab。",
                        color = RiftMuted,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )
                }
            }
            item { SectionTitle("REAL POST DATA / 赛后真实源") }
            item {
                Panel {
                    Text("当前没有可用终局记录", color = RiftCyan, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        latest?.latestEvent ?: "正在等待 LPL 官方赛后数据源返回；不使用 Mock MVP / 排行榜填空。",
                        color = RiftMuted,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        } else {
            item {
                Panel(accent = resolved.seriesFinished) {
                    Text(
                        if (resolved.seriesFinished) "POST MATCH · FINAL" else "POST MATCH · COMPLETED GAMES",
                        color = if (resolved.seriesFinished) RiftCyan else RiftMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${resolved.teamA}  ${resolved.scoreA} : ${resolved.scoreB}  ${resolved.teamB}",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (resolved.seriesFinished) "WINNER · ${resolved.winner}" else "系列赛仍在进行 · 已结束小局已归档",
                        color = RiftMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            item { SectionTitle("GAME RESULTS / 小局终局数据") }
            items(resolved.games) { game ->
                Panel(accent = false) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("GAME ${game.game}", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(MatchSessionStore.formatTime(game.elapsedSeconds), color = RiftMuted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(game.blue, modifier = Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("${game.blueKills} : ${game.redKills}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            game.red,
                            modifier = Modifier.weight(1f),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "GOLD ${if (game.blueGold > 0) "%.1fK".format(game.blueGold / 1000f) else "—"} : ${if (game.redGold > 0) "%.1fK".format(game.redGold / 1000f) else "—"} · DIFF ${formatGoldDiff(game.goldDiff)}",
                        color = RiftMuted,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(5.dp))
                    MetricRow(
                        "K ${game.blueKills}:${game.redKills}",
                        "T ${game.blueTowers}:${game.redTowers}",
                        "D ${game.blueDragons}:${game.redDragons}",
                        "B ${game.blueBarons}:${game.redBarons}"
                    )
                }
            }

            item { SectionTitle("REAL POST DATA / 赛后真实源") }
            item {
                Panel {
                    Text(resolved.source, color = RiftCyan, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "共恢复 ${resolved.games.size} 局终局数据。MVP / 赛后官方评选只有在上游提供可核实字段后才展示，不生成 Mock 结论。",
                        color = RiftMuted,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun MatchHero'''
pattern = re.compile(r'@Composable\nprivate fun PostScreen\(\) \{.*?\n\}\n\n@Composable\nprivate fun MatchHero', re.S)
if not pattern.search(text):
    raise SystemExit('RiftLabApp: PostScreen block not found')
text = pattern.sub(new_post, text, count=1)
ui.write_text(text, encoding='utf-8')

print('post-match series archive patch applied')
