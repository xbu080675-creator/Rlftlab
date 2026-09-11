package com.riftlab.app.data

import android.content.Context
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
    val announcements: List<StartingRosterAnnouncement> = emptyList(),
    val lastCheckedEpochMs: Long = 0L,
    val endpointLabel: String = "NONE",
    val usedLastGood: Boolean = false,
    val checkedEndpoints: Int = 0,
    val diagnostics: String = "",
    val message: String = "等待赛程目标"
)

/**
 * Global minute-level official roster watcher.
 *
 * Normalized evidence is preferred, but official announcement metadata is kept
 * independently. A failed OCR/parser must therefore degrade to "官宣已发现，解析中"
 * instead of making the information disappear from the client.
 */
object StartingRosterCenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(StartingRosterState())
    val state: StateFlow<StartingRosterState> = mutableState.asStateFlow()
    private var feed: StartingRosterFeed? = null
    private var announcementFeed: StartingRosterAnnouncementFeed? = null
    private var job: Job? = null

    fun initialize(context: Context) {
        if (feed == null) feed = StartingRosterFeed(context.applicationContext)
        if (announcementFeed == null) announcementFeed = StartingRosterAnnouncementFeed()
        ensureRunning()
    }

    fun ensureRunning() {
        val activeFeed = feed ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val target = MatchSessionStore.targetMatch.value
                if (target == null || target.teams.size < 2) {
                    mutableState.value = mutableState.value.copy(message = "等待赛程目标", diagnostics = "target_not_ready")
                    delay(2_000L)
                    continue
                }

                val matchKey = target.eventId.ifBlank { target.matchId }.ifBlank {
                    "${target.startTimeIso}|${target.teams.take(2).joinToString("|") { it.code.ifBlank { it.name } }}"
                }

                runCatching { activeFeed.fetchFor(target) }
                    .onSuccess { result ->
                        val announcements = runCatching {
                            announcementFeed?.fetchFor(target).orEmpty()
                        }.getOrDefault(emptyList())
                        val left = findFor(target.teams[0], result.evidence)
                        val right = findFor(target.teams[1], result.evidence)
                        val rows = listOfNotNull(left, right)
                        val count = rows.size
                        val conflictCount = rows.count { it.conflict }
                        val crossCount = rows.count { it.crossConfirmed }
                        val unparsedCount = announcements.count { !it.parsed }
                        val transport = if (result.usedLastGood) "LAST-GOOD CACHE" else result.endpointLabel
                        mutableState.value = StartingRosterState(
                            matchKey = matchKey,
                            left = left,
                            right = right,
                            announcements = announcements,
                            lastCheckedEpochMs = System.currentTimeMillis(),
                            endpointLabel = result.endpointLabel,
                            usedLastGood = result.usedLastGood,
                            checkedEndpoints = result.checkedEndpoints,
                            diagnostics = buildString {
                                append(result.diagnostics)
                                if (announcements.isNotEmpty()) {
                                    if (isNotEmpty()) append(" · ")
                                    append("RAW_OFFICIAL:${announcements.size};UNPARSED:$unparsedCount")
                                }
                            },
                            message = when {
                                conflictCount > 0 -> "OFFICIAL ROSTER · 官方来源存在冲突，等待确认 · $transport"
                                count == 2 && crossCount == 2 -> "OFFICIAL ROSTER · 两队首发已交叉确认 · $transport"
                                count == 2 -> "OFFICIAL ROSTER · 两队首发已确认 · $transport"
                                count == 1 -> "OFFICIAL ROSTER · 1/2 队首发已确认 · $transport"
                                announcements.isNotEmpty() -> "OFFICIAL ROSTER · 已发现官方发布 · 自动解析中 · ${announcements.first().account}"
                                result.endpointLabel == "VALID_NO_MATCH" -> "OFFICIAL ROSTER · 数据源正常，当前比赛暂无匹配官宣"
                                result.endpointLabel == "NO_VALID_SOURCE" -> "OFFICIAL ROSTER · 分发链异常，正在等待可用源"
                                else -> "OFFICIAL ROSTER · 尚未发现匹配的官方首发 · $transport"
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
            !evidence.conflict && token(evidence.team) in aliases(team)
        }
    }

    private fun findFor(team: EsportsTeamRef, rows: Map<String, StartingRosterEvidence>): StartingRosterEvidence? =
        aliases(team).firstNotNullOfOrNull { rows[it] }

    private fun aliases(team: EsportsTeamRef): Set<String> =
        listOf(team.code, team.name, team.slug, team.id).map(::token).filter { it.isNotBlank() }.toSet()

    private fun token(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9\\p{L}\\p{N}]+"), "")
}
