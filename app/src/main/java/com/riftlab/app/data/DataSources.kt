package com.riftlab.app.data

import kotlinx.coroutines.flow.Flow

/**
 * 联赛赛程/赛前数据接入点。
 */
interface ScheduleDataSource {
    suspend fun fetchLeagueSchedule(): List<ScheduledEsportsMatch>
}

/**
 * 队伍与当前 Riot roster 接入点。
 */
interface TeamDataSource {
    suspend fun fetchTeam(slug: String): EsportsTeamDetails?
}

/**
 * 实时比赛数据接入点。UI 只依赖标准化 LiveSnapshot。
 */
interface LiveMatchDataSource {
    fun observe(matchId: String): Flow<LiveSnapshot>
}

/**
 * AI 接入点。后续把用户的中转 API 接在这里。
 */
interface AiInsightEngine {
    suspend fun analyze(snapshot: LiveSnapshot, previous: LiveSnapshot?): String
}
