#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


archive = "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt"
research = "app/src/main/java/com/riftlab/app/data/TournamentResearch.kt"
resilient = "app/src/main/java/com/riftlab/app/data/RiotResilientHttp.kt"
sync = "tools/sync_riot_persisted_mirror.py"
workflow = ".github/workflows/riot-mirror-sync.yml"

# Durable patch versions live with the Tournament Edition rather than the current schedule window.
replace_once(
    archive,
    "    val scheduleSeriesCount: Int = 0,\n    val archivedSlots: List<TournamentEditionSlot> = emptyList(),",
    "    val scheduleSeriesCount: Int = 0,\n    val patchVersions: List<String> = emptyList(),\n    val archivedSlots: List<TournamentEditionSlot> = emptyList(),",
)
replace_once(
    archive,
    "    private val historicalStandingsClient = LolEsportsStandingsClient()\n    private val hydratedStandings = linkedMapOf<String, TournamentStandings>()",
    "    private val historicalStandingsClient = LolEsportsStandingsClient()\n    private val eventHistoryProvider = TournamentEventHistoryProvider()\n    private val hydratedStandings = linkedMapOf<String, TournamentStandings>()\n    private val hydratedHistory = linkedMapOf<String, TournamentEventHistorySnapshot>()",
)
replace_once(
    archive,
    "            standingsState = StandingsCenterStore.state.value\n        )",
    "            standingsState = StandingsCenterStore.state.value,\n            historyOverride = cachedHydratedHistory(record.tournamentId)\n        )",
)

old_hydrate = '''            val fetched = runCatching {\n                historicalStandingsClient.fetchTournamentStandings(record.tournamentId)\n            }.getOrNull()?.takeIf(::hasStandingsRows)\n\n            if (manualSelectedTournamentId != record.tournamentId) return@launch\n            if (fetched == null) {\n                _state.value = _state.value.copy(\n                    statusMessage = "${record.displayName} · 当前 Riot 历史 Standings 无可用结构，保留已归档槽位"\n                )\n                return@launch\n            }\n\n            synchronized(hydratedStandings) {\n                hydratedStandings[record.tournamentId] = fetched\n            }\n            val latestRecord = _state.value.editions.firstOrNull { it.tournamentId == record.tournamentId }\n                ?: return@launch\n            val hydrated = buildDetail(\n                record = latestRecord,\n                schedule = MatchSessionStore.schedule.value,\n                standingsState = StandingsCenterStore.state.value,\n                standingsOverride = fetched\n            )\n            replaceEdition(hydrated.edition)\n            _state.value = _state.value.copy(\n                selected = hydrated,\n                lastRefreshEpochMs = System.currentTimeMillis(),\n                statusMessage = "${record.displayName} · 历史 Standings 已独立同步，不影响当前赛事上下文"\n            )\n            persist(_state.value.editions)\n'''
new_hydrate = '''            val fetchedStandings = runCatching {\n                historicalStandingsClient.fetchTournamentStandings(record.tournamentId)\n            }.getOrNull()?.takeIf(::hasStandingsRows)\n            val fetchedHistory = runCatching {\n                eventHistoryProvider.fetch(record)\n            }.getOrNull()?.takeIf(::hasHistoryData)\n\n            if (manualSelectedTournamentId != record.tournamentId) return@launch\n            if (fetchedStandings != null) {\n                synchronized(hydratedStandings) {\n                    hydratedStandings[record.tournamentId] = fetchedStandings\n                }\n            }\n            if (fetchedHistory != null) {\n                synchronized(hydratedHistory) {\n                    hydratedHistory[record.tournamentId] = fetchedHistory\n                }\n            }\n            if (fetchedStandings == null && fetchedHistory == null) {\n                _state.value = _state.value.copy(\n                    statusMessage = "${record.displayName} · Riot 历史 Standings / Completed Events 当前均不可用，保留已归档槽位"\n                )\n                return@launch\n            }\n\n            val latestRecord = _state.value.editions.firstOrNull { it.tournamentId == record.tournamentId }\n                ?: return@launch\n            val hydrated = buildDetail(\n                record = latestRecord,\n                schedule = MatchSessionStore.schedule.value,\n                standingsState = StandingsCenterStore.state.value,\n                standingsOverride = fetchedStandings ?: cachedHydratedStandings(record.tournamentId),\n                historyOverride = fetchedHistory ?: cachedHydratedHistory(record.tournamentId)\n            )\n            replaceEdition(hydrated.edition)\n            _state.value = _state.value.copy(\n                selected = hydrated,\n                lastRefreshEpochMs = System.currentTimeMillis(),\n                statusMessage = buildString {\n                    append(record.displayName).append(" · 历史数据独立同步：")\n                    append(if (fetchedStandings != null) "Standings ✓" else "Standings —")\n                    append(" · ")\n                    append(if (fetchedHistory?.completedSeries?.isNotEmpty() == true) "Completed Events ✓" else "Completed Events —")\n                    append(" · ")\n                    append(if (hydrated.edition.patchVersions.isNotEmpty()) "Patch ✓" else "Patch —")\n                }\n            )\n            persist(_state.value.editions)\n'''
replace_once(archive, old_hydrate, new_hydrate)

