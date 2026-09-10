package com.riftlab.app.data

import com.riftlab.app.RiftLabApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Verified award mirror for global/overseas matches.
 *
 * Riot schedule/EventDetails and LiveStats do not consistently expose MVP/POG awards, so awards
 * live in a provenance-preserving mirror rather than being invented from score/KDA. A mirror row
 * may cite Riot/league official material or a clearly labelled secondary verification source.
 */
internal class GlobalVerifiedAwardsProvider {
    companion object {
        private const val CACHE_TTL_MS = 30L * 60L * 1000L
        private val ENDPOINTS = listOf(
            "https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main/data/global/match_awards.json",
            "https://cdn.jsdelivr.net/gh/xbu080675-creator/Rlftlab@main/data/global/match_awards.json"
        )

        private data class Cached(val fetchedAt: Long, val root: JSONObject)
        private val cache = AtomicReference<Cached?>(null)
    }

    suspend fun fetch(match: ScheduledEsportsMatch): OfficialAwardsResult = withContext(Dispatchers.IO) {
        val root = runCatching { directory() }.getOrElse {
            return@withContext OfficialAwardsResult(status = "全球 MVP 镜像暂不可达")
        }
        val rows = root.optJSONArray("awards")
            ?: return@withContext OfficialAwardsResult(status = "全球 MVP 镜像暂无数据")

        val eventId = match.eventId.trim()
        val matchId = match.matchId.trim()
        val teamTokens = match.teams.take(2)
            .flatMap { listOf(it.code, it.name, it.slug) }
            .map(::token)
            .filter { it.isNotBlank() }
            .toSet()
        val date = match.startTimeIso.take(10)

        var selected: JSONObject? = null
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val exactId = (eventId.isNotBlank() && row.optString("eventId") == eventId) ||
                (matchId.isNotBlank() && row.optString("matchId") == matchId)
            val rowTeams = row.optJSONArray("teams")
            val rowTeamTokens = buildSet {
                if (rowTeams != null) {
                    for (i in 0 until rowTeams.length()) token(rowTeams.optString(i)).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            val teamOverlap = rowTeamTokens.size >= 2 && rowTeamTokens.all { wanted -> teamTokens.any { it == wanted } }
            val dateMatches = row.optString("date").isBlank() || date.isBlank() || row.optString("date") == date
            if (exactId || (teamOverlap && dateMatches)) {
                selected = row
                break
            }
        }

        val row = selected ?: return@withContext OfficialAwardsResult(
            status = "全球 MVP / POG · 暂无已核实奖项记录"
        )
        val seriesNode = row.optJSONObject("seriesMvp")
        val seriesMvp = seriesNode?.let { node ->
            val player = node.optString("playerName").trim()
            if (player.isBlank()) null else OfficialMvpRecord(
                game = null,
                playerName = player,
                team = node.optString("team"),
                role = node.optString("role"),
                source = buildString {
                    append(node.optString("source").ifBlank { "RiftLab Verified Awards Mirror" })
                    node.optString("award").takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    append(" · 已核实外部奖项记录")
                }
            )
        }

        val gameMvps = buildList {
            val games = row.optJSONArray("gameMvps")
            if (games != null) {
                for (i in 0 until games.length()) {
                    val node = games.optJSONObject(i) ?: continue
                    val game = node.optInt("game", 0)
                    val player = node.optString("playerName").trim()
                    if (game <= 0 || player.isBlank()) continue
                    add(
                        OfficialMvpRecord(
                            game = game,
                            playerName = player,
                            team = node.optString("team"),
                            role = node.optString("role"),
                            source = buildString {
                                append(node.optString("source").ifBlank { "RiftLab Verified Awards Mirror" })
                                node.optString("award").takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                                append(" · 已核实外部奖项记录")
                            }
                        )
                    )
                }
            }
        }

        OfficialAwardsResult(
            seriesMvp = seriesMvp,
            gameMvps = gameMvps,
            status = when {
                seriesMvp != null && gameMvps.isNotEmpty() -> "全球已核实 MVP · 系列赛 + ${gameMvps.size} 小局"
                seriesMvp != null -> "全球已核实 MVP · ${seriesMvp.playerName}"
                gameMvps.isNotEmpty() -> "全球已核实 POG · ${gameMvps.size} 小局"
                else -> "全球 MVP / POG · 当前记录没有可展示奖项"
            }
        )
    }

    private fun directory(): JSONObject {
        val now = System.currentTimeMillis()
        cache.get()?.takeIf { now - it.fetchedAt < CACHE_TTL_MS }?.let { return it.root }
        var last: Throwable? = null
        for (endpoint in ENDPOINTS) {
            runCatching { getJson(endpoint) }
                .onSuccess { root ->
                    if (root.optInt("schemaVersion", 0) > 0 && root.optJSONArray("awards") != null) {
                        cache.set(Cached(now, root))
                        return root
                    }
                }
                .onFailure { last = it }
        }
        loadBundled("match_awards.json")?.let { root ->
            if (root.optInt("schemaVersion", 0) > 0 && root.optJSONArray("awards") != null) {
                cache.set(Cached(now, root))
                return root
            }
        }
        throw last ?: IllegalStateException("global awards mirror unavailable")
    }

    private fun loadBundled(name: String): JSONObject? = runCatching {
        RiftLabApplication.appContext.assets.open(name).bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "RiftLab/1.0 Android")
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun token(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
}
