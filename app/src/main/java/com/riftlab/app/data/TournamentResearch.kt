package com.riftlab.app.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ResearchEvidence {
    VERIFIED,
    PROVIDER,
    PENDING
}

data class TournamentVersionSnapshot(
    val versionLabel: String,
    val detail: String,
    val source: String,
    val evidence: ResearchEvidence
)

data class TournamentResearchUpdate(
    val category: String,
    val title: String,
    val detail: String,
    val effectiveAt: String = "",
    val source: String,
    val evidence: ResearchEvidence
)

data class TournamentResearchCoverage(
    val label: String,
    val state: String,
    val detail: String
)

data class TournamentResearchSnapshot(
    val title: String,
    val year: String,
    val family: String,
    val scope: String,
    val version: TournamentVersionSnapshot,
    val updates: List<TournamentResearchUpdate>,
    val coverage: List<TournamentResearchCoverage>,
    val sourceSummary: String
)

/**
 * Builds one research archive per tournament edition/year.
 *
 * This intentionally does not invent patch versions or rule text. Exact game-version information is
 * only promoted when a provider explicitly exposes it. Schedule/standing structure remains labelled
 * as provider data, while official rule/draw snapshots keep their verified provenance.
 */
object TournamentResearchProvider {
    private val localZone = ZoneId.systemDefault()
    private val dayFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")

    fun resolve(
        tournament: EsportsTournamentRef?,
        competitionTitle: String,
        matches: List<ScheduledEsportsMatch>,
        standings: TournamentStandings?,
        governance: TournamentGovernanceSnapshot
    ): TournamentResearchSnapshot {
        val identity = listOf(
            tournament?.leagueSlug.orEmpty(),
            tournament?.leagueName.orEmpty(),
            tournament?.slug.orEmpty(),
            competitionTitle,
            matches.firstOrNull()?.leagueSlug.orEmpty(),
            matches.firstOrNull()?.league.orEmpty()
        ).joinToString(" ").lowercase()

        val year = resolveYear(tournament, competitionTitle, matches)
        val family = resolveFamily(identity, competitionTitle)
        val scope = if (isInternational(identity)) "国际赛事" else "赛区联赛"
        val version = resolveVersion(matches)
        val updates = buildUpdates(competitionTitle, matches, standings, governance)
        val coverage = buildCoverage(matches, standings, governance, version)
        val sources = buildList {
            if (matches.isNotEmpty()) add("Unified Schedule")
            if (standings != null) add("Riot Standings")
            if (governance.rules.items.any { it.verified }) add("Official Rule Snapshot")
            if (governance.draw.slots.any { it.verified }) add("Official Draw/Slot Snapshot")
            if (version.evidence != ResearchEvidence.PENDING) add(version.source)
        }.distinct()

        return TournamentResearchSnapshot(
            title = "$competitionTitle · 赛事研究",
            year = year,
            family = family,
            scope = scope,
            version = version,
            updates = updates,
            coverage = coverage,
            sourceSummary = sources.joinToString(" + ").ifBlank { "研究档案已建立 · 等待可信赛事源" }
        )
    }

    private fun resolveYear(
        tournament: EsportsTournamentRef?,
        competitionTitle: String,
        matches: List<ScheduledEsportsMatch>
    ): String {
        tournament?.startDate?.take(4)?.takeIf { it.all { ch -> ch.isDigit() } }?.let { return it }
        matches.firstOrNull()?.startTimeIso?.take(4)?.takeIf { it.all { ch -> ch.isDigit() } }?.let { return it }
        Regex("(?:19|20)\\d{2}").find(competitionTitle)?.value?.let { return it }
        return "年份待确认"
    }

