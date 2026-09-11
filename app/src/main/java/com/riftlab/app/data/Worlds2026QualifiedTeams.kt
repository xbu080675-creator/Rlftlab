package com.riftlab.app.data

/**
 * Official Worlds 2026 qualification snapshot.
 *
 * This is a dated authoritative snapshot, not a predicted seed table. It exists because Worlds can
 * publish qualified teams before its own match schedule/standings payload contains participants.
 * The source/check date is carried into every consumer so a later sync can replace it cleanly.
 */
internal data class WorldsQualifiedTeam(
    val code: String,
    val region: String,
    val qualificationOrigin: String,
    val source: String,
    val checkedAt: String
)

internal object Worlds2026QualifiedTeams {
    const val TOURNAMENT_ID = "115660540725177488"
    const val CHECKED_AT = "2026-09-11"
    const val SOURCE = "Riot LoL Esports · Worlds 2026 Qualifying Teams · checked 2026-09-11"
    const val SOURCE_URL = "https://lolesports.com/en-GB/tournament/115660540725177488/overview"

    val teams = listOf(
        WorldsQualifiedTeam("KC", "LEC", "Riot Worlds 2026 qualifying team", SOURCE, CHECKED_AT),
        WorldsQualifiedTeam("G2", "LEC", "Riot Worlds 2026 qualifying team", SOURCE, CHECKED_AT),
        WorldsQualifiedTeam("DK", "LCK", "Riot Worlds 2026 qualifying team", SOURCE, CHECKED_AT),
        WorldsQualifiedTeam("T1", "LCK", "Riot Worlds 2026 qualifying team", SOURCE, CHECKED_AT),
        WorldsQualifiedTeam("GEN", "LCK", "Riot Worlds 2026 qualifying team", SOURCE, CHECKED_AT),
        WorldsQualifiedTeam("HLE", "LCK", "MSI 2026 champion / Riot Worlds 2026 qualifying team", SOURCE, CHECKED_AT),
        WorldsQualifiedTeam("CFO", "LCP", "Riot Worlds 2026 qualifying team", SOURCE, CHECKED_AT),
        WorldsQualifiedTeam("MVK", "LCP", "Riot Worlds 2026 qualifying team", SOURCE, CHECKED_AT),
        WorldsQualifiedTeam("TSW", "LCP", "Riot Worlds 2026 qualifying team", SOURCE, CHECKED_AT),
        WorldsQualifiedTeam("BLG", "LPL", "Riot Worlds 2026 qualifying team", SOURCE, CHECKED_AT)
    )

    fun find(code: String): WorldsQualifiedTeam? =
        teams.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }
}
