package com.riftlab.app.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Device capability tiers are about sustained real-time inference, not whether a model can merely
 * be opened once. RiftLab keeps Tier 0 usable with the deterministic rules engine only.
 */
enum class LocalAiTier { TIER_0_RULES, TIER_1_LITE, TIER_2_SLM, TIER_3_HIGH, TIER_4_EXPERIMENTAL }

enum class LocalAiScope {
    LIVE_HUD,
    DRAFT,
    PRE_MATCH,
    POST_MATCH,
    HOME_INSIGHT,
    SEARCH_QA,
    NOTIFICATION
}

data class DeviceAiProfile(
    val tier: LocalAiTier,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val availableStorageBytes: Long,
    val cpuCores: Int,
    val supportedAbis: List<String>,
    val thermalStatus: Int?,
    val reason: String
)

data class LocalModelDescriptor(
    val id: String,
    val displayName: String,
    val minTier: LocalAiTier,
    val approximateBytes: Long,
    val runtime: String,
    val quantization: String,
    val noThink: Boolean,
    val downloadUrl: String? = null,
    val sha256: String? = null,
    val experimental: Boolean = false
)

data class LocalModelRecommendation(
    val model: LocalModelDescriptor,
    val recommended: Boolean,
    val runnable: Boolean,
    val reason: String
)

data class LocalAiState(
    val initialized: Boolean = false,
    val profile: DeviceAiProfile? = null,
    val recommendations: List<LocalModelRecommendation> = emptyList(),
    val selectedModelId: String? = null,
    val modelReady: Boolean = false,
    val enabled: Boolean = false,
    val lastDecision: TrendDecision? = null
)

/**
 * Catalog is deliberately replaceable. The APK only ships a safe seed entry; a later remote catalog
 * can add/revoke model builds and download mirrors without requiring an app update.
 */
object LocalModelCatalog {
    private val seed = listOf(
        LocalModelDescriptor(
            id = "qwen3-0.6b-int4-nothink",
            displayName = "Qwen3 0.6B · INT4 · No-think",
            minTier = LocalAiTier.TIER_2_SLM,
            approximateBytes = 330L * 1024L * 1024L,
            runtime = "litert-lm",
            quantization = "INT4",
            noThink = true
        )
    )

    fun current(): List<LocalModelDescriptor> = seed

    fun recommend(profile: DeviceAiProfile): List<LocalModelRecommendation> {
        return current().map { model ->
            val runnable = profile.tier.ordinal >= model.minTier.ordinal &&
                profile.availableStorageBytes >= model.approximateBytes * 2
            val recommended = runnable && profile.tier.ordinal <= LocalAiTier.TIER_3_HIGH.ordinal
            LocalModelRecommendation(
                model = model,
                recommended = recommended,
                runnable = runnable,
                reason = when {
                    !runnable && profile.tier.ordinal < model.minTier.ordinal -> "设备持续推理档位不足"
                    !runnable -> "可用存储不足，需预留模型文件至少 2 倍空间"
                    recommended -> "适合本机实时场景识别"
                    else -> "可以运行，但建议先执行本机基准测试"
                }
            )
        }
    }
}

object DeviceAiProfiler {
    fun inspect(context: Context): DeviceAiProfile {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val totalRam = memory.totalMem
        val availableRam = memory.availMem
        val storage = context.filesDir.usableSpace
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus
        } else null

        val thermalPenalty = thermal != null && thermal >= PowerManager.THERMAL_STATUS_SEVERE
        val tier = when {
            thermalPenalty -> LocalAiTier.TIER_1_LITE
            totalRam >= 16L * GIB && cores >= 8 -> LocalAiTier.TIER_4_EXPERIMENTAL
            totalRam >= 12L * GIB && cores >= 8 -> LocalAiTier.TIER_3_HIGH
            totalRam >= 8L * GIB && cores >= 6 -> LocalAiTier.TIER_2_SLM
            totalRam >= 6L * GIB -> LocalAiTier.TIER_1_LITE
            else -> LocalAiTier.TIER_0_RULES
        }
        val reason = when (tier) {
            LocalAiTier.TIER_4_EXPERIMENTAL -> "高内存/多核心设备，可开放更高档本地模型并以基准测试决定是否启用"
            LocalAiTier.TIER_3_HIGH -> "高性能设备，适合持续本地小模型推理"
            LocalAiTier.TIER_2_SLM -> "适合 Qwen3 0.6B INT4 一类实时小模型"
            LocalAiTier.TIER_1_LITE -> if (thermalPenalty) "当前热状态较高，暂时降级" else "建议轻量分类器或规则辅助"
            LocalAiTier.TIER_0_RULES -> "保持规则引擎，不建议下载生成式本地模型"
        }
        return DeviceAiProfile(
            tier = tier,
            totalRamBytes = totalRam,
            availableRamBytes = availableRam,
            availableStorageBytes = storage,
            cpuCores = cores,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            thermalStatus = thermal,
            reason = reason
        )
    }

    private const val GIB = 1024L * 1024L * 1024L
}

/**
 * Process-wide local intelligence service. Every RiftLab surface may consume the same engine and
 * current match context. Models are optional; rules remain available at all times.
 *
 * A real model backend is installed only after the user explicitly downloads/enables a compatible
 * model. Until then the deterministic backend is used and the rest of the app behaves normally.
 */
object LocalAiCore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(LocalAiState())
    val state: StateFlow<LocalAiState> = mutableState.asStateFlow()

    @Volatile
    private var backend: TrendInferenceBackend = RuleTrendFallback

    fun initialize(context: Context) {
        if (mutableState.value.initialized) return
        scope.launch {
            val profile = DeviceAiProfiler.inspect(context.applicationContext)
            mutableState.value = mutableState.value.copy(
                initialized = true,
                profile = profile,
                recommendations = LocalModelCatalog.recommend(profile)
            )
        }
    }

    /** User choice only. Merely being recommended never turns the model on automatically. */
    fun selectModel(modelId: String?) {
        val allowed = mutableState.value.recommendations
            .firstOrNull { it.model.id == modelId && it.runnable }
        mutableState.value = mutableState.value.copy(
            selectedModelId = allowed?.model?.id,
            modelReady = false,
            enabled = false
        )
        backend = RuleTrendFallback
    }

    /** Called by the model manager after checksum + local benchmark both pass. */
    fun installBackend(modelId: String, modelBackend: TrendInferenceBackend) {
        if (mutableState.value.selectedModelId != modelId) return
        backend = modelBackend
        mutableState.value = mutableState.value.copy(modelReady = true, enabled = true)
    }

    fun disableModel() {
        backend = RuleTrendFallback
        mutableState.value = mutableState.value.copy(enabled = false)
    }

    /**
     * Global entry point. scope identifies the consumer, while facts remain immutable verified data.
     * AI never blocks the caller from using immediate rule facts; consumers should treat the returned
     * decision as an asynchronous enhancement.
     */
    suspend fun analyze(scope: LocalAiScope, frame: TrendFrame, latencyBudgetMs: Long = 500L): TrendDecision {
        val started = System.currentTimeMillis()
        val selected = backend
        val result = runCatching { selected.infer(frame) }
            .getOrElse { RuleTrendFallback.infer(frame) }
        val elapsed = System.currentTimeMillis() - started
        val finalResult = if (elapsed <= latencyBudgetMs || selected === RuleTrendFallback) {
            result
        } else {
            RuleTrendFallback.infer(frame)
        }
        mutableState.value = mutableState.value.copy(lastDecision = finalResult)
        return finalResult
    }
}
