#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "app/src/main/java/com/riftlab/app/data/LolEsportsApiClient.kt"
AUDIT = ROOT / "tools/dev73_schedule_completeness_audit.py"
WORKFLOW = ROOT / ".github/workflows/dev73-global-schedule-fix.yml"
SELF = Path(__file__)

text = TARGET.read_text(encoding="utf-8")

old_config = '''    // Tier-one regional + international competitions tracked by RiftLab. IDs are discovered
    // dynamically from Riot getLeagues so league reshuffles do not require an APK update.
    val GLOBAL_MAJOR_LEAGUE_SLUGS = setOf(
        "worlds", "msi", "first-stand", "first_stand", "firststand", "ewc", "esports-world-cup", "americas-cup", "emea-masters", "demacia-cup", "demacia-cup-global-invitational", "demacia-global-invitational", "wscl",
        "lpl", "lck", "lec", "lcs", "lta", "lta-north", "lta_north", "lta-south", "lta_south", "lcp",
        "cblol", "cblol-brazil", "pcs", "vcs", "ljl", "lla", "lrn", "lrs", "fls",
        "lck-cl", "lck_challengers", "lcp-wild-card",
        "greek-legends-league", "lit", "nlc", "esports-balkan-league", "tcl", "hitpoint-masters", "rift-legends", "nacl"
    )
    val GLOBAL_TRACKED_LEAGUE_NAMES = setOf(
        "americas cup", "emea masters", "demacia cup global invitational", "demacia cup", "wscl", "fls", "lck cl", "lck challengers", "lcp wild card",
        "greek legends league", "lit", "nlc", "esports balkan league",
        "tcl", "hitpoint masters", "rift legends", "nacl"
    )
'''
new_config = '''    // Riot getLeagues is already the authoritative League of Legends competition catalogue.
    // Do not maintain a positive allowlist here: it silently drops newly added / renamed regional
    // leagues (for example LJL, LFL, Prime League, Arabian League and the regional leagues). Keep
    // only a tiny negative list for products that are not League of Legends.
    val GLOBAL_EXCLUDED_LEAGUE_SLUGS = setOf(
        "tft_esports", "tft-esports"
    )
    val GLOBAL_EXCLUDED_LEAGUE_NAMES = setOf(
        "tft esports"
    )
'''
if old_config not in text:
    raise SystemExit("config block not found")
text = text.replace(old_config, new_config, 1)

old_global = '''    suspend fun fetchGlobalSchedule(): List<ScheduledEsportsMatch> {
        val leagues = fetchTrackedLeagues()
        if (leagues.isEmpty()) return emptyList()

        val semaphore = Semaphore(4)
        val results = coroutineScope {
            leagues.map { league ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        league to runCatching { fetchLeagueScheduleWindow(league) }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll()
        }

        return results
            .flatMap { it.second }
            .distinctBy { it.eventId.ifBlank { it.matchId } }
            .sortedWith(
                compareBy<ScheduledEsportsMatch> { parseInstant(it.startTimeIso) ?: Instant.MAX }
                    .thenBy { it.leagueSlug }
                    .thenBy { it.eventId }
            )
    }
'''
new_global = '''    suspend fun fetchGlobalSchedule(): List<ScheduledEsportsMatch> {
        // Prefer Riot's unfiltered schedule. It is both more complete and much cheaper than polling
        // a hand-maintained subset league-by-league, and it automatically includes newly added LoL
        // competitions. Six pages in each direction covers the useful current/recent schedule window;
        // the long-term historical archive remains the responsibility of the central mirror.
        val global = runCatching { fetchGlobalScheduleWindow() }.getOrDefault(emptyList())
        if (global.isNotEmpty()) return global

        // Resilient fallback: if Riot's unfiltered endpoint is temporarily unavailable, discover the
        // live LoL catalogue dynamically and collect a small per-league window. No positive allowlist.
        val leagues = fetchTrackedLeagues()
        if (leagues.isEmpty()) return emptyList()
        val semaphore = Semaphore(4)
        val results = coroutineScope {
            leagues.map { league ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        league to runCatching { fetchLeagueScheduleWindow(league) }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll()
        }
        return sortAndDeduplicate(results.flatMap { it.second })
    }

    private suspend fun fetchGlobalScheduleWindow(): List<ScheduledEsportsMatch> {
        val pages = mutableListOf<JSONObject>()
        val visitedTokens = mutableSetOf<String>()
        val center = fetchSchedulePage(null, "")
        pages += center

        for (direction in listOf("older", "newer")) {
            var token = schedulePageToken(center, direction)
            repeat(6) {
                if (token.isBlank() || !visitedTokens.add("$direction:$token")) return@repeat
                val page = fetchSchedulePage(token, "")
                pages += page
                token = schedulePageToken(page, direction)
            }
        }

        val fallback = TrackedLeagueRef(id = "", slug = "global", name = "LoL Esports")
        return sortAndDeduplicate(pages.flatMap { parseSchedulePage(it, fallback) })
    }

    private fun sortAndDeduplicate(matches: List<ScheduledEsportsMatch>): List<ScheduledEsportsMatch> =
        matches
            .distinctBy { it.eventId.ifBlank { it.matchId } }
            .sortedWith(
                compareBy<ScheduledEsportsMatch> { parseInstant(it.startTimeIso) ?: Instant.MAX }
                    .thenBy { it.leagueSlug }
                    .thenBy { it.eventId }
            )
'''
if old_global not in text:
    raise SystemExit("fetchGlobalSchedule block not found")
