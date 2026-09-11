package com.riftlab.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CompletedSeriesSnapshot(
    val matchKey: String,
    val teamA: String,
    val teamB: String,
    val scoreA: Int,
    val scoreB: Int,
    val games: List<LiveSnapshot>,
    val seriesFinished: Boolean,
    val source: String,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    val winner: String
        get() = when {
            scoreA > scoreB -> teamA
            scoreB > scoreA -> teamB
            else -> "—"
        }
}

/**
 * Post-match archive. It is intentionally separated from the live surface.
 *
 * A completed game can be reconstructed from an explicitly sourced post-match provider after the
 * game has ended, so the post tab does not depend on RiftLab having been open for the final live
 * frame. LPL may use TJStats; other leagues may use a labelled global supplement. Missing data stays
 * unavailable rather than being synthesized.
 */
object CompletedGameArchive {
    private val _latest = MutableStateFlow<LiveSnapshot?>(null)
    val latest: StateFlow<LiveSnapshot?> = _latest.asStateFlow()

    private val _series = MutableStateFlow<CompletedSeriesSnapshot?>(null)
    val series: StateFlow<CompletedSeriesSnapshot?> = _series.asStateFlow()

    fun publish(snapshot: LiveSnapshot) {
        if (snapshot.game <= 0) return
        _latest.value = snapshot.copy(
            latestEvent = "G${snapshot.game} 已结束 · ${snapshot.latestEvent}"
        )
    }

    fun publishSeries(snapshot: CompletedSeriesSnapshot) {
        if (snapshot.games.isEmpty()) return
        val normalizedGames = snapshot.games
            .filter { it.game > 0 }
            .distinctBy { it.game }
            .sortedBy { it.game }
        if (normalizedGames.isEmpty()) return

        val normalized = snapshot.copy(games = normalizedGames)
        _series.value = normalized
        publish(normalizedGames.last())
    }
}
