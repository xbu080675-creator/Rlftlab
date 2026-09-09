package com.riftlab.app.data

/**
 * Cito API transport contract for League of Legends.
 *
 * Keep endpoint definitions centralized so the eventual provider can switch between REST polling
 * and the paid WebSocket transport without leaking credentials into UI or logs.
 */
internal object CitoApiConfig {
    const val REST_BASE_URL = "https://api.citoapi.com/api/v1"
    const val LIVE_WEBSOCKET_URL = "wss://api.citoapi.com/api/v1/lol/live/ws"
    const val API_KEY_HEADER = "x-api-key"

    fun apiKey(): String? = ProviderCredentialStore.readCitoApiKey()

    fun liveMatchesUrl(): String = "$REST_BASE_URL/lol/live"

    fun coverageUrl(matchId: String): String =
        "$REST_BASE_URL/lol/matches/${encodePath(matchId)}/coverage"

    fun liveSeriesUrl(matchId: String): String =
        "$REST_BASE_URL/lol/live/${encodePath(matchId)}/series"

    fun liveBoardUrl(gameId: String): String =
        "$REST_BASE_URL/lol/live/${encodePath(gameId)}/stats"

    fun liveWindowUrl(gameId: String): String =
        "$REST_BASE_URL/lol/live/${encodePath(gameId)}/window"

    fun liveDetailsUrl(gameId: String): String =
        "$REST_BASE_URL/lol/live/${encodePath(gameId)}/details"

    fun liveEventsUrl(gameId: String): String =
        "$REST_BASE_URL/lol/live/${encodePath(gameId)}/events"

    private fun encodePath(value: String): String = java.net.URLEncoder
        .encode(value, Charsets.UTF_8.name())
        .replace("+", "%20")
}
