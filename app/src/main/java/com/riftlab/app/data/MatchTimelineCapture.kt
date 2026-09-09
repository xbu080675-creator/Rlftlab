package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Bridges the existing live router into the persistent event-sourced timeline. */
object MatchTimelineCapture {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var lastLive: LiveSnapshot? = null
            var previousPhase = LiveSourcePhase.IDLE

            combine(MatchSessionStore.live, MatchSessionStore.liveSourceStatus) { snapshot, status ->
                snapshot to status
            }.collect { (snapshot, status) ->
                if (status.phase == LiveSourcePhase.LIVE && snapshot.game > 0) {
                    val previous = lastLive
                    val switchedGame = previous != null && (
                        previous.game != snapshot.game ||
                            (previous.gameId.isNotBlank() && snapshot.gameId.isNotBlank() && previous.gameId != snapshot.gameId)
                        )
                    if (switchedGame) {
                        MatchTimelineStore.markCompleted(previous!!)
                    }
                    MatchTimelineStore.ingest(snapshot)
                    lastLive = snapshot
                }

                if (previousPhase == LiveSourcePhase.LIVE && status.phase == LiveSourcePhase.BETWEEN_GAMES) {
                    lastLive?.let(MatchTimelineStore::markCompleted)
                    lastLive = null
                }
                previousPhase = status.phase
            }
        }
    }
}
