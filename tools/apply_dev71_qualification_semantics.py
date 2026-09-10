#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:160]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def append_once(path: str, marker: str, addition: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if addition.strip() in text:
        return
    if marker not in text:
        raise SystemExit(f"append marker not found in {path}: {marker!r}")
    p.write_text(text.replace(marker, marker + addition, 1), encoding="utf-8")


qualification = "app/src/main/java/com/riftlab/app/data/QualificationCenter.kt"
qualification_ui = "app/src/main/java/com/riftlab/app/ui/QualificationPathUi.kt"
archive_ui = "app/src/main/java/com/riftlab/app/ui/TournamentEditionArchiveUi.kt"
archive = "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt"
audit = "docs/RIFTLAB_DEV71_DATA_COVERAGE_AUDIT.md"

# Qualification mechanics are not synonymous with Championship Points.
replace_once(
    qualification,
    '''enum class QualificationNodeState(val label: String) {
    CONFIRMED("已确认"),
    AVAILABLE("可继续争夺"),
    BLOCKED("已关闭"),
    PENDING("待确认")
}
''',
    '''enum class QualificationNodeState(val label: String) {
    CONFIRMED("已确认"),
    AVAILABLE("可继续争夺"),
    BLOCKED("已关闭"),
    PENDING("待确认")
}

enum class QualificationMechanism(val label: String) {
    CHAMPIONSHIP_POINTS("Championship Points"),
    DIRECT_PLACEMENT("联赛 / 季后赛名次直通"),
    REGIONAL_QUALIFIER("区域资格赛 / 附加赛"),
    PARTICIPANT_ORIGIN("国际赛参赛资格来源"),
    MIXED("混合资格体系"),
    UNKNOWN("资格规则待确认")
}
'''
)

replace_once(
    qualification,
    '''data class QualificationTournamentSnapshot(
    val tournamentId: String,
    val title: String,
    val targetEvent: String,
    val routes: List<TeamQualificationRoute> = emptyList(),
    val rules: List<QualificationRuleRecord> = emptyList(),
    val sourceSummary: String = "",
    val note: String = ""
)''',
    '''data class QualificationTournamentSnapshot(
    val tournamentId: String,
    val title: String,
    val targetEvent: String,
    val routes: List<TeamQualificationRoute> = emptyList(),
    val rules: List<QualificationRuleRecord> = emptyList(),
    val sourceSummary: String = "",
    val note: String = "",
    val mechanism: QualificationMechanism = QualificationMechanism.UNKNOWN,
    val mechanismEvidence: QualificationEvidence = QualificationEvidence.PENDING,
    val mechanismDetail: String = ""
)'''
)

old_global = '''        // Global model, conservative data policy: establish the archive position for international
        // editions but do not infer participant qualification origins from the participant list.
        if (edition.family.uppercase() in setOf("WORLDS", "MSI", "FIRST_STAND")) {
            return QualificationTournamentSnapshot(
                tournamentId = edition.tournamentId,
                title = "${edition.displayName} · 参赛资格来源",
                targetEvent = edition.displayName,
                sourceSummary = "等待赛事官方 / Riot / 已核实 Provider",
                note = "该届国际赛已建立资格档案位；参赛队资格来源未拿到可信映射前保持待确认。"
            )
        }
        return null
'''
new_global = '''        // International target events use participant-origin semantics. Observing a team in the
        // tournament participant set proves participation, not the region/seed/path that qualified it.
        if (edition.family.uppercase() in setOf("WORLDS", "MSI", "FIRST_STAND")) {
            return buildInternationalParticipationSnapshot(edition)
        }

        // Regional editions can use points, direct placement, qualifiers or a mixed system. Until a
        // trusted rule source says which one applies, classify the mechanism as UNKNOWN instead of
        // presenting an empty Championship Points cell as if points were expected.
        return QualificationTournamentSnapshot(
            tournamentId = edition.tournamentId,
            title = "${edition.displayName} · 资格体系",
            targetEvent = "后续资格目标待确认",
            sourceSummary = "等待赛事官方 / Riot / 已核实 Provider",
            note = "当前尚未确认该届采用 Championship Points、直接名次、资格赛还是混合规则；RiftLab 不把未知机制误报为积分缺失。",
            mechanism = QualificationMechanism.UNKNOWN,
            mechanismEvidence = QualificationEvidence.PENDING,
            mechanismDetail = "资格机制未核实；不根据 Standings 或参赛名单自行推断。"
        )
'''
replace_once(qualification, old_global, new_global)

