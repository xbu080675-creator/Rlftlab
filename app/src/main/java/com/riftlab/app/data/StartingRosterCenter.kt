package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class StartingRosterState(
    val matchKey: String = "",
    val left: StartingRosterEvidence? = null,
    val right: StartingRosterEvidence? = null,
    val lastCheckedEpochMs: Long = 0L,
    val message: String = "等待赛程目标"
)

/**
 * Global minute-level official roster watcher.
 *
 * The client consumes normalized evidence only; social crawling/OCR happens upstream. That means a
 * mainland user does not need direct access to X/Instagram/YouTube or another overseas source just
 * to receive a confirmed roster.
 */
object StartingRosterCenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val feed = StartingRosterFeed()
    private val mutableState = MutableStateFlow(StartingRosterState())
    val state: StateFlow<StartingRosterState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun ensureRunning() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val target = MatchSessionStore.targetMatch.value
                if (target == null || target.teams.size < 2) {
                    mutableState.value = StartingRosterState(message = "等待赛程目标")
                    delay(60_000L)
                    continue
                }

                val matchKey = target.eventId.ifBlank { target.matchId }.ifBlank {
                    "${target.startTimeIso}|${target.teams.take(2).joinToString("|") { it.code.ifBlank { it.name } }}"
                }

                runCatching { feed.fetchFor(target) }
                    .onSuccess { evidence ->
                        val left = findFor(target.teams[0], evidence)
                        val right = findFor(target.teams[1], evidence)
                        val rows = listOfNotNull(left, right)
                        val count = rows.size
                        val conflictCount = rows.count { it.conflict }
                        val crossCount = rows.count { it.crossConfirmed }
                        mutableState.value = StartingRosterState(
                            matchKey = matchKey,
                            left = left,
                            right = right,
                            lastCheckedEpochMs = System.currentTimeMillis(),
                            message = when {
                                conflictCount > 0 -> "OFFICIAL ROSTER · 官方来源存在冲突，等待确认"
                                count == 2 && crossCount == 2 -> "OFFICIAL ROSTER · 两队首发已交叉确认"
                                count == 2 -> "OFFICIAL ROSTER · 两队首发已确认"
                                count == 1 -> "OFFICIAL ROSTER · 1/2 队首发已确认"
                                else -> "OFFICIAL ROSTER · 尚未发现匹配的官方首发"
                            }
                        )
                    }
                    .onFailure { error ->
                        val old = mutableState.value
                        mutableState.value = old.copy(
                            matchKey = matchKey,
                            lastCheckedEpochMs = System.currentTimeMillis(),
                            message = "OFFICIAL ROSTER · 同步失败 · ${error.message?.take(80) ?: error::class.java.simpleName}"
                        )
                    }
                delay(60_000L)
            }
        }
    }

    fun evidenceFor(team: EsportsTeamRef): StartingRosterEvidence? {
        val current = mutableState.value
        return listOfNotNull(current.left, current.right).firstOrNull { evidence ->
            token(evidence.team) in aliases(team)
        }
    }

    private fun findFor(team: EsportsTeamRef, rows: Map<String, StartingRosterEvidence>): StartingRosterEvidence? =
        aliases(team).firstNotNullOfOrNull { rows[it] }

    private fun aliases(team: EsportsTeamRef): Set<String> =
        listOf(team.code, team.name, team.slug, team.id).map(::token).filter { it.isNotBlank() }.toSet()

    private fun token(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9\\p{L}\\p{N}]+"), "")
}
