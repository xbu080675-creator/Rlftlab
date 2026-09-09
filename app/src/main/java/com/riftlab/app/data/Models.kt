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

data class EsportsTournamentRef(
    val id: String,
    val slug: String,
    val startDate: String,
    val endDate: String
)

data class StandingTeam(
    val ordinal: Int,
    val team: EsportsTeamRef,
    val wins: Int,
    val losses: Int,
    val points: Int? = null
)

data class StandingBracketMatch(
    val id: String,
    val state: String,
    val previousMatchIds: List<String>,
    val teams: List<EsportsTeamRef>
)

data class StandingSection(
    val name: String,
    val rankings: List<StandingTeam>,
    val matches: List<StandingBracketMatch>
)

data class StandingStage(
    val id: String,
    val name: String,
    val slug: String,
    val type: String,
    val sections: List<StandingSection>
)

data class TournamentStandings(
    val tournamentId: String,
    val stages: List<StandingStage>
)

data class StandingsCenterState(
    val tournaments: List<EsportsTournamentRef> = emptyList(),
    val selectedTournament: EsportsTournamentRef? = null,
    val standings: TournamentStandings? = null,
    val lastRefreshEpochMs: Long = 0L,
    val statusMessage: String = "排名数据尚未同步"
)

enum class ScheduleMatchPhase {
    LIVE,
    UPCOMING,
    COMPLETED
}

enum class ScheduleActivityState {
    GAME_LIVE,
    EVENT_LIVE,
    BETWEEN_GAMES,
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

data class EsportsSocialLink(
    val platform: String,
    val url: String,
    val label: String = platform,
    val source: String = ""
)

data class EsportsPlayerRef(
    val id: String,
    val summonerName: String,
    val role: String,
    val imageUrl: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val socialLinks: List<EsportsSocialLink> = emptyList()
)

data class EsportsStaffRef(
    val name: String,
    val role: String,
    val source: String,
    val realName: String = "",
    val socialLinks: List<EsportsSocialLink> = emptyList()
)

data class EsportsTeamDetails(
    val id: String,
    val slug: String,
    val code: String,
    val name: String,
    val imageUrl: String = "",
    val players: List<EsportsPlayerRef>,
    val staff: List<EsportsStaffRef> = emptyList(),
    val management: List<EsportsStaffRef> = emptyList(),
    val socialLinks: List<EsportsSocialLink> = emptyList()
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