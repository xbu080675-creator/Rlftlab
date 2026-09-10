#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


DATA = "app/src/main/java/com/riftlab/app/data/QualificationCenter.kt"
UI = "app/src/main/java/com/riftlab/app/ui/QualificationPathUi.kt"

# A MIXED tournament is not useful enough by itself. Model the independent mechanisms explicitly
# without assigning an individual team's origin unless that origin is separately proven.
replace_once(
    DATA,
    '''enum class QualificationMechanism(val label: String) {\n    CHAMPIONSHIP_POINTS("Championship Points"),\n    DIRECT_PLACEMENT("联赛 / 季后赛名次直通"),\n    REGIONAL_QUALIFIER("区域资格赛 / 附加赛"),\n    PARTICIPANT_ORIGIN("国际赛参赛资格来源"),\n    MIXED("混合资格体系"),\n    UNKNOWN("资格规则待确认")\n}\n\n''',
    '''enum class QualificationMechanism(val label: String) {\n    CHAMPIONSHIP_POINTS("Championship Points"),\n    DIRECT_PLACEMENT("联赛 / 季后赛名次直通"),\n    REGIONAL_QUALIFIER("区域资格赛 / 附加赛"),\n    PARTICIPANT_ORIGIN("国际赛参赛资格来源"),\n    MIXED("混合资格体系"),\n    UNKNOWN("资格规则待确认")\n}\n\nenum class QualificationSegmentType(val label: String) {\n    DIRECT_QUALIFICATION("名次 / 冠军直通"),\n    CHAMPIONSHIP_POINTS("Championship Points"),\n    REGIONAL_QUALIFIER("区域资格赛 / 附加赛"),\n    PARTICIPANT_ORIGIN("国际赛参赛来源")\n}\n\ndata class QualificationMechanismSegment(\n    val type: QualificationSegmentType,\n    val detail: String,\n    val source: String,\n    val evidence: QualificationEvidence\n)\n\n'''
)
replace_once(
    DATA,
    '''    val routes: List<TeamQualificationRoute> = emptyList(),\n    val rules: List<QualificationRuleRecord> = emptyList(),\n''',
    '''    val routes: List<TeamQualificationRoute> = emptyList(),\n    val segments: List<QualificationMechanismSegment> = emptyList(),\n    val rules: List<QualificationRuleRecord> = emptyList(),\n'''
)

# Regional handbook-backed editions now expose the mechanism pieces independently.
replace_once(
    DATA,
    '''            routes = participantRoutes,\n            rules = rules,\n            sourceSummary = listOf(\n''',
    '''            routes = participantRoutes,\n            segments = officialMechanismSegments(edition, handbookQualification),\n            rules = rules,\n            sourceSummary = listOf(\n'''
)

# International target events remain participation-origin semantics rather than regional points.
replace_once(
    DATA,
    '''            routes = routes,\n            sourceSummary = when {\n                officialWorldsTeams.isNotEmpty() -> Worlds2026QualifiedTeams.SOURCE\n''',
    '''            routes = routes,\n            segments = listOf(\n                QualificationMechanismSegment(\n                    type = QualificationSegmentType.PARTICIPANT_ORIGIN,\n                    detail = "本届按已确认参赛事实建档；Region / Seed / Qualification Origin 各自等待字段级证据，不从参赛名单顺序反推。",\n                    source = if (officialWorldsTeams.isNotEmpty()) Worlds2026QualifiedTeams.SOURCE else participantSource,\n                    evidence = if (officialWorldsTeams.isNotEmpty()) QualificationEvidence.OFFICIAL else if (routes.isNotEmpty()) QualificationEvidence.PROVIDER else QualificationEvidence.PENDING\n                )\n            ),\n            sourceSummary = when {\n                officialWorldsTeams.isNotEmpty() -> Worlds2026QualifiedTeams.SOURCE\n'''
)

# The LPL 2026 route specifically separates direct qualification, annual points, and the independent
# Regional Qualifier. This avoids the old UI collapsing all three into one opaque MIXED label.
replace_once(
    DATA,
    '''            routes = routes,\n            rules = rules,\n            sourceSummary = listOf(\n                LplChampionshipPoints2026.sourceLabel,\n''',
    '''            routes = routes,\n            segments = lpl2026MechanismSegments(governance),\n            rules = rules,\n            sourceSummary = listOf(\n                LplChampionshipPoints2026.sourceLabel,\n'''
)

