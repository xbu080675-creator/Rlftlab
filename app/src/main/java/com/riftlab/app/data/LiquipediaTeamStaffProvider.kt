package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

internal data class TeamStaffSupplement(
    val staff: List<EsportsStaffRef> = emptyList(),
    val status: String = "Liquipedia · 未同步"
)

/**
 * Coaching/organisation supplement.
 *
 * Riot getTeams exposes the active player roster but, as of 2026-09, does not expose coaches or
 * staff. Keep this source explicit: UI must never label these rows as Riot official data.
 */
internal class LiquipediaTeamStaffProvider {
    suspend fun fetch(team: EsportsTeamRef, details: EsportsTeamDetails? = null): TeamStaffSupplement =
        withContext(Dispatchers.IO) {
            val candidates = pageCandidates(team, details)
            var lastError = ""
            for (page in candidates) {
                val result = runCatching {
                    val document = Jsoup.connect("https://liquipedia.net/leagueoflegends/$page")
                        .userAgent("RiftLab/1.0 (Android esports companion; github.com/xbu080675-creator/Rlftlab)")
                        .referrer("https://liquipedia.net/leagueoflegends/")
                        .timeout(10_000)
                        .get()
                    parseActiveOrganisation(document.selectFirst("#mw-content-text") ?: document.body())
                }
                val staff = result.getOrNull().orEmpty()
                if (staff.isNotEmpty()) {
                    return@withContext TeamStaffSupplement(
                        staff = staff,
                        status = "Liquipedia · Coaching Staff · ${staff.size} 人"
                    )
                }
                lastError = result.exceptionOrNull()?.message.orEmpty()
            }
            TeamStaffSupplement(
                status = if (lastError.isBlank()) "Liquipedia · 暂未返回教练组" else "Liquipedia · 教练组同步失败"
            )
        }

    private fun parseActiveOrganisation(root: Element): List<EsportsStaffRef> {
        val organisation = root.select("h2").firstOrNull { headingText(it).equals("Organization", true) }
            ?: return emptyList()

        var node = organisation.nextElementSibling()
        var active = false
        val rows = mutableListOf<EsportsStaffRef>()

        while (node != null) {
            if (node.tagName().equals("h2", true)) break
            if (node.tagName().equals("h3", true)) {
                val title = headingText(node)
                if (title.equals("Active", true)) {
                    active = true
                    node = node.nextElementSibling()
                    continue
                }
                if (active) break
            }

            if (active) {
                val tables = if (node.tagName().equals("table", true)) listOf(node) else node.select("table")
                for (table in tables) {
                    for (tr in table.select("tr")) {
                        parseStaffRow(tr)?.let(rows::add)
                    }
                }
            }
            node = node.nextElementSibling()
        }
        return rows.distinctBy { token(it.name) + "|" + token(it.role) }
    }

    private fun parseStaffRow(row: Element): EsportsStaffRef? {
        val cells = row.select("td")
        if (cells.size < 2) return null
        val texts = cells.map { it.text().trim() }
        val roleIndex = texts.indexOfFirst { isStaffRole(it) }
        if (roleIndex < 0) return null

        val id = texts.firstOrNull().orEmpty().substringBefore(" ").trim()
        if (id.isBlank() || id.equals("ID", true)) return null
        val realName = texts.getOrNull(1).orEmpty().takeUnless { it.equals(id, true) }.orEmpty()
        return EsportsStaffRef(
            name = id,
            role = normalizeStaffRole(texts[roleIndex]),
            source = "Liquipedia",
            realName = realName
        )
    }

    private fun isStaffRole(value: String): Boolean {
        val v = value.lowercase()
        return v.contains("coach") || v.contains("analyst") || v == "manager" || v.contains("strategic")
    }

    private fun normalizeStaffRole(value: String): String {
        val v = value.lowercase()
        return when {
            v.contains("head coach") -> "HEAD_COACH"
            v.contains("assistant coach") -> "ASSISTANT_COACH"
            v.contains("strategic") && v.contains("coach") -> "STRATEGIC_COACH"
            v.contains("coach") -> "COACH"
            v.contains("analyst") -> "ANALYST"
            v.contains("manager") -> "MANAGER"
            else -> value.uppercase().replace(' ', '_')
        }
    }

    private fun headingText(element: Element): String =
        element.selectFirst(".mw-headline")?.text()?.trim() ?: element.text().trim()

    private fun pageCandidates(team: EsportsTeamRef, details: EsportsTeamDetails?): List<String> {
        val raw = listOf(
            details?.slug.orEmpty(),
            team.slug,
            details?.name.orEmpty(),
            team.name
        ).filter { it.isNotBlank() }

        return raw.flatMap { value ->
            val normalized = value.trim().replace('_', '-').replace(' ', '-')
                .split('-').filter { it.isNotBlank() }
                .joinToString("_") { word ->
                    word.lowercase().replaceFirstChar { c -> c.uppercase() }
                }
            listOf(normalized, value.trim().replace(' ', '_'))
        }.filter { it.isNotBlank() }.distinct()
    }

    private fun token(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
}
