package com.riftlab.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds only the most recently completed small game.
 * Live UI must never read from this archive; it exists for the post-match surface only.
 */
object CompletedGameArchive {
    private val _latest = MutableStateFlow<LiveSnapshot?>(null)
    val latest: StateFlow<LiveSnapshot?> = _latest.asStateFlow()

    fun publish(snapshot: LiveSnapshot) {
        if (snapshot.game <= 0) return
        _latest.value = snapshot.copy(
            latestEvent = "G${snapshot.game} 已结束 · ${snapshot.latestEvent}"
        )
    }
}
