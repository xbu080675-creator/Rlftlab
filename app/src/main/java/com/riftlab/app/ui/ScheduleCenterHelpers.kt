package com.riftlab.app.ui

import com.riftlab.app.data.EsportsTeamRef
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.ScheduleActivityState
import com.riftlab.app.data.ScheduleMatchPhase
import com.riftlab.app.data.ScheduledEsportsMatch

internal fun translateSectionName(value: String): String = when {
    value.contains("Ascend", true) -> "登峰组"
    value.contains("Nirvana", true) -> "涅槃组"
    else -> translateStageName(value)
}

internal fun translateStageName(value: String): String = when {
    value.contains("Knights", true) -> "骑士之路"
    value.equals("Playoffs", true) -> "淘汰赛"
    value.contains("Regional", true) -> "区域资格赛"
    value.contains("Group Stage", true) -> "组内赛"
    value.equals("Finals", true) -> "决赛"
    else -> value.uppercase()
}

internal fun scheduleActivityText(value: ScheduleActivityState): String = when (value) {
    ScheduleActivityState.GAME_LIVE -> "小局直播"
    ScheduleActivityState.EVENT_LIVE -> "赛事进行中"
    ScheduleActivityState.BETWEEN_GAMES -> "局间"
    ScheduleActivityState.UPCOMING -> "待开"
    ScheduleActivityState.COMPLETED -> "已结束"
}

internal fun bracketState(value: String): String = when {
    value.contains("complete", true) -> "已结束"
    value.contains("progress", true) || value.equals("live", true) -> "LIVE"
    else -> "待开"
}

internal fun scoreFor(team: EsportsTeamRef?, schedule: ScheduledEsportsMatch?): String {
    if (team == null) return "—"
    val scheduled = schedule?.teams?.firstOrNull {
        it.id == team.id || canonicalTeamCode(it) == canonicalTeamCode(team)
    }
    val score = scheduled?.gameWins ?: team.gameWins
    val completed = schedule?.let { MatchSessionStore.schedulePhase(it) == ScheduleMatchPhase.COMPLETED } == true
    return if (score > 0 || completed) score.toString() else "—"
}

private fun canonicalTeamCode(team: EsportsTeamRef): String =
    team.code.ifBlank { team.name }.uppercase()
