#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Global team staff: network-first, APK-seed fallback for mainland/offline resilience.
staff = "app/src/main/java/com/riftlab/app/data/GlobalTeamStaffProvider.kt"
patch(
    staff,
    "package com.riftlab.app.data\n\nimport kotlinx.coroutines.Dispatchers\n",
    "package com.riftlab.app.data\n\nimport com.riftlab.app.RiftLabApplication\nimport kotlinx.coroutines.Dispatchers\n"
)
patch(
    staff,
    '''        for (endpoint in MIRRORS) {
            runCatching { getJson(endpoint, 5_000, 7_000) }
                .onSuccess { root ->
                    if (root.optInt("schemaVersion", 0) > 0 && root.optJSONObject("teams") != null) {
                        directoryCache = DirectoryCache(now, root)
                        return root
                    }
                    last = IllegalStateException("global staff mirror schema invalid")
                }
                .onFailure { last = it }
        }
        throw last ?: IllegalStateException("global staff mirror unavailable")
    }
''',
    '''        for (endpoint in MIRRORS) {
            runCatching { getJson(endpoint, 5_000, 7_000) }
                .onSuccess { root ->
                    if (root.optInt("schemaVersion", 0) > 0 && root.optJSONObject("teams") != null) {
                        directoryCache = DirectoryCache(now, root)
                        return root
                    }
                    last = IllegalStateException("global staff mirror schema invalid")
                }
                .onFailure { last = it }
        }
        loadBundled("team_staff.json")?.let { root ->
            if (root.optInt("schemaVersion", 0) > 0 && root.optJSONObject("teams") != null) {
                directoryCache = DirectoryCache(now, root)
                return root
            }
        }
        throw last ?: IllegalStateException("global staff mirror unavailable")
    }
'''
)
patch(
    staff,
    '''    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun getJson(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): JSONObject {
''',
    '''    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun loadBundled(name: String): JSONObject? = runCatching {
        RiftLabApplication.appContext.assets.open(name).bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()

    private fun getJson(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): JSONObject {
'''
)

# Global awards: same network-first + bundled-seed fallback policy.
awards = "app/src/main/java/com/riftlab/app/data/GlobalVerifiedAwardsProvider.kt"
patch(
    awards,
    "package com.riftlab.app.data\n\nimport kotlinx.coroutines.Dispatchers\n",
    "package com.riftlab.app.data\n\nimport com.riftlab.app.RiftLabApplication\nimport kotlinx.coroutines.Dispatchers\n"
)
patch(
    awards,
    '''        for (endpoint in ENDPOINTS) {
            runCatching { getJson(endpoint) }
                .onSuccess { root ->
                    if (root.optInt("schemaVersion", 0) > 0 && root.optJSONArray("awards") != null) {
                        cache.set(Cached(now, root))
                        return root
                    }
                }
                .onFailure { last = it }
        }
        throw last ?: IllegalStateException("global awards mirror unavailable")
    }
''',
    '''        for (endpoint in ENDPOINTS) {
            runCatching { getJson(endpoint) }
                .onSuccess { root ->
                    if (root.optInt("schemaVersion", 0) > 0 && root.optJSONArray("awards") != null) {
                        cache.set(Cached(now, root))
                        return root
                    }
                }
                .onFailure { last = it }
        }
        loadBundled("match_awards.json")?.let { root ->
            if (root.optInt("schemaVersion", 0) > 0 && root.optJSONArray("awards") != null) {
                cache.set(Cached(now, root))
                return root
            }
        }
        throw last ?: IllegalStateException("global awards mirror unavailable")
    }
'''
)
patch(
    awards,
    '''    private fun getJson(url: String): JSONObject {
''',
    '''    private fun loadBundled(name: String): JSONObject? = runCatching {
        RiftLabApplication.appContext.assets.open(name).bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()

    private fun getJson(url: String): JSONObject {
'''
)

# Release build bundles the global resilience seeds, just like the existing LPL seeds.
ota = ".github/workflows/ota-direct.yml"
patch(
    ota,
    '''          for name in riot_persisted_mirror.json team_profiles.json people.json team_archive.json; do
            if [ -f "data/lpl/$name" ]; then
              cp "data/lpl/$name" "app/src/main/assets/$name"
            fi
          done
          if [ -f "data/esports/esports_graph.json" ]; then
''',
    '''          for name in riot_persisted_mirror.json team_profiles.json people.json team_archive.json; do
            if [ -f "data/lpl/$name" ]; then
              cp "data/lpl/$name" "app/src/main/assets/$name"
            fi
          done
          for name in team_staff.json match_awards.json; do
            if [ -f "data/global/$name" ]; then
              cp "data/global/$name" "app/src/main/assets/$name"
            fi
          done
          if [ -f "data/esports/esports_graph.json" ]; then
'''
)

# Official LCP 2026 Championship Points formula. This is rules-only: no numeric team total is
# fabricated until a trustworthy total/split-result ingestion path is present.
lcp_rules = ROOT / "app/src/main/java/com/riftlab/app/data/OfficialLcpChampionshipPoints2026.kt"
lcp_rules.write_text('''package com.riftlab.app.data

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
''', encoding="utf-8")

