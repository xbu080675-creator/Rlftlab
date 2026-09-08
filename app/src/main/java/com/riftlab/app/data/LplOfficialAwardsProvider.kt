package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class OfficialAwardsResult(
    val seriesMvp: OfficialMvpRecord? = null,
    val gameMvps: List<OfficialMvpRecord> = emptyList(),
    val votes: List<OfficialVoteRecord> = emptyList(),
    val status: String = "MVP / 投票数据未同步"
)

/**
 * Official Tencent/TJStats award resolver.
 *
 * We distinguish two different concepts:
 * 1) official MVP/POG result for this match/game;
 * 2) the official post-game POG VOTES panel shown by LPL/TJStats.
 *
 * playerMvpVotesRank is deliberately NOT used here because that endpoint is a season/group MVP
 * points leaderboard, not the per-match POG voting panel.
 */
internal class LplOfficialAwardsProvider {
    companion object {
        private const val AUTH_BASE = "https://open.tjstats.com/match-auth-app/open/v1"
        private const val COMM_BASE = "https://open.tjstats.com/comm-match-app/open/v1"
        private const val AUTH = "7935be4c41d8760a28c05581a7b1f570"
    }

    suspend fun fetch(bmid: String): OfficialAwardsResult = withContext(Dispatchers.IO) {
        if (bmid.isBlank()) return@withContext OfficialAwardsResult(status = "MVP · 缺少 bMatchId")

        val payloads = mutableListOf<Pair<String, JSONObject>>()
        val attempts = listOf(
            "TJStats match-auth mvp" to listOf(
                "$AUTH_BASE/compound/mvp?matchId=${enc(bmid)}",
                "$AUTH_BASE/compound/mvp?bMatchId=${enc(bmid)}",
                "$AUTH_BASE/compound/mvp?bmid=${enc(bmid)}"
            ),
            "TJStats comm mvpDetail" to listOf(
                "$COMM_BASE/compound/mvpDetail?matchId=${enc(bmid)}",
                "$COMM_BASE/compound/mvpDetail?bMatchId=${enc(bmid)}",
                "$COMM_BASE/compound/mvpDetail?bmid=${enc(bmid)}"
            ),
            "TJStats comm postMatchData" to listOf(
                "$COMM_BASE/compound/postMatchData?matchId=${enc(bmid)}",
                "$COMM_BASE/compound/postMatchData?bMatchId=${enc(bmid)}",
                "$COMM_BASE/compound/postMatchData?bmid=${enc(bmid)}"
            )
        )

        for ((label, urls) in attempts) {
            for (url in urls) {
                val root = runCatching { getJson(url) }.getOrNull() ?: continue
                if (root.has("success") && !root.optBoolean("success", false)) continue
                val data = root.optJSONObject("data") ?: root
                if (data.length() == 0) continue
                payloads += label to data
                break
            }
        }

        if (payloads.isEmpty()) {
            return@withContext OfficialAwardsResult(
                status = "官方 MVP / POG 投票面板暂未返回 · bmid=$bmid"
            )
        }

        val mvps = mutableListOf<OfficialMvpRecord>()
        val votes = mutableListOf<OfficialVoteRecord>()
        for ((source, data) in payloads) {
            collectMvpRecords(data, source, mvps)
            collectVoteRecords(data, source, votes)
        }

        val dedupMvps = mvps
            .filter { it.playerName.isNotBlank() }
            .distinctBy { "${it.game ?: 0}|${key(it.playerName)}|${key(it.team)}" }
        val gameMvps = dedupMvps.filter { it.game != null && it.game > 0 }.sortedBy { it.game }
        val seriesMvp = dedupMvps.firstOrNull { it.game == null || it.game == 0 }
            ?: dedupMvps.takeIf { gameMvps.isEmpty() }?.firstOrNull()
        val dedupVotes = votes
            .filter { it.options.isNotEmpty() }
            .distinctBy { it.title + "|" + it.options.joinToString { option -> "${option.label}:${option.votes}" } }

        OfficialAwardsResult(
            seriesMvp = seriesMvp,
            gameMvps = gameMvps,
            votes = dedupVotes,
            status = when {
                dedupMvps.isNotEmpty() && dedupVotes.isNotEmpty() -> "官方 MVP + POG VOTES 已连接 · bmid=$bmid"
                dedupMvps.isNotEmpty() -> "官方 MVP 已连接；POG VOTES 面板当前未返回 · bmid=$bmid"
                dedupVotes.isNotEmpty() -> "官方 POG VOTES 已连接；MVP 结果当前未单独标记 · bmid=$bmid"
                else -> "TJStats 有返回，但未识别到官方 MVP / POG VOTES 字段 · bmid=$bmid"
            }
        )
    }

