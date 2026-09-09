package com.riftlab.app.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Tournament rules / draw metadata deliberately keeps provenance on every layer.
 * A rule may be official, provider-backed, or only derived from the public schedule structure.
 * RiftLab must never present a derived rule as an official rulebook statement.
 */
data class TournamentRuleItem(
    val title: String,
    val detail: String,
    val source: String,
    val verified: Boolean
)

data class TournamentRulesSnapshot(
    val title: String,
    val items: List<TournamentRuleItem>,
    val sourceSummary: String,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class TournamentDrawSlot(
    val label: String,
    val left: String,
    val right: String,
    val scheduledAt: String = "",
    val status: String = "",
    val source: String,
    val verified: Boolean,
    val bracketMatchId: String = ""
)

data class TournamentDrawSnapshot(
    val title: String,
    val slots: List<TournamentDrawSlot>,
    val note: String,
    val sourceSummary: String,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class TournamentGovernanceSnapshot(
    val rules: TournamentRulesSnapshot,
    val draw: TournamentDrawSnapshot
)

object TournamentGovernanceProvider {
    private val cnZone = ZoneId.of("Asia/Shanghai")
    private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm 'UTC+8'")

    fun resolve(
        tournament: EsportsTournamentRef?,
        competitionTitle: String,
        matches: List<ScheduledEsportsMatch>,
        standings: TournamentStandings?
    ): TournamentGovernanceSnapshot {
        val identity = listOf(
            tournament?.leagueSlug.orEmpty(),
            tournament?.leagueName.orEmpty(),
            tournament?.slug.orEmpty(),
            competitionTitle,
            matches.firstOrNull()?.leagueSlug.orEmpty(),
            matches.firstOrNull()?.league.orEmpty()
        ).joinToString(" ").lowercase()

        val rules = if (is2026LplThirdStage(identity, tournament, matches)) {
            verified2026LplWorldsQualificationRules()
        } else {
            deriveRules(competitionTitle, matches, standings)
        }

        val draw = if (is2026LplThirdStage(identity, tournament, matches)) {
            mergeDraw(
                official = verified2026LplRegionalQualifierSlots(),
                standings = standings
            )
        } else {
            deriveDraw(competitionTitle, matches, standings)
        }

        return TournamentGovernanceSnapshot(rules = rules, draw = draw)
    }

    private fun is2026LplThirdStage(
        identity: String,
        tournament: EsportsTournamentRef?,
        matches: List<ScheduledEsportsMatch>
    ): Boolean {
        val year = tournament?.startDate?.take(4)
            ?: matches.firstOrNull()?.startTimeIso?.take(4)
            ?: ""
        val lpl = identity.contains("lpl")
        val split3 = identity.contains("split_3") || identity.contains("split-3") ||
            identity.contains("third") || identity.contains("第三赛段") ||
            identity.contains("regional") || identity.contains("资格赛")
        return year == "2026" && lpl && split3
    }

    private fun verified2026LplWorldsQualificationRules(): TournamentRulesSnapshot {
        val source = "英雄联盟赛事官方公告 · 2026-09-09"
        return TournamentRulesSnapshot(
            title = "2026 LPL 全球总决赛资格规则",
            items = listOf(
                TournamentRuleItem(
                    title = "一号种子",
                    detail = "第三赛段最终捧起银龙杯的冠军战队直接以 LPL 一号种子身份出征全球总决赛。",
                    source = source,
                    verified = true
                ),
                TournamentRuleItem(
                    title = "二号种子",
                    detail = "除第三赛段冠军外，全年 Championship Points 最高的队伍获得 LPL 二号种子。",
                    source = source,
                    verified = true
                ),
                TournamentRuleItem(
                    title = "三号种子",
                    detail = "区域资格赛 M1 胜者获得三号种子。",
                    source = source,
                    verified = true
                ),
                TournamentRuleItem(
                    title = "四号种子",
                    detail = "M1 败者与 M2 胜者进入 M3，M3 胜者获得最后一个全球总决赛席位。",
                    source = source,
                    verified = true
                ),
                TournamentRuleItem(
                    title = "区域资格赛窗口",
                    detail = "M1 / M2 / M3 分别安排在 9 月 17 日、18 日、19 日 17:00（UTC+8）。",
                    source = source,
                    verified = true
                )
            ),
            sourceSummary = source
        )
    }

    private fun verified2026LplRegionalQualifierSlots(): TournamentDrawSnapshot {
        val source = "英雄联盟赛事官方公告 · 2026-09-09"
        return TournamentDrawSnapshot(
            title = "区域资格赛签位 / 对阵确认",
            slots = listOf(
                TournamentDrawSlot(
                    label = "M1",
                    left = "TES",
                    right = "TBD",
                    scheduledAt = "09-17 17:00 UTC+8",
                    status = "官方已确认一侧",
                    source = source,
                    verified = true
                ),
                TournamentDrawSlot(
                    label = "M2",
                    left = "WE",
                    right = "JDG",
                    scheduledAt = "09-18 17:00 UTC+8",
                    status = "官方已确认",
                    source = source,
                    verified = true
                ),
                TournamentDrawSlot(
                    label = "M3",
                    left = "TBD",
                    right = "TBD",
                    scheduledAt = "09-19 17:00 UTC+8",
                    status = "承接 M1 败者 / M2 胜者",
                    source = source,
                    verified = true
                )
            ),
            note = "这里展示的是官方已确认的资格赛签位/对阵关系；并不把种子顺位或赛制分配错误描述为随机抽签。",
            sourceSummary = source
        )
    }

    private fun deriveRules(
        competitionTitle: String,
        matches: List<ScheduledEsportsMatch>,
        standings: TournamentStandings?
    ): TournamentRulesSnapshot {
        val items = mutableListOf<TournamentRuleItem>()
        val bo = matches.map { it.bestOf }.filter { it > 0 }.distinct().sorted()
        if (bo.isNotEmpty()) {
            items += TournamentRuleItem(
                title = "对局长度",
                detail = if (bo.size == 1) "公开赛程中的系列赛为 BO${bo.single()}。" else "公开赛程包含 ${bo.joinToString(" / ") { "BO$it" }}。",
                source = "Riot Schedule · 结构推导",
                verified = false
            )
        }
        val stageNames = standings?.stages.orEmpty()
            .map { it.name.ifBlank { it.slug } }
            .filter { it.isNotBlank() }
            .distinct()
        if (stageNames.isNotEmpty()) {
            items += TournamentRuleItem(
                title = "赛事阶段",
                detail = stageNames.joinToString(" → "),
                source = "Riot Standings · 结构推导",
                verified = false
            )
        }
        if (items.isEmpty()) {
            items += TournamentRuleItem(
                title = "官方规则待同步",
                detail = "RiftLab 已为 $competitionTitle 保留规则档案位；在 Riot / 赛区官方 / Cito 返回可核实规则前不自行补写赛制。",
                source = "RiftLab Governance",
                verified = false
            )
        }
        return TournamentRulesSnapshot(
            title = "$competitionTitle · 赛事规则",
            items = items,
            sourceSummary = if (items.all { !it.verified }) "当前仅有公开结构推导；等待官方规则源" else items.first().source
        )
    }

    private fun deriveDraw(
        competitionTitle: String,
        matches: List<ScheduledEsportsMatch>,
        standings: TournamentStandings?
    ): TournamentDrawSnapshot {
        val bracketMatches = standings?.stages.orEmpty()
            .flatMap { stage -> stage.sections.flatMap { it.matches } }
        val slots = bracketMatches.mapIndexed { index, match ->
            val schedule = matches.firstOrNull { it.matchId == match.id || it.eventId == match.id }
            val left = teamCode(match.teams.getOrNull(0) ?: schedule?.teams?.getOrNull(0))
            val right = teamCode(match.teams.getOrNull(1) ?: schedule?.teams?.getOrNull(1))
            TournamentDrawSlot(
                label = "M${index + 1}",
                left = left,
                right = right,
                scheduledAt = schedule?.startTimeIso?.let(::formatTime).orEmpty(),
                status = match.state.ifBlank { "bracket" },
                source = "Riot Standings",
                verified = left != "TBD" || right != "TBD",
                bracketMatchId = match.id
            )
        }
        return TournamentDrawSnapshot(
            title = "$competitionTitle · 抽签 / 签位",
            slots = slots,
            note = if (slots.isEmpty()) {
                "官方抽签或签位尚未同步。RiftLab 不会根据排名自行猜测对阵。"
            } else {
                "Riot Standings 返回的是公开签位/Bracket 关系；只有来源明确标注为官方抽签时才称为抽签结果。"
            },
            sourceSummary = if (slots.isEmpty()) "等待 Riot / 赛事官方 / Cito" else "Riot Standings"
        )
    }

    private fun mergeDraw(
        official: TournamentDrawSnapshot,
        standings: TournamentStandings?
    ): TournamentDrawSnapshot {
        val regional = standings?.stages.orEmpty()
            .filter { it.slug.contains("regional", true) || it.name.contains("regional", true) || it.name.contains("资格", true) }
            .flatMap { stage -> stage.sections.flatMap { it.matches } }
        if (regional.isEmpty()) return official
        val merged = official.slots.mapIndexed { index, slot ->
            val bracket = regional.getOrNull(index) ?: return@mapIndexed slot
            slot.copy(bracketMatchId = bracket.id)
        }
        return official.copy(slots = merged)
    }

    private fun teamCode(team: EsportsTeamRef?): String {
        if (team == null) return "TBD"
        return team.code.ifBlank { team.name }.ifBlank { "TBD" }.uppercase()
    }

    private fun formatTime(value: String): String = runCatching {
        Instant.parse(value).atZone(cnZone).format(timeFormatter)
    }.getOrElse { value.take(16) }
}