insert_marker = '''    private fun build2026LplWorldsSnapshot(
'''
international_builder = '''    private fun buildInternationalParticipationSnapshot(
        edition: TournamentEditionArchiveRecord
    ): QualificationTournamentSnapshot {
        val participantSource = "Tournament Edition participant set · Schedule / Standings / Completed Events"
        val pendingSource = "等待赛事官方 / Riot / 已核实 Provider"
        val teams = edition.participantTeamCodes
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() && it != "TBD" && it != "—" }
            .distinct()
            .sorted()
        val routes = teams.map { teamCode ->
            TeamQualificationRoute(
                tournamentId = edition.tournamentId,
                teamId = stableLocalTeamId(teamCode),
                teamCode = teamCode,
                targetEvent = edition.displayName,
                status = QualificationTeamState.LOCKED,
                route = listOf(
                    QualificationRouteNode(
                        id = "$teamCode:participant-observed",
                        label = "参赛席位已观测",
                        detail = "该队已出现在此 Tournament Edition 的可信参赛集合中；这只确认参赛事实，不等于已确认赛区、Seed 或晋级原因。",
                        state = QualificationNodeState.CONFIRMED,
                        source = participantSource,
                        evidence = QualificationEvidence.PROVIDER
                    ),
                    QualificationRouteNode(
                        id = "$teamCode:origin-pending",
                        label = "赛区 / Seed / 晋级来源",
                        detail = "等待可信映射。未拿到官方或可核实 Provider 记录前，不从队名、排名或对阵自行反推。",
                        state = QualificationNodeState.PENDING,
                        source = pendingSource,
                        evidence = QualificationEvidence.PENDING
                    )
                ),
                source = participantSource,
                evidence = QualificationEvidence.PROVIDER,
                updatedThrough = edition.endDate.ifBlank { edition.startDate }
            )
        }
        return QualificationTournamentSnapshot(
            tournamentId = edition.tournamentId,
            title = "${edition.displayName} · 参赛资格来源",
            targetEvent = edition.displayName,
            routes = routes,
            sourceSummary = if (routes.isEmpty()) pendingSource else "$participantSource + $pendingSource",
            note = if (routes.isEmpty()) {
                "该届国际赛已建立资格档案位；参赛集合本身尚未恢复，等待历史赛程 / Standings / Completed Events。"
            } else {
                "已确认的是参赛事实；Region / Seed / Qualification Origin 仍需可信映射。国际赛本身没有理由显示一张虚构的 Championship Points 缺失表。"
            },
            mechanism = QualificationMechanism.PARTICIPANT_ORIGIN,
            mechanismEvidence = if (routes.isEmpty()) QualificationEvidence.PENDING else QualificationEvidence.PROVIDER,
            mechanismDetail = "目标赛事按 Qualification Origin / Region / Seed 建档；Championship Points 只在确实采用该规则的赛区显示。"
        )
    }

'''
replace_once(qualification, insert_marker, international_builder + insert_marker)

replace_once(
    qualification,
    '''            note = "Championship Points 与本届 Standings Points 分栏显示；S3 当前值为保底积分。官方确认与 RiftLab 推导必须分开标识。"
        )''',
    '''            note = "Championship Points 与本届 Standings Points 分栏显示；S3 当前值为保底积分。官方确认与 RiftLab 推导必须分开标识。",
            mechanism = QualificationMechanism.MIXED,
            mechanismEvidence = QualificationEvidence.OFFICIAL,
            mechanismDetail = "2026 LPL 世界赛资格同时包含赛段冠军直通、年度 Championship Points 与区域资格赛节点，因此按混合资格体系记录。"
        )'''
)

