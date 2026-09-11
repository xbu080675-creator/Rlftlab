package com.riftlab.app.data

/**
 * Compatibility wrapper kept for old call sites while dev.88 moves starter discovery to the
 * global StartingRosterFeed. New code must use StartingRosterFeed directly.
 */
@Deprecated("Use StartingRosterFeed")
internal class LplStartingRosterFeed(
    private val delegate: StartingRosterFeed = StartingRosterFeed()
) {
    suspend fun fetchFor(target: ScheduledEsportsMatch): Map<String, StartingRosterEvidence> =
        delegate.fetchFor(target)
}
