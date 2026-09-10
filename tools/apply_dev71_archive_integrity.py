#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt"
text = PATH.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    if old not in text:
        raise SystemExit(f"pattern not found: {old[:180]!r}")
    text = text.replace(old, new, 1)


# A rebuilt directory record must not erase patch versions already recovered from LiveStats.
replace_once(
    '''                participantTeamCodes = (old?.participantTeamCodes.orEmpty() + knownTeams).distinct().sorted(),
                scheduleSeriesCount = maxOf(old?.scheduleSeriesCount ?: 0, matches.size),
                archivedSlots = old?.archivedSlots.orEmpty(),''',
    '''                participantTeamCodes = (old?.participantTeamCodes.orEmpty() + knownTeams).distinct().sorted(),
                scheduleSeriesCount = maxOf(old?.scheduleSeriesCount ?: 0, matches.size),
                patchVersions = old?.patchVersions.orEmpty(),
                archivedSlots = old?.archivedSlots.orEmpty(),'''
)

# Reopening an already hydrated old edition should immediately reuse its cached historical standings.
replace_once(
    '''            record = record,
            schedule = MatchSessionStore.schedule.value,
            standingsState = StandingsCenterStore.state.value,
            historyOverride = cachedHydratedHistory(record.tournamentId)
        )''',
    '''            record = record,
            schedule = MatchSessionStore.schedule.value,
            standingsState = StandingsCenterStore.state.value,
            standingsOverride = cachedHydratedStandings(record.tournamentId),
            historyOverride = cachedHydratedHistory(record.tournamentId)
        )'''
)

# Completed Events is itself a participant source. Old tournaments with no current schedule rows must
# recover their team set from the historical series, otherwise qualification-origin UI stays empty.
replace_once(
    '''        val standingsTeams = standings.orEmptyTeamCodes()
        val participantTeams = (record.participantTeamCodes + standingsTeams).distinct().sorted()
        val provisional = record.copy(''',
    '''        val standingsTeams = standings.orEmptyTeamCodes()
        val matchTeams = matches
            .flatMap { it.teams }
            .map { it.code.ifBlank { it.name }.trim().uppercase() }
            .filter { it.isNotBlank() && it != "TBD" && it != "—" }
        val participantTeams = (record.participantTeamCodes + standingsTeams + matchTeams)
            .distinct()
            .sorted()
        val provisional = record.copy('''
)

PATH.write_text(text, encoding="utf-8")
print("dev71 archive integrity patch prepared")