text = text.replace(old_global, new_global, 1)

start = text.index("    private suspend fun fetchLeagueScheduleWindow(")
end = text.index("\n    // Compatibility alias", start)
new_league_window = '''    private suspend fun fetchLeagueScheduleWindow(league: TrackedLeagueRef): List<ScheduledEsportsMatch> {
        val pages = mutableListOf<JSONObject>()
        val visitedTokens = mutableSetOf<String>()
        val center = fetchSchedulePage(null, league.id)
        pages += center

        // This path is only a fallback for the global endpoint, so one neighbouring page in each
        // direction is enough to recover a useful current window without exploding phone requests.
        for (direction in listOf("older", "newer")) {
            val token = schedulePageToken(center, direction)
            if (token.isNotBlank() && visitedTokens.add("$direction:$token")) {
                pages += fetchSchedulePage(token, league.id)
            }
        }

        return sortAndDeduplicate(pages.flatMap { parseSchedulePage(it, league) })
    }
'''
text = text[:start] + new_league_window + text[end:]

old_tracked = '''                    val slug = league.optString("slug").lowercase()
                    val normalized = slug.replace('_', '-')
                    val normalizedName = league.optString("name").trim().lowercase()
                    val tracked = slug in LolEsportsConfig.GLOBAL_MAJOR_LEAGUE_SLUGS ||
                        normalized in LolEsportsConfig.GLOBAL_MAJOR_LEAGUE_SLUGS ||
                        LolEsportsConfig.GLOBAL_TRACKED_LEAGUE_NAMES.any { trackedName ->
                            normalizedName == trackedName || normalizedName.contains(trackedName)
                        }
                    if (id.isNotBlank() && tracked) {
'''
new_tracked = '''                    val slug = league.optString("slug").lowercase()
                    val normalized = slug.replace('_', '-')
                    val normalizedName = league.optString("name").trim().lowercase()
                    val excluded = slug in LolEsportsConfig.GLOBAL_EXCLUDED_LEAGUE_SLUGS ||
                        normalized in LolEsportsConfig.GLOBAL_EXCLUDED_LEAGUE_SLUGS ||
                        LolEsportsConfig.GLOBAL_EXCLUDED_LEAGUE_NAMES.any { excludedName ->
                            normalizedName == excludedName || normalizedName.contains(excludedName)
                        }
                    if (id.isNotBlank() && !excluded) {
'''
if old_tracked not in text:
    raise SystemExit("tracked league predicate not found")
text = text.replace(old_tracked, new_tracked, 1)
TARGET.write_text(text, encoding="utf-8")

# Keep the audit meaningful after moving from a positive allowlist to global-unfiltered collection.
audit = AUDIT.read_text(encoding="utf-8")
audit = audit.replace('''    slugs = set_for("GLOBAL_MAJOR_LEAGUE_SLUGS")
    names = set_for("GLOBAL_TRACKED_LEAGUE_NAMES")
    deep_match = re.search(r"val deepPaged.*?in setOf\\((.*?)\\n\\s*\\)", text, re.S)
    deep = set(re.findall(r'"([^\"]+)"', deep_match.group(1))) if deep_match else set()
    return slugs, names, deep
''', '''    slugs = set_for("GLOBAL_EXCLUDED_LEAGUE_SLUGS")
    names = set_for("GLOBAL_EXCLUDED_LEAGUE_NAMES")
    return slugs, names, set()
''')
audit = audit.replace('''    return slug in slugs or normalized in slugs or any(name == x or x in name for x in names)
''', '''    excluded = slug in slugs or normalized in slugs or any(name == x or x in name for x in names)
    return not excluded
''')
old_runtime = '''    # Reproduce the current Android pagination behavior exactly enough to quantify misses.
    current_events: dict[str, dict[str, Any]] = {}
    full_tracked_events: dict[str, dict[str, Any]] = {}
    pagination_losses = []
    for league in tracked:
        lid = str(league.get("id", ""))
        slug = str(league.get("slug", "")).lower().replace("_", "-")
        try:
            current = collect_window(lid, 1 if slug in deep else 0)
            full = collect_window(lid, 6)
        except Exception as exc:
            errors[lid] = repr(exc)
            continue
        for row in current:
            current_events[event_key(row)] = row
        for row in full:
            full_tracked_events[event_key(row)] = row
        missed = [x for x in full if event_key(x) not in {event_key(y) for y in current}]
        if missed:
            pagination_losses.append({
                "id": lid,
                "slug": league.get("slug", ""),
                "name": league.get("name", ""),
                "currentCount": len(current),
                "sixPageCount": len(full),
                "missingCount": len(missed),
                "sampleMissing": [summary_row(x) for x in missed[:8]],
            })
'''
new_runtime = '''    # Reproduce the new Android primary path: Riot global schedule, six pages each direction.
    # The per-league catalogue is now only a resilient fallback, so it is audited separately above.
    runtime_rows = collect_window(None, 6)
    current_events = {event_key(x): x for x in runtime_rows if event_key(x)}
    full_tracked_events = dict(current_events)
    pagination_losses = []
'''
if old_runtime not in audit:
    raise SystemExit("audit runtime block not found")
audit = audit.replace(old_runtime, new_runtime, 1)
AUDIT.write_text(audit, encoding="utf-8")

# Temporary patch machinery must never land in the release tree.
if WORKFLOW.exists():
    WORKFLOW.unlink()
if SELF.exists():
    SELF.unlink()

print("patched global schedule coverage and removed temporary patch machinery")
