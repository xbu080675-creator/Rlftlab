package com.riftlab.app.data

/**
 * dev.79 depth bridge.
 *
 * The existing providers already hold more verified data than the first comprehensive graph exposed:
 * CompletedGameArchive owns every finished game in a Series and MatchTimelineStore owns the captured
 * event stream. This bridge joins those stores without inventing fields or promoting provider-only
 * labels to official facts.
 */
object ComprehensiveDataDepthEnricher {
    fun completedSeriesForMatch(
        series: CompletedSeriesSnapshot?,
        scheduled: ScheduledEsportsMatch?
    ): CompletedSeriesSnapshot? = series?.takeIf { candidate ->
        scheduled != null && MatchSessionStore.schedulePhase(scheduled) == ScheduleMatchPhase.COMPLETED &&
            samePair(candidate.teamA, candidate.teamB, scheduled)
    }

    fun completedGameForMatch(
        snapshot: LiveSnapshot?,
        scheduled: ScheduledEsportsMatch?
    ): LiveSnapshot? = snapshot?.takeIf { candidate ->
        scheduled != null && MatchSessionStore.schedulePhase(scheduled) == ScheduleMatchPhase.COMPLETED &&
            labelsBelongToMatch(candidate.blue, candidate.red, scheduled)
    }

    fun enrich(
        base: ComprehensiveDataSnapshot,
        scheduled: ScheduledEsportsMatch?,
        live: LiveSnapshot?,
        completedSeries: CompletedSeriesSnapshot?,
        timelines: Map<String, GameTimeline>,
        sourceErrors: Set<ComprehensiveDataDomain> = emptySet()
    ): ComprehensiveDataSnapshot {
        val graph = base.graph
        val alignedSeries = completedSeriesForMatch(completedSeries, scheduled)
        val completedSnapshots = alignedSeries?.games.orEmpty()
            .filter { it.game > 0 }
            .distinctBy { snapshotKey(it) }
            .sortedBy { it.game }
        val liveSnapshot = live?.takeIf { it.game > 0 && scheduled != null }

        val mergedGames = linkedMapOf<String, ComprehensiveGameRecord>()
        graph.games.forEach { game -> mergedGames[gameKey(game)] = game }
        completedSnapshots.forEach { snapshot ->
            val record = toGameRecord(snapshot, graph.series, graph.teams, "COMPLETED")
            mergedGames[gameKey(record)] = record
        }

        // A player identity/champion row with every numeric field at zero is useful for presentation,
        // but it is not verified player-stat coverage. Never let those identity-only rows inflate the
        // comprehensive score.
        val verifiedExistingStats = graph.playerGameStats.filter(::hasMeaningfulStats)
        val extraStats = completedSnapshots.flatMap { snapshot ->
            val game = mergedGames.values.firstOrNull { it.gameNumber == snapshot.game && it.state == "COMPLETED" }
                ?: return@flatMap emptyList()
            toPlayerStats(snapshot, game, graph.teams)
        }
        val mergedStats = (verifiedExistingStats + extraStats)
            .filter(::hasMeaningfulStats)
            .distinctBy { "${it.gameId}|${playerToken(it.playerId)}" }

        val timelineSnapshots = (completedSnapshots + listOfNotNull(liveSnapshot))
            .distinctBy(::snapshotKey)
        val timelineRecords = buildList {
            addAll(graph.timeline)
            timelineSnapshots.forEach { snapshot ->
                val timeline = MatchTimelineStore.find(snapshot, timelines) ?: return@forEach
                val gameId = snapshot.gameId.ifBlank {
                    mergedGames.values.firstOrNull { it.gameNumber == snapshot.game }?.gameId.orEmpty()
                }
                timeline.events.sortedBy { it.seconds }.forEachIndexed { index, event ->
                    add(
                        ComprehensiveTimelineEvent(
                            gameId = gameId,
                            sequence = event.seconds.toLong() * 1_000L + index,
                            gameTimeSeconds = event.seconds,
                            type = event.type.name,
                            teamId = resolveTeamId(event.team, graph.teams),
                            detail = listOf(event.title, event.detail)
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            source = event.source.ifBlank { "RiftLab MatchTimelineStore" },
                            verified = event.evidence != TimelineEventEvidence.DERIVED_WINDOW
                        )
                    )
                }
            }
        }.distinctBy { "${it.gameId}|${it.sequence}|${it.type}|${it.teamId}" }
            .sortedWith(compareBy<ComprehensiveTimelineEvent> { it.gameId }.thenBy { it.sequence })

        val provenance = buildList {
            addAll(graph.provenance)
            if (completedSnapshots.isNotEmpty()) {
                add(
                    DataProvenance(
                        sourceId = "completed-series-archive",
                        displayName = alignedSeries?.source?.ifBlank { "Completed Series Archive" }
                            ?: "Completed Series Archive",
                        authority = DataAuthority.LOCAL_CACHE,
                        freshness = DataFreshnessClass.STATIC
                    )
                )
            }
            if (timelineRecords.isNotEmpty()) {
                add(
                    DataProvenance(
                        sourceId = "match-timeline-store",
                        displayName = "RiftLab verified timeline archive",
                        authority = DataAuthority.LOCAL_CACHE,
                        freshness = DataFreshnessClass.STATIC
                    )
                )
            }
        }.distinctBy { it.sourceId + ":" + it.displayName }

        val enrichedGraph = graph.copy(
            games = mergedGames.values.sortedWith(compareBy<ComprehensiveGameRecord> { it.gameNumber }.thenBy { it.state }),
            playerGameStats = mergedStats,
            timeline = timelineRecords,
            provenance = provenance,
            postmatchAvailable = graph.postmatchAvailable || completedSnapshots.isNotEmpty(),
            historyAvailable = graph.historyAvailable || completedSnapshots.isNotEmpty() || timelineRecords.isNotEmpty()
        )
        return base.copy(
            graph = enrichedGraph,
            coverage = ComprehensiveCoverageEngine.evaluate(enrichedGraph, sourceErrors),
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    private fun toGameRecord(
        snapshot: LiveSnapshot,
        series: ComprehensiveSeriesRecord?,
        teams: List<ComprehensiveTeamRecord>,
        state: String
    ): ComprehensiveGameRecord = ComprehensiveGameRecord(
        gameId = snapshot.gameId.ifBlank { "${series?.seriesId.orEmpty()}:G${snapshot.game.coerceAtLeast(1)}" },
        seriesId = series?.seriesId.orEmpty(),
        gameNumber = snapshot.game,
        state = state,
        elapsedSeconds = snapshot.elapsedSeconds,
        blueTeamId = resolveTeamId(snapshot.blue, teams),
        redTeamId = resolveTeamId(snapshot.red, teams),
        blueGold = snapshot.blueGold,
        redGold = snapshot.redGold,
        blueKills = snapshot.blueKills,
        redKills = snapshot.redKills,
        blueTowers = snapshot.blueTowers,
        redTowers = snapshot.redTowers,
        blueDragons = snapshot.blueDragons,
        redDragons = snapshot.redDragons,
        blueBarons = snapshot.blueBarons,
        redBarons = snapshot.redBarons,
        source = snapshot.source
    )

    private fun toPlayerStats(
        snapshot: LiveSnapshot,
        game: ComprehensiveGameRecord,
        teams: List<ComprehensiveTeamRecord>
    ): List<ComprehensivePlayerGameStats> {
        fun side(players: List<LivePlayerSnapshot>, fallbackTeamId: String) = players
            .filter(::hasMeaningfulPlayerSnapshot)
            .map { player ->
                ComprehensivePlayerGameStats(
                    playerId = player.summonerName.ifBlank { "participant:${player.participantId}" },
                    teamId = player.teamId.ifBlank { fallbackTeamId },
                    gameId = game.gameId,
                    role = player.role,
                    championId = player.championId,
                    level = player.level,
                    kills = player.kills,
                    deaths = player.deaths,
                    assists = player.assists,
                    creepScore = player.creepScore,
                    gold = player.gold,
                    source = snapshot.source
                )
            }
        return side(snapshot.bluePlayers, game.blueTeamId.ifBlank { resolveTeamId(snapshot.blue, teams) }) +
            side(snapshot.redPlayers, game.redTeamId.ifBlank { resolveTeamId(snapshot.red, teams) })
    }

    private fun hasMeaningfulPlayerSnapshot(player: LivePlayerSnapshot): Boolean =
        player.level > 0 || player.gold > 0 || player.creepScore > 0 ||
            player.kills > 0 || player.deaths > 0 || player.assists > 0

    private fun hasMeaningfulStats(stats: ComprehensivePlayerGameStats): Boolean =
        stats.level > 0 || stats.gold > 0 || stats.creepScore > 0 ||
            stats.kills > 0 || stats.deaths > 0 || stats.assists > 0

    private fun gameKey(game: ComprehensiveGameRecord): String =
        game.gameId.ifBlank { "${game.seriesId}:G${game.gameNumber}" }

    private fun snapshotKey(snapshot: LiveSnapshot): String =
        snapshot.gameId.ifBlank { "G${snapshot.game}:${teamToken(snapshot.blue)}:${teamToken(snapshot.red)}" }

    private fun resolveTeamId(label: String, teams: List<ComprehensiveTeamRecord>): String {
        val token = teamToken(label)
        if (token.isBlank()) return ""
        return teams.firstOrNull { team ->
            listOf(team.teamId, team.code, team.name, team.slug)
                .map(::teamToken)
                .any { it.isNotBlank() && (it == token || (it.length >= 3 && token.contains(it)) || (token.length >= 3 && it.contains(token))) }
        }?.teamId.orEmpty()
    }

    private fun samePair(teamA: String, teamB: String, scheduled: ScheduledEsportsMatch): Boolean =
        labelsBelongToMatch(teamA, teamB, scheduled)

    private fun labelsBelongToMatch(left: String, right: String, scheduled: ScheduledEsportsMatch): Boolean {
        val scheduledTokens = scheduled.teams.take(2)
            .flatMap { team -> listOf(team.id, team.code, team.name, team.slug) }
            .map(::teamToken)
            .filter { it.isNotBlank() }
            .toSet()
        if (scheduledTokens.isEmpty()) return false
        fun belongs(label: String): Boolean {
            val token = teamToken(label)
            return token.isNotBlank() && scheduledTokens.any { candidate ->
                token == candidate || (token.length >= 3 && candidate.contains(token)) ||
                    (candidate.length >= 3 && token.contains(candidate))
            }
        }
        return belongs(left) && belongs(right) && teamToken(left) != teamToken(right)
    }

    private fun teamToken(value: String): String =
        value.trim().uppercase().replace(Regex("[^A-Z0-9]+"), "")

    // Do not ASCII-strip names here: CJK and other non-Latin player IDs must stay distinct.
    private fun playerToken(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")
}
