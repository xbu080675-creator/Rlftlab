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
    val deviceOcrTraces: List<RosterDeviceOcrTrace> = emptyList(),
    val riftClawState: String = "DISABLED",
    val riftClawHitCount: Int = 0,
    val riftClawPreview: String = "",
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
 * Normalized evidence is preferred. RiftClaw is an optional LPL discovery lane only: when the
 * official normalized feed has fewer than two teams confirmed, a paired local RiftClaw may query
 * Weibo. Its text is sanitized and surfaced as discovery diagnostics, never promoted directly into
 * StartingRosterEvidence. Official-source/date/matchup/5+5 validation remains mandatory.
 */
object StartingRosterCenter {
    private const val RIFTCLAW_CACHE_MS = 5 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(StartingRosterState())
    val state: StateFlow<StartingRosterState> = mutableState.asStateFlow()
    private var feed: StartingRosterFeed? = null
    private var announcementFeed: StartingRosterAnnouncementFeed? = null
    private var deviceOcrResolver: RosterDeviceOcrResolver? = null
    private var job: Job? = null
    private var riftClawCacheKey: String = ""
    private var riftClawCacheAt: Long = 0L
    private var riftClawCache: RiftClawDiscovery = RiftClawDiscovery("DISABLED", 0, "")

    private data class RiftClawDiscovery(
        val state: String,
        val hitCount: Int,
        val preview: String,
        val diagnostic: String = state
    )

