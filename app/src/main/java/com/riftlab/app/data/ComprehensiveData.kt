package com.riftlab.app.data

import java.time.Instant
import java.time.LocalDate

/**
 * dev.68 comprehensive-data contract.
 *
 * "Comprehensive" in RiftLab means graph continuity, not filling every field with a value.
 * Tournament -> Series -> Game -> Team -> Player -> Draft/Stats/Timeline must keep stable identity,
 * provenance and an explicit coverage state. Unknown data stays unknown.
 */
enum class ComprehensiveDataDomain(val label: String) {
    TOURNAMENT("赛事"),
    SCHEDULE("赛程"),
    TEAM("队伍"),
    PLAYER("选手"),
    ROSTER("阵容"),
    PREMATCH("赛前"),
    LIVE("赛中"),
    POSTMATCH("赛后"),
    STANDINGS("排名"),
    QUALIFICATION("晋级"),
    HISTORY("历史"),
    PROVENANCE("来源")
}

enum class DataCoverageState(val label: String) {
    COMPLETE("完整"),
    PARTIAL("部分"),
    PENDING("待同步"),
    SOURCE_ERROR("来源异常"),
    NOT_APPLICABLE("不适用")
}

enum class DataAuthority(val label: String) {
    OFFICIAL("官方确认"),
    PROVIDER("Provider 返回"),
    DERIVED("结构推导"),
    LOCAL_CACHE("本地缓存"),
    APK_SEED("APK Seed"),
    USER_INPUT("用户输入")
}

enum class DataFreshnessClass(val label: String, val targetMaxAgeMs: Long?) {
    STATIC("静态", null),
    DAILY("日级", 24L * 60 * 60 * 1000),
    HOURLY("小时级", 60L * 60 * 1000),
    MINUTES("分钟级", 5L * 60 * 1000),
    REALTIME("实时", 15L * 1000)
}

data class DataProvenance(
    val sourceId: String,
    val displayName: String,
    val authority: DataAuthority,
    val freshness: DataFreshnessClass,
    val observedAtEpochMs: Long = System.currentTimeMillis(),
    val sourceUpdatedAtEpochMs: Long = 0L,
    val sourceUri: String = "",
    val verified: Boolean = authority == DataAuthority.OFFICIAL
)

data class TournamentEditionIdentity(
    val tournamentId: String,
    val family: String,
    val leagueId: String,
    val leagueSlug: String,
    val seasonYear: Int?,
    val editionKey: String,
    val displayName: String,
    val stage: String = ""
)

data class ComprehensiveSeriesRecord(
    val seriesId: String,
    val eventId: String,
    val tournamentId: String,
    val leagueId: String,
    val leagueSlug: String,
    val stage: String,
    val startTimeIso: String,
    val state: String,
    val bestOf: Int,
    val teamIds: List<String>,
    val teamCodes: List<String>,
    val gameWins: List<Int>
)

data class ComprehensiveGameRecord(
    val gameId: String,
    val seriesId: String,
    val gameNumber: Int,
    val state: String,
    val elapsedSeconds: Int,
    val blueTeamId: String,
    val redTeamId: String,
    val blueGold: Int,
    val redGold: Int,
    val blueKills: Int,
    val redKills: Int,
    val blueTowers: Int,
    val redTowers: Int,
    val blueDragons: Int,
    val redDragons: Int,
    val blueBarons: Int,
    val redBarons: Int,
    val source: String
)

data class ComprehensiveTeamRecord(
    val teamId: String,
    val code: String,
    val name: String,
    val slug: String,
    val imageUrl: String,
    val leagueId: String,
    val leagueSlug: String
)

data class ComprehensivePlayerRecord(
    val playerId: String,
    val summonerName: String,
    val role: String,
    val teamId: String,
    val firstName: String = "",
    val lastName: String = "",
    val imageUrl: String = ""
)

