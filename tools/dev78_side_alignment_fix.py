#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "app/src/main/java/com/riftlab/app/data"
UI = ROOT / "app/src/main/java/com/riftlab/app/ui"


def rewrite(path: Path, transform):
    original = path.read_text(encoding="utf-8")
    updated = transform(original)
    if updated == original:
        raise SystemExit(f"no changes applied to {path}")
    path.write_text(updated, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one {label}, found {count}")
    return text.replace(old, new, 1)


# Persisted lifecycle frames may contain correct blue/red telemetry with wrong schedule-order labels.
# For completed games the terminal snapshot is authoritative side truth, so expose archived frames
# with those canonical labels without swapping any metrics.
def patch_archive(text: str) -> str:
    old = '''    fun framesFor(game: Int): List<MatchLifecycleFrame> = games[game].orEmpty()\n\n    fun latestGame(game: Int): LiveSnapshot? =\n        games[game]?.lastOrNull()?.snapshot ?: finalGames[game]\n'''
    new = '''    fun framesFor(game: Int): List<MatchLifecycleFrame> {\n        val frames = games[game].orEmpty()\n        val terminal = finalGames[game] ?: return frames\n        val canonicalBlue = terminal.blue.trim().takeUnless {\n            it.isBlank() || it == "—" || it.equals("BLUE", ignoreCase = true)\n        } ?: return frames\n        val canonicalRed = terminal.red.trim().takeUnless {\n            it.isBlank() || it == "—" || it.equals("RED", ignoreCase = true)\n        } ?: return frames\n        if (canonicalBlue.equals(canonicalRed, ignoreCase = true)) return frames\n\n        return frames.map { frame ->\n            val snapshot = frame.snapshot\n            if (snapshot.blue == canonicalBlue && snapshot.red == canonicalRed) frame\n            else frame.copy(snapshot = snapshot.copy(blue = canonicalBlue, red = canonicalRed))\n        }\n    }\n\n    fun latestGame(game: Int): LiveSnapshot? =\n        framesFor(game).lastOrNull()?.snapshot ?: finalGames[game]\n'''
    return replace_once(text, old, new, "framesFor/lastGame block")


rewrite(DATA / "MatchLifecycleArchive.kt", patch_archive)


# Riot history telemetry is side-based. Never fall back from BLUE/RED to schedule list index because
# schedule order is not per-game side assignment. Prefer a stable Riot team id, then terminal side
# truth when available, otherwise keep a neutral BLUE/RED label rather than confidently lying.
def patch_riot_history(text: str) -> str:
    text = replace_once(
        text,
        '''            val sideIds = parseSideIds(gameObject)\n            val kickoff = try {\n''',
        '''            val sideIds = parseSideIds(gameObject)\n            val canonicalSideTruth = MatchLifecycleArchive.find(match)?.finalGames?.get(gameNumber)\n            val kickoff = try {\n''',
        "canonical side truth lookup"
    )
    text = replace_once(
        text,
        '''                            sideIds = sideIds,\n                            elapsedSeconds = elapsed\n''',
        '''                            sideIds = sideIds,\n                            canonicalSideTruth = canonicalSideTruth,\n                            elapsedSeconds = elapsed\n''',
        "parseSnapshot canonical argument"
    )
    text = replace_once(
        text,
        '''        metadata: JSONObject,\n        sideIds: Pair<String, String>,\n        elapsedSeconds: Int\n''',
        '''        metadata: JSONObject,\n        sideIds: Pair<String, String>,\n        canonicalSideTruth: LiveSnapshot?,\n        elapsedSeconds: Int\n''',
        "parseSnapshot signature"
    )
    text = replace_once(
        text,
        '''        val blueCode = teamCode(match, blueId, 0, "BLUE")\n        val redCode = teamCode(match, redId, 1, "RED")\n''',
        '''        val blueCode = teamCode(match, blueId, canonicalSideTruth?.blue.orEmpty(), "BLUE")\n        val redCode = teamCode(match, redId, canonicalSideTruth?.red.orEmpty(), "RED")\n''',
        "unsafe schedule-index side labels"
    )
    old_fun = '''    private fun teamCode(match: ScheduledEsportsMatch, teamId: String, index: Int, fallback: String): String {\n        match.teams.firstOrNull { it.id == teamId }?.let { team ->\n            if (team.code.isNotBlank()) return team.code\n            if (team.name.isNotBlank()) return team.name\n        }\n        match.teams.getOrNull(index)?.let { team ->\n            if (team.code.isNotBlank()) return team.code\n            if (team.name.isNotBlank()) return team.name\n        }\n        return fallback\n    }\n'''
    new_fun = '''    private fun teamCode(\n        match: ScheduledEsportsMatch,\n        teamId: String,\n        canonicalSideLabel: String,\n        fallback: String\n    ): String {\n        match.teams.firstOrNull { teamId.isNotBlank() && it.id == teamId }?.let { team ->\n            if (team.code.isNotBlank()) return team.code\n            if (team.name.isNotBlank()) return team.name\n        }\n        canonicalSideLabel.trim().takeUnless {\n            it.isBlank() || it == "—" || it.equals("BLUE", ignoreCase = true) || it.equals("RED", ignoreCase = true)\n        }?.let { return it }\n        return fallback\n    }\n'''
    return replace_once(text, old_fun, new_fun, "teamCode fallback")


rewrite(DATA / "RiotLiveStatsHistoryResolver.kt", patch_riot_history)


# If terminal and historical providers happen to emit the same game second, let the current/terminal
# snapshot win. This avoids a stale historical row shadowing the authoritative final at that second.
def patch_operations(text: str) -> str:
    old = '''        (archived + current)\n            .distinctBy { it.elapsedSeconds }\n            .sortedBy { it.elapsedSeconds }\n'''
    new = '''        (archived + current)\n            .associateBy { it.elapsedSeconds }\n            .values\n            .sortedBy { it.elapsedSeconds }\n'''
    return replace_once(text, old, new, "history/current merge")


rewrite(UI / "MatchOperationsContent.kt", patch_operations)


def patch_gradle(text: str) -> str:
    text = replace_once(text, "versionCode = 77", "versionCode = 78", "versionCode")
    text = replace_once(text, 'versionName = "1.0.0-dev.77"', 'versionName = "1.0.0-dev.78"', "versionName")
    return text


rewrite(ROOT / "app/build.gradle.kts", patch_gradle)

(ROOT / "DEV_CURRENT_CHANGELOG.txt").write_text(
    "dev.78：修复历史比赛运营数据的蓝红方/队伍身份反转。Riot LiveStats 历史帧中的 GOLD/K/T/D/B 等数值本来按真实 BLUE/RED side 返回，但旧逻辑在 Riot teamId 缺失或无法匹配时错误地用赛程 teams[0]/teams[1] 当作蓝/红方；赛程展示顺序并不等于单局选边，因此会出现终局 IG 55.7k / LGD 46.5k 正确，而经济曲线却显示 LGD 55.7k / IG 46.5k 的假反转。现在历史帧优先按稳定 Riot teamId 绑定；无法绑定时使用该小局已核实终局快照作为 canonical side truth；仍无法确认则只标 BLUE/RED，绝不再用赛程顺序猜队伍。MatchLifecycleArchive 对已落盘旧历史帧也会按该局 finalGames 的蓝红方身份进行只改标签、不交换任何数值的运行时纠正，因此旧比赛无需清缓存或重新抓 800+ 帧即可恢复正确显示。同时同秒数据冲突时终局/current 快照优先。版本升级为 1.0.0-dev.78 / versionCode 78。",
    encoding="utf-8"
)

print("dev.78 side alignment fix applied")
