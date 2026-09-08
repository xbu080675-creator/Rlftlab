package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class OfficialDraftResult(
    val drafts: List<DraftPickRecord> = emptyList(),
    val status: String = "BP 数据未同步"
)

/**
 * BP resolver.
 *
 * Priority:
 * 1) TJStats comm-match-app /realtime/singleBp, when the endpoint is available for the bMatchId;
 * 2) terminal TJStats player finals, which can safely restore the five final champion picks.
 *
 * The fallback deliberately leaves bans empty. We never invent a ban sequence from the final comp.
 */
internal class LplOfficialDraftProvider {
    companion object {
        private const val COMM_BASE = "https://open.tjstats.com/comm-match-app/open/v1"
        private const val AUTH = "7935be4c41d8760a28c05581a7b1f570"
    }

    suspend fun fetch(bmid: String, series: CompletedSeriesSnapshot?): OfficialDraftResult = withContext(Dispatchers.IO) {
        if (series == null || series.games.isEmpty()) {
            return@withContext OfficialDraftResult(status = "BP · 等待终局小局数据")
        }

        val fallback = series.games.sortedBy { it.game }.map { game ->
            DraftPickRecord(
                game = game.game,
                bluePicks = game.bluePlayers.mapNotNull { it.championId.takeIf(String::isNotBlank) }.distinct(),
                redPicks = game.redPlayers.mapNotNull { it.championId.takeIf(String::isNotBlank) }.distinct(),
                source = "TJStats FINAL · 最终阵容 Picks（Ban 等待 singleBp）"
            )
        }

        if (bmid.isBlank()) {
            return@withContext OfficialDraftResult(
                drafts = fallback.filter { it.bluePicks.isNotEmpty() || it.redPicks.isNotEmpty() },
                status = "BP · 已恢复最终 Picks；缺少 bMatchId，Ban 暂不可用"
            )
        }

        val official = mutableMapOf<Int, DraftPickRecord>()
        for (game in series.games.sortedBy { it.game }) {
            fetchOfficialGame(bmid, game.game)?.let { official[game.game] = it }
        }

        val merged = fallback.map { base ->
            val live = official[base.game]
            if (live == null) base else live.copy(
                bluePicks = live.bluePicks.ifEmpty { base.bluePicks },
                redPicks = live.redPicks.ifEmpty { base.redPicks },
                source = if (live.blueBans.isNotEmpty() || live.redBans.isNotEmpty()) {
                    "TJStats singleBp · 官方 BP"
                } else {
                    "TJStats singleBp + FINAL · Picks 已恢复，Ban 未返回"
                }
            )
        }.filter { it.blueBans.isNotEmpty() || it.redBans.isNotEmpty() || it.bluePicks.isNotEmpty() || it.redPicks.isNotEmpty() }

        val fullGames = merged.count { it.blueBans.isNotEmpty() || it.redBans.isNotEmpty() }
        OfficialDraftResult(
            drafts = merged,
            status = if (fullGames > 0) {
                "BP · singleBp 已连接 $fullGames/${merged.size} 局；其余使用终局 Picks"
            } else {
                "BP · 已恢复 ${merged.size} 局最终 Picks；singleBp Ban 当前未返回"
            }
        )
    }

    private fun fetchOfficialGame(bmid: String, game: Int): DraftPickRecord? {
        val queries = listOf(
            "bMatchId=${enc(bmid)}&bo=$game",
            "matchId=${enc(bmid)}&bo=$game",
            "bMatchId=${enc(bmid)}&game=$game"
        )
        for (query in queries) {
            val root = runCatching { getJson("$COMM_BASE/realtime/singleBp?$query") }.getOrNull() ?: continue
            if (root.has("success") && !root.optBoolean("success", false)) continue
            val acc = DraftAccumulator()
            scan(root.opt("data") ?: root, acc, sideHint = null, actionHint = null, depth = 0)
            if (acc.hasData()) {
                return DraftPickRecord(
                    game = game,
                    blueBans = acc.blueBans.distinct(),
                    redBans = acc.redBans.distinct(),
                    bluePicks = acc.bluePicks.distinct(),
                    redPicks = acc.redPicks.distinct(),
                    source = "TJStats comm-match-app /realtime/singleBp"
                )
            }
        }
        return null
    }