data class ComprehensiveRosterMembership(
    val playerId: String,
    val teamId: String,
    val role: String,
    val starterStatus: String,
    val startDate: String = "",
    val endDate: String = "",
    val source: String
)

data class ComprehensivePlayerGameStats(
    val playerId: String,
    val teamId: String,
    val gameId: String,
    val role: String,
    val championId: String,
    val level: Int,
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    val creepScore: Int,
    val gold: Int,
    val damageToChampions: Long? = null,
    val damageTaken: Long? = null,
    val visionScore: Int? = null,
    val source: String
)

data class ComprehensiveStandingRecord(
    val tournamentId: String,
    val stageId: String,
    val stageName: String,
    val sectionName: String,
    val teamId: String,
    val ordinal: Int,
    val wins: Int,
    val losses: Int,
    val leaguePoints: Int? = null,
    val championshipPoints: Int? = null
)

data class ComprehensiveQualificationPath(
    val tournamentId: String,
    val teamId: String,
    val targetEvent: String,
    val status: String,
    val path: List<String>,
    val source: String,
    val verified: Boolean
)

data class ComprehensiveTimelineEvent(
    val gameId: String,
    val sequence: Long,
    val gameTimeSeconds: Int,
    val type: String,
    val teamId: String = "",
    val actorPlayerId: String = "",
    val targetPlayerId: String = "",
    val detail: String = "",
    val source: String,
    val verified: Boolean
)

data class ComprehensiveDataGraph(
    val tournament: TournamentEditionIdentity? = null,
    val series: ComprehensiveSeriesRecord? = null,
    val games: List<ComprehensiveGameRecord> = emptyList(),
    val teams: List<ComprehensiveTeamRecord> = emptyList(),
    val players: List<ComprehensivePlayerRecord> = emptyList(),
    val roster: List<ComprehensiveRosterMembership> = emptyList(),
    val playerGameStats: List<ComprehensivePlayerGameStats> = emptyList(),
    val standings: List<ComprehensiveStandingRecord> = emptyList(),
    val qualificationPaths: List<ComprehensiveQualificationPath> = emptyList(),
    val timeline: List<ComprehensiveTimelineEvent> = emptyList(),
    val provenance: List<DataProvenance> = emptyList(),
    val prematchAvailable: Boolean = false,
    val postmatchAvailable: Boolean = false,
    val historyAvailable: Boolean = false
)

data class DataCoverageCell(
    val domain: ComprehensiveDataDomain,
    val state: DataCoverageState,
    val availableFields: Int,
    val requiredFields: Int,
    val missing: List<String> = emptyList(),
    val sourceLabels: List<String> = emptyList(),
    val note: String = ""
)

data class ComprehensiveCoverageReport(
    val cells: List<DataCoverageCell>,
    val generatedAtEpochMs: Long = System.currentTimeMillis()
) {
    val completeDomains: Int get() = cells.count { it.state == DataCoverageState.COMPLETE }
    val partialDomains: Int get() = cells.count { it.state == DataCoverageState.PARTIAL }
    val pendingDomains: Int get() = cells.count { it.state == DataCoverageState.PENDING }

    val scorePercent: Int
        get() {
            val applicable = cells.filter { it.state != DataCoverageState.NOT_APPLICABLE }
            if (applicable.isEmpty()) return 0
            val points = applicable.sumOf {
                when (it.state) {
                    DataCoverageState.COMPLETE -> 100
                    DataCoverageState.PARTIAL -> 50
                    DataCoverageState.PENDING, DataCoverageState.SOURCE_ERROR, DataCoverageState.NOT_APPLICABLE -> 0
                }
            }
            return points / applicable.size
        }

    fun cell(domain: ComprehensiveDataDomain): DataCoverageCell? = cells.firstOrNull { it.domain == domain }
}

