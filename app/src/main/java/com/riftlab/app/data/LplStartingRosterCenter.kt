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
 * Minute-level official-social starter watcher. It is independent from the 5-minute schedule poll so
 * a 23:00 club/LPL Weibo announcement can reach the prematch UI before the website updates.
 */
object LplStartingRosterCenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val feed = LplStartingRosterFeed()
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
                        val count = listOf(left, right).count { it != null }
                        mutableState.value = StartingRosterState(
                            matchKey = matchKey,
                            left = left,
                            right = right,
                            lastCheckedEpochMs = System.currentTimeMillis(),
                            message = when (count) {
                                2 -> "OFFICIAL SOCIAL · 两队首发已确认"
                                1 -> "OFFICIAL SOCIAL · 1/2 队首发已确认"
                                else -> "OFFICIAL SOCIAL · 尚未发现匹配的官方首发"
                            }
                        )
                    }
                    .onFailure { error ->
                        val old = mutableState.value
                        mutableState.value = old.copy(
                            matchKey = matchKey,
                            lastCheckedEpochMs = System.currentTimeMillis(),
                            message = "OFFICIAL SOCIAL · 同步失败 · ${error.message?.take(80) ?: error::class.java.simpleName}"
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

    private fun token(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
}
