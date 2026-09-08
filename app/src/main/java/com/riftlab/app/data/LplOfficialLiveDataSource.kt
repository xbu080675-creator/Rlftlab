package com.riftlab.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Compatibility facade retained for existing MatchSessionStore/UI call sites.
 *
 * Live data now comes from a current-game-only provider: finished small games are archived for
 * the post-match surface and can never be reused as the active live snapshot.
 */
internal class LplOfficialLiveDataSource : LiveMatchDataSource {
    private val delegate = LplCurrentGameLiveDataSource()

    val status: StateFlow<LiveSourceStatus>
        get() = delegate.status

    override fun observe(matchId: String): Flow<LiveSnapshot> = delegate.observe(matchId)
}
