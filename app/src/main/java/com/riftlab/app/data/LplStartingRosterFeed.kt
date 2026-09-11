package com.riftlab.app.data

import android.content.Context

/**
 * Compatibility wrapper kept for old call sites while starter discovery lives in the
 * global StartingRosterFeed. New code must use StartingRosterFeed directly.
 */
@Deprecated("Use StartingRosterFeed")
internal class LplStartingRosterFeed(
    context: Context,
    private val delegate: StartingRosterFeed = StartingRosterFeed(context.applicationContext)
) {
    suspend fun fetchFor(target: ScheduledEsportsMatch): Map<String, StartingRosterEvidence> =
        delegate.fetchFor(target).evidence
}
