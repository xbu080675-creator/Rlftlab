package com.riftlab.app.data

/**
 * Verified snapshot of the Riot LoL Esports 2026 League Handbook.
 *
 * This is intentionally narrow: only statements that are explicitly present in Riot's handbook
 * are promoted to verified rules/participants.  It never derives seeds or qualification origins
 * from ordering in a participant list.  The source URLs are kept on every snapshot so this seed can
 * later be refreshed by the repository data-sync job without changing the domain contract.
 *
 * Snapshot checked: 2026-09-10.
 */
internal object OfficialHandbookGovernance2026 {
    private const val SEASON_ID = "115547545029543948"
    private const val SPLIT3_EVENT_ID = "115548016979679447"
    private const val FIRST_STAND_EVENT_ID = "115548016979679444"
    private const val MSI_EVENT_ID = "115548016979679446"
    private const val WORLDS_EVENT_ID = "115548016979679448"

    private const val LPL_ID = "98767991314006698"
    private const val LCK_ID = "98767991310872058"
    private const val LEC_ID = "98767991302996019"
    private const val LCS_ID = "98767991299243165"
    private const val CBLOL_ID = "98767991332355509"
    private const val LCP_ID = "113476371197627891"

    data class QualifiedParticipant(
        val teamCode: String,
        val region: String,
        val source: String
    )

    fun rulesFor(
        tournament: EsportsTournamentRef?,
        competitionTitle: String,
        identity: String
    ): TournamentRulesSnapshot? {
        if (!is2026(tournament, competitionTitle, identity)) return null
        val token = normalizedIdentity(tournament, competitionTitle, identity)

        return when {
            isWorlds(token) -> worldsRules()
            isMsi(token) -> msiRules()
            isFirstStand(token) -> firstStandRules()
            isSplit3(token) -> regionalSplit3Rules(tournament, token)
            else -> null
        }
    }

    fun participantCodesFor(
        tournament: EsportsTournamentRef?,
        competitionTitle: String
    ): List<String> {
        val identity = normalizedIdentity(tournament, competitionTitle, competitionTitle.lowercase())
        if (!is2026(tournament, competitionTitle, identity) || !isWorlds(identity)) return emptyList()
        return worldsQualifiedParticipants().map { it.teamCode }
    }

    fun participantSourceFor(
        tournament: EsportsTournamentRef?,
        competitionTitle: String
    ): String = if (participantCodesFor(tournament, competitionTitle).isNotEmpty()) worldsSource() else ""

    fun qualificationSummaryFor(
        tournament: EsportsTournamentRef?,
        competitionTitle: String,
        identity: String
    ): Pair<String, String>? {
        if (!is2026(tournament, competitionTitle, identity)) return null
        val token = normalizedIdentity(tournament, competitionTitle, identity)
        if (isWorlds(token)) {
            val participants = worldsQualifiedParticipants()
            return (
                "Riot Handbook 已列出 ${participants.size} 支已确认 Worlds 参赛队；这里只确认参赛资格和赛区，不从列表顺序猜 Seed / 晋级原因。" to worldsSource()
            )
        }
        if (!isSplit3(token)) return null
        return when (canonicalLeague(tournament, token)) {
            "LCK" -> "Riot Handbook：Split 3 季后赛前三名晋级 Worlds。" to split3Source(LCK_ID)
            "LCP" -> "Riot Handbook：季后赛前两名晋级 Worlds，第三个名额通过 Championship Points。" to split3Source(LCP_ID)
            "LCS" -> "Riot Handbook：Split 3 季后赛前三名晋级 Worlds。" to split3Source(LCS_ID)
            "CBLOL" -> "Riot Handbook：Split 3 冠军晋级 Worlds。" to split3Source(CBLOL_ID)
            "LEC" -> "Riot Handbook 已给出 Worlds 晋级卡与季后赛描述；具体第三席来源在没有更明确官方字段前不自行补写。" to split3Source(LEC_ID)
            "LPL" -> "Riot Handbook：第三赛段冠军直通 Worlds，Championship Points 排名前列进入区域资格赛竞争其余席位。" to split3Source(LPL_ID)
            else -> null
        }
    }

