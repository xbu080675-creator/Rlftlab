package com.riftlab.app.data

/**
 * 2026 LPL Championship Points snapshot.
 *
 * This is deliberately separate from Riot getStandings ranking points. The latter describes
 * the selected tournament/group table, while Championship Points are annual Worlds-qualification
 * points accumulated across splits.
 *
 * Split 3 values below are guaranteed-floor points after the 2026-09-08 matchday, not a guessed
 * final placement. Keep [updatedThrough] visible in UI until this snapshot is refreshed.
 */
data class LplChampionshipPointRow(
    val rank: Int,
    val teamCode: String,
    val split1: Int,
    val split2: Int,
    val split3Floor: Int,
    val total: Int,
    val status: LplWorldsStatus
)

enum class LplWorldsStatus(val label: String) {
    WORLDS_LOCKED("世界赛已锁定"),
    REGIONAL_LOCKED("资格赛已锁定"),
    ELIMINATED("无缘资格赛")
}

object LplChampionshipPoints2026 {
    const val season = 2026
    const val updatedThrough = "2026-09-08 赛后"
    const val sourceLabel = "英雄联盟赛事数据 · 全球总决赛实时积分"
    const val note = "S3 为当前已锁定的保底积分；后续最终名次只会上调或保持，不提前按未完成赛果结算。"

    val rows: List<LplChampionshipPointRow> = listOf(
        LplChampionshipPointRow(1, "BLG", 80, 110, 110, 300, LplWorldsStatus.WORLDS_LOCKED),
        LplChampionshipPointRow(2, "AL", 20, 30, 80, 130, LplWorldsStatus.REGIONAL_LOCKED),
        LplChampionshipPointRow(3, "TES", 10, 80, 15, 105, LplWorldsStatus.REGIONAL_LOCKED),
        LplChampionshipPointRow(4, "IG", 10, 0, 80, 90, LplWorldsStatus.REGIONAL_LOCKED),
        LplChampionshipPointRow(5, "WE", 5, 50, 30, 85, LplWorldsStatus.REGIONAL_LOCKED),
        LplChampionshipPointRow(6, "JDG", 50, 15, 15, 80, LplWorldsStatus.REGIONAL_LOCKED),
        LplChampionshipPointRow(7, "LGD", 0, 15, 50, 65, LplWorldsStatus.ELIMINATED),
        LplChampionshipPointRow(8, "WBG", 40, 0, 0, 40, LplWorldsStatus.ELIMINATED),
        LplChampionshipPointRow(9, "NIP", 5, 0, 30, 35, LplWorldsStatus.ELIMINATED),
        LplChampionshipPointRow(10, "EDG", 0, 10, 0, 10, LplWorldsStatus.ELIMINATED),
        LplChampionshipPointRow(10, "TT", 0, 10, 0, 10, LplWorldsStatus.ELIMINATED),
        LplChampionshipPointRow(12, "LNG", 0, 0, 0, 0, LplWorldsStatus.ELIMINATED)
    )
}