# Current rebuild can reuse any historical hydration already loaded in this process.
replace_once(
    archive,
    "                standingsOverride = cachedHydratedStandings(it.tournamentId)\n            )",
    "                standingsOverride = cachedHydratedStandings(it.tournamentId),\n                historyOverride = cachedHydratedHistory(it.tournamentId)\n            )",
)
replace_once(
    archive,
    "        standingsState: StandingsCenterState,\n        standingsOverride: TournamentStandings? = null\n    ): TournamentEditionDetail {",
    "        standingsState: StandingsCenterState,\n        standingsOverride: TournamentStandings? = null,\n        historyOverride: TournamentEventHistorySnapshot? = null\n    ): TournamentEditionDetail {",
)
replace_once(
    archive,
    "        val matches = matchesForEdition(ref, schedule)\n        val standings = standingsOverride",
    "        val matches = (matchesForEdition(ref, schedule) + historyOverride?.completedSeries.orEmpty())\n            .distinctBy { it.eventId.ifBlank { it.matchId } }\n            .sortedBy { it.startTimeIso }\n        val standings = standingsOverride",
)
replace_once(
    archive,
    "            standings = standings,\n            governance = governance\n        )",
    "            standings = standings,\n            governance = governance,\n            verifiedPatchVersions = (record.patchVersions + historyOverride?.patchVersions.orEmpty()).distinct()\n        )",
)
replace_once(
    archive,
    "        val provisional = record.copy(\n            participantTeamCodes = participantTeams,\n            scheduleSeriesCount = maxOf(record.scheduleSeriesCount, matches.size)\n        )\n        val liveSlots = buildSlots(provisional, matches, standings, governance, research)",
    "        val provisional = record.copy(\n            participantTeamCodes = participantTeams,\n            scheduleSeriesCount = maxOf(record.scheduleSeriesCount, matches.size),\n            patchVersions = (record.patchVersions + historyOverride?.patchVersions.orEmpty()).distinct()\n        )\n        val liveSlots = buildSlots(provisional, matches, standings, governance, research, historyOverride)",
)
replace_once(
    archive,
    "        governance: TournamentGovernanceSnapshot,\n        research: TournamentResearchSnapshot\n    ): List<TournamentEditionSlot> {",
    "        governance: TournamentGovernanceSnapshot,\n        research: TournamentResearchSnapshot,\n        history: TournamentEventHistorySnapshot?\n    ): List<TournamentEditionSlot> {",
)

