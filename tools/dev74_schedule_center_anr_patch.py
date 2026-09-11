#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ui = ROOT / "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt"
gradle = ROOT / "app/build.gradle.kts"
changelog = ROOT / "DEV_CURRENT_CHANGELOG.txt"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one replacement target, found {count}\n{old[:600]}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Move expensive directory construction off the Compose/UI thread. The tournament catalogue arrives
# asynchronously a moment after the dialog opens, which previously caused a large synchronous
# tournaments × matches scan in composition and could make Android report the app as not responding.
replace_once(
    ui,
    "import androidx.compose.runtime.mutableStateOf\n",
    "import androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.produceState\n",
)
replace_once(
    ui,
    "import com.riftlab.app.data.ResearchEvidence\n",
    "import com.riftlab.app.data.ResearchEvidence\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext\n",
)
replace_once(
    ui,
    """    val buckets = remember(center.matches, standingsCenter.tournaments, archiveCenter.editions) {
        buildCompetitionBuckets(center.matches, standingsCenter.tournaments, archiveCenter.editions)
    }
""",
    """    val buckets by produceState(
        initialValue = emptyList<ScheduleCompetitionBucket>(),
        center.matches,
        standingsCenter.tournaments,
        archiveCenter.editions
    ) {
        value = withContext(Dispatchers.Default) {
            buildCompetitionBuckets(center.matches, standingsCenter.tournaments, archiveCenter.editions)
        }
    }
""",
)

text = ui.read_text(encoding="utf-8")
start = text.index("private fun buildCompetitionBuckets(\n")
end = text.index("private fun tournamentStartEpochMs(", start)
replacement = r'''private data class IndexedScheduleMatch(
    val match: ScheduledEsportsMatch,
    val date: LocalDate
)

/**
 * Index schedule rows once before binding them to tournament editions.
 *
 * The old implementation scanned every schedule row for every tournament. After dev.73 expanded
 * the global Riot schedule and tournament catalogue, the standings catalogue arriving shortly after
 * opening the dialog could turn that into millions of date/string comparisons on the UI thread.
 */
private class ScheduleMatchIndex(matches: List<ScheduledEsportsMatch>) {
    private val indexed = matches.mapNotNull { match ->
        matchStartDate(match)?.let { date -> IndexedScheduleMatch(match, date) }
    }
    private val byLeagueId = indexed
        .filter { it.match.leagueId.isNotBlank() }
        .groupBy { it.match.leagueId }
    private val byLeagueSlug = indexed
        .filter { it.match.leagueSlug.isNotBlank() }
        .groupBy { normalizeLeagueToken(it.match.leagueSlug) }
    private val byLeagueName = indexed
        .filter { it.match.league.isNotBlank() }
        .groupBy { normalizeLeagueToken(it.match.league) }
    private val noLeagueIdBySlug = indexed
        .filter { it.match.leagueId.isBlank() && it.match.leagueSlug.isNotBlank() }
        .groupBy { normalizeLeagueToken(it.match.leagueSlug) }
    private val noLeagueIdNoSlugByName = indexed
        .filter { it.match.leagueId.isBlank() && it.match.leagueSlug.isBlank() && it.match.league.isNotBlank() }
        .groupBy { normalizeLeagueToken(it.match.league) }
    private val noSlugByName = indexed
        .filter { it.match.leagueSlug.isBlank() && it.match.league.isNotBlank() }
        .groupBy { normalizeLeagueToken(it.match.league) }

    fun matchesFor(tournament: EsportsTournamentRef): List<ScheduledEsportsMatch> {
        val leagueId = tournament.leagueId
        val leagueSlug = normalizeLeagueToken(tournament.leagueSlug)
        val leagueName = normalizeLeagueToken(tournament.leagueName)
        val candidates = when {
            leagueId.isNotBlank() -> buildList {
                addAll(byLeagueId[leagueId].orEmpty())
                if (leagueSlug.isNotBlank()) addAll(noLeagueIdBySlug[leagueSlug].orEmpty())
                if (leagueName.isNotBlank()) addAll(noLeagueIdNoSlugByName[leagueName].orEmpty())
            }
            leagueSlug.isNotBlank() -> buildList {
                addAll(byLeagueSlug[leagueSlug].orEmpty())
                if (leagueName.isNotBlank()) addAll(noSlugByName[leagueName].orEmpty())
            }
            leagueName.isNotBlank() -> byLeagueName[leagueName].orEmpty()
            else -> emptyList()
        }
        val start = runCatching { LocalDate.parse(tournament.startDate.take(10)) }.getOrNull() ?: return emptyList()
        val end = runCatching { LocalDate.parse(tournament.endDate.take(10)) }.getOrNull() ?: return emptyList()
        return candidates.asSequence()
            .filter { row -> !row.date.isBefore(start) && !row.date.isAfter(end) }
            .map { it.match }
            .distinctBy(::scheduleIdentity)
            .sortedBy(::matchStartEpochMs)
            .toList()
    }
}

private fun buildCompetitionBuckets(
    matches: List<ScheduledEsportsMatch>,
    tournaments: List<EsportsTournamentRef>,
    archivedEditions: List<TournamentEditionArchiveRecord> = emptyList()
): List<ScheduleCompetitionBucket> {
    // Build the schedule index once. This keeps tournament-directory updates roughly O(matches +
    // matching rows) instead of O(tournaments × matches).
    val matchIndex = ScheduleMatchIndex(matches)

    // Tournament existence comes from the Tournament Directory / durable archive. A temporarily
    // empty schedule only means that the match list is still syncing (or has not been published);
    // it must never delete the event itself from the directory.
    val official = tournaments.map { tournament ->
        val tournamentMatches = matchIndex.matchesFor(tournament)
        ScheduleCompetitionBucket(
            key = tournament.id,
            title = StandingsCenterStore.displayTournamentName(tournament),
            matches = tournamentMatches,
            firstEpochMs = tournamentMatches.minOfOrNull(::matchStartEpochMs) ?: tournamentStartEpochMs(tournament),
            tournamentId = tournament.id,
            tournament = tournament,
            researchOnly = tournamentMatches.isEmpty()
        )
    }

    val officialIds = official.mapNotNull { it.tournamentId }.toSet()
    val archived = archivedEditions
        .filter { it.tournamentId.isNotBlank() && it.tournamentId !in officialIds }
        .map { edition ->
            val tournament = EsportsTournamentRef(
                id = edition.tournamentId,
                slug = edition.slug,
                startDate = edition.startDate,
                endDate = edition.endDate,
                leagueId = edition.leagueId,
                leagueSlug = edition.leagueSlug,
                leagueName = edition.leagueName
            )
            val editionMatches = matchIndex.matchesFor(tournament)
            ScheduleCompetitionBucket(
                key = edition.tournamentId,
                title = edition.displayName.ifBlank { StandingsCenterStore.displayTournamentName(tournament) },
                matches = editionMatches,
                firstEpochMs = editionMatches.minOfOrNull(::matchStartEpochMs) ?: tournamentStartEpochMs(tournament),
                tournamentId = edition.tournamentId,
                tournament = tournament,
                researchOnly = editionMatches.isEmpty()
            )
        }

    val directory = official + archived
    val used = directory.asSequence().flatMap { it.matches.asSequence() }.map(::scheduleIdentity).toHashSet()
    val fallback = buildFallbackBuckets(matches.filterNot { scheduleIdentity(it) in used })
    val base = (directory + fallback)
        .distinctBy { it.key }
        .sortedBy { it.firstEpochMs }
    return addAnnualResearchPlaceholders(base)
        .distinctBy { it.key }
        .sortedBy { it.firstEpochMs }
}

'''
ui.write_text(text[:start] + replacement + text[end:], encoding="utf-8")

