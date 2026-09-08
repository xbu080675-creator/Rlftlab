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
                    "Riot Standings · ${displayTournamentName(selected)}"
                } else {
                    "Riot Standings · 暂无可用赛事"
                }
            )

            if (selected != null && _state.value.standings?.tournamentId != selected.id) {
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
                statusMessage = "Riot Standings · ${displayTournamentName(tournament)}"
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
        val year = parseDate(tournament.startDate)?.year?.toString().orEmpty()
        return when {
            slug.contains("split_1") -> "$year LPL 第一赛段"
            slug.contains("split_2") -> "$year LPL 第二赛段"
            slug.contains("split_3") -> "$year LPL 第三赛段"
            slug.contains("spring") -> "$year LPL 春季赛"
            slug.contains("summer") -> "$year LPL 夏季赛"
            slug.contains("regional") -> "$year LPL 区域资格赛"
            else -> tournament.slug.replace('_', ' ').ifBlank { "$year LPL" }
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
