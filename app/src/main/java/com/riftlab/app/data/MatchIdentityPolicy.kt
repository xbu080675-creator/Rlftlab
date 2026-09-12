package com.riftlab.app.data

import java.time.Instant
import kotlin.math.abs

/**
 * One identity policy for every live/archive/UI join.
 *
 * Strong IDs are authoritative: when both sides expose an eventId (or matchId), a mismatch is a
 * hard mismatch and must never fall through to "same two teams". Only records lacking comparable
 * strong IDs may use the guarded metadata fallback below.
 */
internal object MatchIdentityPolicy {
    private const val FALLBACK_TIME_WINDOW_MS = 6L * 60L * 60L * 1000L

    fun sameMatch(a: ScheduledEsportsMatch, b: ScheduledEsportsMatch): Boolean {
        val aEvent = a.eventId.trim()
        val bEvent = b.eventId.trim()
        if (aEvent.isNotBlank() && bEvent.isNotBlank()) return aEvent == bEvent

        val aMatch = a.matchId.trim()
        val bMatch = b.matchId.trim()
        if (aMatch.isNotBlank() && bMatch.isNotBlank()) return aMatch == bMatch

        if (a.bestOf > 0 && b.bestOf > 0 && a.bestOf != b.bestOf) return false
        if (!sameLeague(a, b)) return false
        if (!sameTeams(a, b)) return false
        return sameTimeWindow(a.startTimeIso, b.startTimeIso)
    }

    fun sameTeams(a: ScheduledEsportsMatch, b: ScheduledEsportsMatch): Boolean =
        teamAliasSets(a) == teamAliasSets(b) && teamAliasSets(a).size == 2

    fun snapshotBelongsTo(snapshot: LiveSnapshot, target: ScheduledEsportsMatch): Boolean {
        if (snapshot.targetKey.isNotBlank() && snapshot.targetKey != LiveMatchTargetRegistry.key(target)) return false
        val expected = target.teams.take(2).map(::aliases)
        if (expected.size < 2 || expected.any { it.isEmpty() }) return false
        val actual = listOf(snapshot.blue, snapshot.red).map(::token)
        if (actual.any { it.isBlank() }) return false

        fun matches(value: String, candidates: Set<String>): Boolean = candidates.any { candidate ->
            value == candidate ||
                (value.length >= 4 && candidate.length >= 4 &&
                    (value.contains(candidate) || candidate.contains(value)))
        }

        return actual.all { value -> expected.any { matches(value, it) } } &&
            expected.all { candidates -> actual.any { matches(it, candidates) } }
    }

    private fun sameLeague(a: ScheduledEsportsMatch, b: ScheduledEsportsMatch): Boolean {
        val aId = token(a.leagueId)
        val bId = token(b.leagueId)
        if (aId.isNotBlank() && bId.isNotBlank()) return aId == bId

        val aSlug = token(a.leagueSlug)
        val bSlug = token(b.leagueSlug)
        if (aSlug.isNotBlank() && bSlug.isNotBlank()) return aSlug == bSlug

        val aName = token(a.league)
        val bName = token(b.league)
        return aName.isBlank() || bName.isBlank() || aName == bName
    }

    private fun sameTimeWindow(a: String, b: String): Boolean {
        val ai = runCatching { Instant.parse(a) }.getOrNull()
        val bi = runCatching { Instant.parse(b) }.getOrNull()
        if (ai != null && bi != null) {
            return abs(ai.toEpochMilli() - bi.toEpochMilli()) <= FALLBACK_TIME_WINDOW_MS
        }
        val ad = a.take(10)
        val bd = b.take(10)
        return ad.isNotBlank() && ad == bd
    }

    private fun teamAliasSets(match: ScheduledEsportsMatch): Set<String> =
        match.teams.take(2)
            .mapNotNull { team ->
                aliases(team).sorted().firstOrNull()
            }
            .filter { it.isNotBlank() }
            .toSet()

    private fun aliases(team: EsportsTeamRef): Set<String> =
        listOf(team.code, team.name, team.slug)
            .map(::token)
            .filter { it.isNotBlank() }
            .toSet()

    private fun token(value: String): String =
        value.uppercase().filter { it.isLetterOrDigit() }
}
