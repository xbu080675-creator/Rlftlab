#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) Team archive: keep the curated LPL graph, but route every non-LPL / graph-miss team
# through the new global archive provider instead of returning an empty archive.
path = "app/src/main/java/com/riftlab/app/data/TeamArchiveProvider.kt"
replace_once(
    path,
    "internal class TeamArchiveProvider {\n    companion object {",
    "internal class TeamArchiveProvider {\n    private val globalProvider = GlobalTeamArchiveProvider()\n\n    companion object {"
)
replace_once(
    path,
    '''    suspend fun fetch(team: EsportsTeamRef, details: EsportsTeamDetails): TeamArchiveSupplement {\n        val code = resolveCode(team, details)\n        if (code.isBlank()) return TeamArchiveSupplement(sourceMode = "unresolved")\n        val root = directory()\n        return if (root.optJSONArray("teams") != null) {\n            parseGraph(root, code)\n        } else {\n            val node = root.optJSONObject("teams")?.optJSONObject(code)\n                ?: return TeamArchiveSupplement(updatedAt = root.optString("updatedAt"), sourceMode = "missing")\n            parseLegacy(node, root.optString("updatedAt"))\n        }\n    }''',
    '''    suspend fun fetch(team: EsportsTeamRef, details: EsportsTeamDetails): TeamArchiveSupplement {\n        val code = resolveCode(team, details)\n        val local = runCatching {\n            if (code.isBlank()) {\n                TeamArchiveSupplement(sourceMode = "local-code-unresolved")\n            } else {\n                val root = directory()\n                if (root.optJSONArray("teams") != null) {\n                    parseGraph(root, code)\n                } else {\n                    val node = root.optJSONObject("teams")?.optJSONObject(code)\n                    if (node == null) TeamArchiveSupplement(updatedAt = root.optString("updatedAt"), sourceMode = "legacy-missing-team")\n                    else parseLegacy(node, root.optString("updatedAt"))\n                }\n            }\n        }.getOrElse { TeamArchiveSupplement(sourceMode = "local-archive-error") }\n\n        if (hasArchiveData(local)) return local\n\n        // dev.72: overseas teams must receive the same archive surface as LPL teams. Riot Teams\n        // remains roster authority; Leaguepedia supplies region/organization/result history with\n        // explicit provenance. A source failure keeps the local result rather than fabricating data.\n        val global = runCatching { globalProvider.fetch(team, details) }.getOrNull()\n        return global?.takeIf(::hasArchiveData) ?: local\n    }\n\n    private fun hasArchiveData(value: TeamArchiveSupplement): Boolean =\n        value.identity.foundedAt.isNotBlank() || value.identity.lolDivisionFoundedAt.isNotBlank() ||\n            value.identity.region.isNotBlank() || value.identity.city.isNotBlank() ||\n            value.operators.isNotEmpty() || value.parentOrganizations.isNotEmpty() ||\n            value.peopleInCharge.isNotEmpty() || value.honors.isNotEmpty() || value.results.isNotEmpty() ||\n            value.lineage.isNotEmpty() || value.alumni.isNotEmpty()'''
)
replace_once(
    path,
    '''    private fun resolveCode(team: EsportsTeamRef, details: EsportsTeamDetails): String {\n        val candidates = listOf(details.code, team.code, details.name, team.name, details.slug, team.slug).map(::token)\n        return candidates.firstNotNullOfOrNull { aliases[it] }\n            ?: candidates.firstOrNull { it in knownCodes }\n            .orEmpty()\n    }''',
    '''    private fun resolveCode(team: EsportsTeamRef, details: EsportsTeamDetails): String {\n        val explicitCodes = listOf(details.code, team.code).map(::token).filter { it.isNotBlank() }\n        val candidates = listOf(details.code, team.code, details.name, team.name, details.slug, team.slug).map(::token)\n        return candidates.firstNotNullOfOrNull { aliases[it] }\n            ?: explicitCodes.firstOrNull { it in knownCodes }\n            ?: explicitCodes.firstOrNull()\n            .orEmpty()\n    }'''
)

