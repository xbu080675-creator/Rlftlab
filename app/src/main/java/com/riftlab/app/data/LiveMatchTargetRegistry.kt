package com.riftlab.app.data

/**
 * Shared, match-agnostic watch target for every live provider.
 *
 * The registry contains only schedule metadata. Providers must resolve their own upstream IDs
 * (Tencent bMatchId, Riot event/game id, etc.) from team/time metadata instead of hardcoding a
 * particular series.
 */
internal object LiveMatchTargetRegistry {
    @Volatile
    private var current: ScheduledEsportsMatch? = null

    fun update(match: ScheduledEsportsMatch?) {
        current = match
    }

    fun snapshot(): ScheduledEsportsMatch? = current

    fun key(match: ScheduledEsportsMatch?): String = match?.let {
        it.eventId.trim().ifBlank { it.matchId.trim() }.ifBlank {
            val teams = it.teams.take(2).joinToString("|") { team ->
                team.slug.ifBlank { team.code.ifBlank { team.name } }
            }
            "${it.leagueId}|${it.startTimeIso}|$teams"
        }
    }.orEmpty()

    fun snapshotBelongsTo(snapshot: LiveSnapshot, target: ScheduledEsportsMatch?): Boolean {
        target ?: return false
        val expected = target.teams.take(2).map(::aliases)
        if (expected.size < 2 || expected.any { it.isEmpty() }) return false
        val actual = listOf(snapshot.blue, snapshot.red).map(::token)
        if (actual.any { it.isBlank() }) return false
        fun matches(value: String, candidates: Set<String>): Boolean = candidates.any { candidate ->
            value == candidate ||
                (value.length >= 4 && candidate.length >= 4 && (value.contains(candidate) || candidate.contains(value)))
        }
        return actual.all { value -> expected.any { matches(value, it) } } &&
            expected.all { candidates -> actual.any { matches(it, candidates) } }
    }

    private fun aliases(team: EsportsTeamRef): Set<String> =
        listOf(team.code, team.name, team.slug)
            .map(::token)
            .filter { it.isNotBlank() }
            .toSet()

    private fun token(value: String): String =
        value.uppercase().filter { it.isLetterOrDigit() }
}