# UI: neutral title, explicit mechanism, and no fake point placeholders for non-points systems.
replace_once(
    qualification_ui,
    'import com.riftlab.app.data.QualificationEvidence\n',
    'import com.riftlab.app.data.QualificationEvidence\nimport com.riftlab.app.data.QualificationMechanism\n'
)
replace_once(
    qualification_ui,
    '''            "QUALIFICATION / 年度积分与晋级路径",
''',
    '''            "QUALIFICATION / 资格体系与晋级路径",
'''
)
replace_once(
    qualification_ui,
    '''            "Championship Points ≠ 本届 Standings Points；官方确认与 RiftLab 推导分开显示",
''',
    '''            "积分、名次直通、资格赛与国际赛参赛来源分开建模；未知机制不会伪装成“积分缺失”",
'''
)
replace_once(
    qualification_ui,
    '''        Text(snapshot.title, color = RiftText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text("TARGET  ${snapshot.targetEvent}", color = RiftMuted, fontSize = 8.sp)
        Spacer(Modifier.height(7.dp))
''',
    '''        Text(snapshot.title, color = RiftText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text("TARGET  ${snapshot.targetEvent}", color = RiftMuted, fontSize = 8.sp)
        Text(
            "MODE  ${snapshot.mechanism.label} · ${snapshot.mechanismEvidence.label}",
            color = evidenceColor(snapshot.mechanismEvidence),
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (snapshot.mechanismDetail.isNotBlank()) {
            Text(snapshot.mechanismDetail, color = RiftMuted, fontSize = 7.sp, lineHeight = 10.sp)
        }
        Spacer(Modifier.height(7.dp))
'''
)
replace_once(qualification_ui, '        RouteDetail(route)\n', '        RouteDetail(route, snapshot.mechanism)\n')
replace_once(
    qualification_ui,
    '''private fun RouteDetail(route: TeamQualificationRoute) {
''',
    '''private fun RouteDetail(route: TeamQualificationRoute, mechanism: QualificationMechanism) {
'''
)
old_points = '''        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PointCell(
                title = "CHAMPIONSHIP POINTS",
                value = route.championshipPoints?.toString() ?: "—",
                detail = route.annualPointBreakdown.ifBlank { "年度积分待同步" },
                modifier = Modifier.weight(1f)
            )
            PointCell(
                title = "STANDINGS POINTS",
                value = route.leagueStandingPoints?.toString() ?: "—",
                detail = "仅代表当前届次/阶段返回的排名积分",
                modifier = Modifier.weight(1f)
            )
        }

'''
new_points = '''        Spacer(Modifier.height(5.dp))
        if (route.championshipPoints != null || route.leagueStandingPoints != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (route.championshipPoints != null) {
                    PointCell(
                        title = "CHAMPIONSHIP POINTS",
                        value = route.championshipPoints.toString(),
                        detail = route.annualPointBreakdown.ifBlank { "年度积分" },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (route.leagueStandingPoints != null) {
                    PointCell(
                        title = "STANDINGS POINTS",
                        value = route.leagueStandingPoints.toString(),
                        detail = "仅代表当前届次/阶段返回的排名积分",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            Text(
                when (mechanism) {
                    QualificationMechanism.PARTICIPANT_ORIGIN -> "这里不是 Championship Points 缺失：该目标赛事按参赛席位 / Qualification Origin 建档。"
                    QualificationMechanism.DIRECT_PLACEMENT -> "该资格体系按联赛或季后赛名次直通，不需要 Championship Points 占位。"
                    QualificationMechanism.REGIONAL_QUALIFIER -> "该资格体系按资格赛 / 附加赛路径记录，不需要 Championship Points 占位。"
                    QualificationMechanism.UNKNOWN -> "当前资格机制尚未核实，因此不显示虚假的 Championship Points 缺失项。"
                    QualificationMechanism.CHAMPIONSHIP_POINTS -> "该赛事确认采用 Championship Points，但当前队伍积分值尚未取得可信数据。"
                    QualificationMechanism.MIXED -> "该队当前路径没有可核实积分值；混合体系的其他资格节点仍按来源展示。"
                },
                color = RiftMuted,
                fontSize = 7.sp,
                lineHeight = 10.sp
            )
        }

'''
replace_once(qualification_ui, old_points, new_points)

# Archive cards should not imply every qualification model is Championship Points based.
replace_once(
    archive_ui,
    '''                            "已接入 ${qualification?.routes?.size ?: 0} 支队伍的 Championship Points / 晋级路径；详细节点见下方 Qualification Center。"
''',
    '''                            "已接入 ${qualification?.routes?.size ?: 0} 支队伍的资格路径 / 参赛来源；具体机制见下方 Qualification Center。"
'''
)
replace_once(
    archive,
    '''                detail = "资格路径已留独立槽位；dev.70 接入 Championship Points / 资格赛节点后回填，不从参赛名单反推。"
''',
    '''                detail = "资格体系使用独立模型：Championship Points、名次直通、资格赛与国际赛参赛来源分开记录；不从参赛名单反推具体 Seed / 晋级原因。"
'''
)

append_once(
    audit,
    "\n",
    '''\n## 2026-09-10 · Qualification semantics repair\n\n- Added an explicit qualification-mechanism layer: Championship Points, direct placement, regional qualifier, participant origin, mixed and unknown are no longer collapsed into one concept.\n- International Tournament Editions now expose observed participants as provider-backed participation facts while keeping Region / Seed / Qualification Origin pending until a trusted mapping exists.\n- Non-points systems no longer render an empty Championship Points card. Unknown regional rules are labelled as unknown rather than as missing points.\n- 2026 LPL remains a mixed official model because its current path combines direct champion qualification, annual Championship Points and the regional qualifier.\n- Archive/UI wording was made mechanism-neutral so the same surface can correctly represent LPL, LCK, LCP and international target events without inventing rules.\n'''
)

print("dev71 qualification semantics patch prepared")
