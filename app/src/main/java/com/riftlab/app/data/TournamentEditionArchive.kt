package com.riftlab.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * dev.69 turns every Riot tournament/season into a durable edition entity.
 *
 * The archive is append/merge only: a later API window may enrich an edition, but it never deletes
 * an older edition simply because the current upstream response no longer includes it. Persisted
 * summaries therefore survive process/app restarts and can be enriched again when live providers
 * return matching schedule/standings data.
 */
enum class TournamentEditionSlotState(val label: String) {
    COMPLETE("完整"),
    PARTIAL("部分"),
    PENDING("待同步"),
    SOURCE_ERROR("来源异常")
}

data class TournamentEditionSlot(
    val key: String,
    val label: String,
    val state: TournamentEditionSlotState,
    val detail: String,
    val source: String = ""
)

data class TournamentEditionArchiveRecord(
    val tournamentId: String,
    val slug: String,
    val leagueId: String,
    val leagueSlug: String,
    val leagueName: String,
    val family: String,
    val seasonYear: Int?,
    val editionKey: String,
    val displayName: String,
    val stage: String,
    val startDate: String,
    val endDate: String,
    val participantTeamCodes: List<String> = emptyList(),
    val scheduleSeriesCount: Int = 0,
    val patchVersions: List<String> = emptyList(),
    val archivedSlots: List<TournamentEditionSlot> = emptyList(),
    val firstSeenEpochMs: Long = System.currentTimeMillis(),
    val lastSeenEpochMs: Long = System.currentTimeMillis()
)

data class TournamentEditionDetail(
    val edition: TournamentEditionArchiveRecord,
    val matchedSeries: List<ScheduledEsportsMatch> = emptyList(),
    val standings: TournamentStandings? = null,
    val governance: TournamentGovernanceSnapshot? = null,
    val research: TournamentResearchSnapshot? = null,
    val slots: List<TournamentEditionSlot> = emptyList(),
    val provenance: List<DataProvenance> = emptyList()
)

data class TournamentEditionArchiveState(
    val editions: List<TournamentEditionArchiveRecord> = emptyList(),
    val selectedTournamentId: String = "",
    val selected: TournamentEditionDetail? = null,
    val followingCurrent: Boolean = true,
    val lastRefreshEpochMs: Long = 0L,
    val statusMessage: String = "年度赛事档案尚未同步"
)

object TournamentEditionArchiveStore {
    private const val SCHEMA_VERSION = 1
    private const val FILE_NAME = "tournament_editions_v1.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val historicalStandingsClient = LolEsportsStandingsClient()
    private val eventHistoryProvider = TournamentEventHistoryProvider()
    private val hydratedStandings = linkedMapOf<String, TournamentStandings>()
    private val hydratedHistory = linkedMapOf<String, TournamentEventHistorySnapshot>()
    private val _state = MutableStateFlow(TournamentEditionArchiveState())
    val state: StateFlow<TournamentEditionArchiveState> = _state.asStateFlow()

    private var archiveFile: File? = null
    private var syncJob: Job? = null
    private var hydrateJob: Job? = null
    @Volatile private var initialized = false
    @Volatile private var manualSelectedTournamentId = ""

    fun initialize(context: Context) {
        if (initialized) return
        archiveFile = File(context.filesDir, FILE_NAME)
        _state.value = loadPersisted()
        initialized = true
        ensureRunning()
    }

    fun ensureRunning() {
        if (!initialized || syncJob?.isActive == true) return
        MatchSessionStore.ensureDataRunning()
        StandingsCenterStore.ensureRunning()

        syncJob = scope.launch {
            combine(
                MatchSessionStore.schedule,
                MatchSessionStore.targetMatch,
                StandingsCenterStore.state
            ) { schedule, target, standings -> Triple(schedule, target, standings) }
                .collect { (schedule, target, standingsState) ->
                    rebuild(schedule, target, standingsState)
                }
        }
    }

