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
}
