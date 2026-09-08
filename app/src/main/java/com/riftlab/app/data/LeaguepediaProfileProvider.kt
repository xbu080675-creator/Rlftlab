package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class TeamProfileSupplement(
    val teamLinks: List<EsportsSocialLink> = emptyList(),
    val playerLinks: Map<String, List<EsportsSocialLink>> = emptyMap(),
    val management: List<EsportsStaffRef> = emptyList(),
    val status: String = "社交资料尚未同步"
)

/**
 * Small-text profile supplement from Leaguepedia Cargo.
 *
 * Images still come from Riot/OP.GG and remain network-cached by Coil. This provider only fetches
 * text URLs and current organisation roles. Missing fields are omitted rather than fabricated.
 */
internal class LeaguepediaProfileProvider {
    companion object {
        private const val BASE = "https://lol.fandom.com/api.php"
        private const val SOURCE = "Leaguepedia"
        private val managementTokens = listOf(
            "MANAGER", "GENERALMANAGER", "TEAMMANAGER", "ASSISTANTMANAGER",
            "LEADER", "SUPERVISOR", "DIRECTOR", "OWNER", "COOWNER",
            "CEO", "CHIEFEXECUTIVEOFFICER", "COO", "CHIEFOPERATINGOFFICER",
            "HEADOFESPORTS", "HEADOFLOL"
        )
    }

    private val cache = linkedMapOf<String, TeamProfileSupplement>()

    suspend fun fetch(team: EsportsTeamRef, details: EsportsTeamDetails): TeamProfileSupplement {
        val key = token(details.code.ifBlank { team.code }.ifBlank { details.name.ifBlank { team.name } })
        cache[key]?.let { return it }

        val result = withContext(Dispatchers.IO) {
            runCatching { fetchInternal(team, details) }
                .getOrElse { TeamProfileSupplement(status = "Leaguepedia 社交资料暂不可用 · ${it.message?.take(80).orEmpty()}") }
        }
        if (result.teamLinks.isNotEmpty() || result.playerLinks.isNotEmpty() || result.management.isNotEmpty()) {
            cache[key] = result
        }
        return result
    }

    private fun fetchInternal(team: EsportsTeamRef, details: EsportsTeamDetails): TeamProfileSupplement {
        val code = details.code.ifBlank { team.code }
        val requestedName = details.name.ifBlank { team.name }
        val teamRows = cargoQuery(
            tables = "Teams",
            fields = "OverviewPage,Name,Short,Twitter,Youtube,Instagram,Facebook,Website",
            where = buildString {
                val clauses = mutableListOf<String>()
                if (code.isNotBlank()) clauses += "Short=\"${escapeCargo(code)}\""
                if (requestedName.isNotBlank()) clauses += "Name=\"${escapeCargo(requestedName)}\""
                append(clauses.joinToString(" OR ").ifBlank { "Short=\"__RIFTLAB_NONE__\"" })
            },
            limit = 5
        )
        val teamTitle = teamRows.firstOrNull()?.optJSONObject("title")
        val overviewPage = teamTitle?.optString("OverviewPage").orEmpty()
            .ifBlank { teamTitle?.optString("Name").orEmpty() }
            .ifBlank { requestedName }

        val teamLinks = buildLinks(teamTitle)
        val peopleRows = if (overviewPage.isNotBlank()) {
            cargoQuery(
                tables = "Players",
                fields = "ID,Name,Role,Twitter,Weibo,Youtube,Stream,Instagram,Tiktok,IsPersonality",
                where = "Team=\"${escapeCargo(overviewPage)}\"",
                limit = 50
            )
        } else JSONArray()

        val playerLinks = linkedMapOf<String, List<EsportsSocialLink>>()
        val management = mutableListOf<EsportsStaffRef>()
        for (i in 0 until peopleRows.length()) {
            val title = peopleRows.optJSONObject(i)?.optJSONObject("title") ?: continue
            val id = title.optString("ID").trim()
            val role = title.optString("Role").trim()
            val socials = buildLinks(title)
            if (id.isNotBlank() && socials.isNotEmpty()) {
                playerLinks[token(id)] = socials
            }
            if (isManagementRole(role)) {
                management += EsportsStaffRef(
                    name = id.ifBlank { title.optString("Name").ifBlank { "—" } },
                    role = role.ifBlank { "MANAGEMENT" },
                    source = SOURCE,
                    realName = title.optString("Name"),
                    socialLinks = socials
                )
            }
        }

        val totalLinks = teamLinks.size + playerLinks.values.sumOf { it.size }
        return TeamProfileSupplement(
            teamLinks = teamLinks.distinctBy { it.platform to it.url },
            playerLinks = playerLinks,
            management = management.distinctBy { token(it.name) to token(it.role) },
            status = "$SOURCE · 管理层 ${management.size} · 社交账号 $totalLinks"
        )
    }

