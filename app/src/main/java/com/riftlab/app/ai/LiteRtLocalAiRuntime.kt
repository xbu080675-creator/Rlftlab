package com.riftlab.app.ai

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Real on-device LiteRT-LM runner. Rules still own facts; this backend may only classify the scene,
 * choose a side and rank fact keys that already exist in the input frame.
 */
object LiteRtLocalAiRuntime {
    data class RuntimeState(
        val busy: Boolean = false,
        val modelId: String? = null,
        val backendLabel: String = "CPU",
        val loadMs: Long? = null,
        val warmupMs: Long? = null,
        val sampleMs: List<Long> = emptyList(),
        val medianMs: Long? = null,
        val p90Ms: Long? = null,
        val thermalBefore: Int? = null,
        val thermalAfter: Int? = null,
        val message: String = "等待已验证模型"
    )

    // Readiness is based on warmed steady-state latency, never the first cold generation.
    private const val MAX_MEDIAN_MS = 2500L
    private const val MAX_P90_MS = 3500L
    private const val STEADY_SAMPLES = 3
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    @Volatile private var active: LiteRtTrendBackend? = null

    fun benchmarkVerifiedModel(context: Context, descriptor: LocalModelDescriptor) {
        val install = LocalModelManager.state.value
        val path = install.localPath
        if (install.modelId != descriptor.id || install.status != LocalModelInstallStatus.VERIFIED || path.isNullOrBlank()) {
            mutableState.value = mutableState.value.copy(message = "请先完成模型下载与 SHA-256 校验")
            return
        }
        if (mutableState.value.busy) return

        val app = context.applicationContext
        scope.launch {
            val thermalBefore = thermalStatus(app)
            mutableState.value = RuntimeState(
                busy = true,
                modelId = descriptor.id,
                thermalBefore = thermalBefore,
                message = "正在加载真实 LiteRT-LM 模型…"
            )
            runCatching {
                ensureThermalAcceptable(app)
                active?.close()
                active = null

                val loadStarted = System.currentTimeMillis()
                val engine = withContext(Dispatchers.IO) {
                    Engine(
                        EngineConfig(
                            modelPath = path,
                            backend = Backend.CPU(),
                            cacheDir = app.cacheDir.resolve("litertlm_runtime").apply { mkdirs() }.absolutePath
                        )
                    ).also { it.initialize() }
                }
                val loadMs = System.currentTimeMillis() - loadStarted
                val backend = LiteRtTrendBackend(descriptor.id, engine)
                val benchFrame = benchmarkFrame()

                // Cold load and first generation are measured separately. The first generation is a
                // warm-up only and can never fail the device by itself.
                val warmupStarted = System.currentTimeMillis()
                backend.infer(benchFrame)
                val warmupMs = System.currentTimeMillis() - warmupStarted
                ensureThermalAcceptable(app)

                val samples = mutableListOf<Long>()
                repeat(STEADY_SAMPLES) {
                    val started = System.currentTimeMillis()
                    backend.infer(benchFrame)
                    samples += System.currentTimeMillis() - started
                    ensureThermalAcceptable(app)
                }
                val sorted = samples.sorted()
                val medianMs = sorted[sorted.size / 2]
                val p90Ms = sorted[((sorted.size * 9 + 9) / 10 - 1).coerceIn(0, sorted.lastIndex)]
                val thermalAfter = thermalStatus(app)

                if (medianMs > MAX_MEDIAN_MS || p90Ms > MAX_P90_MS) {
                    backend.close()
                    error(
                        "稳态推理未过门槛 · median ${medianMs}ms / P90 ${p90Ms}ms · " +
                            "门槛 ${MAX_MEDIAN_MS}/${MAX_P90_MS}ms；冷启动 ${loadMs}ms、warm-up ${warmupMs}ms 不参与判定"
                    )
                }

                active = backend
                LocalModelManager.markReady(descriptor.id, path, medianMs)
                LocalAiCore.installBackend(descriptor.id, backend)
                mutableState.value = RuntimeState(
                    busy = false,
                    modelId = descriptor.id,
                    backendLabel = "CPU",
                    loadMs = loadMs,
                    warmupMs = warmupMs,
                    sampleMs = samples,
                    medianMs = medianMs,
                    p90Ms = p90Ms,
                    thermalBefore = thermalBefore,
                    thermalAfter = thermalAfter,
                    message = "稳态基准通过 · median ${medianMs}ms · P90 ${p90Ms}ms"
                )
            }.onFailure { error ->
                active?.close()
                active = null
                LocalAiCore.disableModel()
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    thermalAfter = thermalStatus(app),
                    message = "基准测试未通过 · ${error.message ?: error::class.java.simpleName}"
                )
            }
        }
    }

    fun shutdown() {
        active?.close()
        active = null
        LocalAiCore.disableModel()
    }

    private fun benchmarkFrame(): TrendFrame = TrendFrame(
        gameId = "riftlab-benchmark",
        gameTimeSeconds = 720,
        facts = listOf(
            TrendFact("objective_spawn_soon", TrendSide.BLUE, textValue = "dragon 38s", observedAtEpochMs = System.currentTimeMillis()),
            TrendFact("river_first_move", TrendSide.BLUE, actor = "SUP", observedAtEpochMs = System.currentTimeMillis()),
            TrendFact("jungle_exposed", TrendSide.RED, actor = "JUG", observedAtEpochMs = System.currentTimeMillis()),
            TrendFact("flash_unavailable", TrendSide.RED, actor = "MID", observedAtEpochMs = System.currentTimeMillis())
        )
    )

    private fun thermalStatus(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus
    }

    private fun ensureThermalAcceptable(context: Context) {
        val thermal = thermalStatus(context) ?: return
        if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) error("设备当前热状态过高，保持规则模式")
    }
}

