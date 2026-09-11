package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Live bridge from the existing RiftLab stores into the comprehensive graph.
 *
 * Existing providers remain authoritative. This object does not fetch a second copy of the same
 * data and does not invent missing fields; it only normalizes identity/provenance and reports gaps.
 * dev.70 also attaches qualification paths without collapsing Championship Points into Standings.
 * dev.79 joins verified completed-Series depth and persisted timeline events into the same graph.
 */
object ComprehensiveDataCenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _snapshot = MutableStateFlow(ComprehensiveDataSnapshot())
    val snapshot: StateFlow<ComprehensiveDataSnapshot> = _snapshot.asStateFlow()

    private data class CurrentState(
        val target: ScheduledEsportsMatch?,
        val prematch: PreMatchInfo,
        val live: LiveSnapshot
    )

    private data class ArchiveCore(
        val completed: LiveSnapshot?,
        val completedSeries: CompletedSeriesSnapshot?,
        val standings: StandingsCenterState,
        val scheduleStatus: String
    )

    private data class ArchiveState(
        val completed: LiveSnapshot?,
        val completedSeries: CompletedSeriesSnapshot?,
        val standings: StandingsCenterState,
        val scheduleStatus: String,
        val qualification: QualificationCenterState,
        val timelines: Map<String, GameTimeline>
    )

    fun ensureRunning() {
        if (job?.isActive == true) return
        StandingsCenterStore.ensureRunning()
        QualificationCenterStore.ensureRunning()

        val current = combine(
            MatchSessionStore.targetMatch,
            MatchSessionStore.preMatchFlow,
            MatchSessionStore.live
        ) { target, prematch, live ->
            CurrentState(target, prematch, live)
        }

        val archiveCore = combine(
            MatchSessionStore.completedGame,
            MatchSessionStore.completedSeries,
            StandingsCenterStore.state,
            MatchSessionStore.scheduleStatus
        ) { completed, completedSeries, standings, scheduleStatus ->
            ArchiveCore(completed, completedSeries, standings, scheduleStatus)
        }

        val archive = combine(
            archiveCore,
            QualificationCenterStore.state,
            MatchTimelineStore.timelines
        ) { core, qualification, timelines ->
            ArchiveState(
                completed = core.completed,
                completedSeries = core.completedSeries,
                standings = core.standings,
                scheduleStatus = core.scheduleStatus,
                qualification = qualification,
                timelines = timelines
            )
        }

        job = scope.launch {
            combine(current, archive) { liveState, archiveState ->
                val tournament = chooseTournament(liveState.target, archiveState.standings.tournaments)
                val standings = archiveState.standings.standings
                    ?.takeIf { it.tournamentId == tournament?.id }
                val errors = buildSet {
                    if (archiveState.scheduleStatus.contains("ERROR", ignoreCase = true) ||
                        archiveState.scheduleStatus.contains("不可用")) {
                        add(ComprehensiveDataDomain.SCHEDULE)
                    }
                    if (archiveState.standings.statusMessage.contains("ERROR", ignoreCase = true) ||
                        archiveState.standings.statusMessage.contains("不可用")) {
                        add(ComprehensiveDataDomain.STANDINGS)
                    }
                    if (MatchSessionStore.liveSourceStatus.value.phase == LiveSourcePhase.ERROR) {
                        add(ComprehensiveDataDomain.LIVE)
                    }
                }

                val currentLive = liveState.live.takeIf {
                    MatchSessionStore.liveSourceStatus.value.phase == LiveSourcePhase.LIVE
                }
                // CompletedGameArchive follows the latest finished Series globally. Never attach that
                // archive to an unrelated current/next match just because both stores are non-empty.
                val completedForTarget = ComprehensiveDataDepthEnricher.completedGameForMatch(
                    archiveState.completed,
                    liveState.target
                )
                val seriesForTarget = ComprehensiveDataDepthEnricher.completedSeriesForMatch(
                    archiveState.completedSeries,
                    liveState.target
                )

                val normalized = ComprehensiveDataAssembler.fromExisting(
                    scheduled = liveState.target,
                    tournament = tournament,
                    prematch = liveState.prematch.takeIf { liveState.target != null },
                    live = currentLive,
                    completed = completedForTarget,
                    standings = standings,
                    sourceErrors = errors
                )
                val qualificationPaths = QualificationCenterStore.comprehensivePathsForTournament(
                    state = archiveState.qualification,
                    tournamentId = tournament?.id.orEmpty()
                )
                val withQualification = if (qualificationPaths.isEmpty()) {
                    normalized
                } else {
                    val authority = if (qualificationPaths.all { it.verified }) {
                        DataAuthority.OFFICIAL
                    } else {
                        DataAuthority.DERIVED
                    }
                    val graph = normalized.graph.copy(
                        qualificationPaths = qualificationPaths,
                        provenance = (normalized.graph.provenance + DataProvenance(
                            sourceId = "qualification-center",
                            displayName = "RiftLab Qualification Center",
                            authority = authority,
                            freshness = DataFreshnessClass.DAILY,
                            verified = authority == DataAuthority.OFFICIAL
                        )).distinctBy { it.sourceId + ":" + it.displayName }
                    )
                    normalized.copy(
                        graph = graph,
                        coverage = ComprehensiveCoverageEngine.evaluate(graph, errors),
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                }

                ComprehensiveDataDepthEnricher.enrich(
                    base = withQualification,
                    scheduled = liveState.target,
                    live = currentLive,
                    completedSeries = seriesForTarget,
                    timelines = archiveState.timelines,
                    sourceErrors = errors
                )
            }.collect { normalized ->
                _snapshot.value = normalized
            }
        }
    }

    private fun chooseTournament(
        target: ScheduledEsportsMatch?,
        tournaments: List<EsportsTournamentRef>
    ): EsportsTournamentRef? {
        if (target == null || tournaments.isEmpty()) return null
        val targetDate = parseDate(target.startTimeIso)
        val leagueMatched = tournaments.filter { tournament ->
            when {
                target.leagueId.isNotBlank() && tournament.leagueId.isNotBlank() ->
                    target.leagueId == tournament.leagueId
                target.leagueSlug.isNotBlank() && tournament.leagueSlug.isNotBlank() ->
                    target.leagueSlug.equals(tournament.leagueSlug, ignoreCase = true)
                else -> tournament.leagueName.equals(target.league, ignoreCase = true)
            }
        }
        if (leagueMatched.isEmpty()) return null
        val dated = targetDate?.let { date ->
            leagueMatched.filter { StandingsCenterStore.containsDate(it, date) }
        }.orEmpty()
        return dated.maxByOrNull { it.startDate }
            ?: leagueMatched.maxByOrNull { it.startDate }
    }

    private fun parseDate(value: String): LocalDate? = runCatching {
        LocalDate.parse(value.take(10))
    }.getOrNull()
}