# dev.73 is already published; this hotfix needs a monotonic OTA version.
replace_once(gradle, "        versionCode = 73\n", "        versionCode = 74\n")
replace_once(gradle, '        versionName = "1.0.0-dev.73"\n', '        versionName = "1.0.0-dev.74"\n')

changelog.write_text(
    "dev.74：修复赛事中心进入数秒后无响应的问题。根因是 dev.73 扩大全球 Riot 赛程/赛事目录后，Standings 赛事目录异步返回时，ScheduleCenter 会在 Compose 主线程执行 tournaments × matches 的全量绑定扫描，全球数据量下可造成长时间卡死。现在赛事目录构建移到 Dispatchers.Default 后台线程，并新增按 leagueId / leagueSlug / leagueName 的一次性赛程索引，把绑定复杂度从全量笛卡尔扫描降为按联赛候选匹配；同时保持原有缺失字段回退语义、赛事日期边界、Provider identity 和全球赛程完整性逻辑不变。版本升级至 1.0.0-dev.74 / versionCode 74。\n",
    encoding="utf-8"
)

updated = ui.read_text(encoding="utf-8")
assert "produceState(" in updated
assert "withContext(Dispatchers.Default)" in updated
assert "private class ScheduleMatchIndex" in updated
assert "matches.filter { match ->" not in updated[updated.index("private fun buildCompetitionBuckets"):updated.index("private fun tournamentStartEpochMs")]
assert 'versionCode = 74' in gradle.read_text(encoding="utf-8")
assert 'versionName = "1.0.0-dev.74"' in gradle.read_text(encoding="utf-8")
print("dev.74 schedule center ANR patch applied")
