package com.riftlab.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Compatibility facade retained for the existing MatchSessionStore/UI call sites.
 *
 * The actual LPL live implementation is now MatchDetail-first: Tencent's public matchDetail
 * payload is consumed before any stricter realtime endpoint. This keeps the rest of RiftLab
 * decoupled from provider experiments.
 */
internal class LplOfficialLiveDataSource : LiveMatchDataSource {
    private val delegate = LplMatchDetailLiveDataSource()

    val status: StateFlow<LiveSourceStatus>
        get() = delegate.status

    override fun observe(matchId: String): Flow<LiveSnapshot> = delegate.observe(matchId)
}