# Qualification center: expose official mechanism per team without pretending the team's outcome is
# known, and attach the full LCP scoring formula to the LCP snapshot.
qualification = "app/src/main/java/com/riftlab/app/data/QualificationCenter.kt"
patch(
    qualification,
    '''        val rules = governance.rules.items.map { rule ->
            QualificationRuleRecord(
                title = rule.title,
                detail = rule.detail,
                source = rule.source,
                evidence = if (rule.verified) QualificationEvidence.OFFICIAL else QualificationEvidence.DERIVED
            )
        }
        val mechanism = officialRegionalMechanism(edition)
        return QualificationTournamentSnapshot(
''',
    '''        val mechanism = officialRegionalMechanism(edition)
        val supplementalRules = if (isLcpEdition(edition)) OfficialLcpChampionshipPoints2026.rules() else emptyList()
        val rules = (
            governance.rules.items.map { rule ->
                QualificationRuleRecord(
                    title = rule.title,
                    detail = rule.detail,
                    source = rule.source,
                    evidence = if (rule.verified) QualificationEvidence.OFFICIAL else QualificationEvidence.DERIVED
                )
            } + supplementalRules
        ).distinctBy { "${it.title}|${it.detail}" }
        val participantRoutes = edition.participantTeamCodes
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() && it != "TBD" && it != "—" }
            .distinct()
            .sorted()
            .map { teamCode ->
                TeamQualificationRoute(
                    tournamentId = edition.tournamentId,
                    teamId = stableLocalTeamId(teamCode),
                    teamCode = teamCode,
                    targetEvent = "2026 全球总决赛",
                    status = QualificationTeamState.PENDING,
                    route = listOf(
                        QualificationRouteNode(
                            id = "$teamCode:official-mechanism",
                            label = "赛区资格机制已核实",
                            detail = "Riot 官方规则已明确该赛区的 Worlds 资格机制；该队当前是已锁定、仍可争夺还是已淘汰，等待足够的正式赛果/席位数据后再判定。",
                            state = QualificationNodeState.PENDING,
                            source = handbookQualification.second,
                            evidence = QualificationEvidence.OFFICIAL
                        )
                    ),
                    source = handbookQualification.second,
                    evidence = QualificationEvidence.PENDING
                )
            }
        return QualificationTournamentSnapshot(
'''
)
patch(
    qualification,
    '''            routes = emptyList(),
            rules = rules,
            sourceSummary = listOf(handbookQualification.second, governance.rules.sourceSummary)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" + "),
            note = "资格机制已由 Riot League Handbook 核实；队伍级已锁定/仍可争夺/已淘汰状态只在正式赛果或明确席位数据足够时生成，不从参赛名单猜。",
''',
    '''            routes = participantRoutes,
            rules = rules,
            sourceSummary = listOf(
                handbookQualification.second,
                governance.rules.sourceSummary,
                supplementalRules.firstOrNull()?.source.orEmpty()
            )
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" + "),
            note = if (supplementalRules.isNotEmpty()) {
                "资格机制与 Championship Points 计分公式已由 Riot 官方资料核实；当前队伍总分仍保持未知，直到能从完整赛段结果重算或拿到官方总分表，不用不完整赛程窗口硬算。"
            } else {
                "资格机制已由 Riot League Handbook 核实；队伍级已锁定/仍可争夺/已淘汰状态只在正式赛果或明确席位数据足够时生成，不从参赛名单猜。"
            },
'''
)
patch(
    qualification,
    '''    private fun officialRegionalMechanism(edition: TournamentEditionArchiveRecord): QualificationMechanism {
''',
    '''    private fun isLcpEdition(edition: TournamentEditionArchiveRecord): Boolean {
        val token = "${edition.leagueId} ${edition.leagueSlug} ${edition.leagueName}".uppercase()
        return token.contains("113476371197627891") || token.contains("LCP")
    }

    private fun officialRegionalMechanism(edition: TournamentEditionArchiveRecord): QualificationMechanism {
'''
)

# Audit record.
audit = ROOT / "docs/RIFTLAB_DEV71_DATA_COVERAGE_AUDIT.md"
text = audit.read_text(encoding="utf-8")
addition = '''
## 2026-09-10 · LCP Championship Points + global offline resilience

- Added the Riot-published 2026 LCP Championship Points formula as an OFFICIAL rules layer. Team totals intentionally remain null until complete split results or an explicit official totals table can be ingested.
- Regional qualification snapshots now create PENDING team routes for observed participants so reverse lookup works without pretending each team's qualification outcome is already known.
- `GlobalVerifiedAwardsProvider` and `GlobalTeamStaffProvider` now fall back to APK-bundled repository seeds after GitHub Raw/jsDelivr failure.
- `ota-direct.yml` bundles `data/global/team_staff.json` and `data/global/match_awards.json` into the APK, matching the existing resilient LPL seed pattern.
- This improves mainland/offline behavior without reviving Gitee OTA or changing the GitHub-only update transport.
'''
if addition.strip() not in text:
    audit.write_text(text.rstrip() + "\n" + addition, encoding="utf-8")

print("dev71 resilience + LCP points patch prepared")
