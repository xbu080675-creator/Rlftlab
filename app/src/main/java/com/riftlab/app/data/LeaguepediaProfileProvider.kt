package com.riftlab.app.data

internal data class TeamProfileSupplement(
    val teamLinks: List<EsportsSocialLink> = emptyList(),
    val playerLinks: Map<String, List<EsportsSocialLink>> = emptyMap(),
    val management: List<EsportsStaffRef> = emptyList(),
    val status: String = "社交资料尚未同步"
)

/**
 * Small text-only LPL organisation snapshot.
 *
 * Leaguepedia/Fandom Cargo is currently unsuitable as a mobile runtime dependency: direct Android
 * requests can fail to connect and GitHub Actions probes are also anonymously rate-limited even
 * after long retry delays. Keep management data local and deterministic instead of making the
 * user's network decide whether a team page has staff information.
 *
 * Snapshot date: 2026-09-09. Source: publicly indexed Leaguepedia organisation pages plus manual
 * current-status corrections. Entries are intentionally conservative: only people/roles treated
 * as current management are included. Former owners/managers are not kept in the current roster.
 * No social handle is fabricated. The existing social-link model stays in place for a future
 * China-friendly profile JSON mirror.
 */
internal class LeaguepediaProfileProvider {
    companion object {
        private const val SOURCE = "Leaguepedia · 2026-09-09 快照"

        private fun person(name: String, role: String, realName: String = "") =
            EsportsStaffRef(name = name, role = role, source = SOURCE, realName = realName)

        private val managementByCode: Map<String, List<EsportsStaffRef>> = mapOf(
            "AL" to emptyList(),
            "BLG" to listOf(
                person("You", "MANAGER", "You Chang-Xin (尤长鑫)"),
                person("YUZZ", "LEADER", "Zhang Xin-Yu (张新宇)")
            ),
            "TES" to listOf(
                person("Hao", "CEO", "Guo Hao (郭皓)"),
                person("wly", "LEADER", "Wang Liang-Yi (王良毅)"),
                person("Lazi", "MANAGER", "Deng Bao-Xing (邓宝兴)")
            ),
            "JDG" to listOf(
                person("Choice", "CEO", "Shao Xiao-Hang (邵晓航)"),
                person("LLH", "ESPORTS_DIRECTOR", "Lan Bai-Qing (蓝柏清)"),
                person("Fei", "GENERAL_MANAGER", "Pan Fei (潘飞)"),
                person("Seek", "LEADER", "Cui Hu (崔虎)"),
                person("Vus5o", "MANAGER", "Wu Shuo (吴硕)")
            ),
            "LGD" to listOf(
                person("Bigbiao", "VICE_PRESIDENT", "Hu Biao (胡彪)"),
                person("Justin Kenna", "CO_OWNER", "Justin Kenna")
            ),
            "EDG" to listOf(
                person("Ed Zhu", "FOUNDER", "Zhu Yi-Hang (朱一航)"),
                person("Aaron", "MANAGING_DIRECTOR", "Ji Xing (姬星)"),
                person("Jasper", "DEPUTY_MANAGER", "Wang Yi-Fan (王一帆)"),
                person("Bruce", "LEADER", "You Sen-Yu (尤森煜)")
            ),
            "TT" to listOf(
                person("Liu Yi-Fei", "CEO", "Liu Yi-Fei (刘一非)"),
                person("Ben", "LEADER", "Lu Jiang-Cheng (吕江城)"),
                person("Vlone", "MANAGER", "Xiao Chu-Yu (肖楚愚)")
            ),
            "IG" to listOf(
                person("facewind", "MANAGER", "Zheng Hao-Nan (郑浩楠)"),
                person("xiaochen", "LEADER", "Wang Min-Chen (王敏晨)"),
                person("Kezman", "SUPERVISOR", "Son Dae-young (손대영)")
            ),
            "LNG" to listOf(
                person("Li Qi-Lin", "OWNER", "Li Qi-Lin (李麒麟)"),
                person("Shuang Quan", "FOUNDER_AND_CEO", "Shuang Quan (爽全)"),
                person("kaka", "LEADER", "Lin Tao (林涛)"),
                person("Jasper", "MANAGER", "Wan Lei (万磊)")
            ),
            "NIP" to listOf(
                person("Aning", "MANAGER", "Chen Ai-Ning (陈爱宁)")
            ),
            "WBG" to listOf(
                person("KIM", "SUPERVISOR", "Kim Jeong-soo (김정수)")
            ),
            "WE" to listOf(
                person("Smallorc", "CEO", "Zhang Wei (张伟)"),
                person("Sky", "GENERAL_MANAGER", "Li Xiao-Fen (李晓峰)"),
                person("Bigsam", "LEADER", "Shi Xiao-Xi (石晓曦)"),
                person("milk", "MANAGER", "Qiao Si-Yu (乔思昱)")
            )
        )

        private val aliases = mapOf(
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
            "NINJASINPYJAMASCN" to "NIP",
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

    suspend fun fetch(team: EsportsTeamRef, details: EsportsTeamDetails): TeamProfileSupplement {
        val candidates = listOf(
            details.code, team.code,
            details.name, team.name,
            details.slug, team.slug
        ).map(::token).filter { it.isNotBlank() }

        val code = candidates.firstNotNullOfOrNull { candidate ->
            when {
                managementByCode.containsKey(candidate) -> candidate
                aliases.containsKey(candidate) -> aliases[candidate]
                else -> null
            }
        }

        val management = code?.let(managementByCode::get).orEmpty()
        val status = when {
            code == null -> "人员资料快照未识别该战队"
            management.isNotEmpty() -> "$SOURCE · 管理层 ${management.size} 人 · 社交账号等待独立镜像"
            else -> "$SOURCE · 暂无可靠公开管理层记录 · 社交账号等待独立镜像"
        }

        return TeamProfileSupplement(
            teamLinks = emptyList(),
            playerLinks = emptyMap(),
            management = management,
            status = status
        )
    }
}