# Completed Events is a tournament-scoped source, so it upgrades participants/schedule beyond the moving schedule window.
replace_once(
    archive,
    "                state = if (edition.participantTeamCodes.isEmpty()) TournamentEditionSlotState.PENDING else TournamentEditionSlotState.PARTIAL,",
    "                state = when {\n                    edition.participantTeamCodes.isEmpty() -> TournamentEditionSlotState.PENDING\n                    ended && history?.completedSeries?.isNotEmpty() == true -> TournamentEditionSlotState.COMPLETE\n                    else -> TournamentEditionSlotState.PARTIAL\n                },",
)
replace_once(
    archive,
    "                source = if (standings != null) \"Unified Schedule + Riot Standings\" else \"Unified Schedule\"",
    "                source = when {\n                    history?.completedSeries?.isNotEmpty() == true && standings != null -> \"Riot getCompletedEvents + Riot Standings\"\n                    history?.completedSeries?.isNotEmpty() == true -> history.completedEventsSource\n                    standings != null -> \"Unified Schedule + Riot Standings\"\n                    else -> \"Unified Schedule\"\n                }",
)
replace_once(
    archive,
    "                state = if (matches.isEmpty()) TournamentEditionSlotState.PENDING else TournamentEditionSlotState.PARTIAL,\n                detail = if (matches.isEmpty()) \"当前分页没有该届比赛；保留届次实体等待历史回填\" else \"当前已归档 ${matches.size} 场 Series\",\n                source = \"RiftLab Unified Schedule (Riot/Cito)\"",
    "                state = when {\n                    matches.isEmpty() -> TournamentEditionSlotState.PENDING\n                    ended && history?.completedSeries?.isNotEmpty() == true -> TournamentEditionSlotState.COMPLETE\n                    else -> TournamentEditionSlotState.PARTIAL\n                },\n                detail = if (matches.isEmpty()) \"当前分页没有该届比赛；保留届次实体等待历史回填\" else \"当前已归档 ${matches.size} 场 Series\",\n                source = if (history?.completedSeries?.isNotEmpty() == true) history.completedEventsSource else \"RiftLab Unified Schedule (Riot/Cito)\"",
)
replace_once(
    archive,
    '''            TournamentEditionSlot(\n                key = "awards",\n                label = "MVP / FMVP / POG",\n                state = TournamentEditionSlotState.PENDING,\n                detail = "只接收已核实奖项记录；不按 KDA / 伤害自动推断 MVP。"\n            ),''',
    '''            TournamentEditionSlot(\n                key = "awards",\n                label = "MVP / FMVP / POG",\n                state = if (history?.verifiedAwards?.isNotEmpty() == true) TournamentEditionSlotState.PARTIAL else TournamentEditionSlotState.PENDING,\n                detail = if (history?.verifiedAwards?.isNotEmpty() == true) {\n                    "已接入 ${history.verifiedAwards.size} 条已核实 MVP / POG 记录；未覆盖赛事继续保持未知。"\n                } else {\n                    "只接收已核实奖项记录；不按 KDA / 伤害自动推断 MVP。"\n                },\n                source = history?.awardsSource.orEmpty()\n            ),''',
)

# Surface provenance for the new sources.
replace_once(
    archive,
    '''                if (standings != null) {\n                    add(\n                        DataProvenance(\n                            sourceId = "riot-standings",\n                            displayName = "Riot Standings",\n                            authority = DataAuthority.PROVIDER,\n                            freshness = DataFreshnessClass.MINUTES,\n                            verified = false\n                        )\n                    )\n                }''',
    '''                if (standings != null) {\n                    add(\n                        DataProvenance(\n                            sourceId = "riot-standings",\n                            displayName = "Riot Standings",\n                            authority = DataAuthority.PROVIDER,\n                            freshness = DataFreshnessClass.MINUTES,\n                            verified = false\n                        )\n                    )\n                }\n                if (historyOverride?.completedSeries?.isNotEmpty() == true) {\n                    add(\n                        DataProvenance(\n                            sourceId = "riot-completed-events",\n                            displayName = historyOverride.completedEventsSource.ifBlank { "Riot Completed Events" },\n                            authority = DataAuthority.PROVIDER,\n                            freshness = DataFreshnessClass.DAILY,\n                            verified = false\n                        )\n                    )\n                }\n                if (provisional.patchVersions.isNotEmpty()) {\n                    add(\n                        DataProvenance(\n                            sourceId = "riot-livestats-patch",\n                            displayName = "Riot LiveStats · gameMetadata.patchVersion",\n                            authority = DataAuthority.OFFICIAL,\n                            freshness = DataFreshnessClass.STATIC,\n                            verified = true\n                        )\n                    )\n                }''',
)

