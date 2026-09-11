package com.riftlab.app.data

import android.graphics.BitmapFactory
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
                        RosterDeviceOcrTrace(
                            announcementId = announcement.id,
                            account = announcement.account,
                            imageUrl = imageUrl,
                            engines = ocr.engines,
                            lineCount = ocr.lineCount,
                            roleCandidates = extractRoleCandidates(ocr.text),
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

    private fun extractRoleCandidates(text: String): Map<String, List<String>> {
        val result = linkedMapOf(
            "TOP" to mutableListOf<String>(),
            "JUG" to mutableListOf(),
            "MID" to mutableListOf(),
            "BOT" to mutableListOf(),
            "SUP" to mutableListOf()
        )
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEach
            val role = roleFor(line) ?: return@forEach
            val candidates = Regex("[A-Za-z][A-Za-z0-9._-]{1,23}")
                .findAll(line)
                .map { it.value }
                .filterNot { roleFor(it) != null }
                .filterNot { token -> token.uppercase() in NOISE_TOKENS }
                .distinctBy { it.lowercase() }
                .toList()
            result.getValue(role).addAll(candidates)
        }
        return result.mapValues { (_, values) -> values.distinctBy { it.lowercase() }.take(4) }
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
        private val ROLE_ALIASES = mapOf(
            "TOP" to listOf("TOP", "上单", "탑", "トップ"),
            "JUG" to listOf("JUG", "JGL", "JUNGLE", "打野", "정글", "ジャングル"),
            "MID" to listOf("MID", "中单", "미드", "ミッド"),
            "BOT" to listOf("BOT", "ADC", "BOTTOM", "下路", "원딜", "ボット"),
            "SUP" to listOf("SUP", "SUPPORT", "辅助", "서폿", "サポート")
        )
        private val NOISE_TOKENS = setOf(
            "TOP", "JUG", "JGL", "JUNGLE", "MID", "BOT", "ADC", "BOTTOM", "SUP", "SUPPORT",
            "LPL", "LCK", "LEC", "LCS", "LCP", "LOL", "ROSTER", "STARTING", "LINEUP"
        )
    }
}
