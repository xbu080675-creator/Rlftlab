#!/usr/bin/env python3
from pathlib import Path
import runpy

# Batch10 is based on the pre-commit Batch9 branch snapshot. Reapply Batch9 source repairs first so
# this branch remains self-contained even if the Batch9 runner later finishes independently.
runpy.run_path("tools/apply_dev72_audit_batch9.py", run_name="__main__")


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:160]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) Comprehensive graph: never bind a match to an unrelated tournament merely because the dates
# overlap. Among overlapping editions of the same league, prefer the edition that started latest.
replace_once(
    "app/src/main/java/com/riftlab/app/data/ComprehensiveDataCenter.kt",
    '''        val dated = targetDate?.let { date ->\n            leagueMatched.filter { StandingsCenterStore.containsDate(it, date) }\n        }.orEmpty()\n        return dated.minByOrNull { it.startDate }\n            ?: leagueMatched.maxByOrNull { it.startDate }\n            ?: tournaments.firstOrNull { tournament ->\n                targetDate != null && StandingsCenterStore.containsDate(tournament, targetDate)\n            }\n''',
    '''        if (leagueMatched.isEmpty()) return null\n        val dated = targetDate?.let { date ->\n            leagueMatched.filter { StandingsCenterStore.containsDate(it, date) }\n        }.orEmpty()\n        return dated.maxByOrNull { it.startDate }\n            ?: leagueMatched.maxByOrNull { it.startDate }\n'''
)

# 2) Durable archive follows the same rule. firstOrNull depended on upstream directory ordering and
# could attach the home target to an older overlapping edition.
replace_once(
    "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt",
    '''        val candidates = tournaments.filter { ref -> leagueMatches(ref, target) }\n        if (date != null) {\n            candidates.firstOrNull { ref -> containsDate(ref, date) }?.let { return it }\n        }\n        return candidates.maxByOrNull { it.startDate }\n''',
    '''        val candidates = tournaments.filter { ref -> leagueMatches(ref, target) }\n        if (date != null) {\n            candidates\n                .filter { ref -> containsDate(ref, date) }\n                .maxByOrNull { it.startDate }\n                ?.let { return it }\n        }\n        return candidates.maxByOrNull { it.startDate }\n'''
)

# 3) Standings default selection also must not depend on provider list order when several current
# tournaments overlap. Latest start date is the most specific current edition in our directory model.
replace_once(
    "app/src/main/java/com/riftlab/app/data/StandingsCenterStore.kt",
    '''        return tournaments.firstOrNull { tournament ->\n            val start = parseDate(tournament.startDate)\n            val end = parseDate(tournament.endDate)\n            start != null && end != null && !today.isBefore(start) && !today.isAfter(end)\n        } ?: tournaments\n            .filter { parseDate(it.startDate)?.let { date -> !date.isAfter(today) } == true }\n            .maxByOrNull { it.startDate }\n            ?: tournaments.lastOrNull()\n''',
    '''        return tournaments\n            .filter { tournament ->\n                val start = parseDate(tournament.startDate)\n                val end = parseDate(tournament.endDate)\n                start != null && end != null && !today.isBefore(start) && !today.isAfter(end)\n            }\n            .maxByOrNull { it.startDate }\n            ?: tournaments\n                .filter { parseDate(it.startDate)?.let { date -> !date.isAfter(today) } == true }\n                .maxByOrNull { it.startDate }\n            ?: tournaments.minByOrNull { tournament ->\n                parseDate(tournament.startDate)?.let { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, it)) }\n                    ?: Long.MAX_VALUE\n            }\n'''
)

# 4) Bracket empty state is a data-source boundary, not a Riot-only promise. This also covers
# provider-backed international events whose standings/bracket feed is intentionally unknown.
replace_once(
    "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt",
    '''        EmptyData("等待 Riot Standings 淘汰赛数据")\n''',
    '''        EmptyData("当前可信数据源尚未提供淘汰赛 / Bracket 数据")\n'''
)

print("dev72 tournament selection audit batch10 applied")