    private fun resolveFamily(identity: String, fallback: String): String = when {
        identity.contains("worlds") || identity.contains("world championship") || identity.contains("全球总决赛") -> "全球总决赛"
        identity.contains("mid-season") || Regex("(^|[^a-z])msi([^a-z]|$)").containsMatchIn(identity) || identity.contains("季中冠军赛") -> "季中冠军赛"
        identity.contains("first stand") || identity.contains("first-stand") || identity.contains("first_stand") || identity.contains("全球先锋赛") -> "全球先锋赛"
        identity.contains("esports world cup") || Regex("(^|[^a-z])ewc([^a-z]|$)").containsMatchIn(identity) -> "Esports World Cup"
        identity.contains("demacia") || identity.contains("德玛西亚") -> "德杯国际邀请赛"
        Regex("(^|[^a-z])wscl([^a-z]|$)").containsMatchIn(identity) -> "WSCL"
        identity.contains("americas cup") || identity.contains("america cup") || identity.contains("美洲杯") -> "美洲杯"
        identity.contains("emea masters") || identity.contains("emea 大师赛") -> "EMEA 大师赛"
        else -> fallback
    }

    private fun isInternational(identity: String): Boolean =
        identity.contains("worlds") || identity.contains("world championship") || identity.contains("全球总决赛") ||
            identity.contains("mid-season") || Regex("(^|[^a-z])msi([^a-z]|$)").containsMatchIn(identity) ||
            identity.contains("first stand") || identity.contains("first-stand") || identity.contains("first_stand") ||
            identity.contains("esports world cup") || Regex("(^|[^a-z])ewc([^a-z]|$)").containsMatchIn(identity) ||
            identity.contains("demacia") || identity.contains("德玛西亚") ||
            Regex("(^|[^a-z])wscl([^a-z]|$)").containsMatchIn(identity) ||
            identity.contains("americas cup") || identity.contains("emea masters")

    private fun resolveVersion(matches: List<ScheduledEsportsMatch>): TournamentVersionSnapshot {
        // Current normalized schedule/standings models do not expose a trustworthy patch field.
        // Keep the slot explicit instead of guessing from event date.
        val explicit = matches.asSequence()
            .flatMap { sequenceOf(it.blockName) }
            .mapNotNull { Regex("(?<!\\d)(?:1\\d|2\\d)\\.\\d{1,2}(?!\\d)").find(it)?.value }
            .firstOrNull()
        return if (explicit != null) {
            TournamentVersionSnapshot(
                versionLabel = "Patch $explicit",
                detail = "赛事结构数据中检测到明确版本标识；后续仍会由 Riot / Cito / 官方规则源交叉确认。",
                source = "Unified Tournament Metadata",
                evidence = ResearchEvidence.PROVIDER
            )
        } else {
            TournamentVersionSnapshot(
                versionLabel = "比赛版本待同步",
                detail = "RiftLab 已为该年度赛事保留版本档案。当前赛程/排名模型没有可靠 Patch 字段，因此不会按日期猜版本；待 Riot、Cito 或官方赛事资料明确给出后再写入版本号与版本改动。",
                source = "RiftLab Research Archive",
                evidence = ResearchEvidence.PENDING
            )
        }
    }

