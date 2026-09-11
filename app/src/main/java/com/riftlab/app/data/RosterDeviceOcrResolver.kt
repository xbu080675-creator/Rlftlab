package com.riftlab.app.data

import android.graphics.BitmapFactory
import com.riftlab.app.ai.RosterOcrResult
import com.riftlab.app.ai.RosterSystemAiResolver
import com.riftlab.app.ai.RosterVisionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

data class RosterDeviceOcrTrace(
    val announcementId: String,
    val account: String,
    val imageUrl: String,
    val engines: List<String>,
    val lineCount: Int,
    val roleCandidates: Map<String, List<String>>,
    val leftRoleCandidates: Map<String, List<String>> = emptyMap(),
    val rightRoleCandidates: Map<String, List<String>> = emptyMap(),
    val layoutMode: String = "TEXT_ONLY",
    val systemAiStatus: String = "SKIPPED",
    val systemAiLeftCandidates: Map<String, List<String>> = emptyMap(),
    val systemAiRightCandidates: Map<String, List<String>> = emptyMap(),
    val systemAiPreview: String = "",
    val textPreview: String,
    val error: String = "",
    val cached: Boolean = false
)

/**
 * Runs device-side roster recovery only for official announcements that the
 * server could not normalize.
 *
 * Lane order is deliberate: bundled OCR first on every supported Android
 * device; AICore/Gemini Nano only when OCR is incomplete and only when the
 * system reports the feature already AVAILABLE. System AI never downloads a
 * model here and never promotes itself to confirmed evidence.
 *
 * StartingRosterCenter polls every minute, so image/OCR/system-AI results are
 * cached by image URL + league hint. Successful work is reused for six hours;
 * failures retry after ten minutes.
 */
