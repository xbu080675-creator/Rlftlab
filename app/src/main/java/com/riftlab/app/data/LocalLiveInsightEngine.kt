package com.riftlab.app.data

/**
 * Deterministic local interpretation of an already verified live snapshot.
 *
 * This class is not a match-data provider and never fills missing fields. It only turns the
 * current gold differential into short explanatory copy after a real provider frame is present.
 */
class LocalLiveInsightEngine : AiInsightEngine {
    override suspend fun analyze(snapshot: LiveSnapshot, previous: LiveSnapshot?): String {
        if (snapshot.gameId.isBlank()) {
            return "等待实时 Provider 有效帧；未接入前不生成局势判断。"
        }

        val diff = snapshot.goldDiff
        return when {
            diff >= 3000 -> "${snapshot.blue} 已建立明显经济优势，当前要观察资源控制能否继续转化。"
            diff >= 1000 -> "${snapshot.blue} 暂时领先 ${formatGold(diff)}，优势存在，但还远没到一波失误无伤大雅的程度。"
            diff <= -1000 -> "${snapshot.red} 当前掌握经济领先，${snapshot.blue} 需要靠下一轮资源交换止损。"
            else -> "经济仍接近均势，下一波关键资源团的收益可能直接改变领先方。"
        }
    }

    private fun formatGold(value: Int): String = if (kotlin.math.abs(value) >= 1000) {
        "%.1fK".format(value / 1000f)
    } else value.toString()
}