    private fun collectMvpRecords(root: Any?, source: String, out: MutableList<OfficialMvpRecord>, depth: Int = 0) {
        if (root == null || root == JSONObject.NULL || depth > 8) return
        when (root) {
            is JSONArray -> for (i in 0 until root.length()) collectMvpRecords(root.opt(i), source, out, depth + 1)
            is JSONObject -> {
                val keys = root.keys().asSequence().toList()
                val keyText = keys.joinToString("|").lowercase()
                val looksMvp = keyText.contains("mvp") || keyText.contains("pog") ||
                    keys.any { k ->
                        val value = root.optString(k)
                        value.contains("mvp", ignoreCase = true) || value.contains("pog", ignoreCase = true)
                    }
                if (looksMvp) {
                    val player = stringAny(
                        root,
                        "playerName", "summonerName", "mvpPlayerName", "pogPlayerName", "mvpName",
                        "playerNickName", "nickName", "name"
                    )
                    val playerId = stringAny(root, "playerId", "mvpPlayerId", "pogPlayerId", "memberId")
                    if (player.isNotBlank() && (playerId.isNotBlank() || keyText.contains("player") || looksMvp)) {
                        val game = intAny(
                            root,
                            "game", "gameNo", "gameNum", "bo", "matchNum", "gameIndex", "gameOrder"
                        ).takeIf { it > 0 }
                        out += OfficialMvpRecord(
                            game = game,
                            playerName = player,
                            team = stringAny(root, "teamName", "team", "teamCode", "clubName"),
                            role = stringAny(root, "role", "position", "playerLocation", "place"),
                            source = source
                        )
                    }
                }
                for (k in keys) collectMvpRecords(root.opt(k), source, out, depth + 1)
            }
        }
    }

