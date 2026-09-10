#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


governance = "app/src/main/java/com/riftlab/app/data/TournamentGovernance.kt"
archive = "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt"
audit = "docs/RIFTLAB_DEV71_DATA_COVERAGE_AUDIT.md"

patch(
    governance,
    '''        val rules = if (is2026LplThirdStage(identity, tournament, matches)) {
            verified2026LplWorldsQualificationRules()
        } else {
            deriveRules(competitionTitle, matches, standings)
        }
''',
    '''        val rules = if (is2026LplThirdStage(identity, tournament, matches)) {
            verified2026LplWorldsQualificationRules()
        } else {
            OfficialHandbookGovernance2026.rulesFor(
                tournament = tournament,
                competitionTitle = competitionTitle,
                identity = identity
            ) ?: deriveRules(competitionTitle, matches, standings)
        }
'''
)

patch(
    archive,
    '''        val standingsTeams = standings.orEmptyTeamCodes()
        val matchTeams = matches
            .flatMap { it.teams }
            .map { it.code.ifBlank { it.name }.trim().uppercase() }
            .filter { it.isNotBlank() && it != "TBD" && it != "—" }
        val participantTeams = (record.participantTeamCodes + standingsTeams + matchTeams)
            .distinct()
            .sorted()
''',
    '''        val standingsTeams = standings.orEmptyTeamCodes()
        val matchTeams = matches
            .flatMap { it.teams }
            .map { it.code.ifBlank { it.name }.trim().uppercase() }
            .filter { it.isNotBlank() && it != "TBD" && it != "—" }
        val handbookTeams = OfficialHandbookGovernance2026.participantCodesFor(ref, record.displayName)
        val handbookParticipantSource = OfficialHandbookGovernance2026.participantSourceFor(ref, record.displayName)
        val qualificationSummary = OfficialHandbookGovernance2026.qualificationSummaryFor(
            tournament = ref,
            competitionTitle = record.displayName,
            identity = "${record.family} ${record.stage} ${record.slug} ${record.leagueSlug} ${record.leagueName}"
        )
        val participantTeams = (record.participantTeamCodes + standingsTeams + matchTeams + handbookTeams)
            .distinct()
            .sorted()
'''
)

patch(
    archive,
    '''        val liveSlots = buildSlots(provisional, matches, standings, governance, research, historyOverride)
''',
    '''        val liveSlots = buildSlots(
            provisional,
            matches,
            standings,
            governance,
            research,
            historyOverride,
            handbookParticipantSource,
            qualificationSummary
        )
'''
)

patch(
    archive,
    '''        research: TournamentResearchSnapshot,
        history: TournamentEventHistorySnapshot?
    ): List<TournamentEditionSlot> {
''',
    '''        research: TournamentResearchSnapshot,
        history: TournamentEventHistorySnapshot?,
        handbookParticipantSource: String,
        handbookQualification: Pair<String, String>?
    ): List<TournamentEditionSlot> {
'''
)

patch(
    archive,
    '''                source = when {
                    history?.completedSeries?.isNotEmpty() == true && standings != null -> "Riot getCompletedEvents + Riot Standings"
                    history?.completedSeries?.isNotEmpty() == true -> history.completedEventsSource
                    standings != null -> "Unified Schedule + Riot Standings"
                    else -> "Unified Schedule"
                }
            ),
            TournamentEditionSlot(
                key = "qualification",
                label = "资格来源",
                state = TournamentEditionSlotState.PENDING,
                detail = "资格体系使用独立模型：Championship Points、名次直通、资格赛与国际赛参赛来源分开记录；不从参赛名单反推具体 Seed / 晋级原因。"
            ),
''',
    '''                source = when {
                    handbookParticipantSource.isNotBlank() && history?.completedSeries?.isNotEmpty() == true && standings != null -> "Riot Handbook + Riot getCompletedEvents + Riot Standings"
                    handbookParticipantSource.isNotBlank() -> handbookParticipantSource
                    history?.completedSeries?.isNotEmpty() == true && standings != null -> "Riot getCompletedEvents + Riot Standings"
                    history?.completedSeries?.isNotEmpty() == true -> history.completedEventsSource
                    standings != null -> "Unified Schedule + Riot Standings"
                    else -> "Unified Schedule"
                }
            ),
            TournamentEditionSlot(
                key = "qualification",
                label = "资格来源",
                state = if (handbookQualification != null) TournamentEditionSlotState.PARTIAL else TournamentEditionSlotState.PENDING,
                detail = handbookQualification?.first
                    ?: "资格体系使用独立模型：Championship Points、名次直通、资格赛与国际赛参赛来源分开记录；不从参赛名单反推具体 Seed / 晋级原因。",
                source = handbookQualification?.second.orEmpty()
            ),
'''
)

p = ROOT / audit
text = p.read_text(encoding="utf-8")
addition = '''
## 2026-09-10 · Riot League Handbook tranche

- Added a verified 2026 Riot League Handbook governance source for LPL, LCK, LCP, LEC, LCS and CBLOL Split 3 plus First Stand / MSI / Worlds.
- Tournament rules now prefer an official Handbook snapshot over Riot Schedule/Standings structural inference when the edition identity matches.
- 2026 Worlds can recover already-confirmed qualifying participant codes from Riot's official Handbook even before the event appears in the normal schedule window.
- Qualification coverage is now `PARTIAL` when the Handbook explicitly establishes the mechanism; team seed/origin remains unknown unless Riot explicitly states it.
- LEC's Handbook currently contains wording that does not fully explain the third Worlds place. RiftLab preserves that ambiguity instead of inventing the missing mechanism.
'''
if addition.strip() not in text:
    p.write_text(text.rstrip() + "\n" + addition, encoding="utf-8")

print("dev71 Riot handbook governance patch prepared")
