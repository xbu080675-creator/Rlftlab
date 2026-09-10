package com.riftlab.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * dev.70 qualification model.
 *
 * Qualification is deliberately not a synonym for a tournament standings table. League/table
 * points describe one tournament stage; Championship Points are annual qualification currency;
 * route nodes describe how a team can reach a target event. Every route keeps evidence/source so
 * a RiftLab-derived possibility can never be rendered as an official confirmed berth.
 */
enum class QualificationTeamState(val label: String) {
    LOCKED("已锁定"),
    CONTENDING("仍可争夺"),
    ELIMINATED("已淘汰"),
    PENDING("待确认")
}

enum class QualificationEvidence(val label: String) {
    OFFICIAL("官方确认"),
    PROVIDER("Provider 数据"),
    DERIVED("RiftLab 推导"),
    PENDING("等待可信来源")
}

enum class QualificationNodeState(val label: String) {
    CONFIRMED("已确认"),
    AVAILABLE("可继续争夺"),
    BLOCKED("已关闭"),
    PENDING("待确认")
}

data class QualificationRouteNode(
    val id: String,
    val label: String,
    val detail: String,
    val state: QualificationNodeState,
    val source: String,
    val evidence: QualificationEvidence
)

data class QualificationRuleRecord(
    val title: String,
    val detail: String,
    val source: String,
    val evidence: QualificationEvidence
)

data class TeamQualificationRoute(
    val tournamentId: String,
    val teamId: String,
    val teamCode: String,
    val targetEvent: String,
    val status: QualificationTeamState,
    val championshipPoints: Int? = null,
    val leagueStandingPoints: Int? = null,
    val annualPointBreakdown: String = "",
    val route: List<QualificationRouteNode> = emptyList(),
    val source: String = "",
    val evidence: QualificationEvidence = QualificationEvidence.PENDING,
    val updatedThrough: String = ""
)

data class QualificationTournamentSnapshot(
    val tournamentId: String,
    val title: String,
    val targetEvent: String,
    val routes: List<TeamQualificationRoute> = emptyList(),
    val rules: List<QualificationRuleRecord> = emptyList(),
    val sourceSummary: String = "",
    val note: String = ""
)

data class QualificationCenterState(
    val snapshotsByTournamentId: Map<String, QualificationTournamentSnapshot> = emptyMap(),
    val selectedTournamentId: String = "",
    val selectedTeamCode: String = "",
    val lastRefreshEpochMs: Long = 0L,
    val statusMessage: String = "资格路径中心尚未同步"
) {
    val selected: QualificationTournamentSnapshot?
        get() = snapshotsByTournamentId[selectedTournamentId]

    val selectedRoute: TeamQualificationRoute?
        get() = selected?.routes?.firstOrNull { it.teamCode.equals(selectedTeamCode, ignoreCase = true) }
            ?: selected?.routes?.firstOrNull()
}

object QualificationCenterStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var manualTeamCode = ""

    private val _state = MutableStateFlow(QualificationCenterState())
    val state: StateFlow<QualificationCenterState> = _state.asStateFlow()

    fun ensureRunning() {
        if (job?.isActive == true) return
        TournamentEditionArchiveStore.ensureRunning()
        StandingsCenterStore.ensureRunning()

        job = scope.launch {
            combine(TournamentEditionArchiveStore.state, StandingsCenterStore.state) { archive, standings ->
                rebuild(archive, standings)
            }.collect { next ->
                _state.value = next
            }
        }
    }

    fun selectTeam(teamCode: String) {
        val normalized = teamCode.trim().uppercase()
        val selected = _state.value.selected ?: return
        if (selected.routes.none { it.teamCode.equals(normalized, ignoreCase = true) }) return
        manualTeamCode = normalized
        _state.value = _state.value.copy(selectedTeamCode = normalized)
    }

    fun comprehensivePathsForTournament(
        state: QualificationCenterState,
        tournamentId: String
    ): List<ComprehensiveQualificationPath> {
        if (tournamentId.isBlank()) return emptyList()
        return state.snapshotsByTournamentId[tournamentId]?.routes.orEmpty().map { route ->
            ComprehensiveQualificationPath(
                tournamentId = route.tournamentId,
                teamId = route.teamId,
                targetEvent = route.targetEvent,
                status = route.status.name,
                path = route.route.map { it.label },
                source = route.source,
                verified = route.evidence == QualificationEvidence.OFFICIAL,
                teamCode = route.teamCode,
                championshipPoints = route.championshipPoints,
                leagueStandingPoints = route.leagueStandingPoints,
                evidence = route.evidence.name
            )
        }
    }

    fun routeForTeam(teamCode: String): TeamQualificationRoute? =
        _state.value.selected?.routes?.firstOrNull { it.teamCode.equals(teamCode.trim(), ignoreCase = true) }

    private fun rebuild(
        archive: TournamentEditionArchiveState,
        standingsState: StandingsCenterState
    ): QualificationCenterState {
        val snapshots = archive.editions.associateNotNull { edition ->
            buildSnapshot(edition, archive, standingsState)?.let { edition.tournamentId to it }
        }
        val selectedTournamentId = archive.selectedTournamentId
            .takeIf { snapshots.containsKey(it) }
            ?: snapshots.keys.lastOrNull().orEmpty()
        val selected = snapshots[selectedTournamentId]
        val selectedTeam = when {
            manualTeamCode.isNotBlank() && selected?.routes?.any {
                it.teamCode.equals(manualTeamCode, ignoreCase = true)
            } == true -> manualTeamCode
            else -> selected?.routes?.firstOrNull()?.teamCode.orEmpty()
        }
        if (selectedTeam != manualTeamCode && manualTeamCode.isNotBlank()) manualTeamCode = ""

        return QualificationCenterState(
            snapshotsByTournamentId = snapshots,
            selectedTournamentId = selectedTournamentId,
            selectedTeamCode = selectedTeam,
            lastRefreshEpochMs = System.currentTimeMillis(),
            statusMessage = when {
                selected == null -> "当前届次尚无可信资格路径源 · 不根据排名猜测晋级"
                selected.routes.isEmpty() -> "${selected.title} · 资格规则/路径待可信来源"
                else -> "${selected.title} · ${selected.routes.size} 支队伍资格状态已进入独立路径模型"
            }
        )
    }

    private fun buildSnapshot(
        edition: TournamentEditionArchiveRecord,
        archive: TournamentEditionArchiveState,
        standingsState: StandingsCenterState
    ): QualificationTournamentSnapshot? {
        if (is2026LplWorldsContext(edition)) {
            val detail = archive.selected?.takeIf { it.edition.tournamentId == edition.tournamentId }
            val standings = detail?.standings
                ?: standingsState.standings?.takeIf { it.tournamentId == edition.tournamentId }
            val governance = detail?.governance ?: TournamentGovernanceProvider.resolve(
                tournament = edition.toTournamentRef(),
                competitionTitle = edition.displayName,
                matches = detail?.matchedSeries.orEmpty(),
                standings = standings
            )
            return build2026LplWorldsSnapshot(edition, standings, governance, detail?.matchedSeries.orEmpty())
        }

        // The model is global, but we intentionally do not fabricate routes for regions/events that
        // do not yet expose a trusted qualification source to RiftLab.
        if (edition.family in setOf("WORLDS", "MSI", "FIRST_STAND")) {
            return QualificationTournamentSnapshot(
                tournamentId = edition.tournamentId,
                title = "${edition.displayName} · 参赛资格来源",
                targetEvent = edition.displayName,
                routes = emptyList(),
                rules = emptyList(),
                sourceSummary = "等待赛事官方 / Riot / 已核实 Provider",
                note = "该届国际赛已建立资格档案位；参赛队的资格来源未拿到可信映射前保持待确认。"
            )
        }
        return null
    }

    private fun build2026LplWorldsSnapshot(
        edition: TournamentEditionArchiveRecord,
        standings: TournamentStandings?,
        governance: TournamentGovernanceSnapshot,
        matches: List<ScheduledEsportsMatch>
    ): QualificationTournamentSnapshot {
        val leaguePoints = standingsPointsByTeam(standings)
        val rules = governance.rules.items.map { rule ->
            QualificationRuleRecord(
                title = rule.title,
                detail = rule.detail,
                source = rule.source,
                evidence = if (rule.verified) QualificationEvidence.OFFICIAL else QualificationEvidence.DERIVED
            )
        }
        val verifiedSlots = governance.draw.slots.filter { it.verified }

        val routes = LplChampionshipPoints2026.rows.map { row ->
            val teamCode = row.teamCode.uppercase()
            val status = when (row.status) {
                LplWorldsStatus.WORLDS_LOCKED -> QualificationTeamState.LOCKED
                LplWorldsStatus.REGIONAL_LOCKED -> QualificationTeamState.CONTENDING
                LplWorldsStatus.ELIMINATED -> QualificationTeamState.ELIMINATED
            }
            val nodes = buildList {
                add(
                    QualificationRouteNode(
                        id = "$teamCode:annual-points",
                        label = "年度 Championship Points",
                        detail = "S1 ${row.split1} + S2 ${row.split2} + S3 保底 ${row.split3Floor} = ${row.total}",
                        state = QualificationNodeState.CONFIRMED,
                        source = LplChampionshipPoints2026.sourceLabel,
                        evidence = QualificationEvidence.OFFICIAL
                    )
                )

                when (row.status) {
                    LplWorldsStatus.WORLDS_LOCKED -> add(
                        QualificationRouteNode(
                            id = "$teamCode:worlds-locked",
                            label = "全球总决赛资格",
                            detail = "参赛资格已锁定；本快照不据此猜测最终种子顺位。",
                            state = QualificationNodeState.CONFIRMED,
                            source = LplChampionshipPoints2026.sourceLabel,
                            evidence = QualificationEvidence.OFFICIAL
                        )
                    )
                    LplWorldsStatus.REGIONAL_LOCKED -> {
                        add(
                            QualificationRouteNode(
                                id = "$teamCode:regional-locked",
                                label = "区域资格赛 / 后续资格路径",
                                detail = "资格竞争仍存续；具体对阵只在官方签位明确时写入。",
                                state = QualificationNodeState.AVAILABLE,
                                source = LplChampionshipPoints2026.sourceLabel,
                                evidence = QualificationEvidence.OFFICIAL
                            )
                        )
                        val slot = verifiedSlots.firstOrNull { drawContainsTeam(it, teamCode) }
                        if (slot != null) {
                            add(
                                QualificationRouteNode(
                                    id = "$teamCode:${slot.label}",
                                    label = "${slot.label} 官方签位",
                                    detail = "${slot.left} vs ${slot.right}${slot.scheduledAt.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}",
                                    state = QualificationNodeState.CONFIRMED,
                                    source = slot.source,
                                    evidence = QualificationEvidence.OFFICIAL
                                )
                            )
                        } else {
                            add(
                                QualificationRouteNode(
                                    id = "$teamCode:regional-slot-pending",
                                    label = "具体资格赛签位",
                                    detail = "当前已核实签位快照没有该队的确定槽位；保持待确认，不按积分排名自行分配 M1/M2/M3。",
                                    state = QualificationNodeState.PENDING,
                                    source = governance.draw.sourceSummary,
                                    evidence = QualificationEvidence.PENDING
                                )
                            )
                        }
                        add(
                            QualificationRouteNode(
                                id = "$teamCode:worlds-open",
                                label = "全球总决赛席位",
                                detail = "仍需后续正式赛果满足官方资格规则。",
                                state = QualificationNodeState.AVAILABLE,
                                source = governance.rules.sourceSummary,
                                evidence = if (rules.any { it.evidence == QualificationEvidence.OFFICIAL }) QualificationEvidence.OFFICIAL else QualificationEvidence.DERIVED
                            )
                        )
                    }
                    LplWorldsStatus.ELIMINATED -> add(
                        QualificationRouteNode(
                            id = "$teamCode:path-closed",
                            label = "全球总决赛资格路径",
                            detail = "该快照标记为无缘资格赛；路径关闭。",
                            state = QualificationNodeState.BLOCKED,
                            source = LplChampionshipPoints2026.sourceLabel,
                            evidence = QualificationEvidence.OFFICIAL
                        )
                    )
                }
            }

            TeamQualificationRoute(
                tournamentId = edition.tournamentId,
                teamId = resolveTeamId(teamCode, matches, standings),
                teamCode = teamCode,
                targetEvent = "2026 全球总决赛",
                status = status,
                championshipPoints = row.total,
                leagueStandingPoints = leaguePoints[teamCode],
                annualPointBreakdown = "${row.split1} + ${row.split2} + ${row.split3Floor}保底",
                route = nodes,
                source = LplChampionshipPoints2026.sourceLabel,
                evidence = QualificationEvidence.OFFICIAL,
                updatedThrough = LplChampionshipPoints2026.updatedThrough
            )
        }

        return QualificationTournamentSnapshot(
            tournamentId = edition.tournamentId,
            title = "2026 LPL · 全球总决赛资格路径",
            targetEvent = "2026 全球总决赛",
            routes = routes,
            rules = rules,
            sourceSummary = listOf(
                LplChampionshipPoints2026.sourceLabel,
                governance.rules.sourceSummary,
                governance.draw.sourceSummary
            ).filter { it.isNotBlank() }.distinct().joinToString(" + "),
            note = "Championship Points 与本届 Standings Points 分栏显示；S3 当前值为保底积分。官方确认与 RiftLab 推导必须分开标识。"
        )
    }

    private fun is2026LplWorldsContext(edition: TournamentEditionArchiveRecord): Boolean {
        val identity = "${edition.leagueSlug} ${edition.leagueName} ${edition.slug} ${edition.family} ${edition.stage}".lowercase()
        val lpl = identity.contains("lpl")
        val qualificationStage = edition.stage.equals("SPLIT_3", true) ||
            edition.stage.equals("REGIONAL_QUALIFIER", true) ||
            identity.contains("split_3") || identity.contains("split-3") ||
            identity.contains("regional") || identity.contains("资格")
        return edition.seasonYear == 2026 && lpl && qualificationStage
    }

    private fun standingsPointsByTeam(standings: TournamentStandings?): Map<String, Int> {
        if (standings == null) return emptyMap()
        val result = linkedMapOf<String, Int>()
        standings.stages.forEach { stage ->
            stage.sections.forEach { section ->
                section.rankings.forEach { row ->
                    val key = teamToken(row.team.code.ifBlank { row.team.name })
                    row.points?.let { result[key] = it }
                }
            }
        }
        return result
    }

    private fun resolveTeamId(
        teamCode: String,
        matches: List<ScheduledEsportsMatch>,
        standings: TournamentStandings?
    ): String {
        val wanted = teamToken(teamCode)
        matches.asSequence().flatMap { it.teams.asSequence() }
            .firstOrNull { teamToken(it.code.ifBlank { it.name }) == wanted }
            ?.let { team -> return team.id.ifBlank { stableLocalTeamId(teamCode) } }
        standings?.stages.orEmpty().asSequence()
            .flatMap { it.sections.asSequence() }
            .flatMap { it.rankings.asSequence() }
            .map { it.team }
            .firstOrNull { teamToken(it.code.ifBlank { it.name }) == wanted }
            ?.let { team -> return team.id.ifBlank { stableLocalTeamId(teamCode) } }
        return stableLocalTeamId(teamCode)
    }

    private fun drawContainsTeam(slot: TournamentDrawSlot, teamCode: String): Boolean {
        val wanted = teamToken(teamCode)
        return teamToken(slot.left) == wanted || teamToken(slot.right) == wanted
    }

    private fun TournamentEditionArchiveRecord.toTournamentRef(): EsportsTournamentRef = EsportsTournamentRef(
        id = tournamentId,
        slug = slug,
        startDate = startDate,
        endDate = endDate,
        leagueId = leagueId,
        leagueSlug = leagueSlug,
        leagueName = leagueName
    )

    private fun stableLocalTeamId(teamCode: String): String =
        "local-team:${teamCode.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}"

    private fun teamToken(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

    private inline fun <K, V> Iterable<K>.associateNotNull(transform: (K) -> Pair<String, V>?): Map<String, V> {
        val result = linkedMapOf<String, V>()
        for (element in this) transform(element)?.let { (key, value) -> result[key] = value }
        return result
    }
}