    /**
     * Historical browsing is deliberately isolated from the app-wide StandingsCenterStore.
     * Selecting an old edition must not make the current PRE/LIVE coverage suddenly point at an
     * unrelated historical standings table, so an archive-only Riot client hydrates that edition.
     */
    fun selectTournament(tournamentId: String) {
        val record = _state.value.editions.firstOrNull { it.tournamentId == tournamentId } ?: return
        manualSelectedTournamentId = record.tournamentId
        val initial = buildDetail(
            record = record,
            schedule = MatchSessionStore.schedule.value,
            standingsState = StandingsCenterStore.state.value,
            historyOverride = cachedHydratedHistory(record.tournamentId)
        )
        replaceEdition(initial.edition)
        _state.value = _state.value.copy(
            selectedTournamentId = record.tournamentId,
            selected = initial,
            followingCurrent = false,
            statusMessage = "正在打开 ${record.displayName} 年度赛事档案…"
        )
        persist(_state.value.editions)

        hydrateJob?.cancel()
        hydrateJob = scope.launch {
            val fetchedStandings = runCatching {
                historicalStandingsClient.fetchTournamentStandings(record.tournamentId)
            }.getOrNull()?.takeIf(::hasStandingsRows)
            val fetchedHistory = runCatching {
                eventHistoryProvider.fetch(record)
            }.getOrNull()?.takeIf(::hasHistoryData)

            if (manualSelectedTournamentId != record.tournamentId) return@launch
            if (fetchedStandings != null) {
                synchronized(hydratedStandings) {
                    hydratedStandings[record.tournamentId] = fetchedStandings
                }
            }
            if (fetchedHistory != null) {
                synchronized(hydratedHistory) {
                    hydratedHistory[record.tournamentId] = fetchedHistory
                }
            }
            if (fetchedStandings == null && fetchedHistory == null) {
                _state.value = _state.value.copy(
                    statusMessage = "${record.displayName} · Riot 历史 Standings / Completed Events 当前均不可用，保留已归档槽位"
                )
                return@launch
            }

            val latestRecord = _state.value.editions.firstOrNull { it.tournamentId == record.tournamentId }
                ?: return@launch
            val hydrated = buildDetail(
                record = latestRecord,
                schedule = MatchSessionStore.schedule.value,
                standingsState = StandingsCenterStore.state.value,
                standingsOverride = fetchedStandings ?: cachedHydratedStandings(record.tournamentId),
                historyOverride = fetchedHistory ?: cachedHydratedHistory(record.tournamentId)
            )
            replaceEdition(hydrated.edition)
            _state.value = _state.value.copy(
                selected = hydrated,
                lastRefreshEpochMs = System.currentTimeMillis(),
                statusMessage = buildString {
                    append(record.displayName).append(" · 历史数据独立同步：")
                    append(if (fetchedStandings != null) "Standings ✓" else "Standings —")
                    append(" · ")
                    append(if (fetchedHistory?.completedSeries?.isNotEmpty() == true) "Completed Events ✓" else "Completed Events —")
                    append(" · ")
                    append(if (hydrated.edition.patchVersions.isNotEmpty()) "Patch ✓" else "Patch —")
                }
            )
            persist(_state.value.editions)
        }
    }

    fun followCurrentTournament() {
        hydrateJob?.cancel()
        manualSelectedTournamentId = ""
        rebuild(
            MatchSessionStore.schedule.value,
            MatchSessionStore.targetMatch.value,
            StandingsCenterStore.state.value
        )
    }

