package com.riftlab.app.data

import kotlinx.coroutines.flow.Flow

/**
 * 真实数据源接入点。
 * 后续可以实现 GridLiveDataSource / LolEsportsLiveDataSource，UI 不需要改。
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
