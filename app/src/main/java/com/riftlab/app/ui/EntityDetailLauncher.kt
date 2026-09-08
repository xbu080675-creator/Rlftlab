package com.riftlab.app.ui

import android.content.Context
import android.content.Intent
import com.riftlab.app.data.EsportsTeamRef
import com.riftlab.app.data.ScheduledEsportsMatch

internal object EntityDetailLauncher {
    const val EXTRA_MODE = "riftlab.entity.mode"
    const val EXTRA_TEAM_ID = "riftlab.entity.team.id"
    const val EXTRA_TEAM_CODE = "riftlab.entity.team.code"
    const val EXTRA_TEAM_NAME = "riftlab.entity.team.name"
    const val EXTRA_TEAM_SLUG = "riftlab.entity.team.slug"
    const val EXTRA_TEAM_IMAGE = "riftlab.entity.team.image"
    const val EXTRA_MATCH_ID = "riftlab.entity.match.id"
    const val EXTRA_TEAM_A = "riftlab.entity.match.teamA"
    const val EXTRA_TEAM_B = "riftlab.entity.match.teamB"

    const val MODE_TEAM = "team"
    const val MODE_MATCH = "match"

    fun openTeam(context: Context, team: EsportsTeamRef) {
        context.startActivity(
            baseIntent(context, MODE_TEAM)
                .putExtra(EXTRA_TEAM_ID, team.id)
                .putExtra(EXTRA_TEAM_CODE, team.code)
                .putExtra(EXTRA_TEAM_NAME, team.name)
                .putExtra(EXTRA_TEAM_SLUG, team.slug)
                .putExtra(EXTRA_TEAM_IMAGE, team.imageUrl)
        )
    }

    fun openMatch(context: Context, match: ScheduledEsportsMatch) {
        context.startActivity(
            baseIntent(context, MODE_MATCH)
                .putExtra(EXTRA_MATCH_ID, match.matchId.ifBlank { match.eventId })
                .putExtra(EXTRA_TEAM_A, match.teams.getOrNull(0)?.code.orEmpty())
                .putExtra(EXTRA_TEAM_B, match.teams.getOrNull(1)?.code.orEmpty())
        )
    }

    fun openMatch(context: Context, matchId: String, teamA: String = "", teamB: String = "") {
        context.startActivity(
            baseIntent(context, MODE_MATCH)
                .putExtra(EXTRA_MATCH_ID, matchId)
                .putExtra(EXTRA_TEAM_A, teamA)
                .putExtra(EXTRA_TEAM_B, teamB)
        )
    }

    private fun baseIntent(context: Context, mode: String): Intent =
        Intent(context, EntityDetailActivity::class.java)
            .putExtra(EXTRA_MODE, mode)
            .apply {
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
}