    fun worldsQualifiedParticipants(): List<QualifiedParticipant> {
        val source = worldsSource()
        return listOf(
            QualifiedParticipant("KC", "LEC", source),
            QualifiedParticipant("G2", "LEC", source),
            QualifiedParticipant("DK", "LCK", source),
            QualifiedParticipant("T1", "LCK", source),
            QualifiedParticipant("GEN", "LCK", source),
            QualifiedParticipant("HLE", "LCK", source),
            QualifiedParticipant("CFO", "LCP", source),
            QualifiedParticipant("MVK", "LCP", source),
            QualifiedParticipant("TSW", "LCP", source),
            QualifiedParticipant("BLG", "LPL", source)
        )
    }

    private fun regionalSplit3Rules(
        tournament: EsportsTournamentRef?,
        token: String
    ): TournamentRulesSnapshot? = when (canonicalLeague(tournament, token)) {
        "LCK" -> rules(
            title = "2026 LCK Split 3 · Riot 官方赛制",
            source = split3Source(LCK_ID),
            "常规阶段" to "依据 Split 2 成绩分组，组内进行三循环 BO3；部分队伍进入 Play-ins。",
            "Play-ins" to "4 支队参加 Play-in，最终 2 支进入 Playoffs。",
            "Playoffs" to "6 支队参加双败淘汰赛，前三名晋级 Worlds。"
        )
        "LCP" -> rules(
            title = "2026 LCP Split 3 · Riot 官方赛制",
            source = split3Source(LCP_ID),
            "常规阶段" to "根据 Split 2 成绩进入 Swiss 体系，普通轮 BO3，资格/淘汰轮 BO5，3 胜晋级、3 负淘汰。",
            "Playoffs" to "4 支队参加双败淘汰；前两名晋级 Worlds，第三个 Worlds 名额通过 Championship Points。"
        )
        "LCS" -> rules(
            title = "2026 LCS Split 3 · Riot 官方赛制",
            source = split3Source(LCS_ID),
            "常规阶段" to "单循环 BO3，前六名进入 Playoffs。",
            "Playoffs" to "采用带 gauntlet 败者组的改良双败赛制，前三名晋级 Worlds。"
        )
        "CBLOL" -> rules(
            title = "2026 CBLOL Split 3 · Riot 官方赛制",
            source = split3Source(CBLOL_ID),
            "常规阶段" to "单循环 BO3，前六名进入 Playoffs。",
            "Playoffs" to "前六名进行双败淘汰，冠军晋级 Worlds。"
        )
        "LEC" -> rules(
            title = "2026 LEC Split 3 · Riot 官方赛制",
            source = split3Source(LEC_ID),
            "常规阶段" to "单循环常规赛，前六名进入 Playoffs。",
            "Playoffs" to "前六名进行改良双败；Handbook 的 Playoffs 文本明确前两名晋级 Worlds。",
            "Worlds 晋级卡" to "同一官方页面同时列出第 1 / 2 / 3 名均 Qualifies for Worlds；在第三席机制得到更明确官方字段前，RiftLab 不擅自解释来源。"
        )
        "LPL" -> rules(
            title = "2026 LPL Split 3 · Riot 官方赛制",
            source = split3Source(LPL_ID),
            "分组阶段" to "依据 Split 2 成绩分为 Ascend / Nirvana；Ascend 进行双循环 BO3。",
            "骑士之路" to "Ascend 剩余队伍与 Nirvana 前两名进行 BO5，产生 2 个 Playoffs 席位。",
            "Playoffs / Worlds" to "8 队双败；冠军晋级 Worlds，Championship Points 前列进入区域资格赛竞争其余 Worlds 席位。"
        )
        else -> null
    }

    private fun worldsRules(): TournamentRulesSnapshot = rules(
        title = "2026 Worlds · Riot 官方赛制",
        source = worldsSource(),
        "Play-In" to "多个拥有多席位且未赢得 MSI 的赛区中最低种子参加 4 队 BO5 双败 Play-In，产生 1 支 Swiss 队伍。",
        "Swiss" to "16 支队进行最多 5 轮 Swiss，取得 3 胜的 8 支队晋级 Knockout。",
        "Knockout" to "8 支队进行单败淘汰。"
    )