data class ComprehensiveDataSnapshot(
    val graph: ComprehensiveDataGraph = ComprehensiveDataGraph(),
    val coverage: ComprehensiveCoverageReport = ComprehensiveCoverageReport(emptyList()),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

object TournamentIdentityResolver {
    fun resolve(ref: EsportsTournamentRef): TournamentEditionIdentity {
        val year = parseYear(ref.startDate)
        val identity = listOf(ref.slug, ref.leagueSlug, ref.leagueName).joinToString(" ").lowercase()
        val family = when {
            identity.contains("worlds") || identity.contains("world championship") -> "WORLDS"
            identity.contains("mid-season") || Regex("(^|[^a-z])msi([^a-z]|$)").containsMatchIn(identity) -> "MSI"
            identity.contains("first stand") || identity.contains("first-stand") || identity.contains("first_stand") -> "FIRST_STAND"
            identity.contains("esports world cup") || Regex("(^|[^a-z])ewc([^a-z]|$)").containsMatchIn(identity) -> "EWC"
            identity.contains("demacia") -> "DEMACIA"
            identity.contains("emea masters") -> "EMEA_MASTERS"
            identity.contains("americas cup") -> "AMERICAS_CUP"
            identity.contains("wsci") -> "WSCI"
            identity.contains("wscl") -> "WSCL"
            else -> normalizeToken(ref.leagueSlug.ifBlank { ref.leagueName.ifBlank { "UNKNOWN" } })
        }
        val stage = when {
            identity.contains("split_1") || identity.contains("split-1") -> "SPLIT_1"
            identity.contains("split_2") || identity.contains("split-2") -> "SPLIT_2"
            identity.contains("split_3") || identity.contains("split-3") -> "SPLIT_3"
            identity.contains("spring") -> "SPRING"
            identity.contains("summer") -> "SUMMER"
            identity.contains("regional") || identity.contains("qualifier") -> "REGIONAL_QUALIFIER"
            identity.contains("playoff") -> "PLAYOFFS"
            else -> normalizeToken(ref.slug).takeIf { it.isNotBlank() }.orEmpty()
        }
        val editionKey = listOfNotNull(
            family,
            year?.toString(),
            normalizeToken(ref.slug).takeIf { it.isNotBlank() }
        ).joinToString(":")
        val displayName = buildString {
            if (year != null) append("$year ")
            append(
                when (family) {
                    "WORLDS" -> "全球总决赛"
                    "MSI" -> "季中冠军赛"
                    "FIRST_STAND" -> "First Stand"
                    "EWC" -> "Esports World Cup"
                    "WSCI" -> "WSCI"
                    "WSCL" -> "WSCL"
                    else -> ref.leagueName.ifBlank { ref.leagueSlug.uppercase() }.ifBlank { ref.slug }
                }
            )
        }.trim()
        return TournamentEditionIdentity(
            tournamentId = ref.id,
            family = family,
            leagueId = ref.leagueId,
            leagueSlug = ref.leagueSlug,
            seasonYear = year,
            editionKey = editionKey,
            displayName = displayName,
            stage = stage
        )
    }

    private fun parseYear(value: String): Int? = runCatching { LocalDate.parse(value.take(10)).year }.getOrNull()

    private fun normalizeToken(value: String): String = value.trim()
        .uppercase()
        .replace(Regex("[^A-Z0-9]+"), "_")
        .trim('_')
}

object ComprehensiveCoverageEngine {
    fun evaluate(
        graph: ComprehensiveDataGraph,
        sourceErrors: Set<ComprehensiveDataDomain> = emptySet()
    ): ComprehensiveCoverageReport {
        val cells = ComprehensiveDataDomain.entries.map { domain ->
            if (domain in sourceErrors) {
                DataCoverageCell(domain, DataCoverageState.SOURCE_ERROR, 0, 1, note = "上游数据源异常；保留上次已知事实，不用占位值补齐")
            } else {
                evaluateDomain(domain, graph)
            }
        }
        return ComprehensiveCoverageReport(cells)
    }

    private fun evaluateDomain(domain: ComprehensiveDataDomain, graph: ComprehensiveDataGraph): DataCoverageCell = when (domain) {
        ComprehensiveDataDomain.TOURNAMENT -> {
            val t = graph.tournament
            cell(domain, listOf(
                "stable identity" to (t?.tournamentId?.isNotBlank() == true && t.editionKey.isNotBlank()),
                "family" to (t?.family?.isNotBlank() == true),
                "season/year" to (t?.seasonYear != null)
            ))
        }
        ComprehensiveDataDomain.SCHEDULE -> {
            val s = graph.series
            cell(domain, listOf(
                "series id" to (s?.seriesId?.isNotBlank() == true),
                "start time" to (s?.startTimeIso?.isNotBlank() == true),
                "best-of" to ((s?.bestOf ?: 0) > 0),
                "two teams" to ((s?.teamIds?.size ?: 0) >= 2)
            ))
        }
        ComprehensiveDataDomain.TEAM -> cell(domain, listOf(
            "two teams" to (graph.teams.size >= 2),
            "stable team ids" to (graph.teams.take(2).size >= 2 && graph.teams.take(2).all { it.teamId.isNotBlank() }),
            "display identity" to (graph.teams.take(2).size >= 2 && graph.teams.take(2).all { it.code.isNotBlank() || it.name.isNotBlank() })
        ))
        ComprehensiveDataDomain.PLAYER -> cell(domain, listOf(
            "players" to (graph.players.isNotEmpty()),
            "ten-player coverage" to (graph.players.map { it.playerId }.distinct().size >= 10),
            "roles" to (graph.players.isNotEmpty() && graph.players.all { it.role.isNotBlank() })
        ))
        ComprehensiveDataDomain.ROSTER -> cell(domain, listOf(
            "roster memberships" to (graph.roster.isNotEmpty()),
            "two five-player sides" to (graph.roster.map { it.playerId }.distinct().size >= 10),
            "source attached" to (graph.roster.isNotEmpty() && graph.roster.all { it.source.isNotBlank() })
        ))
        ComprehensiveDataDomain.PREMATCH -> cell(domain, listOf(
            "prematch record" to graph.prematchAvailable,
            "series linkage" to (graph.series != null),
            "roster linkage" to (graph.roster.isNotEmpty())
        ))
        ComprehensiveDataDomain.LIVE -> {
            val live = graph.games.lastOrNull { it.state.equals("LIVE", true) }
            cell(domain, listOf(
                "live game" to (live != null),
                "game id" to (live?.gameId?.isNotBlank() == true),
                "team economy" to ((live?.blueGold ?: 0) > 0 || (live?.redGold ?: 0) > 0),
                "player snapshots" to graph.playerGameStats.any { it.gameId == live?.gameId }
            ), pendingWhenEmpty = true)
        }
        ComprehensiveDataDomain.POSTMATCH -> cell(domain, listOf(
            "postmatch record" to graph.postmatchAvailable,
            "completed game" to graph.games.any { it.state.equals("COMPLETED", true) },
            "player stats" to graph.playerGameStats.isNotEmpty(),
            "timeline" to graph.timeline.isNotEmpty()
        ), pendingWhenEmpty = true)
        ComprehensiveDataDomain.STANDINGS -> cell(domain, listOf(
            "standings rows" to graph.standings.isNotEmpty(),
            "team linkage" to (graph.standings.isNotEmpty() && graph.standings.all { it.teamId.isNotBlank() }),
            "stage linkage" to (graph.standings.isNotEmpty() && graph.standings.all { it.stageId.isNotBlank() || it.stageName.isNotBlank() })
        ), pendingWhenEmpty = true)
        ComprehensiveDataDomain.QUALIFICATION -> cell(domain, listOf(
            "qualification paths" to graph.qualificationPaths.isNotEmpty(),
            "team linkage" to (graph.qualificationPaths.isNotEmpty() && graph.qualificationPaths.all { it.teamId.isNotBlank() }),
            "verified/derived label" to (graph.qualificationPaths.isNotEmpty() && graph.qualificationPaths.all { it.source.isNotBlank() })
        ), pendingWhenEmpty = true)
        ComprehensiveDataDomain.HISTORY -> cell(domain, listOf(
            "historical archive" to graph.historyAvailable,
            "historical games" to graph.games.any { it.state.equals("COMPLETED", true) },
            "stable identities" to (graph.tournament?.editionKey?.isNotBlank() == true)
        ), pendingWhenEmpty = true)
        ComprehensiveDataDomain.PROVENANCE -> cell(domain, listOf(
            "source stamps" to graph.provenance.isNotEmpty(),
            "authority labels" to (graph.provenance.isNotEmpty() && graph.provenance.all { it.displayName.isNotBlank() }),
            "freshness policy" to (graph.provenance.isNotEmpty() && graph.provenance.all { it.freshness.label.isNotBlank() })
        ))
    }

    private fun cell(
        domain: ComprehensiveDataDomain,
        checks: List<Pair<String, Boolean>>,
        pendingWhenEmpty: Boolean = false
    ): DataCoverageCell {
        val available = checks.count { it.second }
        val missing = checks.filterNot { it.second }.map { it.first }
        val state = when {
            available == checks.size && checks.isNotEmpty() -> DataCoverageState.COMPLETE
            available == 0 && pendingWhenEmpty -> DataCoverageState.PENDING
            available == 0 -> DataCoverageState.PENDING
            else -> DataCoverageState.PARTIAL
        }
        return DataCoverageCell(
            domain = domain,
            state = state,
            availableFields = available,
            requiredFields = checks.size,
            missing = missing
        )
    }
}

object ComprehensiveDataAssembler {
    fun fromExisting(
        scheduled: ScheduledEsportsMatch?,
        tournament: EsportsTournamentRef?,
        prematch: PreMatchInfo?,
        live: LiveSnapshot?,
        completed: LiveSnapshot?,
        standings: TournamentStandings?,
        sourceErrors: Set<ComprehensiveDataDomain> = emptySet()
    ): ComprehensiveDataSnapshot {
        val tournamentIdentity = tournament?.let(TournamentIdentityResolver::resolve)
        val teams = scheduled?.teams.orEmpty().map { team ->
            ComprehensiveTeamRecord(
                teamId = team.id.ifBlank { stableLocalTeamId(team) },
                code = team.code,
                name = team.name,
                slug = team.slug,
                imageUrl = team.imageUrl,
                leagueId = scheduled?.leagueId.orEmpty(),
                leagueSlug = scheduled?.leagueSlug.orEmpty()
            )
        }
        val series = scheduled?.let { match ->
            ComprehensiveSeriesRecord(
                seriesId = match.matchId.ifBlank { match.eventId },
                eventId = match.eventId,
                tournamentId = tournamentIdentity?.tournamentId.orEmpty(),
                leagueId = match.leagueId,
                leagueSlug = match.leagueSlug,
                stage = match.blockName,
                startTimeIso = match.startTimeIso,
                state = match.state,
                bestOf = match.bestOf,
                teamIds = match.teams.map { it.id.ifBlank { stableLocalTeamId(it) } },
                teamCodes = match.teams.map { it.code.ifBlank { it.name } },
                gameWins = match.teams.map { it.gameWins }
            )
        }

        val roster = buildRoster(prematch, teams)
        val players = roster.distinctBy { it.playerId }.map { member ->
            ComprehensivePlayerRecord(
                playerId = member.playerId,
                summonerName = member.playerId.substringAfterLast(':'),
                role = member.role,
                teamId = member.teamId
            )
        }

        val liveGame = live?.takeIf { it.game > 0 || it.gameId.isNotBlank() }?.let {
            toGameRecord(it, series, teams, "LIVE")
        }
        val completedGame = completed?.let { toGameRecord(it, series, teams, "COMPLETED") }
        val games = listOfNotNull(completedGame, liveGame).distinctBy { listOf(it.gameId, it.gameNumber, it.state) }
        val stats = buildList {
            if (liveGame != null && live != null) addAll(toPlayerStats(live, liveGame, teams))
            if (completedGame != null && completed != null) addAll(toPlayerStats(completed, completedGame, teams))
        }.distinctBy { listOf(it.gameId, it.playerId) }
        val standingRows = flattenStandings(standings)
        val externalSchedule = scheduled?.let {
            it.eventId.startsWith("provider:") || it.leagueId.startsWith("rft-event:")
        } == true
        val externalTournament = tournament?.id?.startsWith("rft-event:") == true

        val provenance = buildList {
            if (scheduled != null) {
                if (externalSchedule) {
                    add(DataProvenance("international-event-mirror", "RFT.gg public event mirror", DataAuthority.PROVIDER, DataFreshnessClass.HOURLY))
                } else {
                    add(DataProvenance("riot-schedule", "Riot LoL Esports Schedule", DataAuthority.OFFICIAL, DataFreshnessClass.MINUTES))
                }
            }
            if (tournament != null || standings != null) {
                if (externalTournament) {
                    add(DataProvenance("international-tournament-mirror", "International Tournament Mirror", DataAuthority.PROVIDER, DataFreshnessClass.HOURLY))
                } else {
                    add(DataProvenance("riot-standings", "Riot Tournament / Standings", DataAuthority.OFFICIAL, DataFreshnessClass.MINUTES))
                }
            }
            if (prematch != null && prematch.blueRoster.isNotEmpty().or(prematch.redRoster.isNotEmpty())) {
                add(DataProvenance("team-data", "Riot/Cito normalized roster", DataAuthority.PROVIDER, DataFreshnessClass.HOURLY))
            }
            if (liveGame != null && live != null) {
                add(DataProvenance("live-router", live.source.ifBlank { "Live Provider Router" }, DataAuthority.PROVIDER, DataFreshnessClass.REALTIME))
            }
            if (completedGame != null && completed != null) {
                add(DataProvenance("completed-archive", completed.source.ifBlank { "Completed Game Archive" }, DataAuthority.LOCAL_CACHE, DataFreshnessClass.STATIC))
            }
        }.distinctBy { it.sourceId + ":" + it.displayName }

        val graph = ComprehensiveDataGraph(
            tournament = tournamentIdentity,
            series = series,
            games = games,
            teams = teams,
            players = players,
            roster = roster,
            playerGameStats = stats,
            standings = standingRows,
            provenance = provenance,
            prematchAvailable = scheduled != null && prematch != null,
            postmatchAvailable = completedGame != null,
            historyAvailable = completedGame != null
        )
        return ComprehensiveDataSnapshot(
            graph = graph,
            coverage = ComprehensiveCoverageEngine.evaluate(graph, sourceErrors)
        )
    }

    private fun buildRoster(
        prematch: PreMatchInfo?,
        teams: List<ComprehensiveTeamRecord>
    ): List<ComprehensiveRosterMembership> {
        if (prematch == null) return emptyList()
        fun side(cards: List<PlayerCard>, team: ComprehensiveTeamRecord?, side: String) = cards.map { card ->
            val rawId = card.id.trim()
            val playerId = if (rawId.isNotBlank()) rawId else "derived:$side:${card.role}"
            ComprehensiveRosterMembership(
                playerId = playerId,
                teamId = team?.teamId.orEmpty(),
                role = card.role,
                starterStatus = "STARTER_CANDIDATE",
                source = "Riot/Cito team data / normalized roster"
            )
        }
        return side(prematch.blueRoster, teams.getOrNull(0), "blue") +
            side(prematch.redRoster, teams.getOrNull(1), "red")
    }

    private fun toGameRecord(
        source: LiveSnapshot,
        series: ComprehensiveSeriesRecord?,
        teams: List<ComprehensiveTeamRecord>,
        state: String
    ): ComprehensiveGameRecord {
        val blueId = resolveTeamId(source.blue, teams.getOrNull(0), teams)
        val redId = resolveTeamId(source.red, teams.getOrNull(1), teams)
        val gameId = source.gameId.ifBlank {
            "${series?.seriesId.orEmpty()}:G${source.game.coerceAtLeast(1)}"
        }
        return ComprehensiveGameRecord(
            gameId = gameId,
            seriesId = series?.seriesId.orEmpty(),
            gameNumber = source.game,
            state = state,
            elapsedSeconds = source.elapsedSeconds,
            blueTeamId = blueId,
            redTeamId = redId,
            blueGold = source.blueGold,
            redGold = source.redGold,
            blueKills = source.blueKills,
            redKills = source.redKills,
            blueTowers = source.blueTowers,
            redTowers = source.redTowers,
            blueDragons = source.blueDragons,
            redDragons = source.redDragons,
            blueBarons = source.blueBarons,
            redBarons = source.redBarons,
            source = source.source
        )
    }

    private fun toPlayerStats(
        source: LiveSnapshot,
        game: ComprehensiveGameRecord,
        teams: List<ComprehensiveTeamRecord>
    ): List<ComprehensivePlayerGameStats> {
        fun side(players: List<LivePlayerSnapshot>, teamId: String) = players.map { p ->
            ComprehensivePlayerGameStats(
                playerId = p.summonerName.ifBlank { "participant:${p.participantId}" },
                teamId = teamId,
                gameId = game.gameId,
                role = p.role,
                championId = p.championId,
                level = p.level,
                kills = p.kills,
                deaths = p.deaths,
                assists = p.assists,
                creepScore = p.creepScore,
                gold = p.gold,
                source = source.source
            )
        }
        return side(source.bluePlayers, game.blueTeamId.ifBlank { teams.getOrNull(0)?.teamId.orEmpty() }) +
            side(source.redPlayers, game.redTeamId.ifBlank { teams.getOrNull(1)?.teamId.orEmpty() })
    }

    private fun flattenStandings(standings: TournamentStandings?): List<ComprehensiveStandingRecord> {
        if (standings == null) return emptyList()
        return standings.stages.flatMap { stage ->
            stage.sections.flatMap { section ->
                section.rankings.map { ranking ->
                    ComprehensiveStandingRecord(
                        tournamentId = standings.tournamentId,
                        stageId = stage.id,
                        stageName = stage.name,
                        sectionName = section.name,
                        teamId = ranking.team.id.ifBlank { stableLocalTeamId(ranking.team) },
                        ordinal = ranking.ordinal,
                        wins = ranking.wins,
                        losses = ranking.losses,
                        leaguePoints = ranking.points,
                        championshipPoints = null
                    )
                }
            }
        }
    }

    private fun resolveTeamId(label: String, fallback: ComprehensiveTeamRecord?, teams: List<ComprehensiveTeamRecord>): String {
        val normalized = label.trim().lowercase()
        return teams.firstOrNull {
            normalized.isNotBlank() && (it.code.equals(label, true) || it.name.equals(label, true) || it.slug.equals(label, true))
        }?.teamId ?: fallback?.teamId.orEmpty()
    }

    private fun stableLocalTeamId(team: EsportsTeamRef): String {
        val base = team.slug.ifBlank { team.code.ifBlank { team.name } }.trim().lowercase()
        return if (base.isBlank()) "" else "local-team:${base.replace(Regex("[^a-z0-9]+"), "-").trim('-')}"
    }
}
