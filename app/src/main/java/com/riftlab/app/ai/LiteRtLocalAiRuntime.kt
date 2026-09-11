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
import java.io.File

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
        val benchmarkMs: Long? = null,
        val message: String = "等待已验证模型"
    )

    private const val MAX_BENCHMARK_MS = 2500L
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
            mutableState.value = RuntimeState(busy = true, modelId = descriptor.id, message = "正在加载真实 LiteRT-LM 模型…")
            runCatching {
                ensureThermalAcceptable(app)
                active?.close()
                active = null

                val started = System.currentTimeMillis()
                val engine = withContext(Dispatchers.IO) {
                    Engine(
                        EngineConfig(
                            modelPath = path,
                            backend = Backend.CPU(),
                            cacheDir = app.cacheDir.resolve("litertlm_runtime").apply { mkdirs() }.absolutePath
                        )
                    ).also { it.initialize() }
                }
                val loadMs = System.currentTimeMillis() - started
                val backend = LiteRtTrendBackend(descriptor.id, engine)

                // The benchmark is deliberately a real generation, not a file-load stopwatch.
                val benchFrame = TrendFrame(
                    gameId = "riftlab-benchmark",
                    gameTimeSeconds = 720,
                    facts = listOf(
                        TrendFact("objective_spawn_soon", TrendSide.BLUE, textValue = "dragon 38s", observedAtEpochMs = System.currentTimeMillis()),
                        TrendFact("river_first_move", TrendSide.BLUE, actor = "SUP", observedAtEpochMs = System.currentTimeMillis()),
                        TrendFact("jungle_exposed", TrendSide.RED, actor = "JUG", observedAtEpochMs = System.currentTimeMillis()),
                        TrendFact("flash_unavailable", TrendSide.RED, actor = "MID", observedAtEpochMs = System.currentTimeMillis())
                    )
                )
                val benchmarkStarted = System.currentTimeMillis()
                backend.infer(benchFrame)
                val benchmarkMs = System.currentTimeMillis() - benchmarkStarted
                ensureThermalAcceptable(app)
                if (benchmarkMs > MAX_BENCHMARK_MS) {
                    backend.close()
                    error("真实短推理 ${benchmarkMs}ms，超过当前实时门槛 ${MAX_BENCHMARK_MS}ms")
                }

                active = backend
                LocalModelManager.markReady(descriptor.id, path, benchmarkMs)
                LocalAiCore.installBackend(descriptor.id, backend)
                mutableState.value = RuntimeState(
                    busy = false,
                    modelId = descriptor.id,
                    backendLabel = "CPU",
                    loadMs = loadMs,
                    benchmarkMs = benchmarkMs,
                    message = "真实模型加载/推理通过 · ${benchmarkMs}ms"
                )
            }.onFailure { error ->
                active?.close()
                active = null
                LocalAiCore.disableModel()
                mutableState.value = RuntimeState(
                    busy = false,
                    modelId = descriptor.id,
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

    private fun ensureThermalAcceptable(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val thermal = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus ?: return
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
