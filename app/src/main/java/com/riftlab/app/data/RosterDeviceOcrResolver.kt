package com.riftlab.app.data

import android.graphics.BitmapFactory
import com.riftlab.app.ai.RosterOcrResult
import com.riftlab.app.ai.RosterOcrToken
import com.riftlab.app.ai.RosterVisionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
    val textPreview: String,
    val error: String = ""
)

/**
 * Runs the universal on-device OCR lane only for official announcements that
 * the server could not normalize. Results are diagnostic/candidate data first;
 * they are never promoted to confirmed starters unless a later validator can
 * prove all five roles against an official source.
 */
internal class RosterDeviceOcrResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
) {
    suspend fun inspect(
        target: ScheduledEsportsMatch,
        announcements: List<StartingRosterAnnouncement>,
        maxAnnouncements: Int = 2,
        maxImagesPerAnnouncement: Int = 2
    ): List<RosterDeviceOcrTrace> = withContext(Dispatchers.IO) {
        val leagueHint = listOf(target.league, target.leagueSlug, target.leagueId)
            .joinToString(" ")
        val traces = mutableListOf<RosterDeviceOcrTrace>()

        announcements.asSequence()
            .filter { !it.parsed && it.imageUrls.isNotEmpty() }
            .take(maxAnnouncements)
            .forEach { announcement ->
                announcement.imageUrls.take(maxImagesPerAnnouncement).forEach { imageUrl ->
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
                        val ocr = try {
                            RosterVisionPipeline.recognizeWithBundledOcr(bitmap, leagueHint)
                        } finally {
                            bitmap.recycle()
                        }
                        val flat = extractRoleCandidates(ocr.text)
                        val spatial = extractSpatialRoleCandidates(ocr)
                        RosterDeviceOcrTrace(
                            announcementId = announcement.id,
                            account = announcement.account,
                            imageUrl = imageUrl,
                            engines = ocr.engines,
                            lineCount = ocr.lineCount,
                            roleCandidates = mergeCandidates(flat, spatial.left, spatial.right),
                            leftRoleCandidates = spatial.left,
                            rightRoleCandidates = spatial.right,
                            layoutMode = spatial.mode,
                            textPreview = ocr.text.replace("\n", " | ").take(520)
                        )
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
                    traces += trace
                }
            }
        traces
    }

    private data class SpatialCandidates(
        val left: Map<String, List<String>>,
        val right: Map<String, List<String>>,
        val mode: String
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

        // Latin OCR is the primary player-ID channel. Other scripts remain useful
        // for role labels but must not create fake Latin-looking player names.
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
                    // A centered token is not safe to assign to either team.
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

    private fun emptyRoleMap(): Map<String, List<String>> = ROLES.associateWith { emptyList() }

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
            "ESPORTS", "GAMING", "GAME", "MATCH", "VS", "BO3", "BO5"
        )
    }
}