    private fun collectVoteRecords(
        root: Any?,
        source: String,
        out: MutableList<OfficialVoteRecord>,
        depth: Int = 0,
        gameHint: Int? = null
    ) {
        if (root == null || root == JSONObject.NULL || depth > 8) return
        when (root) {
            is JSONObject -> {
                val localGame = intAny(
                    root,
                    "game", "gameNo", "gameNum", "bo", "matchNum", "gameIndex", "gameOrder"
                ).takeIf { it > 0 } ?: gameHint
                val keys = root.keys().asSequence().toList()
                for (k in keys) collectVoteRecords(root.opt(k), source, out, depth + 1, localGame)
            }

            is JSONArray -> {
                val aggregated = mutableListOf<VoteOptionRecord>()
                val ballotCounts = linkedMapOf<String, Long>()
                var hasVoteSemantics = false

                for (i in 0 until root.length()) {
                    val obj = root.optJSONObject(i) ?: continue
                    val keys = obj.keys().asSequence().toList()
                    val keyText = keys.joinToString("|").lowercase()
                    val voteish = keyText.contains("vote") || keyText.contains("mvp") || keyText.contains("pog")
                    hasVoteSemantics = hasVoteSemantics || voteish

                    val label = stringAny(
                        obj,
                        "mvpPlayerName", "pogPlayerName", "votePlayerName", "candidateName",
                        "playerName", "summonerName", "playerNickName", "nickName", "optionName", "label", "name"
                    )
                    val votes = longAny(
                        obj,
                        "votes", "vote", "voteCount", "voteNum", "votesNum", "mvpVotes",
                        "mvpVoteCount", "mvpVoteNum", "pogVotes", "pogVoteCount", "pollVotes", "count"
                    )
                    val percent = doubleAny(
                        obj,
                        "percent", "percentage", "votePercent", "mvpVotePercent", "pogVotePercent", "rate"
                    ).takeIf { it > 0.0 }

                    if (label.isNotBlank() && (votes > 0L || percent != null)) {
                        aggregated += VoteOptionRecord(label = label, votes = votes, percent = percent)
                        continue
                    }

                    // Some official panels return one row per judge/commentator rather than an
                    // already aggregated 6/8 result. In that shape, aggregate the selected player.
                    val voter = stringAny(
                        obj,
                        "voterName", "judgeName", "commentatorName", "casterName", "observerName",
                        "expertName", "staffName", "voteUserName", "userName"
                    )
                    val picked = stringAny(
                        obj,
                        "votePlayerName", "mvpPlayerName", "pogPlayerName", "candidateName", "playerName", "summonerName"
                    )
                    if (voteish && picked.isNotBlank() && (voter.isNotBlank() || keys.size <= 12)) {
                        ballotCounts[picked] = (ballotCounts[picked] ?: 0L) + 1L
                    }
                }

                val game = root.optJSONObject(0)?.let {
                    intAny(it, "game", "gameNo", "gameNum", "bo", "matchNum", "gameIndex", "gameOrder")
                        .takeIf { value -> value > 0 }
                } ?: gameHint

                val options = when {
                    aggregated.isNotEmpty() -> aggregated
                        .groupBy { key(it.label) }
                        .values
                        .map { rows ->
                            val first = rows.first()
                            VoteOptionRecord(
                                label = first.label,
                                votes = rows.maxOf { it.votes },
                                percent = rows.mapNotNull { it.percent }.maxOrNull()
                            )
                        }
                        .sortedWith(compareByDescending<VoteOptionRecord> { it.votes }.thenBy { it.label })

                    hasVoteSemantics && ballotCounts.values.sum() >= 2L -> ballotCounts
                        .map { (label, count) -> VoteOptionRecord(label = label, votes = count) }
                        .sortedByDescending { it.votes }

                    else -> emptyList()
                }

                if (options.isNotEmpty()) {
                    val total = options.sumOf { it.votes }.takeIf { it > 0L }
                    out += OfficialVoteRecord(
                        title = game?.let { "G$it 官方 POG / MVP 投票" } ?: "官方 POG / MVP 投票",
                        options = options,
                        totalVotes = total,
                        source = source
                    )
                }

                for (i in 0 until root.length()) {
                    collectVoteRecords(root.opt(i), source, out, depth + 1, game)
                }
            }
        }
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", AUTH)
            connection.setRequestProperty("User-Agent", "RiftLab-Android/1.0")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun stringAny(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.opt(key)?.toString()?.trim().orEmpty()
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun intAny(obj: JSONObject, vararg keys: String): Int {
        for (key in keys) {
            when (val raw = obj.opt(key)) {
                is Number -> return raw.toInt()
                is String -> raw.toDoubleOrNull()?.toInt()?.let { return it }
            }
        }
        return 0
    }

    private fun longAny(obj: JSONObject, vararg keys: String): Long {
        for (key in keys) {
            when (val raw = obj.opt(key)) {
                is Number -> return raw.toLong()
                is String -> raw.toDoubleOrNull()?.toLong()?.let { return it }
            }
        }
        return 0L
    }

    private fun doubleAny(obj: JSONObject, vararg keys: String): Double {
        for (key in keys) {
            when (val raw = obj.opt(key)) {
                is Number -> return raw.toDouble()
                is String -> raw.removeSuffix("%").toDoubleOrNull()?.let { return it }
            }
        }
        return 0.0
    }

    private fun key(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