internal class RosterDeviceOcrResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
) {
    private data class CachedTrace(val storedAtMs: Long, val trace: RosterDeviceOcrTrace)

    private val traceCache = object : LinkedHashMap<String, CachedTrace>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedTrace>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    suspend fun inspect(
        target: ScheduledEsportsMatch,
        announcements: List<StartingRosterAnnouncement>,
        maxAnnouncements: Int = 2,
        maxImagesPerAnnouncement: Int = 2
    ): List<RosterDeviceOcrTrace> = withContext(Dispatchers.IO) {
        val leagueHint = listOf(target.league, target.leagueSlug, target.leagueId)
            .joinToString(" ")
        val teamHints = target.teams.take(2).flatMap { team ->
            listOf(team.code, team.name).filter(String::isNotBlank)
        }.distinct()
        val traces = mutableListOf<RosterDeviceOcrTrace>()

        announcements.asSequence()
            .filter { !it.parsed && it.imageUrls.isNotEmpty() }
            .take(maxAnnouncements)
            .forEach { announcement ->
                announcement.imageUrls.take(maxImagesPerAnnouncement).forEach { imageUrl ->
                    val cacheKey = "${leagueHint.uppercase()}|$imageUrl"
                    val cached = cachedTrace(cacheKey)
                    if (cached != null) {
                        traces += cached.copy(
                            announcementId = announcement.id,
                            account = announcement.account,
                            cached = true
                        )
                        return@forEach
                    }

                    val trace = runCatching {
                        val request = Request.Builder()
                            .url(imageUrl)
                            .header("User-Agent", "RiftLab/1.0 Android")
                            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                            .apply {
                                if (announcement.sourceUrl.startsWith("http")) {
                                    header("Referer", announcement.sourceUrl)
                                }
                            }
                            .build()
                        val bytes = client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) error("HTTP ${response.code}")
                            response.body?.bytes() ?: error("empty image body")
                        }
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: error("bitmap decode failed")
                        try {
                            val ocr = RosterVisionPipeline.recognizeWithBundledOcr(bitmap, leagueHint)
                            val flat = extractRoleCandidates(ocr.text)
                            val spatial = extractSpatialRoleCandidates(ocr)
                            val merged = mergeCandidates(flat, spatial.left, spatial.right)
                            val bundledStrong = isComplete(spatial.left) ||
                                isComplete(spatial.right) ||
                                isComplete(merged)

                            val systemAi = if (!bundledStrong) {
                                RosterSystemAiResolver.analyze(bitmap, leagueHint, teamHints)
                            } else {
                                null
                            }
                            val systemParsed = systemAi?.text
                                ?.takeIf { systemAi.status == "AVAILABLE" }
                                ?.let(::parseSystemAiCandidates)
                                ?: SystemAiCandidates()

                            RosterDeviceOcrTrace(
                                announcementId = announcement.id,
                                account = announcement.account,
                                imageUrl = imageUrl,
                                engines = ocr.engines,
                                lineCount = ocr.lineCount,
                                roleCandidates = merged,
                                leftRoleCandidates = spatial.left,
                                rightRoleCandidates = spatial.right,
                                layoutMode = spatial.mode,
                                systemAiStatus = systemAi?.status ?: "SKIPPED_BUNDLED_COMPLETE",
                                systemAiLeftCandidates = systemParsed.left,
                                systemAiRightCandidates = systemParsed.right,
                                systemAiPreview = systemAi?.text.orEmpty().replace("\n", " ").take(500),
                                textPreview = ocr.text.replace("\n", " | ").take(520)
                            )
                        } finally {
                            bitmap.recycle()
                        }
                    }.getOrElse { error ->
                        RosterDeviceOcrTrace(
                            announcementId = announcement.id,
                            account = announcement.account,
                            imageUrl = imageUrl,
                            engines = emptyList(),
                            lineCount = 0,
                            roleCandidates = emptyMap(),
                            textPreview = "",
                            error = "${error::class.java.simpleName}:${error.message.orEmpty().take(100)}"
                        )
                    }
                    storeTrace(cacheKey, trace)
                    traces += trace
                }
            }
        traces
    }

    @Synchronized
    private fun cachedTrace(key: String): RosterDeviceOcrTrace? {
        val cached = traceCache[key] ?: return null
        val ttl = if (cached.trace.error.isBlank()) SUCCESS_CACHE_MS else FAILURE_CACHE_MS
        if (System.currentTimeMillis() - cached.storedAtMs > ttl) {
            traceCache.remove(key)
            return null
        }
        return cached.trace
    }

    @Synchronized
    private fun storeTrace(key: String, trace: RosterDeviceOcrTrace) {
        traceCache[key] = CachedTrace(System.currentTimeMillis(), trace.copy(cached = false))
    }

    private data class SpatialCandidates(
        val left: Map<String, List<String>>,
        val right: Map<String, List<String>>,
        val mode: String
    )

    private data class SystemAiCandidates(
        val left: Map<String, List<String>> = emptyRoleMapStatic(),
        val right: Map<String, List<String>> = emptyRoleMapStatic()
    )

    private fun extractSpatialRoleCandidates(ocr: RosterOcrResult): SpatialCandidates {
        if (ocr.tokens.isEmpty() || ocr.imageWidth <= 0 || ocr.imageHeight <= 0) {
            return SpatialCandidates(emptyRoleMap(), emptyRoleMap(), "TEXT_ONLY")
        }
        val left = mutableRoleMap()
        val right = mutableRoleMap()
        val roleAnchors = ocr.tokens.mapNotNull { token -> roleFor(token.text)?.let { it to token } }
        if (roleAnchors.isEmpty()) {
            return SpatialCandidates(emptyRoleMap(), emptyRoleMap(), "GEOMETRY_NO_ROLE_ANCHOR")
        }

        val playerTokens = ocr.tokens.filter { token ->
            token.engine == "latin" && playerIdOrNull(token.text) != null && roleFor(token.text) == null
        }
        roleAnchors.forEach { (role, anchor) ->
            val anchorHeight = (anchor.bottom - anchor.top).coerceAtLeast(12)
            val yTolerance = maxOf(30f, anchorHeight * 2.8f)
            val nearby = playerTokens
                .filter { token -> kotlin.math.abs(token.centerY - anchor.centerY) <= yTolerance }
                .sortedBy { kotlin.math.abs(it.centerY - anchor.centerY) }
                .take(10)
            nearby.forEach { token ->
                val player = playerIdOrNull(token.text) ?: return@forEach
                when {
                    token.normalizedCenterX < 0.47f -> left.getValue(role).add(player)
                    token.normalizedCenterX > 0.53f -> right.getValue(role).add(player)
                }
            }
        }

        val normalizedLeft = normalizeRoleMap(left)
        val normalizedRight = normalizeRoleMap(right)
        val leftCount = normalizedLeft.count { it.value.isNotEmpty() }
        val rightCount = normalizedRight.count { it.value.isNotEmpty() }
        val mode = when {
            leftCount >= 3 && rightCount >= 3 -> "TWO_COLUMN"
            leftCount >= 3 -> "LEFT_COLUMN"
            rightCount >= 3 -> "RIGHT_COLUMN"
            else -> "GEOMETRY_PARTIAL"
        }
        return SpatialCandidates(normalizedLeft, normalizedRight, mode)
    }

    private fun parseSystemAiCandidates(raw: String): SystemAiCandidates {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val root = runCatching { JSONObject(cleaned) }.getOrNull() ?: return SystemAiCandidates()
        fun side(name: String): Map<String, List<String>> {
            val obj = root.optJSONObject(name) ?: return emptyRoleMap()
            return ROLES.associateWith { role ->
                val value = playerIdOrNull(obj.optString(role))
                if (value == null) emptyList() else listOf(value)
            }
        }
        return SystemAiCandidates(left = side("left"), right = side("right"))
    }

    private fun extractRoleCandidates(text: String): Map<String, List<String>> {
        val result = mutableRoleMap()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEach
            val role = roleFor(line) ?: return@forEach
            Regex("[A-Za-z][A-Za-z0-9._-]{1,23}")
                .findAll(line)
                .mapNotNull { playerIdOrNull(it.value) }
                .filterNot { roleFor(it) != null }
                .forEach { result.getValue(role).add(it) }
        }
        return normalizeRoleMap(result)
    }

    private fun isComplete(map: Map<String, List<String>>): Boolean =
        ROLES.all { role -> map[role]?.size == 1 } &&
            ROLES.mapNotNull { role -> map[role]?.singleOrNull()?.lowercase() }.distinct().size == ROLES.size

    private fun mergeCandidates(vararg maps: Map<String, List<String>>): Map<String, List<String>> =
        ROLES.associateWith { role ->
            maps.flatMap { it[role].orEmpty() }
                .distinctBy { it.lowercase() }
                .take(6)
        }

    private fun mutableRoleMap(): LinkedHashMap<String, MutableList<String>> = linkedMapOf(
        "TOP" to mutableListOf(),
        "JUG" to mutableListOf(),
        "MID" to mutableListOf(),
        "BOT" to mutableListOf(),
        "SUP" to mutableListOf()
    )

    private fun emptyRoleMap(): Map<String, List<String>> = emptyRoleMapStatic()

    private fun normalizeRoleMap(map: Map<String, List<String>>): Map<String, List<String>> =
        ROLES.associateWith { role ->
            map[role].orEmpty().distinctBy { it.lowercase() }.take(4)
        }

    private fun playerIdOrNull(raw: String): String? {
        val value = raw.trim().trim('(', ')', '[', ']', '{', '}', ':', ';', ',', '.')
        if (!value.matches(Regex("[A-Za-z][A-Za-z0-9._-]{1,23}"))) return null
        val upper = value.uppercase()
        if (upper in NOISE_TOKENS) return null
        if (value.length < 2) return null
        return value
    }

    private fun roleFor(value: String): String? {
        val normalized = value.uppercase()
        return when {
            ROLE_ALIASES.getValue("TOP").any { normalized.contains(it) } -> "TOP"
            ROLE_ALIASES.getValue("JUG").any { normalized.contains(it) } -> "JUG"
            ROLE_ALIASES.getValue("MID").any { normalized.contains(it) } -> "MID"
            ROLE_ALIASES.getValue("BOT").any { normalized.contains(it) } -> "BOT"
            ROLE_ALIASES.getValue("SUP").any { normalized.contains(it) } -> "SUP"
            else -> null
        }
    }

    companion object {
        private const val MAX_CACHE_ENTRIES = 64
        private const val SUCCESS_CACHE_MS = 6 * 60 * 60_000L
        private const val FAILURE_CACHE_MS = 10 * 60_000L
        private val ROLES = listOf("TOP", "JUG", "MID", "BOT", "SUP")
        private val ROLE_ALIASES = mapOf(
            "TOP" to listOf("TOP", "上单", "탑", "トップ"),
            "JUG" to listOf("JUG", "JGL", "JUNGLE", "打野", "정글", "ジャングル"),
            "MID" to listOf("MID", "中单", "미드", "ミッド"),
            "BOT" to listOf("BOT", "ADC", "BOTTOM", "下路", "원딜", "ボット"),
            "SUP" to listOf("SUP", "SUPPORT", "辅助", "서폿", "サポート")
        )
        private val NOISE_TOKENS = setOf(
            "TOP", "JUG", "JGL", "JUNGLE", "MID", "BOT", "ADC", "BOTTOM", "SUP", "SUPPORT",
            "LPL", "LCK", "LEC", "LCS", "LCP", "LOL", "ROSTER", "STARTING", "LINEUP",
            "ESPORTS", "GAMING", "GAME", "MATCH", "VS", "BO3", "BO5", "NULL"
        )

        private fun emptyRoleMapStatic(): Map<String, List<String>> =
            ROLES.associateWith { emptyList() }
    }
}
