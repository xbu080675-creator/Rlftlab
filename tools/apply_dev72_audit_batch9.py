#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:140]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) Cross-provider Series de-duplication.
# Raw provider team ids and tournament ids live in different namespaces, so they must not be used
# as hard equality/rejection keys. Match by real team aliases + close scheduled time + BO shape.
replace_once(
    "app/src/main/java/com/riftlab/app/data/LolEsportsDataSources.kt",
    '''    private fun sameSeries(a: ScheduledEsportsMatch, b: ScheduledEsportsMatch): Boolean {\n        if (a.matchId.isNotBlank() && b.matchId.isNotBlank() && a.matchId == b.matchId) return true\n        if (a.eventId.isNotBlank() && b.eventId.isNotBlank() && a.eventId == b.eventId) return true\n        val aTeams = a.teams.take(2).map { teamToken(it.code.ifBlank { it.name }) }.toSet()\n        val bTeams = b.teams.take(2).map { teamToken(it.code.ifBlank { it.name }) }.toSet()\n        if (aTeams.size < 2 || aTeams != bTeams) return false\n        val aDay = a.startTimeIso.take(10)\n        val bDay = b.startTimeIso.take(10)\n        return aDay.isNotBlank() && aDay == bDay\n    }\n''',
    '''    private fun sameSeries(a: ScheduledEsportsMatch, b: ScheduledEsportsMatch): Boolean {\n        if (a.matchId.isNotBlank() && b.matchId.isNotBlank() && a.matchId == b.matchId) return true\n        if (a.eventId.isNotBlank() && b.eventId.isNotBlank() && a.eventId == b.eventId) return true\n        if (a.bestOf > 0 && b.bestOf > 0 && a.bestOf != b.bestOf) return false\n\n        val aStart = parseStart(a.startTimeIso)\n        val bStart = parseStart(b.startTimeIso)\n        if (aStart != null && bStart != null) {\n            val deltaMs = kotlin.math.abs(aStart.toEpochMilli() - bStart.toEpochMilli())\n            if (deltaMs > 90L * 60L * 1000L) return false\n        } else {\n            val aDay = a.startTimeIso.take(10)\n            val bDay = b.startTimeIso.take(10)\n            if (aDay.isBlank() || aDay != bDay) return false\n        }\n\n        val aTeams = a.teams.take(2).map(::teamAliases)\n        val bTeams = b.teams.take(2).map(::teamAliases)\n        if (aTeams.size < 2 || bTeams.size < 2 || aTeams.any { it.isEmpty() } || bTeams.any { it.isEmpty() }) return false\n\n        return aTeams.all { left -> bTeams.any { right -> left.intersect(right).isNotEmpty() } } &&\n            bTeams.all { right -> aTeams.any { left -> right.intersect(left).isNotEmpty() } }\n    }\n\n    private fun teamAliases(team: EsportsTeamRef): Set<String> =\n        listOf(team.slug, team.code, team.name)\n            .map(::teamToken)\n            .filter { it.isNotBlank() }\n            .toSet()\n'''
)

# 2) Participant-origin evidence must name the actual provider for mirrored tournament editions.
replace_once(
    "app/src/main/java/com/riftlab/app/data/QualificationCenter.kt",
    '''        val participantSource = "Tournament Edition participant set · Schedule / Standings / Completed Events"\n        val pendingSource = "等待赛事官方 / Riot / 已核实 Provider"\n''',
    '''        val externalProvider = edition.tournamentId.startsWith("rft-event:") || edition.leagueId.startsWith("rft-event:")\n        val participantSource = if (externalProvider) {\n            "RFT.gg public event mirror · Schedule participant set"\n        } else {\n            "Tournament Edition participant set · Schedule / Standings / Completed Events"\n        }\n        val pendingSource = if (externalProvider) {\n            "等待赛事官方 / 已核实 Provider"\n        } else {\n            "等待赛事官方 / Riot / 已核实 Provider"\n        }\n'''
)

# 3) Non-LPL match detail must not imply every global row came from Riot.
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

# 4) Tournament archive source authority and empty-state labels must follow the actual source.
replace_once(
    "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt",
    '''                            authority = DataAuthority.PROVIDER,\n                            freshness = DataFreshnessClass.DAILY,\n                            verified = false\n                        )\n                    )\n                }\n                if (matches.isNotEmpty()) {\n''',
    '''                            authority = DataAuthority.OFFICIAL,\n                            freshness = DataFreshnessClass.DAILY,\n                            verified = true\n                        )\n                    )\n                }\n                if (matches.isNotEmpty()) {\n'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt",
    '''                            sourceId = "riot-standings",\n                            displayName = "Riot Standings",\n                            authority = DataAuthority.PROVIDER,\n                            freshness = DataFreshnessClass.MINUTES,\n                            verified = false\n''',
    '''                            sourceId = "riot-standings",\n                            displayName = "Riot Standings",\n                            authority = DataAuthority.OFFICIAL,\n                            freshness = DataFreshnessClass.MINUTES,\n                            verified = true\n'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt",
    '''                            sourceId = "riot-completed-events",\n                            displayName = historyOverride.completedEventsSource.ifBlank { "Riot Completed Events" },\n                            authority = DataAuthority.PROVIDER,\n                            freshness = DataFreshnessClass.DAILY,\n                            verified = false\n''',
    '''                            sourceId = "riot-completed-events",\n                            displayName = historyOverride.completedEventsSource.ifBlank { "Riot Completed Events" },\n                            authority = DataAuthority.OFFICIAL,\n                            freshness = DataFreshnessClass.DAILY,\n                            verified = true\n'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt",
    '''                detail = if (governance.draw.slots.isEmpty()) {\n                    "等待官方抽签 / Riot Bracket"\n                } else {\n''',
    '''                detail = if (governance.draw.slots.isEmpty()) {\n                    if (isExternalProviderEdition(edition)) {\n                        "等待赛事官方 / 已核实 Provider 抽签或 Bracket"\n                    } else {\n                        "等待官方抽签 / Riot Bracket"\n                    }\n                } else {\n'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt",
    '''                detail = if (standingsRows > 0) {\n                    "${standings?.stages?.size ?: 0} 个阶段 · $standingsRows 个排名/签位记录"\n                } else {\n                    "等待该届 Riot Standings"\n                },\n''',
    '''                detail = if (standingsRows > 0) {\n                    "${standings?.stages?.size ?: 0} 个阶段 · $standingsRows 个排名/签位记录"\n                } else if (isExternalProviderEdition(edition)) {\n                    "当前可信 Provider 未提供 Standings / Bracket，保持未知"\n                } else {\n                    "等待该届 Riot Standings"\n                },\n'''
)

print("dev72 source-truth audit batch9 applied")
