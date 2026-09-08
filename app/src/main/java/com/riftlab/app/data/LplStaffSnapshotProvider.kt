package com.riftlab.app.data

internal data class TeamStaffSupplement(
    val staff: List<EsportsStaffRef> = emptyList(),
    val status: String = "教练组尚未同步"
)

/**
 * Small, text-only LPL coaching staff snapshot.
 *
 * Riot getTeams is authoritative for the live player roster, but its current payload does not
 * expose coaches/staff. Direct Liquipedia/Leaguepedia requests are rate-limited (429) and are not
 * suitable as a mobile runtime dependency, so dev.18 ships a tiny sourced snapshot instead.
 * Images remain remote and are NOT bundled here.
 *
 * Snapshot date: 2026-09-09. Source labels stay visible in UI. This provider is intentionally
 * isolated so it can later be replaced by a China-friendly remote JSON without changing UI/model.
 */
internal class LplStaffSnapshotProvider {
    companion object {
        private const val SOURCE = "Liquipedia · 2026-09-09 快照"

        private fun staff(name: String, role: String, realName: String = "") =
            EsportsStaffRef(name = name, role = role, source = SOURCE, realName = realName)

        private val byCode: Map<String, List<EsportsStaffRef>> = mapOf(
            "AL" to listOf(
                staff("MingZhe", "HEAD_COACH", "Zhang Yu"),
                staff("BigWei", "HEAD_COACH", "Fu Chien-wei"),
                staff("Pewpew", "COACH", "Jin Can"),
                staff("Teacherma", "ASSISTANT_COACH", "Jiang Chen"),
                staff("Despa1r", "ASSISTANT_COACH", "Zhou Lipeng"),
                staff("May", "ANALYST", "Bo Renjie")
            ),
            "BLG" to listOf(
                staff("Daeny", "HEAD_COACH", "Yang Dae-in"),
                staff("Ben", "COACH", "Nam Dong-hyun"),
                staff("Chieh", "ASSISTANT_COACH", "Li Chieh"),
                staff("Zyb", "ANALYST", "Zhuang Yibin")
            ),
            "TES" to listOf(
                staff("Poppy", "HEAD_COACH", "Chang Po-hao"),
                staff("River", "COACH", "Wang Yang"),
                staff("Shuijing", "COACH"),
                staff("Bobo", "ASSISTANT_COACH"),
                staff("Leo", "ASSISTANT_COACH"),
                staff("BZ", "ANALYST", "Zhao Yetong")
            ),
            "JDG" to listOf(
                staff("Tabe", "HEAD_COACH", "Wong Pak Kan"),
                staff("Xiasu", "COACH", "Chen Long"),
                staff("Zoom", "COACH", "Zhang Xingran"),
                staff("Xiaobai", "COACH", "Yang Zhonghe"),
                staff("Huge", "ASSISTANT_COACH", "Cui Hu"),
                staff("Vus5o", "ANALYST", "Wu Shuo"),
                staff("Karma", "ANALYST", "Huang Yihong"),
                staff("Zizheng", "ANALYST", "Jia Zizheng")
            ),
            "LGD" to listOf(
                staff("1874", "HEAD_COACH", "Chen Lixin"),
                staff("Chelizi", "ASSISTANT_COACH", "Xia Hanxi")
            ),
            "EDG" to listOf(
                staff("Mni", "HEAD_COACH", "Peng Fang"),
                staff("Liet", "ANALYST", "Liu Zhengyang")
            ),
            "TT" to listOf(
                staff("NoName", "HEAD_COACH", "Zhou Qilin"),
                staff("Benny", "ASSISTANT_COACH", "Lien Hsiu-chi"),
                staff("Vlone", "ANALYST")
            ),
            "IG" to listOf(
                staff("Helper", "HEAD_COACH", "Kwon Young-jae"),
                staff("Fury", "COACH", "Lee Jin-yong"),
                staff("Kezman", "SUPERVISOR", "Son Dae-young")
            ),
            "LNG" to listOf(
                staff("YiL", "HEAD_COACH", "Wang Liangyi"),
                staff("333", "ANALYST", "Huang Yihong")
            ),
            "NIP" to listOf(
                staff("Maizijian", "HEAD_COACH", "Zeng Xinyi"),
                staff("Cluo", "ANALYST", "Shin Min-sung")
            ),
            "WBG" to listOf(
                staff("Shine", "HEAD_COACH", "Shin Dong-wook"),
                staff("Clearlove", "HEAD_COACH", "Ming Kai"),
                staff("Tselin", "COACH", "Zhao Zelin"),
                staff("Medusa", "ANALYST")
            ),
            "WE" to listOf(
                staff("Condi", "ASSISTANT_COACH", "Xiang Renjie"),
                staff("JinJin", "ASSISTANT_COACH", "Jin Guanghua"),
                staff("694", "ASSISTANT_COACH", "He Xin"),
                staff("zhaozhao", "ANALYST")
            )
        )

        private val nameAliases = mapOf(
            "ANYONESLEGEND" to "AL",
            "BILIBILIGAMING" to "BLG",
            "TOPESPORTS" to "TES",
            "BEIJINGJDGESPORTS" to "JDG",
            "JDGAMING" to "JDG",
            "LGDGAMING" to "LGD",
            "EDWARDGAMING" to "EDG",
            "THUNDERTALKGAMING" to "TT",
            "INVICTUSGAMING" to "IG",
            "SUZHOULNGESPORTS" to "LNG",
            "LNGESPORTS" to "LNG",
            "SHENZHENNINJASINPYJAMAS" to "NIP",
            "NINJASINPYJAMAS" to "NIP",
            "WEIBOGAMING" to "WBG",
            "XIATEKTEAMWE" to "WE",
            "XIAN TEAM WE" to "WE",
            "TEAMWE" to "WE"
        ).mapKeys { token(it.key) }

        private fun token(value: String): String =
            value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
    }

    fun fetch(team: EsportsTeamRef, details: EsportsTeamDetails? = null): TeamStaffSupplement {
        val candidates = listOf(
            details?.code.orEmpty(), team.code,
            details?.name.orEmpty(), team.name,
            details?.slug.orEmpty(), team.slug
        ).map(::token).filter { it.isNotBlank() }

        val code = candidates.firstNotNullOfOrNull { candidate ->
            when {
                byCode.containsKey(candidate) -> candidate
                nameAliases.containsKey(candidate) -> nameAliases[candidate]
                else -> null
            }
        }
        val rows = code?.let(byCode::get).orEmpty()
        return if (rows.isNotEmpty()) {
            TeamStaffSupplement(
                staff = rows,
                status = "$SOURCE · ${rows.size} 人"
            )
        } else {
            TeamStaffSupplement(status = "教练组暂无可靠快照 · 不猜测")
        }
    }
}