    private fun rebuild(
        schedule: List<ScheduledEsportsMatch>,
        target: ScheduledEsportsMatch?,
        standingsState: StandingsCenterState
    ) {
        val now = System.currentTimeMillis()
        val previous = _state.value.editions.associateBy { it.tournamentId }.toMutableMap()

        standingsState.tournaments.forEach { ref ->
            val identity = TournamentIdentityResolver.resolve(ref)
            val matches = matchesForEdition(ref, schedule)
            val knownTeams = matches
                .flatMap { it.teams }
                .map { it.code.ifBlank { it.name }.trim().uppercase() }
                .filter { it.isNotBlank() && it != "TBD" && it != "—" }
                .distinct()
                .sorted()
            val old = previous[ref.id]
            previous[ref.id] = TournamentEditionArchiveRecord(
                tournamentId = ref.id,
                slug = ref.slug,
                leagueId = ref.leagueId,
                leagueSlug = ref.leagueSlug,
                leagueName = ref.leagueName,
                family = identity.family,
                seasonYear = identity.seasonYear,
                editionKey = identity.editionKey,
                displayName = StandingsCenterStore.displayTournamentName(ref),
                stage = identity.stage,
                startDate = ref.startDate,
                endDate = ref.endDate,
                participantTeamCodes = (old?.participantTeamCodes.orEmpty() + knownTeams).distinct().sorted(),
                scheduleSeriesCount = maxOf(old?.scheduleSeriesCount ?: 0, matches.size),
                archivedSlots = old?.archivedSlots.orEmpty(),
                firstSeenEpochMs = old?.firstSeenEpochMs ?: now,
                lastSeenEpochMs = now
            )
        }

        var ordered = previous.values
            .distinctBy { it.editionKey.ifBlank { it.tournamentId } }
            .sortedWith(
                compareBy<TournamentEditionArchiveRecord> { it.startDate.ifBlank { "9999-99-99" } }
                    .thenBy { it.family }
                    .thenBy { it.stage }
                    .thenBy { it.tournamentId }
            )

        val targetEdition = chooseTargetEdition(target, standingsState.tournaments)
        val selectedId = when {
            manualSelectedTournamentId.isNotBlank() && ordered.any { it.tournamentId == manualSelectedTournamentId } ->
                manualSelectedTournamentId
            targetEdition != null -> targetEdition.id
            standingsState.selectedTournament != null && ordered.any { it.tournamentId == standingsState.selectedTournament.id } ->
                standingsState.selectedTournament.id
            else -> ordered.lastOrNull()?.tournamentId.orEmpty()
        }
        val selectedRecord = ordered.firstOrNull { it.tournamentId == selectedId }
        val detail = selectedRecord?.let {
            buildDetail(
                record = it,
                schedule = schedule,
                standingsState = standingsState,
                standingsOverride = cachedHydratedStandings(it.tournamentId),
                historyOverride = cachedHydratedHistory(it.tournamentId)
            )
        }
        if (detail != null) {
            ordered = ordered.map { record ->
                if (record.tournamentId == detail.edition.tournamentId) detail.edition else record
            }
        }

        _state.value = TournamentEditionArchiveState(
            editions = ordered,
            selectedTournamentId = selectedId,
            selected = detail,
            followingCurrent = manualSelectedTournamentId.isBlank(),
            lastRefreshEpochMs = now,
            statusMessage = when {
                ordered.isEmpty() -> "Riot Tournament Directory · 当前没有可归档届次"
                detail == null -> "已归档 ${ordered.size} 个 Tournament Edition"
                manualSelectedTournamentId.isNotBlank() -> "已归档 ${ordered.size} 个 Tournament Edition · 浏览 ${detail.edition.displayName}"
                else -> "已归档 ${ordered.size} 个 Tournament Edition · 当前 ${detail.edition.displayName}"
            }
        )
        persist(ordered)
    }

