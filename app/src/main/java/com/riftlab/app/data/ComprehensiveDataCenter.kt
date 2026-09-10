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

    private data class ArchiveState(
        val completed: LiveSnapshot?,
        val standings: StandingsCenterState,
        val scheduleStatus: String,
        val qualification: QualificationCenterState
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

        val archive = combine(
            MatchSessionStore.completedGame,
            StandingsCenterStore.state,
            MatchSessionStore.scheduleStatus,
            QualificationCenterStore.state
        ) { completed, standings, scheduleStatus, qualification ->
            ArchiveState(completed, standings, scheduleStatus, qualification)
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
                val normalized = ComprehensiveDataAssembler.fromExisting(
                    scheduled = liveState.target,
                    tournament = tournament,
                    prematch = liveState.prematch.takeIf { liveState.target != null },
                    live = liveState.live.takeIf {
                        MatchSessionStore.liveSourceStatus.value.phase == LiveSourcePhase.LIVE
                    },
                    completed = archiveState.completed,
                    standings = standings,
                    sourceErrors = errors
                )
                val qualificationPaths = QualificationCenterStore.comprehensivePathsForTournament(
                    state = archiveState.qualification,
                    tournamentId = tournament?.id.orEmpty()
                )
                if (qualificationPaths.isEmpty()) {
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
        val dated = targetDate?.let { date ->
            leagueMatched.filter { StandingsCenterStore.containsDate(it, date) }
        }.orEmpty()
        return dated.minByOrNull { it.startDate }
            ?: leagueMatched.maxByOrNull { it.startDate }
            ?: tournaments.firstOrNull { tournament ->
                targetDate != null && StandingsCenterStore.containsDate(tournament, targetDate)
            }
    }

    private fun parseDate(value: String): LocalDate? = runCatching {
        LocalDate.parse(value.take(10))
    }.getOrNull()
}
