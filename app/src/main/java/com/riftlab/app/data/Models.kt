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

data class ScheduledEsportsMatch(
    val eventId: String,
    val matchId: String,
    val league: String,
    val blockName: String,
    val startTimeIso: String,
    val state: String,
    val bestOf: Int,
    val teams: List<EsportsTeamRef>
)

data class EsportsTeamRef(
    val id: String,
    val code: String,
    val name: String,
    val slug: String = "",
    val imageUrl: String = "",
    val gameWins: Int = 0,
    val outcome: String = "",
    val recordWins: Int = 0,
    val recordLosses: Int = 0
)

enum class ScheduleMatchPhase {
    LIVE,
    UPCOMING,
    COMPLETED
}

data class ScheduleCenterState(
    val matches: List<ScheduledEsportsMatch> = emptyList(),
    val currentMatch: ScheduledEsportsMatch? = null,
    val nextMatch: ScheduledEsportsMatch? = null,
    val selectedMatch: ScheduledEsportsMatch? = null,
    val liveDetectedAtEpochMs: Map<String, Long> = emptyMap(),
    val lastRefreshEpochMs: Long = 0L,
    val statusMessage: String = "赛程中心尚未同步"
)

data class EsportsPlayerRef(
    val id: String,
    val summonerName: String,
    val role: String,
    val imageUrl: String = ""
)

data class EsportsTeamDetails(
    val id: String,
    val slug: String,
    val code: String,
    val name: String,
    val players: List<EsportsPlayerRef>
)

data class LivePlayerSnapshot(
    val participantId: Int,
    val role: String,
    val summonerName: String,
    val championId: String,
    val level: Int,
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    val creepScore: Int,
    val gold: Int
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
    val latestEvent: String,
    val blueBarons: Int = 0,
    val redBarons: Int = 0,
    val bluePlayers: List<LivePlayerSnapshot> = emptyList(),
    val redPlayers: List<LivePlayerSnapshot> = emptyList(),
    val source: String = "unknown",
    val gameId: String = ""
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

enum class LiveSourcePhase {
    IDLE,
    WAITING_FOR_MATCH,
    BETWEEN_GAMES,
    LIVE,
    ERROR
}

data class LiveSourceStatus(
    val phase: LiveSourcePhase,
    val message: String,
    val eventId: String = "",
    val gameId: String = "",
    val lastUpdateEpochMs: Long = 0L
)