    private fun buildDetail(
        record: TournamentEditionArchiveRecord,
        schedule: List<ScheduledEsportsMatch>,
        standingsState: StandingsCenterState,
        standingsOverride: TournamentStandings? = null,
        historyOverride: TournamentEventHistorySnapshot? = null
    ): TournamentEditionDetail {
        val ref = EsportsTournamentRef(
            id = record.tournamentId,
            slug = record.slug,
            startDate = record.startDate,
            endDate = record.endDate,
            leagueId = record.leagueId,
            leagueSlug = record.leagueSlug,
            leagueName = record.leagueName
        )
        val matches = (matchesForEdition(ref, schedule) + historyOverride?.completedSeries.orEmpty())
            .distinctBy { it.eventId.ifBlank { it.matchId } }
            .sortedBy { it.startTimeIso }
        val standings = standingsOverride
            ?: standingsState.standings?.takeIf { it.tournamentId == record.tournamentId }
        val governance = TournamentGovernanceProvider.resolve(
            tournament = ref,
            competitionTitle = record.displayName,
            matches = matches,
            standings = standings
        )
        val research = TournamentResearchProvider.resolve(
            tournament = ref,
            competitionTitle = record.displayName,
            matches = matches,
            standings = standings,
            governance = governance,
            verifiedPatchVersions = (record.patchVersions + historyOverride?.patchVersions.orEmpty()).distinct()
        )
        val standingsTeams = standings.orEmptyTeamCodes()
        val participantTeams = (record.participantTeamCodes + standingsTeams).distinct().sorted()
        val provisional = record.copy(
            participantTeamCodes = participantTeams,
            scheduleSeriesCount = maxOf(record.scheduleSeriesCount, matches.size),
            patchVersions = (record.patchVersions + historyOverride?.patchVersions.orEmpty()).distinct()
        )
        val liveSlots = buildSlots(provisional, matches, standings, governance, research, historyOverride)
        val mergedSlots = mergeSlots(record.archivedSlots, liveSlots)
        val enriched = provisional.copy(archivedSlots = mergedSlots)

        return TournamentEditionDetail(
            edition = enriched,
            matchedSeries = matches,
            standings = standings,
            governance = governance,
            research = research,
            slots = mergedSlots,
            provenance = buildList {
                add(
                    DataProvenance(
                        sourceId = "riot-tournament-directory",
                        displayName = "Riot Tournament Directory",
                        authority = DataAuthority.PROVIDER,
                        freshness = DataFreshnessClass.DAILY,
                        verified = false
                    )
                )
                if (matches.isNotEmpty()) {
                    add(
                        DataProvenance(
                            sourceId = "unified-schedule",
                            displayName = "RiftLab Unified Schedule (Riot/Cito)",
                            authority = DataAuthority.PROVIDER,
                            freshness = DataFreshnessClass.MINUTES,
                            verified = false
                        )
                    )
                }
                if (standings != null) {
                    add(
                        DataProvenance(
                            sourceId = "riot-standings",
                            displayName = "Riot Standings",
                            authority = DataAuthority.PROVIDER,
                            freshness = DataFreshnessClass.MINUTES,
                            verified = false
                        )
                    )
                }
                if (historyOverride?.completedSeries?.isNotEmpty() == true) {
                    add(
                        DataProvenance(
                            sourceId = "riot-completed-events",
                            displayName = historyOverride.completedEventsSource.ifBlank { "Riot Completed Events" },
                            authority = DataAuthority.PROVIDER,
                            freshness = DataFreshnessClass.DAILY,
                            verified = false
                        )
                    )
                }
                if (provisional.patchVersions.isNotEmpty()) {
                    add(
                        DataProvenance(
                            sourceId = "riot-livestats-patch",
                            displayName = "Riot LiveStats · gameMetadata.patchVersion",
                            authority = DataAuthority.OFFICIAL,
                            freshness = DataFreshnessClass.STATIC,
                            verified = true
                        )
                    )
                }
                if (governance.rules.items.any { it.verified } || governance.draw.slots.any { it.verified }) {
                    add(
                        DataProvenance(
                            sourceId = "official-governance-snapshot",
                            displayName = "Official Rule / Draw Snapshot",
                            authority = DataAuthority.OFFICIAL,
                            freshness = DataFreshnessClass.STATIC,
                            verified = true
                        )
                    )
                }
                add(
                    DataProvenance(
                        sourceId = "tournament-edition-local-archive",
                        displayName = "RiftLab Tournament Edition Archive",
                        authority = DataAuthority.LOCAL_CACHE,
                        freshness = DataFreshnessClass.STATIC,
                        verified = false
                    )
                )
            }
        )
    }

