package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Connects schedule/live/final/detail flows to [MatchLifecycleArchive].
 *
 * Nothing here depends on which screen is currently open. Every join is identity-gated: a final
 * from the current series must not attach to an older series merely because the same two teams met
 * before.
 */
object MatchLifecycleCapture {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scheduleJob: Job? = null
    private var liveJob: Job? = null
    private var finalJob: Job? = null
    private var detailJob: Job? = null

    fun start() {
        if (scheduleJob?.isActive != true) {
            scheduleJob = scope.launch {
                MatchSessionStore.scheduleCenter.collect { center ->
                    MatchLifecycleArchive.observeSchedule(center.matches)
                }
            }
        }

        if (liveJob?.isActive != true) {
            liveJob = scope.launch {
                combine(
                    MatchSessionStore.live,
                    MatchSessionStore.liveSourceStatus,
                    MatchSessionStore.scheduleCenter
                ) { snapshot, status, center -> Triple(snapshot, status, center) }
                    .collect { (snapshot, status, center) ->
                        if (status.phase != LiveSourcePhase.LIVE || snapshot.game <= 0) return@collect
                        val match = resolveLiveMatch(center, status) ?: return@collect
                        val verdict = LiveFrameIdentityGate.validate(snapshot, match, status)
                        if (!verdict.allowed) return@collect
                        MatchLifecycleArchive.observeLive(match, snapshot)
                    }
            }
        }

        if (finalJob?.isActive != true) {
            finalJob = scope.launch {
                MatchSessionStore.completedSeries.collect { series ->
                    series ?: return@collect
                    val center = MatchSessionStore.scheduleCenter.value
                    val match = resolveSeriesMatch(center, series) ?: return@collect
                    MatchLifecycleArchive.observeCompletedSeries(match, series)
                }
            }
        }

        if (detailJob?.isActive != true) {
            detailJob = scope.launch {
                MatchDetailRepository.state.collect { state ->
                    val match = state.match ?: return@collect
                    MatchLifecycleArchive.observeScheduleMatch(match)
                    val series = state.series ?: return@collect
                    MatchLifecycleArchive.observeCompletedSeries(match, series)
                }
            }
        }
    }

    private fun resolveLiveMatch(
        center: ScheduleCenterState,
        status: LiveSourceStatus
    ): ScheduledEsportsMatch? {
        val eventId = status.eventId.trim()
        if (eventId.isNotBlank()) {
            return center.matches.firstOrNull { it.eventId == eventId || it.matchId == eventId }
        }
        return center.currentMatch
            ?: center.selectedMatch?.takeIf { MatchSessionStore.schedulePhase(it) == ScheduleMatchPhase.LIVE }
    }

    private fun resolveSeriesMatch(
        center: ScheduleCenterState,
        series: CompletedSeriesSnapshot
    ): ScheduledEsportsMatch? {
        fun teamsMatch(match: ScheduledEsportsMatch): Boolean {
            val seriesTeams = setOf(teamToken(series.teamA), teamToken(series.teamB)).filter { it.isNotBlank() }.toSet()
            val scheduled = match.teams.take(2)
                .map { teamToken(it.code.ifBlank { it.name }) }
                .filter { it.isNotBlank() }
                .toSet()
            return seriesTeams.size == 2 && seriesTeams == scheduled
        }

        center.currentMatch?.takeIf(::teamsMatch)?.let { return it }
        center.selectedMatch?.takeIf(::teamsMatch)?.let { return it }

        // Historical fallback is allowed only when there is no active/selected matching series.
        // Pick the newest completed schedule row, never an arbitrary older same-team meeting.
        return center.matches.asSequence()
            .filter(::teamsMatch)
            .filter { MatchSessionStore.schedulePhase(it) == ScheduleMatchPhase.COMPLETED }
            .maxByOrNull { it.startTimeIso }
    }

    private fun teamToken(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
}
