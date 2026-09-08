package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

internal data class TeamDetailState(
    val team: EsportsTeamRef? = null,
    val details: EsportsTeamDetails? = null,
    val imageUrl: String = "",
    val starters: Set<String> = emptySet(),
    val loading: Boolean = false,
    val status: String = "选择一支战队查看详情",
    val lineupStatus: String = "首发阵容尚未识别",
    val staffStatus: String = "教练组尚未同步",
    val errorMessage: String? = null
)

/** Independent team-detail navigation state for the event center. */
internal object TeamDetailRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val source: TeamDataSource = LolEsportsTeamDataSource()
    private val assetProvider = RiotTeamAssetProvider()
    private val staffProvider = LiquipediaTeamStaffProvider()
    private val lineupProvider = OpggMatchSupplementProvider()
    private val cache = linkedMapOf<String, EsportsTeamDetails>()
    private val imageCache = linkedMapOf<String, String>()
    private val starterCache = linkedMapOf<String, Set<String>>()
    private var loadJob: Job? = null
    private var lastMatches: List<ScheduledEsportsMatch> = emptyList()

    private val _state = MutableStateFlow(TeamDetailState())
    val state: StateFlow<TeamDetailState> = _state.asStateFlow()

    fun open(
        team: EsportsTeamRef,
        matches: List<ScheduledEsportsMatch> = emptyList(),
        forceRefresh: Boolean = false
    ) {
        if (matches.isNotEmpty()) lastMatches = matches
        val lineupMatches = if (matches.isNotEmpty()) matches else lastMatches
        val key = TeamAssetCatalog.canonicalKey(team)
        val aliases = arrayOf(team.id, team.code, team.name, team.slug)
        val cached = cache[key]
        val cachedStarters = starterCache[key].orEmpty()
        val cachedImage = EsportsAssetCache.normalize(team.imageUrl)
            .ifBlank { EsportsAssetCache.normalize(imageCache[key].orEmpty()) }
            .ifBlank { EsportsAssetCache.team(*aliases) }
            .ifBlank { EsportsAssetCache.normalize(cached?.imageUrl.orEmpty()) }

        val hasCompletedContext = lineupMatches.any { match ->
            match.teams.any { sameTeam(it, team) } && isCompletedMatch(match) && matchEpoch(match) <= System.currentTimeMillis()
        }
        val cachedLineupReady = !hasCompletedContext || cachedStarters.size >= 5
        if (!forceRefresh && cached != null && cachedImage.isNotBlank() && cachedLineupReady) {
            val substitutes = substituteCount(cached.players, cachedStarters)
            _state.value = TeamDetailState(
                team = team,
                details = cached,
                imageUrl = cachedImage,
                starters = cachedStarters,
                status = rosterSummary(cached.players.size, cachedStarters.size, substitutes, cached.staff.size),
                lineupStatus = if (cachedStarters.size >= 5) "OP.GG · 最近正式比赛实际出场阵容" else "暂无已结束比赛用于判定当前首发",
                staffStatus = if (cached.staff.isNotEmpty()) "教练组数据已缓存" else "教练组尚未同步"
            )
            return
        }

        loadJob?.cancel()
        _state.value = TeamDetailState(
            team = team,
            imageUrl = cachedImage,
            loading = true,
            status = "正在同步 ${team.code.ifBlank { team.name }} 完整战队资料…"
        )
        loadJob = scope.launch {
            val lookup = team.id.ifBlank { team.slug }
            if (lookup.isBlank()) {
                val resolved = runCatching { assetProvider.resolve(team.copy(imageUrl = "")) }
                    .getOrDefault(cachedImage)
                    .let(EsportsAssetCache::normalize)
                if (resolved.isNotBlank()) EsportsAssetCache.putTeam(resolved, *aliases)
                _state.value = TeamDetailState(
                    team = team,
                    imageUrl = resolved,
                    loading = false,
                    status = "战队资料暂不可用",
                    errorMessage = "缺少 Riot team id/slug"
                )
                return@launch
            }

            val detailResult = runCatching { source.fetchTeam(lookup) }
            val baseDetails = detailResult.getOrNull()
            val effectiveTeam = baseDetails?.let { detail ->
                team.copy(
                    id = detail.id.ifBlank { team.id },
                    slug = detail.slug.ifBlank { team.slug },
                    code = detail.code.ifBlank { team.code },
                    name = detail.name.ifBlank { team.name },
                    imageUrl = detail.imageUrl.ifBlank { team.imageUrl }
                )
            } ?: team

            val image = cachedImage
                .ifBlank { EsportsAssetCache.normalize(baseDetails?.imageUrl.orEmpty()) }
                .ifBlank {
                    runCatching { assetProvider.resolve(effectiveTeam.copy(imageUrl = "")) }.getOrDefault("")
                        .let(EsportsAssetCache::normalize)
                }

            val now = System.currentTimeMillis()
            val latestMatch = lineupMatches
                .asSequence()
                .filter { match -> match.teams.any { sameTeam(it, effectiveTeam) } }
                .filter(::isCompletedMatch)
                .filter { matchEpoch(it) in 1..now }
                .maxByOrNull(::matchEpoch)

            val starters = if (latestMatch != null && baseDetails != null) {
                runCatching { lineupProvider.fetchLatestLineup(latestMatch, effectiveTeam) }
                    .getOrDefault(emptySet())
                    .takeIf { it.size >= 5 }
                    ?: cachedStarters
            } else cachedStarters

            val staffSupplement = if (baseDetails != null) {
                runCatching { staffProvider.fetch(effectiveTeam, baseDetails) }
                    .getOrElse { TeamStaffSupplement(status = "教练组数据源暂不可用") }
            } else TeamStaffSupplement(status = "教练组等待 Riot roster")

            val staff = staffSupplement.staff.ifEmpty { cached?.staff.orEmpty() }
            val details = baseDetails?.copy(staff = staff)
            if (details != null) cache[key] = details
            if (starters.size >= 5) starterCache[key] = starters
            if (image.isNotBlank()) {
                imageCache[key] = image
                EsportsAssetCache.putTeam(image, *aliases)
            }

            val substitutes = details?.let { substituteCount(it.players, starters) } ?: 0
            _state.value = TeamDetailState(
                team = effectiveTeam,
                details = details,
                imageUrl = image,
                starters = starters,
                loading = false,
                status = when {
                    details != null -> rosterSummary(details.players.size, starters.size, substitutes, details.staff.size)
                    detailResult.isFailure -> "战队资料同步失败"
                    else -> "Riot Teams 暂未返回战队详情"
                },
                lineupStatus = when {
                    starters.size >= 5 && latestMatch != null -> "OP.GG · ${latestMatch.blockName.ifBlank { "最近正式比赛" }} · 实际出场五人"
                    starters.size >= 5 -> "OP.GG · 已缓存最近正式比赛实际出场阵容"
                    latestMatch == null -> "暂无已结束比赛用于判定当前首发"
                    else -> "最近比赛阵容暂未匹配；不猜首发/替补"
                },
                staffStatus = if (staff.isNotEmpty() && staffSupplement.staff.isEmpty()) "教练组 · 本地缓存" else staffSupplement.status,
                errorMessage = detailResult.exceptionOrNull()?.message
            )
        }
    }

    fun refresh() {
        val snapshot = _state.value
        snapshot.team?.let { open(it, matches = lastMatches, forceRefresh = true) }
    }

    fun close() {
        loadJob?.cancel()
        _state.value = TeamDetailState()
    }

    private fun substituteCount(players: List<EsportsPlayerRef>, starters: Set<String>): Int {
        if (starters.size < 5) return 0
        val starterTokens = starters.map(::token).toSet()
        return players.count { token(it.summonerName) !in starterTokens }
    }

    private fun rosterSummary(players: Int, starters: Int, substitutes: Int, staff: Int): String = when {
        starters >= 5 -> "Riot Teams · $players 名选手 · 首发 $starters · 替补 $substitutes · 教练组 $staff"
        else -> "Riot Teams · $players 名现役选手 · 教练组 $staff"
    }

    private fun sameTeam(a: EsportsTeamRef, b: EsportsTeamRef): Boolean =
        (a.id.isNotBlank() && b.id.isNotBlank() && a.id == b.id) ||
            a.code.equals(b.code, ignoreCase = true) ||
            (a.slug.isNotBlank() && b.slug.isNotBlank() && a.slug.equals(b.slug, ignoreCase = true))

    private fun isCompletedMatch(match: ScheduledEsportsMatch): Boolean {
        val state = match.state.lowercase().replace("_", "").replace("-", "").replace(" ", "")
        if (state.contains("complete") || state == "finished") return true
        val requiredWins = if (match.bestOf > 0) match.bestOf / 2 + 1 else 1
        return (match.teams.maxOfOrNull { it.gameWins } ?: 0) >= requiredWins
    }

    private fun matchEpoch(match: ScheduledEsportsMatch): Long =
        runCatching { Instant.parse(match.startTimeIso).toEpochMilli() }.getOrDefault(0L)

    private fun token(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
}