    private fun buildSlots(
        edition: TournamentEditionArchiveRecord,
        matches: List<ScheduledEsportsMatch>,
        standings: TournamentStandings?,
        governance: TournamentGovernanceSnapshot,
        research: TournamentResearchSnapshot,
        history: TournamentEventHistorySnapshot?
    ): List<TournamentEditionSlot> {
        val standingsRows = standings?.stages.orEmpty().sumOf { stage ->
            stage.sections.sumOf { it.rankings.size + it.matches.size }
        }
        val rulesVerified = governance.rules.items.count { it.verified }
        val drawVerified = governance.draw.slots.count { it.verified }
        val ended = parseDate(edition.endDate)?.isBefore(LocalDate.now()) == true
        val terminalOutcome = resolveTerminalOutcome(standings)

        return listOf(
            TournamentEditionSlot(
                key = "identity",
                label = "届次 / 年份",
                state = if (edition.seasonYear != null && edition.editionKey.isNotBlank()) TournamentEditionSlotState.COMPLETE else TournamentEditionSlotState.PARTIAL,
                detail = listOfNotNull(
                    edition.seasonYear?.toString(),
                    edition.family,
                    edition.stage.takeIf { it.isNotBlank() }
                ).joinToString(" · ").ifBlank { edition.displayName },
                source = "Riot Tournament Directory"
            ),
            TournamentEditionSlot(
                key = "patch",
                label = "比赛版本",
                state = when (research.version.evidence) {
                    ResearchEvidence.VERIFIED -> TournamentEditionSlotState.COMPLETE
                    ResearchEvidence.PROVIDER -> TournamentEditionSlotState.PARTIAL
                    ResearchEvidence.PENDING -> TournamentEditionSlotState.PENDING
                },
                detail = research.version.versionLabel,
                source = research.version.source
            ),
            TournamentEditionSlot(
                key = "participants",
                label = "参赛队",
                state = when {
                    edition.participantTeamCodes.isEmpty() -> TournamentEditionSlotState.PENDING
                    ended && history?.completedSeries?.isNotEmpty() == true -> TournamentEditionSlotState.COMPLETE
                    else -> TournamentEditionSlotState.PARTIAL
                },
                detail = if (edition.participantTeamCodes.isEmpty()) {
                    "等待可信赛程 / Standings"
                } else {
                    "已识别 ${edition.participantTeamCodes.size} 支：${edition.participantTeamCodes.take(8).joinToString(" / ")}${if (edition.participantTeamCodes.size > 8) " …" else ""}"
                },
                source = when {
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
                detail = "资格路径已留独立槽位；dev.70 接入 Championship Points / 资格赛节点后回填，不从参赛名单反推。"
            ),
            TournamentEditionSlot(
                key = "rules",
                label = "规则 / 赛制",
                state = when {
                    rulesVerified > 0 -> TournamentEditionSlotState.COMPLETE
                    governance.rules.items.isNotEmpty() -> TournamentEditionSlotState.PARTIAL
                    else -> TournamentEditionSlotState.PENDING
                },
                detail = if (rulesVerified > 0) {
                    "${governance.rules.items.size} 条 · $rulesVerified 条官方核实"
                } else {
                    "${governance.rules.items.size} 条结构记录"
                },
                source = governance.rules.sourceSummary
            ),
            TournamentEditionSlot(
                key = "draw",
                label = "分组 / 签位",
                state = when {
                    drawVerified > 0 -> TournamentEditionSlotState.COMPLETE
                    governance.draw.slots.isNotEmpty() -> TournamentEditionSlotState.PARTIAL
                    else -> TournamentEditionSlotState.PENDING
                },
                detail = if (governance.draw.slots.isEmpty()) {
                    "等待官方抽签 / Riot Bracket"
                } else {
                    "${governance.draw.slots.size} 个槽位 · $drawVerified 个已核实"
                },
                source = governance.draw.sourceSummary
            ),
            TournamentEditionSlot(
                key = "schedule",
                label = "赛程",
                state = when {
                    matches.isEmpty() -> TournamentEditionSlotState.PENDING
                    ended && history?.completedSeries?.isNotEmpty() == true -> TournamentEditionSlotState.COMPLETE
                    else -> TournamentEditionSlotState.PARTIAL
                },
                detail = if (matches.isEmpty()) "当前分页没有该届比赛；保留届次实体等待历史回填" else "当前已归档 ${matches.size} 场 Series",
                source = if (history?.completedSeries?.isNotEmpty() == true) history.completedEventsSource else "RiftLab Unified Schedule (Riot/Cito)"
            ),
            TournamentEditionSlot(
                key = "standings",
                label = "Standings / Bracket",
                state = if (standingsRows > 0) TournamentEditionSlotState.PARTIAL else TournamentEditionSlotState.PENDING,
                detail = if (standingsRows > 0) {
                    "${standings?.stages?.size ?: 0} 个阶段 · $standingsRows 个排名/签位记录"
                } else {
                    "等待该届 Riot Standings"
                },
                source = if (standingsRows > 0) "Riot Standings" else ""
            ),
            TournamentEditionSlot(
                key = "placement",
                label = "最终名次",
                state = if (terminalOutcome != null && ended) TournamentEditionSlotState.PARTIAL else TournamentEditionSlotState.PENDING,
                detail = terminalOutcome ?: if (ended) "赛事已结束；等待可信最终名次来源" else "赛事未结束；最终名次尚未产生",
                source = if (terminalOutcome != null) "Riot Standings · terminal bracket outcome" else ""
            ),
            TournamentEditionSlot(
                key = "awards",
                label = "MVP / FMVP / POG",
                state = if (history?.verifiedAwards?.isNotEmpty() == true) TournamentEditionSlotState.PARTIAL else TournamentEditionSlotState.PENDING,
                detail = if (history?.verifiedAwards?.isNotEmpty() == true) {
                    "已接入 ${history.verifiedAwards.size} 条已核实 MVP / POG 记录；未覆盖赛事继续保持未知。"
                } else {
                    "只接收已核实奖项记录；不按 KDA / 伤害自动推断 MVP。"
                },
                source = history?.awardsSource.orEmpty()
            ),
            TournamentEditionSlot(
                key = "provenance",
                label = "来源 / 更新时间",
                state = TournamentEditionSlotState.COMPLETE,
                detail = "Tournament Directory + 可用 Schedule / Standings / Governance；本地档案只做保留，不冒充官方源。",
                source = "RiftLab Provenance"
            )
        )
    }

    /** Never downgrade a known historical slot because a later network window is thinner. */
    private fun mergeSlots(
        archived: List<TournamentEditionSlot>,
        current: List<TournamentEditionSlot>
    ): List<TournamentEditionSlot> {
        val oldByKey = archived.associateBy { it.key }
        return current.map { fresh ->
            val old = oldByKey[fresh.key] ?: return@map fresh
            if (slotRank(fresh.state) >= slotRank(old.state)) fresh else old
        } + archived.filter { old -> current.none { it.key == old.key } }
    }

    private fun slotRank(state: TournamentEditionSlotState): Int = when (state) {
        TournamentEditionSlotState.COMPLETE -> 3
        TournamentEditionSlotState.PARTIAL -> 2
        TournamentEditionSlotState.PENDING -> 1
        TournamentEditionSlotState.SOURCE_ERROR -> 0
    }

    private fun resolveTerminalOutcome(standings: TournamentStandings?): String? {
        val bracket = standings?.stages.orEmpty().flatMap { stage -> stage.sections.flatMap { it.matches } }
        if (bracket.isEmpty()) return null
        val referenced = bracket.flatMap { it.previousMatchIds }.toSet()
        val terminal = bracket.lastOrNull {
            it.id.isNotBlank() && it.id !in referenced && isCompleted(it.state)
        } ?: bracket.lastOrNull { isCompleted(it.state) } ?: return null
        val winner = terminal.teams.firstOrNull { it.outcome.contains("win", ignoreCase = true) }
        val loser = terminal.teams.firstOrNull {
            it.outcome.contains("loss", ignoreCase = true) || it.outcome.contains("lose", ignoreCase = true)
        }
        if (winner == null) return null
        val winnerCode = winner.code.ifBlank { winner.name }
        val loserCode = loser?.let { it.code.ifBlank { it.name } }.orEmpty()
        return if (loserCode.isNotBlank()) {
            "冠军候选：$winnerCode · 亚军候选：$loserCode（Bracket 终局结构推导，仍待官方最终名次源确认）"
        } else {
            "冠军候选：$winnerCode（Bracket 终局结构推导，仍待官方最终名次源确认）"
        }
    }

    private fun isCompleted(value: String): Boolean {
        val normalized = value.lowercase().replace("_", "").replace("-", "").replace(" ", "")
        return normalized.contains("complete") || normalized.contains("finished")
    }

    private fun hasStandingsRows(standings: TournamentStandings): Boolean = standings.stages.any { stage ->
        stage.sections.any { it.rankings.isNotEmpty() || it.matches.isNotEmpty() }
    }

    private fun cachedHydratedStandings(tournamentId: String): TournamentStandings? =
        synchronized(hydratedStandings) { hydratedStandings[tournamentId] }

    private fun cachedHydratedHistory(tournamentId: String): TournamentEventHistorySnapshot? =
        synchronized(hydratedHistory) { hydratedHistory[tournamentId] }

    private fun hasHistoryData(history: TournamentEventHistorySnapshot): Boolean =
        history.completedSeries.isNotEmpty() || history.patchVersions.isNotEmpty() || history.verifiedAwards.isNotEmpty()

    private fun replaceEdition(record: TournamentEditionArchiveRecord) {
        val current = _state.value
        val replaced = current.editions.map { existing ->
            if (existing.tournamentId == record.tournamentId) record else existing
        }
        _state.value = current.copy(editions = replaced)
    }

    private fun chooseTargetEdition(
        target: ScheduledEsportsMatch?,
        tournaments: List<EsportsTournamentRef>
    ): EsportsTournamentRef? {
        if (target == null) return null
        val date = parseDate(target.startTimeIso)
        val candidates = tournaments.filter { ref -> leagueMatches(ref, target) }
        if (date != null) {
            candidates.firstOrNull { ref -> containsDate(ref, date) }?.let { return it }
        }
        return candidates.maxByOrNull { it.startDate }
    }

    private fun matchesForEdition(
        edition: EsportsTournamentRef,
        schedule: List<ScheduledEsportsMatch>
    ): List<ScheduledEsportsMatch> {
        val start = parseDate(edition.startDate)
        val end = parseDate(edition.endDate)
        return schedule.filter { match ->
            if (!leagueMatches(edition, match)) return@filter false
            val date = parseDate(match.startTimeIso) ?: return@filter false
            when {
                start != null && end != null -> !date.isBefore(start) && !date.isAfter(end)
                start != null -> date == start
                else -> false
            }
        }.distinctBy { it.matchId.ifBlank { it.eventId } }.sortedBy { it.startTimeIso }
    }

    private fun leagueMatches(ref: EsportsTournamentRef, match: ScheduledEsportsMatch): Boolean = when {
        ref.leagueId.isNotBlank() && match.leagueId.isNotBlank() -> ref.leagueId == match.leagueId
        ref.leagueSlug.isNotBlank() && match.leagueSlug.isNotBlank() -> ref.leagueSlug.equals(match.leagueSlug, ignoreCase = true)
        else -> ref.leagueName.equals(match.league, ignoreCase = true)
    }

    private fun containsDate(ref: EsportsTournamentRef, date: LocalDate): Boolean {
        val start = parseDate(ref.startDate) ?: return false
        val end = parseDate(ref.endDate) ?: return false
        return !date.isBefore(start) && !date.isAfter(end)
    }

    private fun parseDate(value: String): LocalDate? = runCatching {
        LocalDate.parse(value.take(10))
    }.getOrNull()

    private fun TournamentStandings?.orEmptyTeamCodes(): List<String> = this?.stages.orEmpty()
        .flatMap { stage -> stage.sections }
        .flatMap { section -> section.rankings }
        .map { row -> row.team.code.ifBlank { row.team.name } }
        .filter { it.isNotBlank() && it != "TBD" && it != "—" }
        .distinct()

    private fun loadPersisted(): TournamentEditionArchiveState {
        val file = archiveFile ?: return TournamentEditionArchiveState()
        if (!file.exists()) {
            return TournamentEditionArchiveState(
                statusMessage = "年度赛事本地档案为空 · 等待 Riot Tournament Directory"
            )
        }
        return runCatching {
            val root = JSONObject(file.readText())
            if (root.optInt("schemaVersion", 0) != SCHEMA_VERSION) {
                return@runCatching TournamentEditionArchiveState()
            }
            val rows = root.optJSONArray("editions") ?: JSONArray()
            val editions = buildList {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val tournamentId = row.optString("tournamentId")
                    if (tournamentId.isBlank()) continue
                    add(
                        TournamentEditionArchiveRecord(
                            tournamentId = tournamentId,
                            slug = row.optString("slug"),
                            leagueId = row.optString("leagueId"),
                            leagueSlug = row.optString("leagueSlug"),
                            leagueName = row.optString("leagueName"),
                            family = row.optString("family"),
                            seasonYear = row.optInt("seasonYear", 0).takeIf { it > 0 },
                            editionKey = row.optString("editionKey").ifBlank { tournamentId },
                            displayName = row.optString("displayName").ifBlank { row.optString("slug") },
                            stage = row.optString("stage"),
                            startDate = row.optString("startDate"),
                            endDate = row.optString("endDate"),
                            participantTeamCodes = jsonStrings(row.optJSONArray("participantTeamCodes")),
                            scheduleSeriesCount = row.optInt("scheduleSeriesCount", 0),
                            patchVersions = jsonStrings(row.optJSONArray("patchVersions")),
                            archivedSlots = parseSlots(row.optJSONArray("slots")),
                            firstSeenEpochMs = row.optLong("firstSeenEpochMs", 0L)
                                .takeIf { it > 0 } ?: System.currentTimeMillis(),
                            lastSeenEpochMs = row.optLong("lastSeenEpochMs", 0L)
                        )
                    )
                }
            }.sortedBy { it.startDate }
            TournamentEditionArchiveState(
                editions = editions,
                selectedTournamentId = "",
                followingCurrent = true,
                lastRefreshEpochMs = root.optLong("savedAtEpochMs", 0L),
                statusMessage = "已从本地恢复 ${editions.size} 个 Tournament Edition · 等待在线增量同步"
            )
        }.getOrElse {
            TournamentEditionArchiveState(
                statusMessage = "年度赛事本地档案损坏 · 不采用损坏缓存，等待在线重建"
            )
        }
    }

