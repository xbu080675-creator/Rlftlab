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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class SideSelectionRecord(
    val game: Int,
    val blueTeam: String,
    val redTeam: String,
    val firstPickTeam: String,
    val selectionOwner: String = "",
    val source: String
)

internal data class SideSelectionState(
    val loading: Boolean = false,
    val records: List<SideSelectionRecord> = emptyList(),
    val status: String = "选边数据尚未同步"
)

/** Independent pre-match side-selection surface. */
internal object SideSelectionStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val provider = RiotSideSelectionProvider()
    private var job: Job? = null
    private var lastKey = ""

    private val _state = MutableStateFlow(SideSelectionState())
    val state: StateFlow<SideSelectionState> = _state.asStateFlow()

    fun load(match: ScheduledEsportsMatch, force: Boolean = false) {
        val key = match.eventId.ifBlank { match.matchId }
        if (!force && key.isNotBlank() && key == lastKey && (_state.value.loading || _state.value.records.isNotEmpty())) return
        lastKey = key
        job?.cancel()
        _state.value = SideSelectionState(loading = true, status = "正在同步 Riot EventDetails 选边信息…")
        job = scope.launch {
            val result = runCatching { provider.fetch(match) }
            val rows = result.getOrDefault(emptyList())
            _state.value = SideSelectionState(
                loading = false,
                records = rows,
                status = when {
                    result.isFailure -> "选边同步失败 · ${result.exceptionOrNull()?.message?.take(100) ?: "unknown"}"
                    rows.isNotEmpty() -> "Riot EventDetails · 已确认 ${rows.size} 局蓝红方"
                    else -> "官方尚未确认选边；EventDetails 未开赛槽位不作为公布结果"
                }
            )
        }
    }
}

/**
 * Riot EventDetails contains blue/red slots even for some games that have not started. Those slots
 * can be placeholders and must not be presented as an official pre-match side-selection announcement.
 * We only trust a side assignment when the game has actually started/finished, or when the payload
 * contains an explicit side-selection confirmation marker.
 */
private class RiotSideSelectionProvider {
    suspend fun fetch(match: ScheduledEsportsMatch): List<SideSelectionRecord> {
        if (match.eventId.isBlank()) return emptyList()
        val root = getJson("${LolEsportsConfig.PERSISTED_BASE}/getEventDetails?hl=en-US&id=${match.eventId}")
        val eventMatch = root.optJSONObject("data")
            ?.optJSONObject("event")
            ?.optJSONObject("match") ?: return emptyList()

        val namesById = linkedMapOf<String, String>()
        val eventTeams = eventMatch.optJSONArray("teams") ?: JSONArray()
        for (i in 0 until eventTeams.length()) {
            val team = eventTeams.optJSONObject(i) ?: continue
            val id = team.optString("id")
            val label = team.optString("code").ifBlank { team.optString("name") }
            if (id.isNotBlank()) namesById[id] = label
        }
        match.teams.forEach { team ->
            if (team.id.isNotBlank()) namesById[team.id] = team.code.ifBlank { team.name }
        }

        val games = eventMatch.optJSONArray("games") ?: return emptyList()
        return buildList {
            for (i in 0 until games.length()) {
                val game = games.optJSONObject(i) ?: continue
                if (!isConfirmedSideAssignment(game)) continue

                val sides = game.optJSONArray("teams") ?: JSONArray()
                var blueId = ""
                var redId = ""
                for (j in 0 until sides.length()) {
                    val side = sides.optJSONObject(j) ?: continue
                    when (side.optString("side").lowercase()) {
                        "blue" -> blueId = side.optString("id")
                        "red" -> redId = side.optString("id")
                    }
                }
                if (blueId.isBlank() || redId.isBlank()) continue
                val blue = namesById[blueId].orEmpty().ifBlank { blueId }
                val red = namesById[redId].orEmpty().ifBlank { redId }
                add(
                    SideSelectionRecord(
                        game = game.optInt("number", i + 1),
                        blueTeam = blue,
                        redTeam = red,
                        firstPickTeam = blue,
                        source = "Riot LoL Esports · EventDetails（已确认）"
                    )
                )
            }
        }.sortedBy { it.game }
    }

    private fun isConfirmedSideAssignment(game: JSONObject): Boolean {
        val state = game.optString("state")
            .lowercase()
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "")
        if (state.contains("progress") || state.contains("complete") || state == "finished") return true

        if (game.optBoolean("hasSideSelection", false) || game.optBoolean("sideSelectionConfirmed", false)) return true
        val explicitFields = listOf(
            "sideSelection",
            "sideSelectionStatus",
            "selectionOwner",
            "sideSelectionOwner",
            "sideSelectedBy",
            "sideChoice"
        )
        return explicitFields.any { key ->
            val raw = game.opt(key)
            raw != null && raw != JSONObject.NULL && raw.toString().trim().let { it.isNotBlank() && !it.equals("null", true) }
        }
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 6_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("x-api-key", LolEsportsConfig.API_KEY)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "RiftLab/1.0 Android")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Riot EventDetails HTTP $code")
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