    fun initialize(context: Context) {
        if (feed == null) feed = StartingRosterFeed(context.applicationContext)
        if (announcementFeed == null) announcementFeed = StartingRosterAnnouncementFeed()
        if (deviceOcrResolver == null) deviceOcrResolver = RosterDeviceOcrResolver()
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
                        val unparsed = announcements.filter { !it.parsed }

                        val riftClaw = riftClawDiscovery(target, matchKey, count)

                        val deviceTraces = if (count < 2 && unparsed.any { it.imageUrls.isNotEmpty() }) {
                            runCatching {
                                deviceOcrResolver?.inspect(target, unparsed).orEmpty()
                            }.getOrDefault(emptyList())
                        } else {
                            emptyList()
                        }
                        val ocrUseful = deviceTraces.count { trace ->
                            trace.error.isBlank() && trace.roleCandidates.values.count { it.isNotEmpty() } >= 3
                        }
                        val transport = if (result.usedLastGood) "LAST-GOOD CACHE" else result.endpointLabel
                        mutableState.value = StartingRosterState(
                            matchKey = matchKey,
                            left = left,
                            right = right,
                            announcements = announcements,
                            deviceOcrTraces = deviceTraces,
                            riftClawState = riftClaw.state,
                            riftClawHitCount = riftClaw.hitCount,
                            riftClawPreview = riftClaw.preview,
                            lastCheckedEpochMs = System.currentTimeMillis(),
                            endpointLabel = result.endpointLabel,
                            usedLastGood = result.usedLastGood,
                            checkedEndpoints = result.checkedEndpoints,
                            diagnostics = buildString {
                                append(result.diagnostics)
                                if (announcements.isNotEmpty()) {
                                    if (isNotEmpty()) append(" · ")
                                    append("RAW_OFFICIAL:${announcements.size};UNPARSED:${unparsed.size}")
                                }
                                if (riftClaw.diagnostic.isNotBlank()) {
                                    if (isNotEmpty()) append(" · ")
                                    append("RIFTCLAW:")
                                    append(riftClaw.diagnostic.take(180))
                                }
                                if (deviceTraces.isNotEmpty()) {
                                    if (isNotEmpty()) append(" · ")
                                    append("DEVICE_OCR:${deviceTraces.size};USEFUL:$ocrUseful")
                                    deviceTraces.take(2).forEach { trace ->
                                        append(" · OCR[")
                                        append(trace.account.take(18))
                                        append("]:")
                                        if (trace.error.isNotBlank()) {
                                            append(trace.error.take(90))
                                        } else {
                                            append(trace.engines.joinToString("+"))
                                            append(":")
                                            append(trace.lineCount)
                                            append("L:")
                                            append(trace.roleCandidates.filterValues { it.isNotEmpty() }.keys.joinToString(","))
                                            if (trace.textPreview.isNotBlank()) {
                                                append(":")
                                                append(trace.textPreview.take(180))
                                            }
                                        }
                                    }
                                }
                            },
                            message = when {
                                conflictCount > 0 -> "OFFICIAL ROSTER · 官方来源存在冲突，等待确认 · $transport"
                                count == 2 && crossCount == 2 -> "OFFICIAL ROSTER · 两队首发已交叉确认 · $transport"
                                count == 2 -> "OFFICIAL ROSTER · 两队首发已确认 · $transport"
                                count == 1 -> "OFFICIAL ROSTER · 1/2 队首发已确认 · $transport"
                                riftClaw.hitCount > 0 -> "OFFICIAL ROSTER · 微博增强已提前命中 · 正在做官方证据/5+5 校验"
                                ocrUseful > 0 -> "OFFICIAL ROSTER · 官宣已发现 · 本机 OCR 已识别部分位置，继续校验"
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

    private suspend fun riftClawDiscovery(
        target: ScheduledEsportsMatch,
        matchKey: String,
        confirmedCount: Int
    ): RiftClawDiscovery {
        if (confirmedCount >= 2) return RiftClawDiscovery("SKIP_CONFIRMED", 0, "")
        if (!ProviderCredentialStore.riftClawPaired.value) return RiftClawDiscovery("UNPAIRED", 0, "")
        val leagueToken = token(target.league)
        if (leagueToken != "LPL") return RiftClawDiscovery("UNSUPPORTED_LEAGUE", 0, "")

        val matchDate = target.startTimeIso.take(10)
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(matchDate)) {
            return RiftClawDiscovery("DATE_UNAVAILABLE", 0, "")
        }
        val teamA = target.teams[0].code.ifBlank { target.teams[0].slug }.trim()
        val teamB = target.teams[1].code.ifBlank { target.teams[1].slug }.trim()
        val safeCode = Regex("^[A-Za-z0-9._+-]{1,48}$")
        if (!safeCode.matches(teamA) || !safeCode.matches(teamB)) {
            return RiftClawDiscovery("TEAM_CODE_UNAVAILABLE", 0, "")
        }

        val cacheKey = "$matchKey|$matchDate|${teamA.uppercase()}|${teamB.uppercase()}"
        val now = System.currentTimeMillis()
        if (riftClawCacheKey == cacheKey && now - riftClawCacheAt < RIFTCLAW_CACHE_MS) {
            return riftClawCache.copy(diagnostic = "CACHE:${riftClawCache.state};HITS:${riftClawCache.hitCount}")
        }

        val request = RiftClawContract.SearchRequest(
            requestId = "roster-$matchDate-${teamA.lowercase()}-${teamB.lowercase()}-${now.toString().takeLast(6)}",
            matchDate = matchDate,
            league = "LPL",
            teamA = teamA,
            teamB = teamB
        )
        val discovered = RiftClawClient.searchStartingRoster(request).fold(
            onSuccess = { response ->
                val preview = response.hits.firstOrNull()?.text.orEmpty().replace('\n', ' ').take(220)
                RiftClawDiscovery(
                    state = if (response.hits.isEmpty()) "NO_CONTENT" else "HIT",
                    hitCount = response.hits.size,
                    preview = preview,
                    diagnostic = "${if (response.hits.isEmpty()) "NO_CONTENT" else "HIT"};HITS:${response.hits.size}"
                )
            },
            onFailure = { error ->
                RiftClawDiscovery(
                    state = "ERROR",
                    hitCount = 0,
                    preview = "",
                    diagnostic = "ERROR:${error.message?.take(120) ?: error::class.java.simpleName}"
                )
            }
        )
        riftClawCacheKey = cacheKey
        riftClawCacheAt = now
        riftClawCache = discovered
        return discovered
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
