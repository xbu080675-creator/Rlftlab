#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_all(path: str, old: str, new: str, minimum: int = 1) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count < minimum:
        raise SystemExit(f"{path}: expected >= {minimum} matches, got {count}: {old!r}")
    p.write_text(text.replace(old, new), encoding="utf-8")


# 1) WSCI and WSCL are distinct tournament families. Do not silently rename WSCI to WSCL.
replace_once(
    "app/src/main/java/com/riftlab/app/data/ComprehensiveData.kt",
    '''            identity.contains("americas cup") -> "AMERICAS_CUP"\n            identity.contains("wscl") -> "WSCL"\n''',
    '''            identity.contains("americas cup") -> "AMERICAS_CUP"\n            identity.contains("wsci") -> "WSCI"\n            identity.contains("wscl") -> "WSCL"\n'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/ComprehensiveData.kt",
    '''                    "EWC" -> "Esports World Cup"\n                    else -> ref.leagueName.ifBlank { ref.leagueSlug.uppercase() }.ifBlank { ref.slug }\n''',
    '''                    "EWC" -> "Esports World Cup"\n                    "WSCI" -> "WSCI"\n                    "WSCL" -> "WSCL"\n                    else -> ref.leagueName.ifBlank { ref.leagueSlug.uppercase() }.ifBlank { ref.slug }\n'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/TournamentResearch.kt",
    '''        identity.contains("americas cup") || identity.contains("america cup") || identity.contains("美洲杯") -> "美洲杯"\n        identity.contains("emea masters") || identity.contains("emea 大师赛") -> "EMEA 大师赛"\n        Regex("(^|[^a-z])wsc[il]([^a-z]|$)").containsMatchIn(identity) -> "WSCL"\n''',
    '''        identity.contains("americas cup") || identity.contains("america cup") || identity.contains("美洲杯") -> "美洲杯"\n        identity.contains("emea masters") || identity.contains("emea 大师赛") -> "EMEA 大师赛"\n        Regex("(^|[^a-z])wsci([^a-z]|$)").containsMatchIn(identity) -> "WSCI"\n        Regex("(^|[^a-z])wscl([^a-z]|$)").containsMatchIn(identity) -> "WSCL"\n'''
)
replace_all(
    "app/src/main/java/com/riftlab/app/data/TournamentResearch.kt",
    "RiftLab Unified Schedule (Riot/Cito)",
    "RiftLab Unified Schedule (Riot/Cito/International Mirror)",
    minimum=2
)

# 2) Provenance must follow the selected source instead of labelling every schedule/tournament Riot.
replace_once(
    "app/src/main/java/com/riftlab/app/data/ComprehensiveData.kt",
    '''        val standingRows = flattenStandings(standings)\n\n        val provenance = buildList {\n            if (scheduled != null) add(DataProvenance("riot-schedule", "Riot LoL Esports Schedule", DataAuthority.OFFICIAL, DataFreshnessClass.MINUTES))\n            if (tournament != null || standings != null) add(DataProvenance("riot-standings", "Riot Tournament / Standings", DataAuthority.OFFICIAL, DataFreshnessClass.MINUTES))\n            if (prematch != null && prematch.blueRoster.isNotEmpty().or(prematch.redRoster.isNotEmpty())) {\n                add(DataProvenance("riot-teams", "Riot getTeams roster", DataAuthority.OFFICIAL, DataFreshnessClass.HOURLY))\n            }\n''',
    '''        val standingRows = flattenStandings(standings)\n        val externalSchedule = scheduled?.let {\n            it.eventId.startsWith("provider:") || it.leagueId.startsWith("rft-event:")\n        } == true\n        val externalTournament = tournament?.id?.startsWith("rft-event:") == true\n\n        val provenance = buildList {\n            if (scheduled != null) {\n                if (externalSchedule) {\n                    add(DataProvenance("international-event-mirror", "RFT.gg public event mirror", DataAuthority.PROVIDER, DataFreshnessClass.HOURLY))\n                } else {\n                    add(DataProvenance("riot-schedule", "Riot LoL Esports Schedule", DataAuthority.OFFICIAL, DataFreshnessClass.MINUTES))\n                }\n            }\n            if (tournament != null || standings != null) {\n                if (externalTournament) {\n                    add(DataProvenance("international-tournament-mirror", "International Tournament Mirror", DataAuthority.PROVIDER, DataFreshnessClass.HOURLY))\n                } else {\n                    add(DataProvenance("riot-standings", "Riot Tournament / Standings", DataAuthority.OFFICIAL, DataFreshnessClass.MINUTES))\n                }\n            }\n            if (prematch != null && prematch.blueRoster.isNotEmpty().or(prematch.redRoster.isNotEmpty())) {\n                add(DataProvenance("team-data", "Riot/Cito normalized roster", DataAuthority.PROVIDER, DataFreshnessClass.HOURLY))\n            }\n'''
)
replace_all(
    "app/src/main/java/com/riftlab/app/data/ComprehensiveData.kt",
    'source = "Riot getTeams / normalized roster"',
    'source = "Riot/Cito team data / normalized roster"'
)

