package com.riftlab.app.data

/**
 * Cito API transport contract for League of Legends.
 *
 * Endpoints mirror Cito's published LoL API surface. WebSocket is optional at runtime: some docs
 * and plan pages expose the paid WSS add-on while older REST docs still describe it as coming soon,
 * so RiftLab always keeps REST as the safe fallback and never assumes WSS entitlement.
 */
internal object CitoApiConfig {
    const val REST_BASE_URL = "https://api.citoapi.com/api/v1"
    const val LIVE_WEBSOCKET_URL = "wss://api.citoapi.com/api/v1/lol/live/ws"
    const val API_KEY_HEADER = "x-api-key"

    fun apiKey(): String? = ProviderCredentialStore.readCitoApiKey()

    fun liveMatchesUrl(): String = "$REST_BASE_URL/lol/live"
    fun coverageMatrixUrl(): String = "$REST_BASE_URL/lol/coverage"
    fun scheduleTodayUrl(): String = "$REST_BASE_URL/lol/schedule/today"
    fun scheduleUpcomingUrl(): String = "$REST_BASE_URL/lol/schedule/upcoming"
    fun leaguesUrl(): String = "$REST_BASE_URL/lol/leagues"
    fun teamsUrl(): String = "$REST_BASE_URL/lol/teams"
    fun transfersUrl(): String = "$REST_BASE_URL/lol/transfers"

    fun coverageUrl(matchId: String): String =
        "$REST_BASE_URL/lol/matches/${encodePath(matchId)}/coverage"

    fun matchUrl(matchId: String): String =
        "$REST_BASE_URL/lol/matches/${encodePath(matchId)}"

    fun matchGamesUrl(matchId: String): String =
        "$REST_BASE_URL/lol/matches/${encodePath(matchId)}/games"

    fun matchMediaUrl(matchId: String): String =
        "$REST_BASE_URL/lol/matches/${encodePath(matchId)}/media"

    fun liveSeriesUrl(matchId: String): String =
        "$REST_BASE_URL/lol/live/${encodePath(matchId)}/series"

    fun liveWatchUrl(matchId: String): String =
        "$REST_BASE_URL/lol/live/${encodePath(matchId)}/watch"

    fun liveBoardUrl(gameId: String): String =
        "$REST_BASE_URL/lol/live/${encodePath(gameId)}/stats"

    fun liveMapUrl(gameId: String): String =
        "$REST_BASE_URL/lol/live/${encodePath(gameId)}/map"

    fun liveVisualStateUrl(gameId: String): String =
        "$REST_BASE_URL/lol/live/${encodePath(gameId)}/visual-state"

    fun liveWindowUrl(gameId: String): String =
        "$REST_BASE_URL/lol/live/${encodePath(gameId)}/window"

    fun liveDetailsUrl(gameId: String): String =
        "$REST_BASE_URL/lol/live/${encodePath(gameId)}/details"

    fun liveEventsUrl(gameId: String): String =
        "$REST_BASE_URL/lol/live/${encodePath(gameId)}/events"

    fun gameUrl(gameId: String): String =
        "$REST_BASE_URL/lol/games/${encodePath(gameId)}"

    fun gameStatsUrl(gameId: String): String =
        "$REST_BASE_URL/lol/games/${encodePath(gameId)}/stats"

    fun gameTimelineUrl(gameId: String): String =
        "$REST_BASE_URL/lol/games/${encodePath(gameId)}/timeline"

    fun gamePostgameUrl(gameId: String): String =
        "$REST_BASE_URL/lol/games/${encodePath(gameId)}/postgame"

    fun gamePlatesUrl(gameId: String): String =
        "$REST_BASE_URL/lol/games/${encodePath(gameId)}/plates"

    fun gameVisionUrl(gameId: String): String =
        "$REST_BASE_URL/lol/games/${encodePath(gameId)}/vision"

    fun gameJungleShareUrl(gameId: String): String =
        "$REST_BASE_URL/lol/games/${encodePath(gameId)}/jungle-share"

    fun draftAnalyticsUrl(matchId: String): String =
        "$REST_BASE_URL/lol/analytics/drafts/${encodePath(matchId)}"

    fun leagueScheduleUrl(leagueId: String): String =
        "$REST_BASE_URL/lol/leagues/${encodePath(leagueId)}/schedule"

    fun leagueStandingsUrl(leagueId: String): String =
        "$REST_BASE_URL/lol/leagues/${encodePath(leagueId)}/standings"

    fun teamRosterHistoryUrl(slug: String): String =
        "$REST_BASE_URL/lol/teams/${encodePath(slug)}/roster/history"

    fun teamObjectivesUrl(slug: String): String =
        "$REST_BASE_URL/lol/teams/${encodePath(slug)}/objectives"

    fun teamTransfersUrl(slug: String): String =
        "$REST_BASE_URL/lol/transfers/team/${encodePath(slug)}"

    fun playerStatsUrl(playerId: String): String =
        "$REST_BASE_URL/lol/players/${encodePath(playerId)}/stats"

    fun playerFormUrl(playerId: String): String =
        "$REST_BASE_URL/lol/players/${encodePath(playerId)}/form"

    fun playerChampionPoolUrl(playerId: String): String =
        "$REST_BASE_URL/lol/players/${encodePath(playerId)}/champion-pool"

    fun playerTeamsUrl(playerId: String): String =
        "$REST_BASE_URL/lol/players/${encodePath(playerId)}/teams"

    fun playerTransfersUrl(playerId: String): String =
        "$REST_BASE_URL/lol/transfers/player/${encodePath(playerId)}"

    private fun encodePath(value: String): String = java.net.URLEncoder
        .encode(value, Charsets.UTF_8.name())
        .replace("+", "%20")
}