    private fun msiRules(): TournamentRulesSnapshot = rules(
        title = "2026 MSI · Riot 官方赛制",
        source = eventSource(MSI_EVENT_ID),
        "参赛规模" to "LCK / LCS / LCP / LEC / LPL 各 2 队，CBLOL 1 队，共 11 队。",
        "Play-In" to "4 支队进行 BO5 双败，1 支晋级 Bracket Stage。",
        "Bracket" to "8 支队进行 BO5 双败；MSI 冠军直接获得 Worlds 资格，亚军所在赛区获得额外 Worlds 席位。"
    )

    private fun firstStandRules(): TournamentRulesSnapshot = rules(
        title = "2026 First Stand · Riot 官方赛制",
        source = eventSource(FIRST_STAND_EVENT_ID),
        "参赛资格" to "各赛区 Split 1 头名参加，LCK 与 LPL 另有第二名参加。",
        "Round 1" to "8 支队分为两个四队双败组，全部 BO5。",
        "Round 2" to "单败 BO5；冠军赛区获得 MSI Bracket Stage bye。"
    )

    private fun rules(
        title: String,
        source: String,
        vararg items: Pair<String, String>
    ) = TournamentRulesSnapshot(
        title = title,
        items = items.map { (name, detail) ->
            TournamentRuleItem(name, detail, source, verified = true)
        },
        sourceSummary = source
    )

    private fun is2026(
        tournament: EsportsTournamentRef?,
        competitionTitle: String,
        identity: String
    ): Boolean = tournament?.startDate?.startsWith("2026") == true ||
        competitionTitle.contains("2026") || identity.contains("2026")

    private fun normalizedIdentity(
        tournament: EsportsTournamentRef?,
        competitionTitle: String,
        identity: String
    ): String = listOf(
        identity,
        tournament?.leagueId.orEmpty(),
        tournament?.leagueSlug.orEmpty(),
        tournament?.leagueName.orEmpty(),
        tournament?.slug.orEmpty(),
        competitionTitle
    ).joinToString(" ").lowercase()

    private fun canonicalLeague(tournament: EsportsTournamentRef?, token: String): String = when {
        tournament?.leagueId == LPL_ID || token.contains(" lpl") -> "LPL"
        tournament?.leagueId == LCK_ID || token.contains(" lck") -> "LCK"
        tournament?.leagueId == LEC_ID || token.contains(" lec") -> "LEC"
        tournament?.leagueId == LCS_ID || token.contains(" lcs") -> "LCS"
        tournament?.leagueId == CBLOL_ID || token.contains(" cblol") -> "CBLOL"
        tournament?.leagueId == LCP_ID || token.contains(" lcp") -> "LCP"
        else -> ""
    }

    private fun isSplit3(token: String): Boolean = token.contains("split_3") ||
        token.contains("split-3") || token.contains("split 3") || token.contains("third") ||
        token.contains("第三赛段")

    private fun isWorlds(token: String): Boolean = token.contains("worlds") || token.contains("world championship") || token.contains("全球总决赛")
    private fun isMsi(token: String): Boolean = token.contains(" msi") || token.contains("mid-season")
    private fun isFirstStand(token: String): Boolean = token.contains("first stand") || token.contains("first-stand") || token.contains("first_stand")

    private fun split3Source(leagueId: String): String =
        "Riot LoL Esports 2026 League Handbook · https://lolesports.com/en-US/season/$SEASON_ID/handbook/$SPLIT3_EVENT_ID/league/$leagueId · checked 2026-09-10"

    private fun worldsSource(): String = eventSource(WORLDS_EVENT_ID)

    private fun eventSource(eventId: String): String =
        "Riot LoL Esports 2026 League Handbook · https://lolesports.com/en-US/season/$SEASON_ID/handbook/$eventId · checked 2026-09-10"
}
