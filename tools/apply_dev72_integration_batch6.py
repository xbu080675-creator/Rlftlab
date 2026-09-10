#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected block not found in {path}: {old[:120]!r}")
    if text.count(old) != 1:
        raise SystemExit(f"expected exactly one block in {path}, found {text.count(old)}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "app/src/main/java/com/riftlab/app/data/LolEsportsDataSources.kt",
    '''    override suspend fun fetchLeagueSchedule(): List<ScheduledEsportsMatch> {
        val riot = client.fetchGlobalSchedule().map(::verifySeriesCompletion)
        val citoRows = runCatching { cito.fetch() }.getOrDefault(emptyList()).map(::verifySeriesCompletion)
        val merged = mergeSchedule(riot, citoRows)
        CitoArchiveCoordinator.observe(merged)
        return TeamAssetCatalog.enrichMatches(merged)
    }
''',
    '''    override suspend fun fetchLeagueSchedule(): List<ScheduledEsportsMatch> {
        val riot = client.fetchGlobalSchedule().map(::verifySeriesCompletion)
        val citoRows = runCatching { cito.fetch() }
            .getOrDefault(emptyList())
            .map(::verifySeriesCompletion)
        val internationalRows = runCatching { InternationalEventMirrorProvider.fetchMatches() }
            .getOrDefault(emptyList())
            .map(::verifySeriesCompletion)
        val merged = mergeSchedule(mergeSchedule(riot, citoRows), internationalRows)
        CitoArchiveCoordinator.observe(merged)
        return TeamAssetCatalog.enrichMatches(merged)
    }
'''
)

replace_once(
    "app/src/main/java/com/riftlab/app/data/LolEsportsDataSources.kt",
    '''    override suspend fun fetchLeagueTournaments(): List<EsportsTournamentRef> {
        val tournaments = client.fetchGlobalTournaments()
        tournamentRefs.clear()
        tournaments.forEach { tournamentRefs[it.id] = it }
        return tournaments
    }

    override suspend fun fetchStandings(tournamentId: String): TournamentStandings? {
        val riot = runCatching { client.fetchTournamentStandings(tournamentId) }.getOrNull()
        val hasRiotRows = riot?.stages?.any { stage ->
            stage.sections.any { it.rankings.isNotEmpty() || it.matches.isNotEmpty() }
        } == true
        if (hasRiotRows) return riot
        val tournament = tournamentRefs[tournamentId] ?: return riot
        return runCatching { cito.fetch(tournament) }.getOrNull() ?: riot
    }
''',
    '''    override suspend fun fetchLeagueTournaments(): List<EsportsTournamentRef> {
        val riot = client.fetchGlobalTournaments()
        val international = runCatching { InternationalEventMirrorProvider.fetchTournaments() }
            .getOrDefault(emptyList())
        val tournaments = (riot + international).distinctBy { it.id }
        tournamentRefs.clear()
        tournaments.forEach { tournamentRefs[it.id] = it }
        return tournaments
    }

    override suspend fun fetchStandings(tournamentId: String): TournamentStandings? {
        // Provider-only international events currently contribute schedule / participants / results,
        // not a fabricated standings table. Keep Standings explicitly unavailable until a verified
        // provider standings feed is added.
        if (tournamentId.startsWith("rft-event:")) return null

        val riot = runCatching { client.fetchTournamentStandings(tournamentId) }.getOrNull()
        val hasRiotRows = riot?.stages?.any { stage ->
            stage.sections.any { it.rankings.isNotEmpty() || it.matches.isNotEmpty() }
        } == true
        if (hasRiotRows) return riot
        val tournament = tournamentRefs[tournamentId] ?: return riot
        return runCatching { cito.fetch(tournament) }.getOrNull() ?: riot
    }
'''
)

replace_once(
    "tools/sync_international_events.py",
    'OUTPUT = Path("data/global/international_events.json")\n',
    'OUTPUT = Path("data/global/international_events.json")\nASSET_OUTPUT = Path("app/src/main/assets/data/international_events.json")\n'
)

replace_once(
    "tools/sync_international_events.py",
    '''    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(root, ensure_ascii=False, indent=2) + "\\n", encoding="utf-8")
    print(f"wrote {OUTPUT}: {len(events)} events / {len(normalized)} matches", file=sys.stderr)
''',
    '''    payload = json.dumps(root, ensure_ascii=False, indent=2) + "\\n"
    for output in (OUTPUT, ASSET_OUTPUT):
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(payload, encoding="utf-8")
    print(
        f"wrote {OUTPUT} + {ASSET_OUTPUT}: {len(events)} events / {len(normalized)} matches",
        file=sys.stderr,
    )
'''
)

print("dev72 integration batch6 source patches applied")