private class LiteRtTrendBackend(
    private val modelId: String,
    private val engine: Engine
) : TrendInferenceBackend, Closeable {
    private val mutex = Mutex()

    override suspend fun infer(frame: TrendFrame): TrendDecision = mutex.withLock {
        val started = System.currentTimeMillis()
        val factByKey = frame.facts.associateBy { it.key }
        val prompt = buildPrompt(frame)
        val raw = withContext(Dispatchers.Default) {
            engine.createConversation().use { conversation ->
                conversation.sendMessage(prompt).toString()
            }
        }

        val scene = token(raw, "SCENE")?.let { runCatching { TrendScene.valueOf(it) }.getOrNull() } ?: frame.previousScene
        val side = token(raw, "SIDE")?.let { runCatching { TrendSide.valueOf(it) }.getOrNull() } ?: TrendSide.NEUTRAL
        val confidence = token(raw, "CONF")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f
        val priorities = parsePriorities(raw)
        val highlights = token(raw, "KEYS")
            ?.split(',')
            ?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
            ?.distinct()
            ?.mapNotNull { key ->
                val fact = factByKey[key] ?: return@mapNotNull null
                TrendHighlight(
                    factKey = key,
                    side = fact.side,
                    actor = fact.actor,
                    priority = priorities[key] ?: TrendPriority.A,
                    shortLabel = fact.textValue?.take(18)?.ifBlank { null } ?: key.replace('_', ' '),
                    reason = "本地模型从已验证事实中选为当前高价值信号"
                )
            }
            ?.take(3)
            .orEmpty()

        if (highlights.isEmpty() || scene == TrendScene.UNKNOWN) return RuleTrendFallback.infer(frame)
        TrendDecision(
            scene = scene,
            leadingSide = side,
            confidence = confidence,
            highlights = highlights,
            modelId = modelId,
            latencyMs = System.currentTimeMillis() - started,
            generatedAtEpochMs = System.currentTimeMillis()
        )
    }

    override fun close() = engine.close()

    private fun buildPrompt(frame: TrendFrame): String {
        val facts = frame.facts.joinToString("|") { f ->
            listOfNotNull(f.key, f.side.name, f.actor, f.role, f.numericValue?.toString(), f.textValue)
                .joinToString(":")
        }
        return """
            You are RiftLab local scene ranker. Facts are immutable; never invent a fact key.
            Return exactly four lines, no prose:
            SCENE=<${TrendScene.entries.joinToString("|") { it.name }}>
            SIDE=<BLUE|RED|NEUTRAL>
            CONF=<0.0..1.0>
            KEYS=<up to 3 existing fact keys, comma separated; optional @S/@A/@B after key>
            game=${frame.gameTimeSeconds};previous=${frame.previousScene.name}/${frame.previousLeadingSide.name};facts=$facts
        """.trimIndent()
    }

    private fun token(raw: String, name: String): String? =
        Regex("(?im)^\\s*$name\\s*=\\s*([^\\r\\n]+)").find(raw)?.groupValues?.getOrNull(1)?.trim()

    private fun parsePriorities(raw: String): Map<String, TrendPriority> {
        val keys = token(raw, "KEYS") ?: return emptyMap()
        return keys.split(',').mapNotNull { item ->
            val parts = item.trim().split('@', limit = 2)
            val key = parts.firstOrNull()?.trim().orEmpty()
            if (key.isBlank()) return@mapNotNull null
            val priority = parts.getOrNull(1)?.trim()?.let { runCatching { TrendPriority.valueOf(it) }.getOrNull() }
                ?: TrendPriority.A
            key to priority
        }.toMap()
    }
}
