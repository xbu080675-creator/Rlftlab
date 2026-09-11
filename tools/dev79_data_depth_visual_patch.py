from pathlib import Path


def replace_once(path: Path, old: str, new: str):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected 1 match, got {count}: {old[:100]!r}")
    path.write_text(text.replace(old, new), encoding="utf-8")


opgg = Path("app/src/main/java/com/riftlab/app/data/OpggMatchSupplementProvider.kt")
replace_once(
    opgg,
    '''            val bluePicks = mutableListOf<String>()\n            val redPicks = mutableListOf<String>()\n            data class Rating(val name: String, val team: String, val role: String, val point: Double)''',
    '''            val bluePicks = mutableListOf<String>()\n            val redPicks = mutableListOf<String>()\n            // OP.GG exposes player/champion/role identity even when its public game payload does not\n            // expose a trustworthy numeric stat line. Keep that identity so the UI can show the\n            // actual five champions instead of ten \"data missing\" rows; zero numeric fields remain\n            // explicitly non-measured and are excluded from comprehensive stat coverage.\n            val bluePlayerSnapshots = mutableListOf<LivePlayerSnapshot>()\n            val redPlayerSnapshots = mutableListOf<LivePlayerSnapshot>()\n            data class Rating(val name: String, val team: String, val role: String, val point: Double)'''
)
replace_once(
    opgg,
    '''                val playerName = player.optString("nickName")\n                    .ifBlank { row.optString("nickName") }\n                    .ifBlank { "P${i + 1}" }\n                val teamCode = team.optString("acronym")\n                    .ifBlank { team.optString("name") }\n                    .ifBlank { teamCodeById[teamId].orEmpty() }\n                val playerImage = normalizeAssetUrl(player.optString("imageUrl"))\n                if (playerImage.isNotBlank()) {\n                    EsportsAssetCache.putPlayer(playerName, teamCode, playerImage)\n                }\n\n                val point = number(row.opt("mvpPoint"))\n                if (point > 0.0) {\n                    val role = row.optString("position").ifBlank { player.optString("position") }\n                    ratings += Rating(''',
    '''                val playerName = player.optString("nickName")\n                    .ifBlank { row.optString("nickName") }\n                    .ifBlank { "P${i + 1}" }\n                val teamCode = team.optString("acronym")\n                    .ifBlank { team.optString("name") }\n                    .ifBlank { teamCodeById[teamId].orEmpty() }\n                val role = normalizeLineupRole(row.optString("position").ifBlank { player.optString("position") })\n                val playerImage = normalizeAssetUrl(player.optString("imageUrl"))\n                if (playerImage.isNotBlank()) {\n                    EsportsAssetCache.putPlayer(playerName, teamCode, playerImage)\n                }\n                val identitySnapshot = LivePlayerSnapshot(\n                    participantId = i + 1,\n                    role = role,\n                    summonerName = playerName,\n                    championId = champion,\n                    level = 0,\n                    kills = 0,\n                    deaths = 0,\n                    assists = 0,\n                    creepScore = 0,\n                    gold = 0,\n                    teamId = teamId,\n                    side = side.uppercase()\n                )\n                when (side) {\n                    "blue" -> bluePlayerSnapshots += identitySnapshot\n                    "red" -> redPlayerSnapshots += identitySnapshot\n                }\n\n                val point = number(row.opt("mvpPoint"))\n                if (point > 0.0) {\n                    ratings += Rating('''
)
replace_once(
    opgg,
    '''                    blueBarons = blueBarons,\n                    redBarons = redBarons,\n                    source = "OP.GG Esports · gameByMatch FINAL · third-party",''',
    '''                    blueBarons = blueBarons,\n                    redBarons = redBarons,\n                    bluePlayers = bluePlayerSnapshots.sortedBy { lineupRoleOrder(it.role) },\n                    redPlayers = redPlayerSnapshots.sortedBy { lineupRoleOrder(it.role) },\n                    source = "OP.GG Esports · gameByMatch FINAL · third-party",'''
)

ui = Path("app/src/main/java/com/riftlab/app/ui/MatchDetailUi.kt")
replace_once(
    ui,
    '''        Text(status, color = RiftMuted, fontSize = 11.sp, maxLines = 2, modifier = Modifier.weight(1f))''',
    '''        Text(status, color = RiftMuted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))'''
)
replace_once(
    ui,
    '''private fun playerStats(player: LivePlayerSnapshot?): String =\n    player?.let { "${it.kills}/${it.deaths}/${it.assists} · CS ${it.creepScore} · G ${it.gold}" } ?: "—"''',
    '''private fun playerStats(player: LivePlayerSnapshot?): String = when {\n    player == null -> "—"\n    player.level <= 0 && player.gold <= 0 && player.creepScore <= 0 &&\n        player.kills <= 0 && player.deaths <= 0 && player.assists <= 0 -> "—"\n    else -> "${player.kills}/${player.deaths}/${player.assists} · CS ${player.creepScore} · G ${player.gold}"\n}'''
)

build = Path("app/build.gradle.kts")
replace_once(build, 'versionCode = 78\n        versionName = "1.0.0-dev.78"', 'versionCode = 79\n        versionName = "1.0.0-dev.79"')

current = Path("DEV_CURRENT_CHANGELOG.txt")
current.write_text(
    "dev.79：数据深度与视觉信息层级第一轮。把 CompletedSeriesSnapshot 中每个已结束小局和 MatchTimelineStore 已归档的真实事件正式接入 ComprehensiveDataGraph，不再只把最后一局终局帧当成整个赛后数据；同时阻止最新已结束比赛被错误挂到无关的当前/下一场比赛上。全球 OP.GG 补充链路现在把已经能核实的选手名、位置和英雄身份带入每局终局快照，即使该来源没有可信 KDA/CS/个人经济，也只显示阵容身份而不把 0 值冒充统计。全面数据页改成 Coverage HUD：覆盖率环、状态轨和赛前/赛中/赛后/赛事四块视觉面板，正常页面只露一个 NEXT GAP，不再把 12 个域全部堆成文字卡。比赛详情的数据源长状态压成单行，减少工程日志感。版本升级为 1.0.0-dev.79 / versionCode 79。\n",
    encoding="utf-8",
)

print("dev79 patch applied")
