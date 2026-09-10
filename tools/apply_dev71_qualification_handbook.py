#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:200]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


center = "app/src/main/java/com/riftlab/app/data/QualificationCenter.kt"
audit = "docs/RIFTLAB_DEV71_DATA_COVERAGE_AUDIT.md"

patch(
    center,
    '''            statusMessage = when {
                selected == null -> "当前届次尚无可信资格路径源 · 不根据排名猜测晋级"
                selected.routes.isEmpty() -> "${selected.title} · 资格来源待可信数据"
                else -> "${selected.title} · ${selected.routes.size} 支队伍资格状态已进入独立路径模型"
            }
''',
    '''            statusMessage = when {
                selected == null -> "当前届次尚无可信资格路径源 · 不根据排名猜测晋级"
                selected.routes.isEmpty() && selected.mechanism != QualificationMechanism.UNKNOWN ->
                    "${selected.title} · ${selected.mechanism.label} 已核实，队伍级路径待正式赛果/席位确认"
                selected.routes.isEmpty() -> "${selected.title} · 资格来源待可信数据"
                else -> "${selected.title} · ${selected.routes.size} 支队伍资格状态已进入独立路径模型"
            }
'''
)

anchor = '''        // International target events use participant-origin semantics. Observing a team in the
        // tournament participant set proves participation, not the region/seed/path that qualified it.
'''
insert = '''        val handbookQualification = OfficialHandbookGovernance2026.qualificationSummaryFor(
            tournament = edition.toTournamentRef(),
            competitionTitle = edition.displayName,
            identity = "${edition.family} ${edition.stage} ${edition.slug} ${edition.leagueSlug} ${edition.leagueName}"
        )
        if (handbookQualification != null && edition.family.uppercase() !in setOf("WORLDS", "MSI", "FIRST_STAND")) {
            return buildOfficialRegionalMechanismSnapshot(edition, archive, standingsState, handbookQualification)
        }

'''
patch(center, anchor, insert + anchor)

insert_before_international = '''    private fun buildInternationalParticipationSnapshot(
'''
helper = '''    private fun buildOfficialRegionalMechanismSnapshot(
        edition: TournamentEditionArchiveRecord,
        archive: TournamentEditionArchiveState,
        standingsState: StandingsCenterState,
        handbookQualification: Pair<String, String>
    ): QualificationTournamentSnapshot {
        val detail = archive.selected?.takeIf { it.edition.tournamentId == edition.tournamentId }
        val standings = detail?.standings
            ?: standingsState.standings?.takeIf { it.tournamentId == edition.tournamentId }
        val governance = detail?.governance ?: TournamentGovernanceProvider.resolve(
            tournament = edition.toTournamentRef(),
            competitionTitle = edition.displayName,
            matches = detail?.matchedSeries.orEmpty(),
            standings = standings
        )
        val rules = governance.rules.items.map { rule ->
            QualificationRuleRecord(
                title = rule.title,
                detail = rule.detail,
                source = rule.source,
                evidence = if (rule.verified) QualificationEvidence.OFFICIAL else QualificationEvidence.DERIVED
            )
        }
        val mechanism = officialRegionalMechanism(edition)
        return QualificationTournamentSnapshot(
            tournamentId = edition.tournamentId,
            title = "${edition.displayName} · 官方资格体系",
            targetEvent = "2026 全球总决赛",
            routes = emptyList(),
            rules = rules,
            sourceSummary = listOf(handbookQualification.second, governance.rules.sourceSummary)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" + "),
            note = "资格机制已由 Riot League Handbook 核实；队伍级已锁定/仍可争夺/已淘汰状态只在正式赛果或明确席位数据足够时生成，不从参赛名单猜。",
            mechanism = mechanism,
            mechanismEvidence = QualificationEvidence.OFFICIAL,
            mechanismDetail = handbookQualification.first
        )
    }

    private fun officialRegionalMechanism(edition: TournamentEditionArchiveRecord): QualificationMechanism {
        val token = "${edition.leagueId} ${edition.leagueSlug} ${edition.leagueName}".uppercase()
        return when {
            token.contains("113476371197627891") || token.contains("LCP") -> QualificationMechanism.MIXED
            token.contains("98767991314006698") || token.contains("LPL") -> QualificationMechanism.MIXED
            token.contains("98767991310872058") || token.contains("LCK") -> QualificationMechanism.DIRECT_PLACEMENT
            token.contains("98767991299243165") || token.contains("LCS") -> QualificationMechanism.DIRECT_PLACEMENT
            token.contains("98767991332355509") || token.contains("CBLOL") -> QualificationMechanism.DIRECT_PLACEMENT
            // Riot's current LEC page lists top 1/2/3 as Worlds qualifiers while its Playoffs prose
            // explicitly mentions only the top two. Keep mechanism unknown rather than inventing #3.
            token.contains("98767991302996019") || token.contains("LEC") -> QualificationMechanism.UNKNOWN
            else -> QualificationMechanism.UNKNOWN
        }
    }

'''
patch(center, insert_before_international, helper + insert_before_international)

p = ROOT / audit
text = p.read_text(encoding="utf-8")
addition = '''
## 2026-09-10 · qualification center handbook tranche

- QualificationCenter now consumes the same Riot Handbook source used by Tournament Governance instead of leaving verified regional rule systems as `UNKNOWN` merely because there are no team routes yet.
- LCK/LCS/CBLOL are classified as direct-placement systems for the matched 2026 Split 3 editions; LCP remains mixed because the official page explicitly combines top-two direct qualification with a Championship Points slot.
- LEC intentionally remains mechanism `UNKNOWN` with official evidence because Riot's current page does not fully explain the third Worlds place; the official rule text is still shown.
- Team-level locked/contending/eliminated states are not inferred from participant lists.
'''
if addition.strip() not in text:
    p.write_text(text.rstrip() + "\n" + addition, encoding="utf-8")

print("dev71 QualificationCenter handbook patch prepared")
