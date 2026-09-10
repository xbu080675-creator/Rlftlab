#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Provider mirrors can expose the same Series twice with different short-code completeness or
# provider ids. Compare stable team aliases and tournament/stage identity instead of code alone.
replace_once(
    "app/src/main/java/com/riftlab/app/data/LolEsportsDataSources.kt",
    '''    private fun sameSeries(a: ScheduledEsportsMatch, b: ScheduledEsportsMatch): Boolean {\n        if (a.matchId.isNotBlank() && b.matchId.isNotBlank() && a.matchId == b.matchId) return true\n        if (a.eventId.isNotBlank() && b.eventId.isNotBlank() && a.eventId == b.eventId) return true\n        val aTeams = a.teams.take(2).map { teamToken(it.code.ifBlank { it.name }) }.toSet()\n        val bTeams = b.teams.take(2).map { teamToken(it.code.ifBlank { it.name }) }.toSet()\n        if (aTeams.size < 2 || aTeams != bTeams) return false\n        val aDay = a.startTimeIso.take(10)\n        val bDay = b.startTimeIso.take(10)\n        return aDay.isNotBlank() && aDay == bDay\n    }\n''',
    '''    private fun sameSeries(a: ScheduledEsportsMatch, b: ScheduledEsportsMatch): Boolean {\n        if (a.matchId.isNotBlank() && b.matchId.isNotBlank() && a.matchId == b.matchId) return true\n        if (a.eventId.isNotBlank() && b.eventId.isNotBlank() && a.eventId == b.eventId) return true\n\n        if (a.leagueId.isNotBlank() && b.leagueId.isNotBlank() && a.leagueId != b.leagueId) return false\n        if (a.leagueSlug.isNotBlank() && b.leagueSlug.isNotBlank() && !a.leagueSlug.equals(b.leagueSlug, true)) return false\n        if (a.bestOf > 0 && b.bestOf > 0 && a.bestOf != b.bestOf) return false\n\n        val aStage = teamToken(a.blockName)\n        val bStage = teamToken(b.blockName)\n        if (aStage.isNotBlank() && bStage.isNotBlank() && aStage != bStage) return false\n\n        val aDay = a.startTimeIso.take(10)\n        val bDay = b.startTimeIso.take(10)\n        if (aDay.isBlank() || aDay != bDay) return false\n\n        val aTeams = a.teams.take(2)\n        val bTeams = b.teams.take(2)\n        if (aTeams.size < 2 || bTeams.size < 2) return false\n        return aTeams.all { left ->\n            val leftAliases = teamAliases(left)\n            leftAliases.isNotEmpty() && bTeams.any { right ->\n                leftAliases.intersect(teamAliases(right)).isNotEmpty()\n            }\n        } && bTeams.all { right ->\n            val rightAliases = teamAliases(right)\n            rightAliases.isNotEmpty() && aTeams.any { left ->\n                rightAliases.intersect(teamAliases(left)).isNotEmpty()\n            }\n        }\n    }\n\n    private fun teamAliases(team: EsportsTeamRef): Set<String> =\n        listOf(team.id, team.slug, team.code, team.name)\n            .map(::teamToken)\n            .filter { it.isNotBlank() }\n            .toSet()\n'''
)

# Participant-origin evidence must name the actual provider for external tournament editions.
replace_once(
    "app/src/main/java/com/riftlab/app/data/QualificationCenter.kt",
    '''        val participantSource = "Tournament Edition participant set · Schedule / Standings / Completed Events"\n        val pendingSource = "等待赛事官方 / Riot / 已核实 Provider"\n''',
    '''        val externalProvider = edition.tournamentId.startsWith("rft-event:") || edition.leagueId.startsWith("rft-event:")\n        val participantSource = if (externalProvider) {\n            "RFT.gg public event mirror · Schedule participant set"\n        } else {\n            "Tournament Edition participant set · Schedule / Standings / Completed Events"\n        }\n        val pendingSource = if (externalProvider) {\n            "等待赛事官方 / 已核实 Provider"\n        } else {\n            "等待赛事官方 / Riot / 已核实 Provider"\n        }\n'''
)

# Non-LPL detail pages may originate from Riot, Cito or the international mirror. Do not label the
# whole path Riot merely because Riot is one possible global source.
replace_once(
    "app/src/main/java/com/riftlab/app/data/MatchDetailRepository.kt",
    '''            } else {\n                OfficialDraftResult(status = "BP · 非 LPL 使用 Riot/OP.GG 可核实补充")\n            }\n''',
    '''            } else {\n                OfficialDraftResult(status = "BP · 非 LPL 使用可核实全球补充源（当前 OP.GG；缺失则保持未知）")\n            }\n'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/MatchDetailRepository.kt",
    '''                    !lplMatch -> "${matchWithRiotAssets.league} · Riot/OP.GG 全球赛后链路 · ${opgg.status} · 未调用 LPL BMatch/TJStats"\n''',
    '''                    !lplMatch -> "${matchWithRiotAssets.league} · 全球赛后补充链路 · ${opgg.status} · 未调用 LPL BMatch/TJStats"\n'''
)

print("dev72 duplicate/source audit batch8 applied")
