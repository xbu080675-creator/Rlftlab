package com.riftlab.app.ui

import com.riftlab.app.data.EsportsTeamRef
import com.riftlab.app.data.MatchDetailRepository
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.ScheduledEsportsMatch
import com.riftlab.app.data.TeamDetailRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

internal data class AppEntityNavigationState(
    val team: EsportsTeamRef? = null,
    val match: ScheduledEsportsMatch? = null
)

/**
 * One navigation surface for every team/match identity in RiftLab.
 *
 * Score cards, post-match summaries, brackets and roster pages should never invent their own
 * isolated detail flows. A team identity always opens Team Detail; a match always opens the same
 * MatchDetailContent used by the event center.
 */
internal object AppEntityNavigator {
    private val _state = MutableStateFlow(AppEntityNavigationState())
    val state: StateFlow<AppEntityNavigationState> = _state.asStateFlow()

    fun openTeam(candidate: EsportsTeamRef) {
        if (teamToken(candidate.code.ifBlank { candidate.name }) in setOf("", "TBD", "NA", "NONE")) return
        val matches = MatchSessionStore.scheduleCenter.value.matches
        val variants = matches.flatMap { it.teams }.filter { sameTeam(it, candidate) }
        val resolved = (variants + candidate).maxByOrNull(::teamQuality) ?: candidate
        TeamDetailRepository.open(resolved, matches)
        _state.value = AppEntityNavigationState(team = resolved)
    }

    fun openMatch(match: ScheduledEsportsMatch) {
        MatchDetailRepository.open(match)
        _state.value = _state.value.copy(match = match)
    }

    fun openLatestSeries(teamA: String, teamB: String): Boolean {
        val a = teamToken(teamA)
        val b = teamToken(teamB)
        if (a.isBlank() || b.isBlank()) return false
        val match = MatchSessionStore.scheduleCenter.value.matches
            .asSequence()
            .filter { item ->
                val tokens = item.teams.map { teamToken(it.code.ifBlank { it.name }) }.toSet()
                a in tokens && b in tokens
            }
            .maxByOrNull(::matchEpoch)
            ?: return false
        openMatch(match)
        return true
    }

    fun back() {
        val current = _state.value
        if (current.match != null && current.team != null) {
            _state.value = current.copy(match = null)
        } else {
            close()
        }
    }

    fun close() {
        TeamDetailRepository.close()
        _state.value = AppEntityNavigationState()
    }

    private fun sameTeam(a: EsportsTeamRef, b: EsportsTeamRef): Boolean {
        if (a.id.isNotBlank() && b.id.isNotBlank() && a.id == b.id) return true
        val aCode = teamToken(a.code)
        val bCode = teamToken(b.code)
        if (aCode.isNotBlank() && aCode == bCode) return true
        val aName = teamToken(a.name)
        val bName = teamToken(b.name)
        if (aName.isNotBlank() && aName == bName) return true
        val aSlug = teamToken(a.slug)
        val bSlug = teamToken(b.slug)
        return aSlug.isNotBlank() && aSlug == bSlug
    }

    private fun teamQuality(team: EsportsTeamRef): Int =
        (if (team.imageUrl.isNotBlank()) 8 else 0) +
            (if (team.id.isNotBlank()) 4 else 0) +
            (if (team.slug.isNotBlank()) 3 else 0) +
            (if (team.code.isNotBlank()) 2 else 0) +
            (if (team.name.isNotBlank()) 1 else 0)

    private fun matchEpoch(match: ScheduledEsportsMatch): Long =
        runCatching { Instant.parse(match.startTimeIso).toEpochMilli() }.getOrDefault(0L)

    private fun teamToken(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
}
