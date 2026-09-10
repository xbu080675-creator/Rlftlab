#!/usr/bin/env python3
from pathlib import Path

PATH = Path("app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt")
text = PATH.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"MatchSessionStore.kt: expected one match, got {count}: {old[:180]!r}")
    text = text.replace(old, new, 1)


# Provider-local numeric team IDs are not Riot team IDs. For RFT-backed schedules, do not send
# those IDs/slugs through Riot/Cito roster lookup until an explicit cross-provider identity map exists.
replace_once(
    '''        val leftDetails = runCatching {\n            teamLookupSlug(left)?.let { teamSource.fetchTeam(it) }\n        }.getOrNull()\n        val rightDetails = runCatching {\n            teamLookupSlug(right)?.let { teamSource.fetchTeam(it) }\n        }.getOrNull()\n\n        val leftRiotRoster = leftDetails?.players.orEmpty().toPlayerCards()\n        val rightRiotRoster = rightDetails?.players.orEmpty().toPlayerCards()\n''',
    '''        val externalProviderTarget = isExternalProviderTarget(target)\n        val leftDetails = if (externalProviderTarget) {\n            null\n        } else {\n            runCatching { teamLookupSlug(left)?.let { teamSource.fetchTeam(it) } }.getOrNull()\n        }\n        val rightDetails = if (externalProviderTarget) {\n            null\n        } else {\n            runCatching { teamLookupSlug(right)?.let { teamSource.fetchTeam(it) } }.getOrNull()\n        }\n\n        val leftRiotRoster = leftDetails?.players.orEmpty().toPlayerCards()\n        val rightRiotRoster = rightDetails?.players.orEmpty().toPlayerCards()\n'''
)

replace_once(
    '''            rosterNote = when {\n                connectedCount == 2 && autoStarterCount == 2 ->\n                    "两队 roster 已连接且五位置均唯一；可作为当前 roster 五人展示，但仍不把 roster pool 额外成员擅自标成替补。Rank 继续等待独立 Ranked 数据源。"\n''',
    '''            rosterNote = when {\n                externalProviderTarget ->\n                    "当前赛程来自外部赛事 Provider；Provider team id 不会冒充 Riot team id。尚未建立可信跨源映射前，Roster / Staff 保持未知。"\n                connectedCount == 2 && autoStarterCount == 2 ->\n                    "两队 roster 已连接且五位置均唯一；可作为当前 roster 五人展示，但仍不把 roster pool 额外成员擅自标成替补。Rank 继续等待独立 Ranked 数据源。"\n'''
)

replace_once(
    '''        _rosterStatus.value = "ROSTER · TEAM SOURCES $connectedCount/2 · AUTO STARTERS $autoStarterCount/2"\n        return "$connectedCount/2"\n''',
    '''        _rosterStatus.value = if (externalProviderTarget) {\n            "ROSTER · PROVIDER TEAM NAMESPACE · CROSSWALK PENDING"\n        } else {\n            "ROSTER · TEAM SOURCES $connectedCount/2 · AUTO STARTERS $autoStarterCount/2"\n        }\n        return "$connectedCount/2"\n'''
)

# Raw IDs are source-local and can collide numerically across Riot/RFT/Cito. Identity matching for
# recent form/H2H therefore uses human/stable aliases only. The schedule-level deduper follows the
# same rule in LolEsportsDataSources.
replace_once(
    '''        val a = listOf(candidate.id, candidate.code, candidate.name, candidate.slug)\n            .map(::teamIdentityToken)\n            .filter { it.isNotBlank() }\n            .toSet()\n        val b = listOf(target.id, target.code, target.name, target.slug)\n            .map(::teamIdentityToken)\n            .filter { it.isNotBlank() }\n            .toSet()\n''',
    '''        val a = listOf(candidate.slug, candidate.code, candidate.name)\n            .map(::teamIdentityToken)\n            .filter { it.isNotBlank() }\n            .toSet()\n        val b = listOf(target.slug, target.code, target.name)\n            .map(::teamIdentityToken)\n            .filter { it.isNotBlank() }\n            .toSet()\n'''
)

# Riot getTeams accepts stable slugs. Prefer those over numeric IDs so the normal Riot path is less
# coupled to source-local identifiers; ID remains a fallback for rows without a slug.
replace_once(
    '''    private fun teamLookupSlug(team: EsportsTeamRef): String? {\n        if (team.id.isNotBlank()) return team.id\n        if (team.slug.isNotBlank()) return team.slug\n\n        return team.name\n''',
    '''    private fun teamLookupSlug(team: EsportsTeamRef): String? {\n        if (team.slug.isNotBlank()) return team.slug\n        if (team.id.isNotBlank()) return team.id\n\n        return team.name\n'''
)

# This field can contain Riot-only data or a Riot/Cito merge, so don't label every player card as
# Riot roster unless the normalized model actually carries that provenance.
replace_once(
    '''                    recent = "Riot roster"\n''',
    '''                    recent = "Verified team source"\n'''
)

# Keep the namespace check local and explicit. eventId is provider:<match>, while leagueId is the
# stable rft-event:<slug> tournament id created by InternationalEventMirrorProvider.
needle = '''    private fun matchesTeamIdentity(candidate: EsportsTeamRef, target: EsportsTeamRef): Boolean {\n'''
helper = '''    private fun isExternalProviderTarget(match: ScheduledEsportsMatch): Boolean =\n        match.eventId.startsWith("provider:") ||\n            match.matchId.startsWith("provider:") ||\n            match.leagueId.startsWith("rft-event:")\n\n'''
if text.count(needle) != 1:
    raise SystemExit("MatchSessionStore.kt: identity helper insertion point not found")
text = text.replace(needle, helper + needle, 1)

PATH.write_text(text, encoding="utf-8")
print("dev72 provider namespace safety batch12 applied")