# Insert segment builders before the existing LCP helper.
marker = '''    private fun isLcpEdition(edition: TournamentEditionArchiveRecord): Boolean {\n'''
helpers = '''    private fun officialMechanismSegments(\n        edition: TournamentEditionArchiveRecord,\n        handbookQualification: Pair<String, String>\n    ): List<QualificationMechanismSegment> {\n        val source = handbookQualification.second\n        val token = "${edition.leagueId} ${edition.leagueSlug} ${edition.leagueName}".uppercase()\n        return when {\n            token.contains("113476371197627891") || token.contains("LCP") -> listOf(\n                QualificationMechanismSegment(\n                    QualificationSegmentType.DIRECT_QUALIFICATION,\n                    "季后赛前两名直接获得 Worlds 席位。",\n                    source,\n                    QualificationEvidence.OFFICIAL\n                ),\n                QualificationMechanismSegment(\n                    QualificationSegmentType.CHAMPIONSHIP_POINTS,\n                    "第三个 Worlds 名额通过 Championship Points 决定；这里与普通 Standings Points 分开记录。",\n                    source,\n                    QualificationEvidence.OFFICIAL\n                )\n            )\n            token.contains("98767991314006698") || token.contains("LPL") -> listOf(\n                QualificationMechanismSegment(\n                    QualificationSegmentType.DIRECT_QUALIFICATION,\n                    "第三赛段冠军直通 Worlds。",\n                    source,\n                    QualificationEvidence.OFFICIAL\n                ),\n                QualificationMechanismSegment(\n                    QualificationSegmentType.CHAMPIONSHIP_POINTS,\n                    "年度 Championship Points 用于后续 Worlds 资格竞争；不与本届 Standings Points 合并。",\n                    source,\n                    QualificationEvidence.OFFICIAL\n                ),\n                QualificationMechanismSegment(\n                    QualificationSegmentType.REGIONAL_QUALIFIER,\n                    "Championship Points 前列进入区域资格赛，竞争其余 Worlds 席位；资格赛本身是独立晋级节点。",\n                    source,\n                    QualificationEvidence.OFFICIAL\n                )\n            )\n            officialRegionalMechanism(edition) == QualificationMechanism.DIRECT_PLACEMENT -> listOf(\n                QualificationMechanismSegment(\n                    QualificationSegmentType.DIRECT_QUALIFICATION,\n                    handbookQualification.first,\n                    source,\n                    QualificationEvidence.OFFICIAL\n                )\n            )\n            officialRegionalMechanism(edition) == QualificationMechanism.CHAMPIONSHIP_POINTS -> listOf(\n                QualificationMechanismSegment(\n                    QualificationSegmentType.CHAMPIONSHIP_POINTS,\n                    handbookQualification.first,\n                    source,\n                    QualificationEvidence.OFFICIAL\n                )\n            )\n            officialRegionalMechanism(edition) == QualificationMechanism.REGIONAL_QUALIFIER -> listOf(\n                QualificationMechanismSegment(\n                    QualificationSegmentType.REGIONAL_QUALIFIER,\n                    handbookQualification.first,\n                    source,\n                    QualificationEvidence.OFFICIAL\n                )\n            )\n            else -> emptyList()\n        }\n    }\n\n    private fun lpl2026MechanismSegments(\n        governance: TournamentGovernanceSnapshot\n    ): List<QualificationMechanismSegment> {\n        val officialRuleSource = governance.rules.items.firstOrNull { it.verified }?.source\n            ?: governance.rules.sourceSummary\n        return listOf(\n            QualificationMechanismSegment(\n                type = QualificationSegmentType.DIRECT_QUALIFICATION,\n                detail = "第三赛段冠军直通 Worlds；这里只描述官方机制，不据当前积分榜猜哪支队通过该路径。",\n                source = officialRuleSource,\n                evidence = QualificationEvidence.OFFICIAL\n            ),\n            QualificationMechanismSegment(\n                type = QualificationSegmentType.CHAMPIONSHIP_POINTS,\n                detail = "年度 Championship Points 独立于当前 Tournament Standings Points，用于决定后续资格竞争位置/路径。",\n                source = LplChampionshipPoints2026.sourceLabel,\n                evidence = QualificationEvidence.OFFICIAL\n            ),\n            QualificationMechanismSegment(\n                type = QualificationSegmentType.REGIONAL_QUALIFIER,\n                detail = "区域资格赛是独立赛程节点，竞争其余 Worlds 席位；M1/M2/M3 等具体签位只在已核实 Draw Slot 出现后绑定到队伍。",\n                source = officialRuleSource,\n                evidence = QualificationEvidence.OFFICIAL\n            )\n        )\n    }\n\n'''
p = Path(DATA)
text = p.read_text(encoding="utf-8")
if text.count(marker) != 1:
    raise SystemExit("QualificationCenter.kt: segment helper insertion point not found")
p.write_text(text.replace(marker, helpers + marker, 1), encoding="utf-8")

# UI: show each mechanism segment directly below MODE. The segment describes the tournament rule,
# not a guessed team-specific origin.
replace_once(
    UI,
    '''        if (snapshot.mechanismDetail.isNotBlank()) {\n            Text(snapshot.mechanismDetail, color = RiftMuted, fontSize = 7.sp, lineHeight = 10.sp)\n        }\n        Spacer(Modifier.height(7.dp))\n\n        if (snapshot.routes.isEmpty()) {\n''',
    '''        if (snapshot.mechanismDetail.isNotBlank()) {\n            Text(snapshot.mechanismDetail, color = RiftMuted, fontSize = 7.sp, lineHeight = 10.sp)\n        }\n        if (snapshot.segments.isNotEmpty()) {\n            Spacer(Modifier.height(6.dp))\n            Text("MECHANISM SEGMENTS / 资格机制拆分", color = RiftText, fontSize = 8.sp, fontWeight = FontWeight.Bold)\n            snapshot.segments.forEach { segment ->\n                Spacer(Modifier.height(4.dp))\n                Column(\n                    Modifier\n                        .fillMaxWidth()\n                        .background(RiftPanel, CutCornerShape(topStart = 4.dp, bottomEnd = 4.dp))\n                        .padding(6.dp)\n                ) {\n                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {\n                        Text(segment.type.label, color = RiftCyan, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)\n                        Text(segment.evidence.label, color = evidenceColor(segment.evidence), fontSize = 7.sp)\n                    }\n                    Text(segment.detail, color = RiftMuted, fontSize = 7.sp, lineHeight = 10.sp)\n                    Text("SOURCE  ${segment.source}", color = RiftMuted, fontSize = 6.sp)\n                }\n            }\n        }\n        Spacer(Modifier.height(7.dp))\n\n        if (snapshot.routes.isEmpty()) {\n'''
)

print("dev72 qualification mechanism segments batch15 applied")
