package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * Resolves the official Bilibili League of Legends match VOD for a schedule match.
 *
 * RiftLab stores only metadata (BVID/CID/page/chapter anchors). Video bytes remain hosted and
 * served by Bilibili through its own player surface; RiftLab never downloads or republishes VODs.
 */
data class BilibiliVodChapter(
    val title: String,
    val fromSeconds: Int,
    val toSeconds: Int,
    val teamName: String = ""
)

data class BilibiliVodPart(
    val game: Int,
    val page: Int,
    val cid: Long,
    val title: String,
    val durationSeconds: Int,
    val chapters: List<BilibiliVodChapter> = emptyList(),
    val gameStartOffsetSeconds: Int = 0
) {
    fun gameSecondFor(videoSecond: Int): Int = (videoSecond - gameStartOffsetSeconds).coerceAtLeast(0)
}

data class BilibiliMatchVod(
    val bvid: String,
    val title: String,
    val ownerName: String,
    val ownerMid: Long,
    val coverUrl: String,
    val publishedEpochSeconds: Long,
    val parts: List<BilibiliVodPart>
) {
    val sourceUrl: String get() = "https://www.bilibili.com/video/$bvid"

    fun playerUrl(part: BilibiliVodPart, startSecond: Int = 0): String = buildString {
        append("https://player.bilibili.com/player.html")
        append("?bvid=").append(enc(bvid))
        append("&cid=").append(part.cid)
        append("&page=").append(part.page)
        append("&high_quality=1&danmaku=0&autoplay=0")
        if (startSecond > 0) append("&t=").append(startSecond.coerceAtLeast(0))
    }

    companion object {
        private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}

data class BilibiliVodState(
    val matchKey: String = "",
    val loading: Boolean = false,
    val vod: BilibiliMatchVod? = null,
    val status: String = "B站官方录像 · 待解析",
    val errorMessage: String? = null,
    val updatedAtEpochMs: Long = 0L
)

object BilibiliVodRepository {
    private const val API_BASE = "https://api.bilibili.com"
    private const val OFFICIAL_OWNER = "哔哩哔哩英雄联盟赛事"
    private const val REQUEST_GAP_MS = 260L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = linkedMapOf<String, BilibiliVodState>()
    private var loadJob: Job? = null

    private val _state = MutableStateFlow(BilibiliVodState())
    val state: StateFlow<BilibiliVodState> = _state.asStateFlow()

    fun keyFor(match: ScheduledEsportsMatch): String =
        match.eventId.ifBlank { match.matchId }.ifBlank {
            listOf(
                match.teams.getOrNull(0)?.code.orEmpty(),
                match.teams.getOrNull(1)?.code.orEmpty(),
                match.startTimeIso
            ).joinToString("|")
        }

    fun open(match: ScheduledEsportsMatch, forceRefresh: Boolean = false) {
        val key = keyFor(match)
        val cached = cache[key]
        if (!forceRefresh && cached != null) {
            _state.value = cached
            return
        }

        loadJob?.cancel()
        loadJob = scope.launch {
            _state.value = BilibiliVodState(
                matchKey = key,
                loading = true,
                status = "B站官方录像 · 正在查找 ${teamsLabel(match)}…"
            )
            val result = runCatching { resolve(match) }
            val vod = result.getOrNull()
            val final = BilibiliVodState(
                matchKey = key,
                loading = false,
                vod = vod,
                status = when {
                    vod != null -> "B站官方录像 · 已匹配 ${vod.title} · ${vod.parts.size} 个小局页面"
                    result.isFailure -> "B站官方录像 · 解析失败"
                    else -> "B站官方录像 · 暂未找到官方完整录像"
                },
                errorMessage = result.exceptionOrNull()?.message,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            cache[key] = final
            _state.value = final
        }
    }

    private suspend fun resolve(match: ScheduledEsportsMatch): BilibiliMatchVod? = withContext(Dispatchers.IO) {
        val left = match.teams.getOrNull(0)?.let { it.code.ifBlank { it.name } }.orEmpty()
        val right = match.teams.getOrNull(1)?.let { it.code.ifBlank { it.name } }.orEmpty()
        if (left.isBlank() || right.isBlank()) return@withContext null

        val date = runCatching { Instant.parse(match.startTimeIso).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        val year = date?.year ?: runCatching { match.startTimeIso.take(4).toInt() }.getOrDefault(0)
        val monthDay = date?.let { "${it.monthValue}月${it.dayOfMonth}日" }.orEmpty()
        val leagueToken = when {
            match.league.contains("LPL", ignoreCase = true) -> "LPL"
            match.league.contains("MSI", ignoreCase = true) -> "MSI"
            match.league.contains("world", ignoreCase = true) || match.league.contains("全球", ignoreCase = true) -> "全球总决赛"
            else -> match.league.take(18)
        }

        val queries = buildList {
            if (year > 0 && leagueToken.isNotBlank() && monthDay.isNotBlank()) add("$year$leagueToken $monthDay $left vs $right")
            if (monthDay.isNotBlank()) add("$monthDay $left vs $right")
            add("$left vs $right")
        }.distinct()

        var best: SearchCandidate? = null
        for (query in queries) {
            val candidates = runCatching { searchVideos(query) }.getOrDefault(emptyList())
            candidates.forEach { candidate ->
                val score = candidateScore(candidate, match, left, right, monthDay, year)
                if (score > (best?.score ?: Int.MIN_VALUE)) best = candidate.copy(score = score)
            }
            if ((best?.score ?: 0) >= 230) break
            delay(REQUEST_GAP_MS)
        }

        val candidate = best?.takeIf { it.score >= 150 } ?: return@withContext null
        delay(REQUEST_GAP_MS)
        val view = getJson("$API_BASE/x/web-interface/view?bvid=${enc(candidate.bvid)}")
        val data = view.optJSONObject("data") ?: return@withContext null
        val owner = data.optJSONObject("owner") ?: JSONObject()
        val ownerName = owner.optString("name")
        if (!isOfficialOwner(ownerName)) return@withContext null

        val rawPages = data.optJSONArray("pages")
        val parsedPages = buildList {
            if (rawPages != null) {
                for (i in 0 until rawPages.length()) {
                    val page = rawPages.optJSONObject(i) ?: continue
                    val pageNumber = page.optInt("page", i + 1).coerceAtLeast(1)
                    val partTitle = page.optString("part").ifBlank { "P$pageNumber" }
                    val game = parseGameNumber(partTitle)
                    add(
                        PageCandidate(
                            game = game,
                            page = pageNumber,
                            cid = page.optLong("cid", 0L),
                            title = partTitle,
                            durationSeconds = page.optInt("duration", 0).coerceAtLeast(0)
                        )
                    )
                }
            }
        }

        val gamePages = normalizeGamePages(parsedPages, match.bestOf)
        val parts = mutableListOf<BilibiliVodPart>()
        for (page in gamePages) {
            if (page.cid <= 0L) continue
            delay(REQUEST_GAP_MS)
            val chapters = runCatching { fetchChapters(candidate.bvid, page.cid) }.getOrDefault(emptyList())
            val startOffset = chapters.firstOrNull { isGameStartChapter(it.title) }?.fromSeconds ?: 0
            parts += BilibiliVodPart(
                game = page.game,
                page = page.page,
                cid = page.cid,
                title = page.title,
                durationSeconds = page.durationSeconds,
                chapters = chapters,
                gameStartOffsetSeconds = startOffset
            )
        }

        if (parts.isEmpty()) return@withContext null
        BilibiliMatchVod(
            bvid = candidate.bvid,
            title = data.optString("title").ifBlank { candidate.title },
            ownerName = ownerName,
            ownerMid = owner.optLong("mid", candidate.mid),
            coverUrl = data.optString("pic"),
            publishedEpochSeconds = data.optLong("pubdate", candidate.pubdate),
            parts = parts.sortedBy { it.game }
        )
    }

    private data class SearchCandidate(
        val bvid: String,
        val title: String,
        val author: String,
        val mid: Long,
        val pubdate: Long,
        val score: Int = 0
    )

    private data class PageCandidate(
        val game: Int,
        val page: Int,
        val cid: Long,
        val title: String,
        val durationSeconds: Int
    )

    private fun searchVideos(query: String): List<SearchCandidate> {
        val root = getJson(
            "$API_BASE/x/web-interface/search/type" +
                "?search_type=video&order=pubdate&page=1&keyword=${enc(query)}"
        )
        val code = root.optInt("code", -1)
        if (code != 0) throw IOException("Bilibili search code=$code · ${root.optString("message")}")
        val result = root.optJSONObject("data")?.optJSONArray("result") ?: return emptyList()
        return buildList {
            for (i in 0 until result.length()) {
                val item = result.optJSONObject(i) ?: continue
                val bvid = item.optString("bvid")
                if (bvid.isBlank()) continue
                add(
                    SearchCandidate(
                        bvid = bvid,
                        title = stripHtml(item.optString("title")),
                        author = stripHtml(item.optString("author")),
                        mid = item.optLong("mid", 0L),
                        pubdate = item.optLong("pubdate", 0L)
                    )
                )
            }
        }
    }

    private fun candidateScore(
        candidate: SearchCandidate,
        match: ScheduledEsportsMatch,
        left: String,
        right: String,
        monthDay: String,
        year: Int
    ): Int {
        if (!isOfficialOwner(candidate.author)) return Int.MIN_VALUE / 4
        val title = candidate.title
        val titleToken = token(title)
        val leftToken = token(left)
        val rightToken = token(right)
        if (leftToken.isBlank() || rightToken.isBlank()) return 0
        if (!titleToken.contains(leftToken) || !titleToken.contains(rightToken)) return 0

        var score = 120
        score += 45
        if (monthDay.isNotBlank() && title.contains(monthDay)) score += 45
        if (year > 0 && title.contains(year.toString())) score += 15
        if (match.league.isNotBlank() && title.contains(match.league, ignoreCase = true)) score += 15
        if (title.contains("速看") || title.contains("TOP5", ignoreCase = true) || title.contains("集锦")) score -= 80
        if (title.contains("vs", ignoreCase = true)) score += 10

        val targetEpoch = runCatching { Instant.parse(match.startTimeIso).epochSecond }.getOrNull()
        if (targetEpoch != null && candidate.pubdate > 0L) {
            val diffHours = abs(candidate.pubdate - targetEpoch) / 3600L
            when {
                diffHours <= 24 -> score += 35
                diffHours <= 48 -> score += 20
                diffHours <= 96 -> score += 5
                else -> score -= 25
            }
        }
        return score
    }

    private fun normalizeGamePages(pages: List<PageCandidate>, bestOf: Int): List<PageCandidate> {
        val explicit = pages.filter { it.game > 0 }.distinctBy { it.game }.sortedBy { it.game }
        if (explicit.isNotEmpty()) return explicit

        val likelyGames = pages.filter { page ->
            val t = page.title
            t.contains("局") && !t.contains("赛前") && !t.contains("赛后") && !t.contains("采访") && !t.contains("评论")
        }
        val source = if (likelyGames.isNotEmpty()) likelyGames else pages
        val maxGames = bestOf.takeIf { it in 1..7 } ?: source.size
        return source.take(maxGames).mapIndexed { index, page -> page.copy(game = index + 1) }
    }

    private fun fetchChapters(bvid: String, cid: Long): List<BilibiliVodChapter> {
        val root = getJson("$API_BASE/x/player/v2?bvid=${enc(bvid)}&cid=$cid")
        val array = root.optJSONObject("data")?.optJSONArray("view_points") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val title = item.optString("content").trim()
                if (title.isBlank()) continue
                val from = item.optDouble("from", 0.0).toInt().coerceAtLeast(0)
                val to = item.optDouble("to", from.toDouble()).toInt().coerceAtLeast(from)
                add(
                    BilibiliVodChapter(
                        title = title,
                        fromSeconds = from,
                        toSeconds = to,
                        teamName = item.optString("team_name")
                    )
                )
            }
        }.distinctBy { "${it.fromSeconds}|${it.title}" }.sortedBy { it.fromSeconds }
    }

    private fun parseGameNumber(title: String): Int {
        Regex("(?:GAME|G)\\s*([1-7])", RegexOption.IGNORE_CASE).find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val chinese = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7)
        Regex("第([一二三四五六七1-7])局").find(title)?.groupValues?.getOrNull(1)?.firstOrNull()?.let { raw ->
            return raw.digitToIntOrNull() ?: chinese[raw] ?: 0
        }
        return 0
    }

    private fun isGameStartChapter(title: String): Boolean {
        val compact = title.replace(" ", "").uppercase()
        return compact == "开始" || compact == "GAMESTART" || compact.contains("比赛开始") || compact.contains("游戏开始")
    }

    private fun isOfficialOwner(name: String): Boolean =
        name.trim() == OFFICIAL_OWNER || name.contains("哔哩哔哩英雄联盟赛事")

    private fun token(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

    private fun stripHtml(value: String): String = value
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()

    private fun teamsLabel(match: ScheduledEsportsMatch): String =
        match.teams.take(2).joinToString(" vs ") { it.code.ifBlank { it.name } }.ifBlank { "比赛" }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 12_000
        connection.readTimeout = 12_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
        )
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
        connection.setRequestProperty("Referer", "https://www.bilibili.com/")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Bilibili HTTP $code · ${text.take(140)}")
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