# Persist/restore patch versions; old schema-1 files remain valid because the field is optional.
replace_once(
    archive,
    "                            scheduleSeriesCount = row.optInt(\"scheduleSeriesCount\", 0),\n                            archivedSlots = parseSlots(row.optJSONArray(\"slots\")),",
    "                            scheduleSeriesCount = row.optInt(\"scheduleSeriesCount\", 0),\n                            patchVersions = jsonStrings(row.optJSONArray(\"patchVersions\")),\n                            archivedSlots = parseSlots(row.optJSONArray(\"slots\")),",
)
replace_once(
    archive,
    "                                    put(\"scheduleSeriesCount\", edition.scheduleSeriesCount)\n                                    put(\"slots\", JSONArray().apply {",
    "                                    put(\"scheduleSeriesCount\", edition.scheduleSeriesCount)\n                                    put(\"patchVersions\", JSONArray(edition.patchVersions))\n                                    put(\"slots\", JSONArray().apply {",
)
replace_once(
    archive,
    '''    private fun cachedHydratedStandings(tournamentId: String): TournamentStandings? =\n        synchronized(hydratedStandings) { hydratedStandings[tournamentId] }\n''',
    '''    private fun cachedHydratedStandings(tournamentId: String): TournamentStandings? =\n        synchronized(hydratedStandings) { hydratedStandings[tournamentId] }\n\n    private fun cachedHydratedHistory(tournamentId: String): TournamentEventHistorySnapshot? =\n        synchronized(hydratedHistory) { hydratedHistory[tournamentId] }\n\n    private fun hasHistoryData(history: TournamentEventHistorySnapshot): Boolean =\n        history.completedSeries.isNotEmpty() || history.patchVersions.isNotEmpty() || history.verifiedAwards.isNotEmpty()\n''',
)

# Tournament Research can now promote an exact Riot LiveStats patch to VERIFIED.
replace_once(
    research,
    "        standings: TournamentStandings?,\n        governance: TournamentGovernanceSnapshot\n    ): TournamentResearchSnapshot {",
    "        standings: TournamentStandings?,\n        governance: TournamentGovernanceSnapshot,\n        verifiedPatchVersions: List<String> = emptyList()\n    ): TournamentResearchSnapshot {",
)
replace_once(research, "        val version = resolveVersion(matches)", "        val version = resolveVersion(matches, verifiedPatchVersions)")
replace_once(
    research,
    "    private fun resolveVersion(matches: List<ScheduledEsportsMatch>): TournamentVersionSnapshot {\n        // Current normalized schedule/standings models do not expose a trustworthy patch field.",
    '''    private fun resolveVersion(\n        matches: List<ScheduledEsportsMatch>,\n        verifiedPatchVersions: List<String>\n    ): TournamentVersionSnapshot {\n        val verified = verifiedPatchVersions\n            .mapNotNull(::normalizePatch)\n            .distinct()\n            .sortedWith(compareBy({ it.substringBefore('.').toIntOrNull() ?: 0 }, { it.substringAfter('.').toIntOrNull() ?: 0 }))\n        if (verified.isNotEmpty()) {\n            val label = verified.joinToString(" / ") { "Patch $it" }\n            return TournamentVersionSnapshot(\n                versionLabel = label,\n                detail = "由该 Tournament Edition 的真实 Riot EventDetails gameId 读取 LiveStats gameMetadata.patchVersion；若赛事跨版本会保留多个已观测版本。",\n                source = "Riot LoL Esports LiveStats · gameMetadata.patchVersion",\n                evidence = ResearchEvidence.VERIFIED\n            )\n        }\n\n        // Current normalized schedule/standings models do not expose a trustworthy patch field.''',
)
replace_once(
    research,
    "    private fun buildUpdates(\n",
    '''    private fun normalizePatch(raw: String): String? {\n        val match = Regex("(?<!\\\\d)(\\\\d{1,2})\\\\.(\\\\d{1,2})(?!\\\\d)").find(raw.trim()) ?: return null\n        return "${match.groupValues[1]}.${match.groupValues[2]}"\n    }\n\n    private fun buildUpdates(\n''',
)

