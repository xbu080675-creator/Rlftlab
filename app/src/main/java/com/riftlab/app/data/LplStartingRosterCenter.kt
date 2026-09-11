package com.riftlab.app.data

/**
 * Compatibility facade for dev.87 call sites. The implementation is global from dev.88 onward.
 */
@Deprecated("Use StartingRosterCenter")
object LplStartingRosterCenter {
    val state get() = StartingRosterCenter.state

    fun ensureRunning() = StartingRosterCenter.ensureRunning()

    fun evidenceFor(team: EsportsTeamRef): StartingRosterEvidence? =
        StartingRosterCenter.evidenceFor(team)
}