    private data class DraftAccumulator(
        val blueBans: MutableList<String> = mutableListOf(),
        val redBans: MutableList<String> = mutableListOf(),
        val bluePicks: MutableList<String> = mutableListOf(),
        val redPicks: MutableList<String> = mutableListOf()
    ) {
        fun hasData(): Boolean = blueBans.isNotEmpty() || redBans.isNotEmpty() || bluePicks.isNotEmpty() || redPicks.isNotEmpty()

        fun add(side: String?, action: String?, hero: String) {
            if (hero.isBlank()) return
            when (side to action) {
                "blue" to "ban" -> blueBans += hero
                "red" to "ban" -> redBans += hero
                "blue" to "pick" -> bluePicks += hero
                "red" to "pick" -> redPicks += hero
            }
        }
    }

    private fun scan(
        node: Any?,
        acc: DraftAccumulator,
        sideHint: String?,
        actionHint: String?,
        depth: Int
    ) {
        if (node == null || node == JSONObject.NULL || depth > 10) return
        when (node) {
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    scan(node.opt(i), acc, sideHint, actionHint, depth + 1)
                }
            }
            is JSONObject -> {
                val explicitSide = sideFrom(node) ?: sideHint
                val explicitAction = actionFrom(node) ?: actionHint
                val hero = heroFrom(node)
                if (hero.isNotBlank() && explicitSide != null && explicitAction != null) {
                    acc.add(explicitSide, explicitAction, hero)
                }

                val keys = node.keys().asSequence().toList()
                for (key in keys) {
                    val lower = key.lowercase()
                    val nextSide = when {
                        lower.contains("blue") -> "blue"
                        lower.contains("red") -> "red"
                        else -> explicitSide
                    }
                    val nextAction = when {
                        lower.contains("ban") -> "ban"
                        lower.contains("pick") || lower.contains("select") -> "pick"
                        else -> explicitAction
                    }
                    val value = node.opt(key)
                    if (nextSide != null && nextAction != null && value !is JSONObject && value !is JSONArray) {
                        scalarHero(value)?.let { acc.add(nextSide, nextAction, it) }
                    } else {
                        scan(value, acc, nextSide, nextAction, depth + 1)
                    }
                }
            }
            else -> if (sideHint != null && actionHint != null) {
                scalarHero(node)?.let { acc.add(sideHint, actionHint, it) }
            }
        }
    }

    private fun sideFrom(obj: JSONObject): String? {
        val raw = stringAny(obj, "side", "teamSide", "camp", "color", "teamColor").lowercase()
        return when {
            raw.contains("blue") || raw == "1" -> "blue"
            raw.contains("red") || raw == "2" -> "red"
            else -> null
        }
    }

    private fun actionFrom(obj: JSONObject): String? {
        val boolBan = boolAny(obj, "isBan", "ban")
        if (boolBan == true) return "ban"
        val boolPick = boolAny(obj, "isPick", "pick")
        if (boolPick == true) return "pick"
        val raw = stringAny(obj, "bpType", "action", "actionType", "type", "operation", "pickBan", "banPick").lowercase()
        return when {
            raw.contains("ban") || raw == "1" -> "ban"
            raw.contains("pick") || raw.contains("select") || raw == "2" -> "pick"
            else -> null
        }
    }

    private fun heroFrom(obj: JSONObject): String = stringAny(
        obj,
        "heroId", "heroID", "championId", "championID", "champion", "hero"
    ).takeIf { it != "0" } ?: ""

    private fun scalarHero(raw: Any?): String? = when (raw) {
        is Number -> raw.toLong().takeIf { it > 0 }?.toString()
        is String -> raw.trim().takeIf { it.isNotBlank() && it != "0" && it.length <= 40 }
        else -> null
    }

    private fun boolAny(obj: JSONObject, vararg keys: String): Boolean? {
        for (key in keys) {
            if (!obj.has(key)) continue
            when (val value = obj.opt(key)) {
                is Boolean -> return value
                is Number -> return value.toInt() != 0
                is String -> when (value.lowercase()) {
                    "true", "1", "yes" -> return true
                    "false", "0", "no" -> return false
                }
            }
        }
        return null
    }

    private fun stringAny(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.opt(key)?.toString()?.trim().orEmpty()
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 6_000
            connection.readTimeout = 8_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", AUTH)
            connection.setRequestProperty("User-Agent", "RiftLab-Android/1.0")
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