    private fun buildUpdates(
        competitionTitle: String,
        matches: List<ScheduledEsportsMatch>,
        standings: TournamentStandings?,
        governance: TournamentGovernanceSnapshot
    ): List<TournamentResearchUpdate> {
        val updates = mutableListOf<TournamentResearchUpdate>()
        if (matches.isNotEmpty()) {
            val epochs = matches.mapNotNull { runCatching { Instant.parse(it.startTimeIso) }.getOrNull() }.sorted()
            val teams = matches.flatMap { it.teams }
                .map { team -> team.code.ifBlank { team.name } }
                .filter { it.isNotBlank() && !it.equals("TBD", true) }
                .map { it.uppercase() }
                .distinct()
            val range = if (epochs.isNotEmpty()) {
                "${epochs.first().atZone(localZone).format(dayFormatter)} - ${epochs.last().atZone(localZone).format(dayFormatter)}"
            } else "日期待确认"
            updates += TournamentResearchUpdate(
                category = "赛事更新",
                title = "赛程快照",
                detail = "$competitionTitle 当前归档 ${matches.size} 场系列赛；赛程窗口 $range。",
                source = "RiftLab Unified Schedule (Riot/Cito)",
                evidence = ResearchEvidence.PROVIDER
            )
            if (teams.isNotEmpty()) {
                updates += TournamentResearchUpdate(
                    category = "参赛阵容",
                    title = "参赛战队快照",
                    detail = "当前赛程已识别 ${teams.size} 支战队。后续资格确认、替补或退赛变更会作为新的赛事更新保留，而不是覆盖掉旧快照。",
                    source = "RiftLab Unified Schedule (Riot/Cito)",
                    evidence = ResearchEvidence.PROVIDER
                )
            }
        }

        val stages = standings?.stages.orEmpty()
            .map { stage -> stage.name.ifBlank { stage.slug } }
            .filter { it.isNotBlank() }
            .distinct()
        if (stages.isNotEmpty()) {
            updates += TournamentResearchUpdate(
                category = "赛制结构",
                title = "阶段结构",
                detail = stages.joinToString(" → "),
                source = "Riot Standings",
                evidence = ResearchEvidence.PROVIDER
            )
        }

        val verifiedRules = governance.rules.items.count { it.verified }
        updates += TournamentResearchUpdate(
            category = "规则",
            title = if (verifiedRules > 0) "规则快照已核实" else "规则档案已建立",
            detail = if (verifiedRules > 0) {
                "当前保存 $verifiedRules 条已核实规则；完整内容进入同一年度赛事的“规则”页。"
            } else {
                "当前仅保存可从公开结构确认的赛制信息；等待赛事官方规则源，不补写未核实条款。"
            },
            source = governance.rules.sourceSummary,
            evidence = if (verifiedRules > 0) ResearchEvidence.VERIFIED else ResearchEvidence.PENDING
        )

        val confirmedSlots = governance.draw.slots.count { it.verified }
        updates += TournamentResearchUpdate(
            category = "抽签 / 签位",
            title = if (confirmedSlots > 0) "签位快照已建立" else "抽签档案待同步",
            detail = if (confirmedSlots > 0) {
                "已保存 $confirmedSlots 个已确认签位/对阵槽位；后续抽签轮次按时间追加，不覆盖历史结果。"
            } else {
                "当前没有可信抽签结果。RiftLab 只保存官方抽签或明确签位关系，不根据排名猜测对阵。"
            },
            source = governance.draw.sourceSummary,
            evidence = if (confirmedSlots > 0) ResearchEvidence.PROVIDER else ResearchEvidence.PENDING
        )

        if (updates.isEmpty()) {
            updates += TournamentResearchUpdate(
                category = "研究档案",
                title = "年度档案已建立",
                detail = "赛程、版本、规则、抽签和赛事更新均已预留入口；等待可信数据源发布。",
                source = "RiftLab Research Archive",
                evidence = ResearchEvidence.PENDING
            )
        }
        return updates
    }

    private fun buildCoverage(
        matches: List<ScheduledEsportsMatch>,
        standings: TournamentStandings?,
        governance: TournamentGovernanceSnapshot,
        version: TournamentVersionSnapshot
    ): List<TournamentResearchCoverage> = listOf(
        TournamentResearchCoverage(
            label = "版本",
            state = if (version.evidence == ResearchEvidence.PENDING) "待同步" else "已归档",
            detail = version.versionLabel
        ),
        TournamentResearchCoverage(
            label = "规则",
            state = if (governance.rules.items.any { it.verified }) "已核实" else "结构档案",
            detail = "${governance.rules.items.size} 条"
        ),
        TournamentResearchCoverage(
            label = "抽签",
            state = if (governance.draw.slots.isEmpty()) "待同步" else "已归档",
            detail = "${governance.draw.slots.size} 个槽位"
        ),
        TournamentResearchCoverage(
            label = "赛程",
            state = if (matches.isEmpty()) "待同步" else "已归档",
            detail = "${matches.size} 场"
        ),
        TournamentResearchCoverage(
            label = "排名 / 淘汰",
            state = if (standings == null) "待同步" else "已归档",
            detail = standings?.stages?.size?.let { "$it 个阶段" } ?: "等待数据源"
        )
    )
}
