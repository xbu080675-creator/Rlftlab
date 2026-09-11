package com.riftlab.app.ai

/**
 * Local-only scene/trend reasoning core for RiftLab.
 *
 * Facts are produced by verified match data and rule extraction. A model may rank/interpret them,
 * but it is never allowed to rewrite facts. The output is deliberately tiny because it feeds the
 * edge HUD, not a chat UI.
 */
enum class TrendSide { BLUE, RED, NEUTRAL }

enum class TrendScene {
    LANE_PRESSURE,
    OBJECTIVE_SETUP,
    OBJECTIVE_CONTEST,
    PICK_WINDOW,
    DIVE_WINDOW,
    ROTATION,
    RESET_WINDOW,
    TEAMFIGHT,
    CHASE,
    SIEGE,
    UNKNOWN
}

enum class TrendPriority { S, A, B, HIDDEN }

data class TrendFact(
    val key: String,
    val side: TrendSide = TrendSide.NEUTRAL,
    val actor: String? = null,
    val role: String? = null,
    val numericValue: Double? = null,
    val textValue: String? = null,
    val confidence: Float = 1f,
    val observedAtEpochMs: Long
)

data class TrendFrame(
    val gameId: String,
    val gameTimeSeconds: Int,
    val facts: List<TrendFact>,
    val previousScene: TrendScene = TrendScene.UNKNOWN,
    val previousLeadingSide: TrendSide = TrendSide.NEUTRAL
)

data class TrendHighlight(
    val factKey: String,
    val side: TrendSide,
    val actor: String? = null,
    val priority: TrendPriority,
    val shortLabel: String,
    val reason: String? = null
)

data class TrendDecision(
    val scene: TrendScene,
    val leadingSide: TrendSide,
    val confidence: Float,
    val highlights: List<TrendHighlight>,
    val modelId: String,
    val latencyMs: Long,
    val generatedAtEpochMs: Long
)

/** Contract implemented by either the local SLM runner or a deterministic fallback. */
fun interface TrendInferenceBackend {
    suspend fun infer(frame: TrendFrame): TrendDecision
}

/**
 * Cheap deterministic fallback used while the local model is cold, unavailable, or over budget.
 * It intentionally handles only obvious cases; ambiguous combinations are left to the model.
 */
object RuleTrendFallback : TrendInferenceBackend {
    override suspend fun infer(frame: TrendFrame): TrendDecision {
        val started = System.currentTimeMillis()
        val facts = frame.facts
        fun has(key: String, side: TrendSide? = null): Boolean = facts.any {
            it.key == key && (side == null || it.side == side)
        }
        fun first(key: String): TrendFact? = facts.firstOrNull { it.key == key }

        val scene = when {
            has("teamfight_active") -> TrendScene.TEAMFIGHT
            has("objective_contest") -> TrendScene.OBJECTIVE_CONTEST
            has("objective_spawn_soon") && (has("river_first_move") || has("jungle_exposed")) -> TrendScene.OBJECTIVE_SETUP
            has("dive_numbers_advantage") -> TrendScene.DIVE_WINDOW
            has("isolated_target") -> TrendScene.PICK_WINDOW
            has("siege_window") -> TrendScene.SIEGE
            has("rotation_advantage") -> TrendScene.ROTATION
            else -> TrendScene.UNKNOWN
        }

        val blueScore = score(TrendSide.BLUE, facts)
        val redScore = score(TrendSide.RED, facts)
        val leading = when {
            blueScore - redScore >= 1.2 -> TrendSide.BLUE
            redScore - blueScore >= 1.2 -> TrendSide.RED
            else -> TrendSide.NEUTRAL
        }

        val candidates = facts.mapNotNull { fact ->
            val priority = priorityFor(fact)
            if (priority == TrendPriority.HIDDEN) return@mapNotNull null
            TrendHighlight(
                factKey = fact.key,
                side = fact.side,
                actor = fact.actor,
                priority = priority,
                shortLabel = labelFor(fact),
                reason = reasonFor(fact)
            )
        }.sortedBy { it.priority.ordinal }.take(3)

        val confidence = when {
            scene == TrendScene.UNKNOWN -> 0.35f
            leading == TrendSide.NEUTRAL -> 0.58f
            else -> 0.72f
        }
        return TrendDecision(
            scene = scene,
            leadingSide = leading,
            confidence = confidence,
            highlights = candidates,
            modelId = "rule-fallback-v1",
            latencyMs = (System.currentTimeMillis() - started).coerceAtLeast(0),
            generatedAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun score(side: TrendSide, facts: List<TrendFact>): Double {
        var score = 0.0
        facts.filter { it.side == side }.forEach { fact ->
            score += when (fact.key) {
                "numbers_advantage" -> 2.0
                "river_first_move" -> 1.4
                "jungle_smite_ready" -> 0.8
                "enemy_jungle_exposed" -> 1.1
                "key_item_spike" -> 1.0
                "lane_priority" -> 0.8
                "vision_control" -> 0.7
                "ultimate_ready" -> 0.45
                "flash_ready" -> 0.35
                else -> 0.0
            } * fact.confidence.coerceIn(0f, 1f)
        }
        return score
    }

    private fun priorityFor(fact: TrendFact): TrendPriority = when (fact.key) {
        "ultimate_unlearned", "jungle_exposed", "smite_unavailable", "isolated_target", "numbers_advantage" -> TrendPriority.S
        "objective_spawn_soon", "river_first_move", "key_item_spike", "flash_unavailable", "vision_gap" -> TrendPriority.A
        "lane_priority", "rotation_advantage", "ultimate_ready" -> TrendPriority.B
        else -> TrendPriority.HIDDEN
    }

    private fun labelFor(fact: TrendFact): String = when (fact.key) {
        "ultimate_unlearned" -> "大招未学习"
        "jungle_exposed" -> "打野位置暴露"
        "smite_unavailable" -> "惩戒不可用"
        "isolated_target" -> "孤立目标"
        "numbers_advantage" -> "局部人数领先"
        "objective_spawn_soon" -> "资源即将刷新"
        "river_first_move" -> "先到河道"
        "key_item_spike" -> "关键装备完成"
        "flash_unavailable" -> "闪现未转好"
        "vision_gap" -> "视野断档"
        "lane_priority" -> "线权优势"
        "rotation_advantage" -> "转线更快"
        else -> fact.textValue ?: fact.key
    }

    private fun reasonFor(fact: TrendFact): String? = when (fact.key) {
        "ultimate_unlearned" -> "会直接改变下一波团战能力"
        "jungle_exposed" -> "对方可据此调整资源与抓人路线"
        "smite_unavailable" -> "会改变中立资源争夺权"
        "river_first_move" -> "可更早建立河道位置与视野"
        "key_item_spike" -> "当前战斗力节点提前到来"
        else -> null
    }
}

/**
 * Filters noisy inference results before they reach the HUD. The model may return many candidates;
 * the viewer should normally see only one S-level or at most two A-level items per side.
 */
object TrendHudGate {
    fun select(decision: TrendDecision, maxPerSide: Int = 2): List<TrendHighlight> {
        return decision.highlights
            .filter { it.priority != TrendPriority.HIDDEN }
            .groupBy { it.side }
            .values
            .flatMap { group ->
                val s = group.filter { it.priority == TrendPriority.S }.take(1)
                if (s.isNotEmpty()) s else group.take(maxPerSide)
            }
            .sortedBy { it.priority.ordinal }
            .take(4)
    }
}