# 2) H2H: make perspective explicit. Multiple L rows can be correct, but the old UI did not say
# whose perspective W/L belonged to, so it looked like a calculation error.
path = "app/src/main/java/com/riftlab/app/ui/RiftLabApp.kt"
replace_once(
    path,
    '''                Text(\n                    if (data.recentHeadToHead.isEmpty()) "当前历史窗口没有可核实的近期直接交手。" else recentSeriesLabel(data.blue, data.recentHeadToHead),\n                    color = RiftText,\n                    fontSize = 9.sp,\n                    lineHeight = 14.sp\n                )\n                Text("SOURCE  Unified Schedule · Riot/Cito", color = RiftMuted, fontSize = 8.sp)''',
    '''                Text(\n                    if (data.recentHeadToHead.isEmpty()) {\n                        "当前历史窗口没有可核实的近期直接交手。"\n                    } else {\n                        "${data.blue} 视角（W/L 均以 ${data.blue} 为准）\\n" + recentSeriesRows(data.recentHeadToHead)\n                    },\n                    color = RiftText,\n                    fontSize = 11.sp,\n                    lineHeight = 17.sp\n                )\n                Text("SOURCE  Unified Schedule · Riot/Cito · 结果视角已标明", color = RiftMuted, fontSize = 9.sp)'''
)
replace_once(
    path,
    '''private fun recentSeriesLabel(team: String, rows: List<PreRecentSeries>): String = buildString {\n    append(team).append("\\n")\n    if (rows.isEmpty()) append("当前历史窗口暂无已结束 Series")\n    else rows.forEach { row ->\n        append(row.outcome).append("  ")\n            .append(row.scoreFor).append(':').append(row.scoreAgainst)\n            .append(" vs ").append(row.opponentCode)\n            .append(" · ").append(row.startTimeIso.take(10))\n            .append("\\n")\n    }\n}.trimEnd()''',
    '''private fun recentSeriesLabel(team: String, rows: List<PreRecentSeries>): String = buildString {\n    append(team).append("\\n")\n    if (rows.isEmpty()) append("当前历史窗口暂无已结束 Series")\n    else append(recentSeriesRows(rows))\n}.trimEnd()\n\nprivate fun recentSeriesRows(rows: List<PreRecentSeries>): String = buildString {\n    rows.forEach { row ->\n        append(row.outcome).append("  ")\n            .append(row.scoreFor).append(':').append(row.scoreAgainst)\n            .append(" vs ").append(row.opponentCode)\n            .append(" · ").append(row.startTimeIso.take(10))\n            .append("\\n")\n    }\n}.trimEnd()'''
)

# 3) Comprehensive coverage card: dev.71 was too small on a phone. Increase the hierarchy without
# changing the layout density enough to push the qualification section off-screen.
path = "app/src/main/java/com/riftlab/app/ui/ComprehensiveDataCoverageUi.kt"
text = Path(path).read_text(encoding="utf-8")
replacements = {
    'fontSize = 11.sp, fontWeight = FontWeight.Bold)': 'fontSize = 13.sp, fontWeight = FontWeight.Bold)',
    'fontSize = 9.sp)\n            }\n            Text("${report.scorePercent}%", color = RiftText, fontSize = 18.sp': 'fontSize = 10.sp)\n            }\n            Text("${report.scorePercent}%", color = RiftText, fontSize = 22.sp',
    'Text(cell.domain.label, color = RiftText, fontSize = 9.sp': 'Text(cell.domain.label, color = RiftText, fontSize = 11.sp',
    'Text(cell.state.label, color = color, fontSize = 9.sp)': 'Text(cell.state.label, color = color, fontSize = 10.sp)',
    'Text("${cell.availableFields}/${cell.requiredFields}", color = RiftMuted, fontSize = 8.sp)': 'Text("${cell.availableFields}/${cell.requiredFields}", color = RiftMuted, fontSize = 9.sp)',
    'color = RiftMuted,\n            fontSize = 9.sp\n        )': 'color = RiftMuted,\n            fontSize = 10.sp\n        )',
}
for old, new in replacements.items():
    if old in text:
        text = text.replace(old, new, 1)
Path(path).write_text(text, encoding="utf-8")

print("dev72 user-audit patch applied")
