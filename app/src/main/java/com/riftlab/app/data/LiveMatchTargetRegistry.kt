package com.riftlab.app.data

/**
 * Shared, match-agnostic watch target for every live provider.
 *
 * The registry contains only schedule metadata. Providers must resolve their own upstream IDs
 * (Tencent bMatchId, Riot event/game id, etc.) from team/time metadata instead of hardcoding a
 * particular series. Identity validation is centralized in MatchIdentityPolicy so every provider,
 * router, archive and UI uses the same fail-closed rule.
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

    fun snapshotBelongsTo(snapshot: LiveSnapshot, target: ScheduledEsportsMatch?): Boolean =
        target != null && MatchIdentityPolicy.snapshotBelongsTo(snapshot, target)
}
