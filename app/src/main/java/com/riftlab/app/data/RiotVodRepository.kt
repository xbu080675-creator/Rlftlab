package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.net.URLEncoder

internal data class RiotVodLink(
    val game: Int,
    val gameId: String,
    val provider: String,
    val locale: String,
    val parameter: String,
    val offsetSeconds: Int = 0
) {
    val isYoutube: Boolean
        get() = provider.contains("youtube", ignoreCase = true) ||
            provider.equals("yt", ignoreCase = true) ||
            parameter.contains("youtube.com", ignoreCase = true) ||
            parameter.contains("youtube-nocookie.com", ignoreCase = true) ||
            parameter.contains("youtu.be", ignoreCase = true)

    val youtubeVideoId: String
        get() {
            if (!isYoutube) return ""
            val raw = parameter.trim()
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) return raw.substringBefore('&').substringBefore('?')
            return when {
                raw.contains("youtu.be/", ignoreCase = true) -> raw.substringAfter("youtu.be/").substringBefore('?').substringBefore('&')
                raw.contains("/embed/", ignoreCase = true) -> raw.substringAfter("/embed/").substringBefore('?').substringBefore('&')
                raw.contains("v=", ignoreCase = true) -> raw.substringAfter("v=").substringBefore('&').substringBefore('#')
                else -> ""
            }
        }

    val embedUrl: String
        get() = youtubeVideoId.takeIf { it.isNotBlank() }
            ?.let { "https://www.youtube.com/embed/$it?playsinline=1&rel=0&fs=1&start=${offsetSeconds.coerceAtLeast(0)}" }
            .orEmpty()

    val sourceUrl: String
        get() = when {
            parameter.startsWith("http://") || parameter.startsWith("https://") -> parameter
            isYoutube -> "https://www.youtube.com/watch?v=$parameter"
            else -> ""
        }
}

internal data class RiotVodState(
    val matchKey: String = "",
    val loading: Boolean = false,
    val links: List<RiotVodLink> = emptyList(),
    val status: String = "Riot VOD 尚未加载",
    val errorMessage: String? = null
)

/** Official overseas/global VOD metadata from Riot getEventDetails. */
internal object RiotVodRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(RiotVodState())
    val state: StateFlow<RiotVodState> = _state.asStateFlow()
    private var job: Job? = null

    fun keyFor(match: ScheduledEsportsMatch): String = match.eventId.ifBlank { match.matchId }

    fun open(match: ScheduledEsportsMatch) {
        val key = keyFor(match)
        if (key.isBlank()) return
        if (_state.value.matchKey == key && (_state.value.loading || _state.value.links.isNotEmpty())) return
        job?.cancel()
        _state.value = RiotVodState(matchKey = key, loading = true, status = "正在读取 Riot 官方 VOD 元数据…")
        job = scope.launch {
            try {
                val encoded = URLEncoder.encode(key, "UTF-8")
                val root = RiotResilientHttp.getJson(
                    "${LolEsportsConfig.PERSISTED_BASE}/getEventDetails?hl=en-US&id=$encoded",
                    connectTimeoutMs = 8_000,
                    readTimeoutMs = 10_000
                )
                val games = root.optJSONObject("data")
                    ?.optJSONObject("event")
                    ?.optJSONObject("match")
                    ?.optJSONArray("games") ?: JSONArray()
                val links = buildList {
                    for (i in 0 until games.length()) {
                        val game = games.optJSONObject(i) ?: continue
                        val number = game.optInt("number", i + 1)
                        val gameId = game.optString("id")
                        val vods = game.optJSONArray("vods") ?: JSONArray()
                        for (j in 0 until vods.length()) {
                            val vod = vods.optJSONObject(j) ?: continue
                            val parameter = vod.optString("parameter")
                            if (parameter.isBlank()) continue
                            add(
                                RiotVodLink(
                                    game = number,
                                    gameId = gameId,
                                    provider = vod.optString("provider").ifBlank { "youtube" },
                                    locale = vod.optString("locale").ifBlank { "und" },
                                    parameter = parameter,
                                    offsetSeconds = vod.optInt("offset", 0)
                                )
                            )
                        }
                    }
                }.distinctBy { "${it.game}|${it.provider}|${it.locale}|${it.parameter}" }
                _state.value = RiotVodState(
                    matchKey = key,
                    loading = false,
                    links = links,
                    status = if (links.isEmpty()) "Riot EventDetails 暂未返回 VOD" else "Riot EventDetails · ${links.size} 条官方 VOD · APP 内播放"
                )
            } catch (t: Throwable) {
                _state.value = RiotVodState(
                    matchKey = key,
                    loading = false,
                    status = "Riot VOD 暂不可用",
                    errorMessage = t.message?.take(140) ?: t::class.java.simpleName
                )
            }
        }
    }

    fun riotVodPage(match: ScheduledEsportsMatch, game: Int): String {
        val key = keyFor(match)
        return if (key.isBlank()) "https://lolesports.com/en-US" else "https://lolesports.com/en-US/vod/$key/$game"
    }
}
