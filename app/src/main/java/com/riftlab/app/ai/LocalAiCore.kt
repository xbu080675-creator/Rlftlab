package com.riftlab.app.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

object LocalModelCatalog {
    private val seed = listOf(
        LocalModelDescriptor(
            id = "qwen3-0.6b-int4-nothink",
            displayName = "Qwen3 0.6B · INT4 · No-think",
            minTier = LocalAiTier.TIER_2_SLM,
            approximateBytes = 347_251_840L,
            runtime = "litert-lm-0.17.0",
            quantization = "INT4 block32",
            noThink = true,
            downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm?download=true",
            sha256 = "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139"
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
                    recommended -> "适合本机实时场景识别；仍需下载后跑真机基准"
                    else -> "可以运行，但必须先执行本机基准测试"
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

object LocalAiCore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(LocalAiState())
    val state: StateFlow<LocalAiState> = mutableState.asStateFlow()
    private val revisions = ConcurrentHashMap<String, AtomicLong>()

    @Volatile private var backend: TrendInferenceBackend = RuleTrendFallback

    fun initialize(context: Context) {
        if (mutableState.value.initialized) return
        refreshProfile(context)
    }

    fun refreshProfile(context: Context) {
        scope.launch {
            val profile = DeviceAiProfiler.inspect(context.applicationContext)
            val recommendations = LocalModelCatalog.recommend(profile)
            val selectedStillRunnable = mutableState.value.selectedModelId?.let { selected ->
                recommendations.any { it.model.id == selected && it.runnable }
            } ?: true
            if (!selectedStillRunnable) backend = RuleTrendFallback
            mutableState.value = mutableState.value.copy(
                initialized = true,
                profile = profile,
                recommendations = recommendations,
                selectedModelId = if (selectedStillRunnable) mutableState.value.selectedModelId else null,
                modelReady = if (selectedStillRunnable) mutableState.value.modelReady else false,
                enabled = if (selectedStillRunnable) mutableState.value.enabled else false
            )
        }
    }

    fun selectModel(modelId: String?) {
        val allowed = mutableState.value.recommendations.firstOrNull { it.model.id == modelId && it.runnable }
        mutableState.value = mutableState.value.copy(
            selectedModelId = allowed?.model?.id,
            modelReady = false,
            enabled = false
        )
        backend = RuleTrendFallback
    }

    /** Called only after checksum + real model load + real short inference + thermal check pass. */
    fun installBackend(modelId: String, modelBackend: TrendInferenceBackend) {
        if (mutableState.value.selectedModelId != modelId) return
        backend = modelBackend
        mutableState.value = mutableState.value.copy(modelReady = true, enabled = false)
    }

    fun setEnabled(enabled: Boolean): Boolean {
        if (enabled && !mutableState.value.modelReady) return false
        mutableState.value = mutableState.value.copy(enabled = enabled && mutableState.value.modelReady)
        return mutableState.value.enabled == enabled
    }

    fun disableModel() {
        mutableState.value = mutableState.value.copy(enabled = false)
    }

    /** Compatibility/one-shot API. Consumers that affect HUD should prefer analyzeRuleFirst(). */
    suspend fun analyze(scope: LocalAiScope, frame: TrendFrame, latencyBudgetMs: Long = 500L): TrendDecision {
        val started = System.currentTimeMillis()
        val selected = if (mutableState.value.enabled) backend else RuleTrendFallback
        val result = runCatching { selected.infer(frame) }.getOrElse { RuleTrendFallback.infer(frame) }
        val elapsed = System.currentTimeMillis() - started
        val finalResult = if (elapsed <= latencyBudgetMs || selected === RuleTrendFallback) result else RuleTrendFallback.infer(frame)
        mutableState.value = mutableState.value.copy(lastDecision = finalResult)
        return finalResult
    }

    /**
     * Production path: rules return immediately; local AI is an asynchronous enhancement only.
     * Each game gets a monotonic revision. If a newer frame arrives before inference returns, the old
     * result is discarded and can never overwrite the newer HUD state.
     */
    suspend fun analyzeRuleFirst(
        consumer: LocalAiScope,
        frame: TrendFrame,
        latencyBudgetMs: Long = 2500L,
        onEnhancement: (TrendDecision) -> Unit
    ): TrendDecision {
        val rule = RuleTrendFallback.infer(frame)
        mutableState.value = mutableState.value.copy(lastDecision = rule)
        if (!mutableState.value.enabled || backend === RuleTrendFallback) return rule

        val counter = revisions.getOrPut(frame.gameId) { AtomicLong(0L) }
        val revision = counter.incrementAndGet()
        val selected = backend
        scope.launch {
            val started = System.currentTimeMillis()
            val result = runCatching { selected.infer(frame) }.getOrNull() ?: return@launch
            val elapsed = System.currentTimeMillis() - started
            if (elapsed > latencyBudgetMs || counter.get() != revision) return@launch
            mutableState.value = mutableState.value.copy(lastDecision = result)
            onEnhancement(result)
        }
        return rule
    }
}
