package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Connects schedule/live/final flows to [MatchLifecycleArchive].
 *
 * Nothing here depends on which screen is currently open. Once MatchSessionStore starts, every
 * schedule revision and every chosen live-provider frame is archived automatically.
 */
object MatchLifecycleCapture {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scheduleJob: Job? = null
    private var liveJob: Job? = null
    private var finalJob: Job? = null

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
                        MatchLifecycleArchive.observeLive(match, snapshot)
                    }
            }
        }

        if (finalJob?.isActive != true) {
            finalJob = scope.launch {
                MatchSessionStore.completedSeries.collect { series ->
                    series ?: return@collect
                    val center = MatchSessionStore.scheduleCenter.value
                    val match = resolveSeriesMatch(center.matches, series) ?: return@collect
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
            center.matches.firstOrNull { it.eventId == eventId || it.matchId == eventId }?.let { return it }
        }
        return center.currentMatch
            ?: center.selectedMatch?.takeIf { MatchSessionStore.schedulePhase(it) == ScheduleMatchPhase.LIVE }
    }

    private fun resolveSeriesMatch(
        matches: List<ScheduledEsportsMatch>,
        series: CompletedSeriesSnapshot
    ): ScheduledEsportsMatch? {
        val seriesTeams = setOf(teamToken(series.teamA), teamToken(series.teamB)).filter { it.isNotBlank() }.toSet()
        if (seriesTeams.size < 2) return null
        return matches.asSequence()
            .filter { match ->
                match.teams.take(2)
                    .map { teamToken(it.code.ifBlank { it.name }) }
                    .filter { it.isNotBlank() }
                    .toSet() == seriesTeams
            }
            .sortedWith(
                compareByDescending<ScheduledEsportsMatch> {
                    MatchSessionStore.schedulePhase(it) == ScheduleMatchPhase.COMPLETED
                }.thenByDescending { it.startTimeIso }
            )
            .firstOrNull()
    }

    private fun teamToken(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
}
