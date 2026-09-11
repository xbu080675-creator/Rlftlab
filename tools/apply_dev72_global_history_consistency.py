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

print("dev72 global Riot history schema consistency applied")
