package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stable identity shared by schedule, post-match and MVP/vote/BP providers. */
data class MatchDetailKey(
    val riotEventId: String,
    val riotMatchId: String,
    val startTimeIso: String,
    val teamAId: String,
    val teamBId: String
) {
    val stableId: String
        get() = riotEventId.ifBlank { riotMatchId }.ifBlank {
            listOf(teamAId, teamBId, startTimeIso).joinToString("|")
        }

    companion object {
        fun from(match: ScheduledEsportsMatch): MatchDetailKey = MatchDetailKey(
            riotEventId = match.eventId,
            riotMatchId = match.matchId,
            startTimeIso = match.startTimeIso,
            teamAId = match.teams.getOrNull(0)?.id.orEmpty(),
            teamBId = match.teams.getOrNull(1)?.id.orEmpty()
        )
    }
}

data class OfficialMvpRecord(
    val game: Int?,
    val playerName: String,
    val team: String,
    val role: String = "",
    val source: String
)

data class VoteOptionRecord(
    val label: String,
    val votes: Long,
    val percent: Double? = null
)

data class OfficialVoteRecord(
    val title: String,
    val options: List<VoteOptionRecord>,
    val totalVotes: Long? = null,
    val source: String
)

data class DraftPickRecord(
    val game: Int,
    val blueBans: List<String> = emptyList(),
    val redBans: List<String> = emptyList(),
    val bluePicks: List<String> = emptyList(),
    val redPicks: List<String> = emptyList(),
    val source: String
)

data class MatchDetailState(
    val key: MatchDetailKey? = null,
    val match: ScheduledEsportsMatch? = null,
    val loading: Boolean = false,
    val series: CompletedSeriesSnapshot? = null,
    val liveGame: LiveSnapshot? = null,
    val seriesMvp: OfficialMvpRecord? = null,
    val gameMvps: List<OfficialMvpRecord> = emptyList(),
    val votes: List<OfficialVoteRecord> = emptyList(),
    val drafts: List<DraftPickRecord> = emptyList(),
    val status: String = "选择一场比赛查看详情",
    val errorMessage: String? = null,
    val updatedAtEpochMs: Long = 0L
)

/**
 * Match detail has its own selection state. Opening a historical match must never mutate the main
 * viewing target, which keeps following the current/next series independently.
 */
object MatchDetailRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val resolver = LplHistoricalPostMatchResolver()
    private val awardsProvider = LplOfficialAwardsProvider()
    private val draftProvider = LplOfficialDraftProvider()
    private val opggProvider = OpggMatchSupplementProvider()
    private val teamAssetProvider = RiotTeamAssetProvider()
    private val resolverMutex = Mutex()
    private val cache = linkedMapOf<String, MatchDetailState>()
    private var loadJob: Job? = null

    private val _state = MutableStateFlow(MatchDetailState())
    val state: StateFlow<MatchDetailState> = _state.asStateFlow()

    fun open(match: ScheduledEsportsMatch, forceRefresh: Boolean = false) {
        val key = MatchDetailKey.from(match)
        val cached = cache[key.stableId]
        if (!forceRefresh && cached != null) {
            _state.value = cached.copy(key = key)
            return
        }

        loadJob?.cancel()
        loadJob = scope.launch {
            val matchWithRiotAssets = runCatching { enrichTeamImages(match) }.getOrDefault(match)
            val phase = MatchSessionStore.schedulePhase(matchWithRiotAssets)
            val base = MatchDetailState(
                key = key,
                match = matchWithRiotAssets,
                loading = phase == ScheduleMatchPhase.COMPLETED,
                liveGame = if (phase == ScheduleMatchPhase.LIVE) currentLiveFor(matchWithRiotAssets) else null,
                status = when (phase) {
                    ScheduleMatchPhase.UPCOMING -> "比赛尚未开始 · 当前展示赛程与赛前元数据"
                    ScheduleMatchPhase.LIVE -> "比赛进行中 · 当前局实时数据由赛中 Provider Router 提供"
                    ScheduleMatchPhase.COMPLETED -> "正在加载历史终局、MVP / POG 与 BP…"
                },
                updatedAtEpochMs = System.currentTimeMillis()
            )
            _state.value = base

            if (phase != ScheduleMatchPhase.COMPLETED) {
                cache[key.stableId] = base
                return@launch
            }

            val result = runCatching {
                resolverMutex.withLock { resolver.resolve(matchWithRiotAssets) }
            }
            val rawResolved = result.getOrNull()?.normalizeDetailRoles()
            val resolved = rawResolved?.let { alignSeriesToMatch(it, matchWithRiotAssets) }
            val bmid = resolved?.matchKey
                ?.takeIf { it.startsWith("TJ:") }
                ?.removePrefix("TJ:")
                .orEmpty()

            val awards = if (bmid.isNotBlank()) {
                runCatching { awardsProvider.fetch(bmid) }.getOrElse {
                    OfficialAwardsResult(status = "MVP / POG 同步失败 · ${it.message?.take(100) ?: it::class.java.simpleName}")
                }
            } else {
                OfficialAwardsResult(status = "MVP / POG 等待历史 bMatchId")
            }

            val draftResult = runCatching { draftProvider.fetch(bmid, resolved) }.getOrElse {
                OfficialDraftResult(status = "BP 同步失败 · ${it.message?.take(100) ?: it::class.java.simpleName}")
            }

            val opgg = runCatching { opggProvider.fetch(matchWithRiotAssets) }.getOrElse {
                OpggMatchSupplement(status = "OP.GG 同步失败 · ${it.message?.take(100) ?: it::class.java.simpleName}")
            }
            val enrichedMatch = runCatching { enrichTeamImages(matchWithRiotAssets, opgg.teamImages) }
                .getOrDefault(matchWithRiotAssets)

            val officialGameMvps = awards.gameMvps.map { it.withDisplaySource(enrichedMatch) }
            val opggGameMvps = opgg.gameMvps.map { it.withDisplaySource(enrichedMatch) }
            val mergedGameMvps = mergeGameMvps(officialGameMvps, opggGameMvps)
            val mergedVotes = if (awards.votes.isNotEmpty()) {
                awards.votes.map { it.withDisplaySource(enrichedMatch) }
            } else {
                opgg.panels.map { it.withDisplaySource(enrichedMatch) }
            }

            val mergedRawDrafts = mergeDrafts(draftResult.drafts, opgg.drafts)
            val decoratedDrafts = runCatching { ChampionCatalog.decorateDrafts(mergedRawDrafts) }
                .getOrDefault(mergedRawDrafts)
                .map { it.withDisplaySource(enrichedMatch) }

            val finalState = base.copy(
                match = enrichedMatch,
                loading = false,
                series = resolved,
                seriesMvp = awards.seriesMvp?.withDisplaySource(enrichedMatch),
                gameMvps = mergedGameMvps,
                votes = mergedVotes,
                drafts = decoratedDrafts,
                status = when {
                    resolved != null -> "已加载 ${resolved.games.size} 局终局数据 · ${awards.status} · ${draftResult.status} · ${opgg.status}"
                    result.isFailure -> "比赛详情同步失败 · ${opgg.status}"
                    else -> "${resolver.status.value} · ${opgg.status}"
                },
                errorMessage = result.exceptionOrNull()?.message,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            cache[key.stableId] = finalState
            _state.value = finalState
        }
    }

    fun refresh() {
        _state.value.match?.let { open(it, forceRefresh = true) }
    }

    fun close() {
        loadJob?.cancel()
        _state.value = MatchDetailState()
    }

    private fun currentLiveFor(match: ScheduledEsportsMatch): LiveSnapshot? {
        val current = MatchSessionStore.scheduleCenter.value.currentMatch ?: return null
        val same = current.matchId == match.matchId ||
            (current.eventId.isNotBlank() && current.eventId == match.eventId)
        return MatchSessionStore.live.value.takeIf { same && it.game > 0 }
    }

    private suspend fun enrichTeamImages(
        match: ScheduledEsportsMatch,
        extraImages: Map<String, String> = emptyMap()
    ): ScheduledEsportsMatch {
        val teams = match.teams.map { team ->
            if (team.imageUrl.isNotBlank()) return@map team
            val extra = listOf(team.id, team.code, team.name, team.slug)
                .asSequence()
                .map(::teamToken)
                .firstNotNullOfOrNull { key -> extraImages[key]?.takeIf { it.isNotBlank() } }
            val image = extra ?: runCatching { teamAssetProvider.resolve(team) }.getOrDefault("")
            if (image.isBlank()) team else team.copy(imageUrl = image)
        }
        return match.copy(teams = teams)
    }

    private fun mergeGameMvps(
        primary: List<OfficialMvpRecord>,
        fallback: List<OfficialMvpRecord>
    ): List<OfficialMvpRecord> {
        val byGame = linkedMapOf<Int, OfficialMvpRecord>()
        primary.filter { (it.game ?: 0) > 0 }.forEach { byGame[it.game!!] = it }
        fallback.filter { (it.game ?: 0) > 0 }.forEach { record -> byGame.putIfAbsent(record.game!!, record) }
        return byGame.values.sortedBy { it.game }
    }

    private fun mergeDrafts(
        primary: List<DraftPickRecord>,
        fallback: List<DraftPickRecord>
    ): List<DraftPickRecord> {
        val primaryByGame = primary.associateBy { it.game }
        val fallbackByGame = fallback.associateBy { it.game }
        return (primaryByGame.keys + fallbackByGame.keys).distinct().sorted().mapNotNull { game ->
            val official = primaryByGame[game]
            val thirdParty = fallbackByGame[game]
            when {
                official != null && (official.blueBans.isNotEmpty() || official.redBans.isNotEmpty()) -> official
                thirdParty != null && (thirdParty.blueBans.isNotEmpty() || thirdParty.redBans.isNotEmpty()) -> thirdParty
                official != null -> official
                else -> thirdParty
            }
        }
    }

    private fun CompletedSeriesSnapshot.normalizeDetailRoles(): CompletedSeriesSnapshot = copy(
        games = games.map { game ->
            game.copy(
                bluePlayers = game.bluePlayers.map { player -> player.copy(role = normalizeRole(player.role)) },
                redPlayers = game.redPlayers.map { player -> player.copy(role = normalizeRole(player.role)) }
            )
        }
    )

    /** Keep the series score aligned to the schedule card's left/right team order. */
    private fun alignSeriesToMatch(
        series: CompletedSeriesSnapshot,
        match: ScheduledEsportsMatch
    ): CompletedSeriesSnapshot {
        val left = match.teams.getOrNull(0) ?: return series
        val right = match.teams.getOrNull(1) ?: return series
        val direct = labelMatchesTeam(series.teamA, left) && labelMatchesTeam(series.teamB, right)
        val swapped = labelMatchesTeam(series.teamA, right) && labelMatchesTeam(series.teamB, left)
        val leftLabel = left.code.ifBlank { left.name }
        val rightLabel = right.code.ifBlank { right.name }
        return when {
            swapped -> series.copy(
                teamA = leftLabel,
                teamB = rightLabel,
                scoreA = series.scoreB,
                scoreB = series.scoreA
            )
            direct -> series.copy(teamA = leftLabel, teamB = rightLabel)
            else -> series
        }
    }

    private fun labelMatchesTeam(label: String, team: EsportsTeamRef): Boolean {
        val a = teamToken(label)
        if (a.isBlank()) return false
        val candidates = listOf(team.id, team.code, team.name, team.slug).map(::teamToken).filter { it.isNotBlank() }
        return candidates.any { b -> a == b || (a.length >= 3 && b.contains(a)) || (b.length >= 3 && a.contains(b)) }
    }

    private fun normalizeRole(raw: String): String {
        val key = raw.trim().uppercase().replace(Regex("[^A-Z0-9]+"), "")
        return when (key) {
            "TOP", "TOPLANE", "1" -> "TOP"
            "JUN", "JUG", "JGL", "JUNG", "JUNGLE", "JUNGLER", "JUNGLEPOSITION", "2" -> "JUG"
            "MID", "MIDDLE", "MIDLANE", "3" -> "MID"
            "BOT", "BOTTOM", "ADC", "AD", "BOTTOMLANE", "4" -> "BOT"
            "SUP", "SUPPORT", "SUPP", "5" -> "SUP"
            else -> raw.uppercase().ifBlank { "—" }
        }
    }

    private fun OfficialMvpRecord.withDisplaySource(match: ScheduledEsportsMatch): OfficialMvpRecord =
        copy(source = displaySource(source, match))

    private fun OfficialVoteRecord.withDisplaySource(match: ScheduledEsportsMatch): OfficialVoteRecord =
        copy(source = displaySource(source, match))

    private fun DraftPickRecord.withDisplaySource(match: ScheduledEsportsMatch): DraftPickRecord =
        copy(source = displaySource(source, match))

    private fun displaySource(raw: String, match: ScheduledEsportsMatch): String {
        val source = raw.trim()
        val lower = source.lowercase()
        if (source.isBlank()) return "来源未标注"
        if (lower.contains("op.gg") || lower.contains("opgg")) return source.ensurePrefix("OP.GG")
        if (lower.contains("tjstats") || lower.contains("lpl.qq.com")) return source.ensurePrefix("LPL 官方")
        if (lower.contains("lolesports") || lower.contains("riot")) {
            val league = match.league.lowercase()
            return when {
                league.contains("world") || league.contains("全球总决赛") -> source.ensurePrefix("全球总决赛官方")
                league.contains("lck") -> source.ensurePrefix("LCK 官方")
                league.contains("lpl") -> source.ensurePrefix("LPL 官方")
                else -> source.ensurePrefix("Riot 官方")
            }
        }
        return source
    }

    private fun String.ensurePrefix(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) this else "$prefix · $this"

    private fun teamToken(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
}
