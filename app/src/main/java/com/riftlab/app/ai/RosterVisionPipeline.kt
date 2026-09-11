package com.riftlab.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File

enum class RosterVisionLane {
    REMOTE_NORMALIZED,
    BUNDLED_OCR,
    SYSTEM_AICORE,
    LOCAL_VISION_MODEL
}

enum class RosterVisionAvailability {
    READY,
    CANDIDATE,
    NOT_INSTALLED,
    UNSUPPORTED
}

data class RosterVisionCapability(
    val lane: RosterVisionLane,
    val availability: RosterVisionAvailability,
    val detail: String
)

data class RosterOcrLine(
    val text: String,
    val engine: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val imageWidth: Int,
    val imageHeight: Int
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val normalizedCenterX: Float get() = if (imageWidth > 0) centerX / imageWidth else 0.5f
}

data class RosterOcrResult(
    val text: String,
    val engines: List<String>,
    val lineCount: Int,
    val lines: List<RosterOcrLine> = emptyList(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0
)

/**
 * Capability router for the four-lane roster pipeline.
 *
 * Remote normalized data remains the universal baseline. Bundled ML Kit OCR is
 * available without Google Play Services. AICore and RiftLab's own visual model
 * are optional enhancement lanes and must never gate roster availability.
 *
 * Layout geometry is preserved because official league posters commonly put two
 * five-player lineups in left/right columns. Flattening OCR to plain text loses
 * the one signal needed to split those teams safely.
 */
object RosterVisionPipeline {
    fun capabilities(context: Context): List<RosterVisionCapability> = listOf(
        RosterVisionCapability(
            RosterVisionLane.REMOTE_NORMALIZED,
            RosterVisionAvailability.READY,
            "远端标准化数据 + 原始官宣证据"
        ),
        RosterVisionCapability(
            RosterVisionLane.BUNDLED_OCR,
            RosterVisionAvailability.READY,
            "ML Kit Latin/中文/日文/韩文模型随 App 打包"
        ),
        systemAiCapability(context),
        localVisionCapability(context)
    )

    suspend fun recognizeWithBundledOcr(bitmap: Bitmap, leagueHint: String): RosterOcrResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizers = buildRecognizers(leagueHint)
        val flatLines = linkedSetOf<String>()
        val layoutLines = mutableListOf<RosterOcrLine>()
        val engines = mutableListOf<String>()
        try {
            for ((label, recognizer) in recognizers) {
                val result = recognizer.process(image).await()
                engines += label
                result.textBlocks
                    .flatMap { it.lines }
                    .forEach { line ->
                        val text = line.text.trim()
                        if (text.isBlank()) return@forEach
                        flatLines += text
                        val box = line.boundingBox
                        if (box != null) {
                            layoutLines += RosterOcrLine(
                                text = text,
                                engine = label,
                                left = box.left.coerceAtLeast(0),
                                top = box.top.coerceAtLeast(0),
                                right = box.right.coerceAtMost(bitmap.width),
                                bottom = box.bottom.coerceAtMost(bitmap.height),
                                imageWidth = bitmap.width,
                                imageHeight = bitmap.height
                            )
                        }
                    }
            }
        } finally {
            recognizers.forEach { (_, recognizer) -> runCatching { recognizer.close() } }
        }
        val dedupedGeometry = layoutLines
            .distinctBy { line ->
                listOf(
                    line.engine,
                    line.text.lowercase(),
                    line.left / 8,
                    line.top / 8,
                    line.right / 8,
                    line.bottom / 8
                ).joinToString(":")
            }
            .sortedWith(compareBy<RosterOcrLine> { it.top }.thenBy { it.left })
        return RosterOcrResult(
            text = flatLines.joinToString("\n"),
            engines = engines,
            lineCount = flatLines.size,
            lines = dedupedGeometry,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height
        )
    }

    private fun buildRecognizers(leagueHint: String): List<Pair<String, TextRecognizer>> {
        val hint = leagueHint.uppercase()
        val out = mutableListOf<Pair<String, TextRecognizer>>()
        // Player IDs are overwhelmingly Latin even on CJK posters, so always run
        // a dedicated Latin pass instead of mixing four scripts in one recognizer.
        out += "latin" to TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        when {
            "LPL" in hint || "LCP" in hint || "PCS" in hint ->
                out += "chinese" to TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            "LCK" in hint ->
                out += "korean" to TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "LJL" in hint ->
                out += "japanese" to TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        }
        return out
    }

    private fun systemAiCapability(context: Context): RosterVisionCapability {
        if (Build.VERSION.SDK_INT < 26) {
            return RosterVisionCapability(
                RosterVisionLane.SYSTEM_AICORE,
                RosterVisionAvailability.UNSUPPORTED,
                "Android API < 26"
            )
        }
        val aicoreInstalled = runCatching {
            context.packageManager.getPackageInfo("com.google.android.aicore", 0)
        }.isSuccess
        val promptApiPresent = runCatching {
            Class.forName("com.google.mlkit.genai.prompt.Generation")
        }.isSuccess
        return if (aicoreInstalled && promptApiPresent) {
            RosterVisionCapability(
                RosterVisionLane.SYSTEM_AICORE,
                RosterVisionAvailability.CANDIDATE,
                "AICore/Prompt API 已发现；运行时仍需 checkStatus() 确认 Gemini Nano"
            )
        } else {
            RosterVisionCapability(
                RosterVisionLane.SYSTEM_AICORE,
                RosterVisionAvailability.UNSUPPORTED,
                "未发现可用 AICore"
            )
        }
    }

    private fun localVisionCapability(context: Context): RosterVisionCapability {
        val dir = File(context.filesDir, "local_vision_models")
        val model = dir.listFiles()?.firstOrNull { file ->
            file.isFile && file.extension.lowercase() in setOf("tflite", "litertlm", "onnx")
        }
        return if (model != null) {
            RosterVisionCapability(
                RosterVisionLane.LOCAL_VISION_MODEL,
                RosterVisionAvailability.CANDIDATE,
                "已发现 ${model.name}；需通过视觉运行时基准后启用"
            )
        } else {
            RosterVisionCapability(
                RosterVisionLane.LOCAL_VISION_MODEL,
                RosterVisionAvailability.NOT_INSTALLED,
                "未安装 RiftLab 本地视觉模型"
            )
        }
    }
}