    private fun cargoQuery(tables: String, fields: String, where: String, limit: Int): JSONArray {
        val params = linkedMapOf(
            "action" to "cargoquery",
            "format" to "json",
            "tables" to tables,
            "fields" to fields,
            "where" to where,
            "limit" to limit.toString()
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "${enc(k)}=${enc(v)}"
        }
        val connection = (URL("$BASE?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 7_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RiftLab/1.0 Android team-profile")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code")
            if (body.isBlank()) error("empty response")
            return JSONObject(body).optJSONArray("cargoquery") ?: JSONArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun buildLinks(row: JSONObject?): List<EsportsSocialLink> {
        if (row == null) return emptyList()
        val links = mutableListOf<EsportsSocialLink>()
        addLink(links, "X", normalizeHandleUrl(row.optString("Twitter"), "https://x.com/"))
        addLink(links, "微博", normalizeDirectUrl(row.optString("Weibo")))
        addLink(links, "YouTube", normalizeYoutube(row.optString("Youtube")))
        addLink(links, "Instagram", normalizeHandleUrl(row.optString("Instagram"), "https://www.instagram.com/"))
        addLink(links, "Facebook", normalizeDirectUrl(row.optString("Facebook")))
        addLink(links, "官网", normalizeDirectUrl(row.optString("Website")))
        addLink(links, "TikTok", normalizeHandleUrl(row.optString("Tiktok"), "https://www.tiktok.com/@"))

        val stream = normalizeDirectUrl(row.optString("Stream"))
        if (stream.isNotBlank()) {
            val platform = when {
                stream.contains("bilibili.com", ignoreCase = true) || stream.contains("b23.tv", ignoreCase = true) -> "B站"
                stream.contains("youtube.com", ignoreCase = true) || stream.contains("youtu.be", ignoreCase = true) -> "YouTube"
                stream.contains("twitch.tv", ignoreCase = true) -> "Twitch"
                else -> "直播"
            }
            addLink(links, platform, stream)
        }
        return links.distinctBy { it.platform to it.url }
    }

    private fun addLink(target: MutableList<EsportsSocialLink>, platform: String, url: String) {
        if (url.isNotBlank()) target += EsportsSocialLink(platform = platform, url = url, source = SOURCE)
    }

    private fun normalizeHandleUrl(raw: String, prefix: String): String {
        val value = raw.trim().trimStart('@')
        if (value.isBlank() || value.equals("null", true)) return ""
        if (value.startsWith("http://") || value.startsWith("https://")) return value.replaceFirst("http://", "https://")
        return prefix + value
    }

    private fun normalizeYoutube(raw: String): String {
        val value = raw.trim()
        if (value.isBlank() || value.equals("null", true)) return ""
        if (value.startsWith("http://") || value.startsWith("https://")) return value.replaceFirst("http://", "https://")
        return "https://www.youtube.com/$value"
    }

    private fun normalizeDirectUrl(raw: String): String {
        val value = raw.trim()
        if (value.isBlank() || value.equals("null", true) || value.equals("undefined", true)) return ""
        return when {
            value.startsWith("https://") -> value
            value.startsWith("http://") -> value.replaceFirst("http://", "https://")
            value.startsWith("//") -> "https:$value"
            value.startsWith("www.") -> "https://$value"
            else -> value.takeIf { it.contains('.') }?.let { "https://$it" }.orEmpty()
        }
    }

    private fun isManagementRole(role: String): Boolean {
        val value = token(role)
        return managementTokens.any { value == it || value.contains(it) }
    }

    private fun escapeCargo(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    private fun token(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
}
