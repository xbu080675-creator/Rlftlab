package com.riftlab.app.data

data class PlayerCard(
    val role: String,
    val id: String,
    val rank: String,
    val recent: String
)

data class PreMatchInfo(
    val league: String,
    val stage: String,
    val blue: String,
    val red: String,
    val startTime: String,
    val blueForm: String,
    val redForm: String,
    val blueRoster: List<PlayerCard>,
    val redRoster: List<PlayerCard>,
    val rosterNote: String
)

data class LiveSnapshot(
    val game: Int,
    val elapsedSeconds: Int,
    val blue: String,
    val red: String,
    val blueGold: Int,
    val redGold: Int,
    val blueKills: Int,
    val redKills: Int,
    val blueTowers: Int,
    val redTowers: Int,
    val blueDragons: Int,
    val redDragons: Int,
    val latestEvent: String
) {
    val goldDiff: Int get() = blueGold - redGold
}

data class PostMatchInfo(
    val score: String,
    val winner: String,
    val mvp: String,
    val mvpRole: String,
    val mvpDpm: Int,
    val mvpGoldDiff15: Int,
    val positionRank: String,
    val keyPoint: String
)