# 3) Derived tournament structure comes from the unified schedule, not always Riot.
replace_all(
    "app/src/main/java/com/riftlab/app/data/TournamentGovernance.kt",
    'source = "Riot Schedule · 结构推导"',
    'source = "Unified Schedule · 结构推导"'
)

# 4) Standings center names/provider states and WSCI display identity.
replace_all(
    "app/src/main/java/com/riftlab/app/data/StandingsCenterStore.kt",
    '"Riot Standings · ${displayTournamentName(selected)}"',
    '"Standings · ${displayTournamentName(selected)}"'
)
replace_all(
    "app/src/main/java/com/riftlab/app/data/StandingsCenterStore.kt",
    '"Riot Standings · 暂无可用赛事"',
    '"Standings · 暂无可用赛事"'
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/StandingsCenterStore.kt",
    '''            _state.value = _state.value.copy(\n                standings = standings,\n                lastRefreshEpochMs = System.currentTimeMillis(),\n                statusMessage = "Riot Standings · ${displayTournamentName(tournament)}"\n            )\n''',
    '''            _state.value = _state.value.copy(\n                standings = standings,\n                lastRefreshEpochMs = System.currentTimeMillis(),\n                statusMessage = when {\n                    tournament.id.startsWith("rft-event:") && standings == null ->\n                        "${displayTournamentName(tournament)} · 当前可信 Provider 未提供 Standings，保持未知"\n                    else -> "Standings · ${displayTournamentName(tournament)}"\n                }\n            )\n'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/StandingsCenterStore.kt",
    '''            identity.contains("esports world cup") || Regex("(^|[^a-z])ewc([^a-z]|$)").containsMatchIn(identity) -> "$year Esports World Cup"\n            slug.contains("split_1") -> "$year $leagueName 第一赛段"\n''',
    '''            identity.contains("esports world cup") || Regex("(^|[^a-z])ewc([^a-z]|$)").containsMatchIn(identity) -> "$year Esports World Cup"\n            Regex("(^|[^a-z])wsci([^a-z]|$)").containsMatchIn(identity) ->\n                if (identity.contains("qualifier") || identity.contains("regional")) "$year WSCI 区域资格赛" else "$year WSCI"\n            Regex("(^|[^a-z])wscl([^a-z]|$)").containsMatchIn(identity) ->\n                if (identity.contains("qualifier") || identity.contains("regional")) "$year WSCL 区域资格赛" else "$year WSCL"\n            slug.contains("split_1") -> "$year $leagueName 第一赛段"\n'''
)

# 5) Schedule-center international menu: preserve both names instead of mapping both to WSCL.
replace_once(
    "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt",
    '''    DEMACIA_GLOBAL("德杯国际邀请赛"),\n    WSCL("WSCL"),\n''',
    '''    DEMACIA_GLOBAL("德杯国际邀请赛"),\n    WSCI("WSCI"),\n    WSCL("WSCL"),\n'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt",
    '''        InternationalCompetitionMenu.WSCL -> Triple(\n            "WSCL",\n            "国际赛事 · 当前赛事入口保留",\n            "WSCL 不归入已经停摆的 2026 LDL。赛程、比分和战队只在可信源返回后展示；不会因为参赛队曾属于次级联赛而错误归类回 LDL。"\n        )\n''',
    '''        InternationalCompetitionMenu.WSCI -> Triple(\n            "WSCI",\n            "国际赛事 · 独立赛事入口",\n            "WSCI 作为独立国际赛事建档。赛程、比分和战队来自已标注 Provider；缺少 Standings、Seed 或晋级来源时保持未知。"\n        )\n        InternationalCompetitionMenu.WSCL -> Triple(\n            "WSCL",\n            "国际赛事 · 当前赛事入口保留",\n            "WSCL 独立归入国际赛事。赛程、比分和战队只在可信源返回后展示；不会因为参赛队曾属于次级联赛而错误归类回联赛目录。"\n        )\n'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt",
    '''        Regex("(^|[^a-z])wsc[il]([^a-z]|$)").containsMatchIn(identity) -> InternationalCompetitionMenu.WSCL\n''',
    '''        Regex("(^|[^a-z])wsci([^a-z]|$)").containsMatchIn(identity) -> InternationalCompetitionMenu.WSCI\n        Regex("(^|[^a-z])wscl([^a-z]|$)").containsMatchIn(identity) -> InternationalCompetitionMenu.WSCL\n'''
)
replace_all(
    "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt",
    'EmptyData("等待 Riot Standings 排名数据")',
    'EmptyData("当前可信数据源尚未提供 Standings 排名数据")'
)

# 6) Qualification semantics: international invitational/tournament participation is not a fake points table.
replace_once(
    "app/src/main/java/com/riftlab/app/data/QualificationCenter.kt",
    '''        if (handbookQualification != null && edition.family.uppercase() !in setOf("WORLDS", "MSI", "FIRST_STAND")) {\n''',
    '''        if (handbookQualification != null && edition.family.uppercase() !in INTERNATIONAL_PARTICIPATION_FAMILIES) {\n'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/QualificationCenter.kt",
    '''        if (edition.family.uppercase() in setOf("WORLDS", "MSI", "FIRST_STAND")) {\n''',
    '''        if (edition.family.uppercase() in INTERNATIONAL_PARTICIPATION_FAMILIES) {\n'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/QualificationCenter.kt",
    '''            } else {\n                "官方已经确认的 Worlds 参赛事实会直接标记已锁定；Seed 及更细晋级来源仍按字段级证据单独确认。国际赛本身不显示虚构的 Championship Points 缺失表。"\n            },\n''',
    '''            } else if (officialWorldsTeams.isNotEmpty()) {\n                "官方已经确认的 Worlds 参赛事实会直接标记已锁定；Seed 及更细晋级来源仍按字段级证据单独确认。国际赛本身不显示虚构的 Championship Points 缺失表。"\n            } else {\n                "可信 Provider 已确认的参赛事实只证明参赛；Region / Seed / Qualification Origin 仍分别等待证据，不从队名或对阵反推，也不显示虚构的 Championship Points 缺失表。"\n            },\n'''
)
# Add one shared family policy inside the store object.
replace_once(
    "app/src/main/java/com/riftlab/app/data/QualificationCenter.kt",
    '''object QualificationCenterStore {\n    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)\n''',
    '''object QualificationCenterStore {\n    private val INTERNATIONAL_PARTICIPATION_FAMILIES = setOf(\n        "WORLDS", "MSI", "FIRST_STAND", "EWC", "WSCI", "WSCL", "EMEA_MASTERS", "AMERICAS_CUP"\n    )\n    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)\n'''
)

# 7) Mixed schedule status text must not claim every row is Riot-owned.
replace_all(
    "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt",
    "正在同步 Riot 全球 LoL Esports 赛程；不会用 Mock 首发或 Rank 填空。",
    "正在同步 Unified Schedule；不会用 Mock 首发或 Rank 填空。"
)
replace_all(
    "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt",
    "RIOT SCHEDULE",
    "UNIFIED SCHEDULE",
    minimum=2
)
replace_all(
    "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt",
    "正在连接 Riot LoL Esports 赛程中心…",
    "正在连接 Unified Schedule 赛事中心…"
)
replace_all(
    "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt",
    "正在同步 Riot 全球赛事分页赛程…",
    "正在同步 Unified Schedule 全球赛事…"
)
replace_all(
    "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt",
    "当前 Riot 分页暂无赛事",
    "当前 Unified Schedule 暂无赛事"
)
replace_all(
    "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt",
    "ROSTER · 正在同步 Riot getTeams…",
    "ROSTER · 正在同步队伍资料源…"
)
replace_all(
    "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt",
    "ROSTER · RIOT GETTEAMS $connectedCount/2 · AUTO STARTERS $autoStarterCount/2",
    "ROSTER · TEAM SOURCES $connectedCount/2 · AUTO STARTERS $autoStarterCount/2"
)
replace_all(
    "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt",
    'source = "Unified Schedule · Riot/Cito"',
    'source = "Unified Schedule · Riot/Cito/International Mirror"'
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt",
    '''        return "${RiotResilientHttp.sourceLabel()} · Schedule · ${matches.size} 场 · 已结束 $completed · $currentText · $nextText"\n''',
    '''        return "Unified Schedule · ${matches.size} 场 · 已结束 $completed · $currentText · $nextText"\n'''
)

# 8) Riot LiveStats must not call Riot event-details with a provider-owned event id.
replace_once(
    "app/src/main/java/com/riftlab/app/data/LolEsportsDataSources.kt",
    '''        while (currentCoroutineContext().isActive) {\n            if (currentEvent == null) {\n''',
    '''        while (currentCoroutineContext().isActive) {\n            val registeredTarget = LiveMatchTargetRegistry.snapshot()\n            if (registeredTarget != null && isExternalProviderTarget(registeredTarget)) {\n                currentEvent = null\n                knownGameIds = emptyList()\n                emittedGameIds.clear()\n                _status.value = LiveSourceStatus(\n                    phase = LiveSourcePhase.WAITING_FOR_MATCH,\n                    message = "Riot LiveStats · 当前赛事使用非 Riot Event ID，等待其它实时源",\n                    eventId = registeredTarget.eventId,\n                    lastUpdateEpochMs = System.currentTimeMillis()\n                )\n                delay(5_000)\n                continue\n            }\n\n            if (currentEvent == null) {\n'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/LolEsportsDataSources.kt",
    '''    private fun selectScheduleEvent(\n''',
    '''    private fun isExternalProviderTarget(match: ScheduledEsportsMatch): Boolean =\n        match.eventId.startsWith("provider:") || match.leagueId.startsWith("rft-event:")\n\n    private fun selectScheduleEvent(\n'''
)

# 9) Tournament Edition must not run Riot history hydration or Riot provenance for provider editions.
replace_once(
    "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt",
    '''        persist(_state.value.editions)\n\n        hydrateJob?.cancel()\n        hydrateJob = scope.launch {\n''',
    '''        persist(_state.value.editions)\n\n        if (isExternalProviderEdition(record)) {\n            _state.value = _state.value.copy(\n                statusMessage = "${record.displayName} · Provider mirror 档案已载入；未提供的 Standings / Patch / Awards 保持未知"\n            )\n            return\n        }\n\n        hydrateJob?.cancel()\n        hydrateJob = scope.launch {\n'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt",
    '''    private fun maybeAutoHydrateCurrentEdition(record: TournamentEditionArchiveRecord) {\n        if (cachedHydratedHistory(record.tournamentId) != null) return\n''',
    '''    private fun maybeAutoHydrateCurrentEdition(record: TournamentEditionArchiveRecord) {\n        if (isExternalProviderEdition(record)) return\n        if (cachedHydratedHistory(record.tournamentId) != null) return\n'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt",
    '''            provenance = buildList {\n                add(\n                    DataProvenance(\n                        sourceId = "riot-tournament-directory",\n                        displayName = "Riot Tournament Directory",\n                        authority = DataAuthority.PROVIDER,\n                        freshness = DataFreshnessClass.DAILY,\n                        verified = false\n                    )\n                )\n''',
    '''            provenance = buildList {\n                if (isExternalProviderEdition(record)) {\n                    add(\n                        DataProvenance(\n                            sourceId = "international-tournament-mirror",\n                            displayName = "RFT.gg public event mirror",\n                            authority = DataAuthority.PROVIDER,\n                            freshness = DataFreshnessClass.HOURLY,\n                            verified = false\n                        )\n                    )\n                } else {\n                    add(\n                        DataProvenance(\n                            sourceId = "riot-tournament-directory",\n                            displayName = "Riot Tournament Directory",\n                            authority = DataAuthority.PROVIDER,\n                            freshness = DataFreshnessClass.DAILY,\n                            verified = false\n                        )\n                    )\n                }\n'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt",
    '''                source = "Riot Tournament Directory"\n''',
    '''                source = if (isExternalProviderEdition(edition)) "RFT.gg public event mirror" else "Riot Tournament Directory"\n'''
)
replace_all(
    "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt",
    "RiftLab Unified Schedule (Riot/Cito)",
    "RiftLab Unified Schedule (Riot/Cito/International Mirror)"
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt",
    '''                ordered.isEmpty() -> "Riot Tournament Directory · 当前没有可归档届次"\n''',
    '''                ordered.isEmpty() -> "Tournament Directory · 当前没有可归档届次"\n'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt",
    '''    private fun hasStandingsRows(standings: TournamentStandings): Boolean = standings.stages.any { stage ->\n''',
    '''    private fun isExternalProviderEdition(record: TournamentEditionArchiveRecord): Boolean =\n        record.tournamentId.startsWith("rft-event:") || record.leagueId.startsWith("rft-event:")\n\n    private fun hasStandingsRows(standings: TournamentStandings): Boolean = standings.stages.any { stage ->\n'''
)

print("dev72 source-truth audit batch7 applied")