# Direct Riot remains primary, but getCompletedEvents also gets a mirror fallback on bad carrier paths.
replace_once(
    resilient,
    '''            "getStandings" -> {\n                val tournamentId = query["tournamentId"].orEmpty()\n                root.optJSONObject("standingsByTournament")?.optJSONObject(tournamentId)\n            }''',
    '''            "getStandings" -> {\n                val tournamentId = query["tournamentId"].orEmpty()\n                root.optJSONObject("standingsByTournament")?.optJSONObject(tournamentId)\n            }\n            "getCompletedEvents" -> {\n                val tournamentId = query["tournamentId"].orEmpty()\n                root.optJSONObject("completedEventsByTournament")?.optJSONObject(tournamentId)\n            }''',
)

# Current fallback dataset is still LPL-scoped, but it must contain historical Completed Events too.
replace_once(
    sync,
    "def collect_event_details(event_ids: list[str]) -> dict[str, Any]:\n",
    '''def collect_completed_events(tournament_ids: list[str]) -> dict[str, Any]:\n    result: dict[str, Any] = {}\n    for tid in tournament_ids:\n        try:\n            result[tid] = fetch("getCompletedEvents", {"tournamentId": tid})\n        except Exception:\n            continue\n    return result\n\n\ndef collect_event_details(event_ids: list[str]) -> dict[str, Any]:\n''',
)
replace_once(
    sync,
    "    standings = collect_standings(tournament_ids)\n    try:\n",
    "    standings = collect_standings(tournament_ids)\n    completed_events = collect_completed_events(tournament_ids)\n    try:\n",
)
replace_once(
    sync,
    '''        "standingsByTournament": standings,\n        "teamLookup": team_lookup,''',
    '''        "standingsByTournament": standings,\n        "completedEventsByTournament": completed_events,\n        "teamLookup": team_lookup,''',
)
replace_once(
    sync,
    '''        f"mirror candidate: pages={len(pages)} team_refs={len(team_refs)} teams={len(team_details)} "\n        f"standings={len(standings)} events={len(event_details)}"''',
    '''        f"mirror candidate: pages={len(pages)} team_refs={len(team_refs)} teams={len(team_details)} "\n        f"standings={len(standings)} completed={len(completed_events)} events={len(event_details)}"''',
)
replace_once(
    sync,
    '''        f"mirror updated: pages={len(pages)} teams={len(team_details)} "\n        f"standings={len(standings)} events={len(event_details)}"''',
    '''        f"mirror updated: pages={len(pages)} teams={len(team_details)} "\n        f"standings={len(standings)} completed={len(completed_events)} events={len(event_details)}"''',
)

# Do not let a feature branch run the data-bot workflow and push generated mirror changes back into it.
replace_once(
    workflow,
    "  push:\n    paths:\n",
    "  push:\n    branches: [ main ]\n    paths:\n",
)

print("dev71 tournament history patch prepared")
