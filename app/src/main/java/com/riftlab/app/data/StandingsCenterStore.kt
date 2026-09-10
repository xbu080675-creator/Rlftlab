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
import java.time.LocalDate

object StandingsCenterStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val source: StandingsDataSource = LolEsportsStandingsDataSource()
    private var refreshJob: Job? = null

    private val _state = MutableStateFlow(StandingsCenterState())
    val state: StateFlow<StandingsCenterState> = _state.asStateFlow()

    fun ensureRunning() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            while (isActive) {
                refreshAll()
                delay(5 * 60 * 1000L)
            }
        }
    }

    fun selectTournament(tournamentId: String) {
        val tournament = _state.value.tournaments.firstOrNull { it.id == tournamentId } ?: return
        if (_state.value.selectedTournament?.id == tournament.id && _state.value.standings?.tournamentId == tournament.id) {
            return
        }
        _state.value = _state.value.copy(
            selectedTournament = tournament,
            statusMessage = "正在同步 ${displayTournamentName(tournament)} 排名数据…"
        )
        scope.launch { refreshStandings(tournament) }
    }

    private suspend fun refreshAll() {
        try {
            val tournaments = source.fetchLeagueTournaments()
            val previousId = _state.value.selectedTournament?.id
            val selected = tournaments.firstOrNull { it.id == previousId }
                ?: chooseCurrentTournament(tournaments)

            _state.value = _state.value.copy(
                tournaments = tournaments,
                selectedTournament = selected,
                lastRefreshEpochMs = System.currentTimeMillis(),
                statusMessage = if (selected != null) {
                    "Standings · ${displayTournamentName(selected)}"
                } else {
                    "Standings · 暂无可用赛事"
                }
            )

            if (selected != null) {
                refreshStandings(selected)
            }
        } catch (t: Throwable) {
            _state.value = _state.value.copy(
                statusMessage = "排名数据暂时不可用：${t.message?.take(120) ?: t::class.java.simpleName}"
            )
        }
    }

    private suspend fun refreshStandings(tournament: EsportsTournamentRef) {
        try {
            val standings = source.fetchStandings(tournament.id)
            if (_state.value.selectedTournament?.id != tournament.id) return
            _state.value = _state.value.copy(
                standings = standings,
                lastRefreshEpochMs = System.currentTimeMillis(),
                statusMessage = when {
                    tournament.id.startsWith("rft-event:") && standings == null ->
                        "${displayTournamentName(tournament)} · 当前可信 Provider 未提供 Standings，保持未知"
                    else -> "Standings · ${displayTournamentName(tournament)}"
                }
            )
        } catch (t: Throwable) {
            if (_state.value.selectedTournament?.id != tournament.id) return
            _state.value = _state.value.copy(
                statusMessage = "排名数据暂时不可用：${t.message?.take(120) ?: t::class.java.simpleName}"
            )
        }
    }

    private fun chooseCurrentTournament(tournaments: List<EsportsTournamentRef>): EsportsTournamentRef? {
        val today = LocalDate.now()
        return tournaments.firstOrNull { tournament ->
            val start = parseDate(tournament.startDate)
            val end = parseDate(tournament.endDate)
            start != null && end != null && !today.isBefore(start) && !today.isAfter(end)
        } ?: tournaments
            .filter { parseDate(it.startDate)?.let { date -> !date.isAfter(today) } == true }
            .maxByOrNull { it.startDate }
            ?: tournaments.lastOrNull()
    }

    fun displayTournamentName(tournament: EsportsTournamentRef): String {
        val slug = tournament.slug.lowercase()
        val leagueSlug = tournament.leagueSlug.lowercase()
        val leagueName = tournament.leagueName.ifBlank { tournament.leagueSlug.uppercase() }.ifBlank { "LoL Esports" }
        val identity = "$leagueSlug $leagueName $slug".lowercase()
        val year = parseDate(tournament.startDate)?.year?.toString().orEmpty()

        return when {
            identity.contains("worlds") || identity.contains("world championship") -> "$year 全球总决赛"
            identity.contains("mid-season") || Regex("(^|[^a-z])msi([^a-z]|$)").containsMatchIn(identity) -> "$year 季中冠军赛"
            identity.contains("first stand") || identity.contains("first-stand") || identity.contains("first_stand") -> "$year First Stand"
            identity.contains("esports world cup") || Regex("(^|[^a-z])ewc([^a-z]|$)").containsMatchIn(identity) -> "$year Esports World Cup"
            Regex("(^|[^a-z])wsci([^a-z]|$)").containsMatchIn(identity) ->
                if (identity.contains("qualifier") || identity.contains("regional")) "$year WSCI 区域资格赛" else "$year WSCI"
            Regex("(^|[^a-z])wscl([^a-z]|$)").containsMatchIn(identity) ->
                if (identity.contains("qualifier") || identity.contains("regional")) "$year WSCL 区域资格赛" else "$year WSCL"
            slug.contains("split_1") -> "$year $leagueName 第一赛段"
            slug.contains("split_2") -> "$year $leagueName 第二赛段"
            slug.contains("split_3") -> "$year $leagueName 第三赛段"
            slug.contains("spring") -> "$year $leagueName 春季赛"
            slug.contains("summer") -> "$year $leagueName 夏季赛"
            slug.contains("regional") -> "$year $leagueName 区域资格赛"
            else -> tournament.slug.replace('_', ' ').ifBlank { "$year $leagueName" }
        }
    }

    fun containsDate(tournament: EsportsTournamentRef, date: LocalDate): Boolean {
        val start = parseDate(tournament.startDate) ?: return false
        val end = parseDate(tournament.endDate) ?: return false
        return !date.isBefore(start) && !date.isAfter(end)
    }

    private fun parseDate(value: String): LocalDate? = runCatching {
        LocalDate.parse(value.take(10))
    }.getOrNull()
}
