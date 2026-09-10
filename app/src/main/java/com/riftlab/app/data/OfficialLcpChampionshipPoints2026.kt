package com.riftlab.app.data

/**
 * Riot-published LCP 2026 Championship Points formula.
 *
 * These are official scoring rules, not a hand-maintained current leaderboard. Numeric team totals
 * remain null until RiftLab can reproduce them from complete split results or consume an explicit
 * official total. This separation prevents a stale/partial schedule window from becoming fake CP.
 */
internal object OfficialLcpChampionshipPoints2026 {
    const val sourceLabel =
        "LoL Esports · LCP 2026 Season Primer · https://lolesports.com/en-SG/news/lcp-2026-season-primer · checked 2026-09-10"

    fun rules(): List<QualificationRuleRecord> = listOf(
        QualificationRuleRecord(
            title = "Split 1 / 2 Regular Season · Game Points",
            detail = "每个小局胜利 +1、失败 -1；Split 2 的 Regular Season Game Points 在赛段结束时乘 2。",
            source = sourceLabel,
            evidence = QualificationEvidence.OFFICIAL
        ),
        QualificationRuleRecord(
            title = "Split 1 / 2 Regular Season · Standing Points",
            detail = "常规赛排名额外给分：第 1 名 7 分、第 2 名 6 分，并依次递减。Game Points 若为负会在该赛段常规赛结束时重置为 0；Standing Points 不受该重置影响。",
            source = sourceLabel,
            evidence = QualificationEvidence.OFFICIAL
        ),
        QualificationRuleRecord(
            title = "Split 1 / 2 Knockout · Top 4",
            detail = "淘汰阶段第 1 / 2 / 3 / 4 名分别获得 20 / 15 / 10 / 5 Championship Points。",
            source = sourceLabel,
            evidence = QualificationEvidence.OFFICIAL
        ),
        QualificationRuleRecord(
            title = "Split 3 Swiss",
            detail = "3-0 / 3-1 / 3-2 / 2-3 / 1-3 / 0-3 分别获得 50 / 40 / 30 / 15 / 3 / 0 分；三负队伍的名次加赛胜者额外 +5。",
            source = sourceLabel,
            evidence = QualificationEvidence.OFFICIAL
        ),
        QualificationRuleRecord(
            title = "Split 3 Playoffs / Worlds",
            detail = "Split 3 Playoffs 第 3 名额外获得 15 分；全年 Championship Points 最高的队伍获得一个 Worlds 席位，与 Playoffs 前两名共同晋级。",
            source = sourceLabel,
            evidence = QualificationEvidence.OFFICIAL
        )
    )
}
