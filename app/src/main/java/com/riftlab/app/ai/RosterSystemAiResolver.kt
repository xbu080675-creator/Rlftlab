package com.riftlab.app.ai

import android.graphics.Bitmap
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest

data class RosterSystemAiResult(
    val status: String,
    val text: String = "",
    val error: String = ""
)

/**
 * Optional system-AI lane. This is enhancement only: callers must always keep
 * remote official evidence and bundled OCR usable when Gemini Nano/AICore is
 * unavailable, still downloading, quota-limited, or returns malformed output.
 */
object RosterSystemAiResolver {
    suspend fun analyze(
        bitmap: Bitmap,
        leagueHint: String,
        teamHints: List<String>
    ): RosterSystemAiResult {
        val model = runCatching { Generation.getClient() }.getOrElse { error ->
            return RosterSystemAiResult(
                status = "CLIENT_UNAVAILABLE",
                error = "${error::class.java.simpleName}:${error.message.orEmpty().take(120)}"
            )
        }

        val status = runCatching { model.checkStatus() }.getOrElse { error ->
            return RosterSystemAiResult(
                status = "STATUS_ERROR",
                error = "${error::class.java.simpleName}:${error.message.orEmpty().take(120)}"
            )
        }
        if (status != FeatureStatus.AVAILABLE) {
            return RosterSystemAiResult(
                status = when (status) {
                    FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
                    FeatureStatus.DOWNLOADING -> "DOWNLOADING"
                    FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
                    else -> "STATUS_$status"
                }
            )
        }

        val prompt = buildString {
            append("You are reading an official League of Legends esports starting-lineup poster. ")
            append("League hint: ")
            append(leagueHint.take(80))
            append(". Expected teams if visible: ")
            append(teamHints.filter { it.isNotBlank() }.joinToString(" vs ").take(120))
            append(". Read only text visibly present in the image. Do not infer or guess missing names. ")
            append("Return JSON only, with this shape: ")
            append("{\"leftTeam\":string|null,\"rightTeam\":string|null,")
            append("\"left\":{\"TOP\":string|null,\"JUG\":string|null,\"MID\":string|null,\"BOT\":string|null,\"SUP\":string|null},")
            append("\"right\":{\"TOP\":string|null,\"JUG\":string|null,\"MID\":string|null,\"BOT\":string|null,\"SUP\":string|null}}. ")
            append("Use null whenever the poster is unreadable or a field is not explicitly visible.")
        }

        return runCatching {
            val request = generateContentRequest(ImagePart(bitmap), TextPart(prompt)) {
                temperature = 0.0f
                topK = 1
                candidateCount = 1
                maxOutputTokens = 600
            }
            val response = model.generateContent(request)
            val text = response.candidates.firstOrNull()?.text.orEmpty().trim()
            if (text.isBlank()) {
                RosterSystemAiResult(status = "EMPTY_RESPONSE")
            } else {
                RosterSystemAiResult(status = "AVAILABLE", text = text.take(4000))
            }
        }.getOrElse { error ->
            RosterSystemAiResult(
                status = "INFERENCE_ERROR",
                error = "${error::class.java.simpleName}:${error.message.orEmpty().take(160)}"
            )
        }
    }
}