    private fun persist(editions: List<TournamentEditionArchiveRecord>) {
        val file = archiveFile ?: return
        runCatching {
            val root = JSONObject().apply {
                put("schemaVersion", SCHEMA_VERSION)
                put("savedAtEpochMs", System.currentTimeMillis())
                put(
                    "editions",
                    JSONArray().apply {
                        editions.forEach { edition ->
                            put(
                                JSONObject().apply {
                                    put("tournamentId", edition.tournamentId)
                                    put("slug", edition.slug)
                                    put("leagueId", edition.leagueId)
                                    put("leagueSlug", edition.leagueSlug)
                                    put("leagueName", edition.leagueName)
                                    put("family", edition.family)
                                    put("seasonYear", edition.seasonYear ?: JSONObject.NULL)
                                    put("editionKey", edition.editionKey)
                                    put("displayName", edition.displayName)
                                    put("stage", edition.stage)
                                    put("startDate", edition.startDate)
                                    put("endDate", edition.endDate)
                                    put("participantTeamCodes", JSONArray(edition.participantTeamCodes))
                                    put("scheduleSeriesCount", edition.scheduleSeriesCount)
                                    put("patchVersions", JSONArray(edition.patchVersions))
                                    put("slots", JSONArray().apply {
                                        edition.archivedSlots.forEach { slot ->
                                            put(JSONObject().apply {
                                                put("key", slot.key)
                                                put("label", slot.label)
                                                put("state", slot.state.name)
                                                put("detail", slot.detail)
                                                put("source", slot.source)
                                            })
                                        }
                                    })
                                    put("firstSeenEpochMs", edition.firstSeenEpochMs)
                                    put("lastSeenEpochMs", edition.lastSeenEpochMs)
                                }
                            )
                        }
                    }
                )
            }
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                file.writeText(root.toString())
                temp.delete()
            }
        }
    }

    private fun parseSlots(array: JSONArray?): List<TournamentEditionSlot> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val key = row.optString("key")
                if (key.isBlank()) continue
                val state = runCatching {
                    TournamentEditionSlotState.valueOf(row.optString("state"))
                }.getOrDefault(TournamentEditionSlotState.PENDING)
                add(
                    TournamentEditionSlot(
                        key = key,
                        label = row.optString("label").ifBlank { key },
                        state = state,
                        detail = row.optString("detail"),
                        source = row.optString("source")
                    )
                )
            }
        }
    }

    private fun jsonStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}
