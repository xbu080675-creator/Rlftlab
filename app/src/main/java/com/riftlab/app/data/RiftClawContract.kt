package com.riftlab.app.data

/**
 * Narrow contract between RiftLab and the optional RiftClaw companion.
 *
 * RiftClaw is not a general OpenClaw agent. It is a localhost-only Weibo search appliance.
 * The only externally callable capability is WEIBO_SEARCH. Everything else is denied by design.
 *
 * Port separation is intentional:
 * - RiftLab talks only to the narrow RiftClaw bridge on 127.0.0.1:18790.
 * - The bridge privately talks to its isolated OpenClaw profile/gateway on 127.0.0.1:18791.
 * RiftLab never receives the OpenClaw operator token and never calls /tools/invoke directly.
 */
internal object RiftClawContract {
    const val DEFAULT_ENDPOINT = "http://127.0.0.1:18790"
    @Deprecated("Use DEFAULT_ENDPOINT; this is the RiftClaw bridge, not the OpenClaw Gateway")
    const val DEFAULT_GATEWAY = DEFAULT_ENDPOINT
    const val PROTOCOL_VERSION = 1

    enum class Capability { WEIBO_SEARCH }

    data class SearchRequest(
        val requestId: String,
        val matchDate: String,
        val league: String,
        val teamA: String,
        val teamB: String,
        val intent: String = "starting_roster"
    )

    data class SearchHit(
        val title: String?,
        val text: String?,
        val source: String?,
        val scheme: String?,
        val publishedAt: String?
    )

    data class SearchResponse(
        val requestId: String,
        val hits: List<SearchHit>,
        val source: String = "riftclaw-weibo",
        val protocolVersion: Int = PROTOCOL_VERSION
    )

    sealed interface Validation {
        data object Allow : Validation
        data class Deny(val reason: String) : Validation
    }

    private val token = Regex("^[A-Za-z0-9._+-]{1,48}$")
    private val date = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val safeIntent = setOf("starting_roster")

    fun validate(request: SearchRequest): Validation {
        if (request.requestId.length !in 8..96) return Validation.Deny("request_id_invalid")
        if (!date.matches(request.matchDate)) return Validation.Deny("date_invalid")
        if (!token.matches(request.league)) return Validation.Deny("league_invalid")
        if (!token.matches(request.teamA) || !token.matches(request.teamB)) {
            return Validation.Deny("team_invalid")
        }
        if (request.teamA.equals(request.teamB, ignoreCase = true)) {
            return Validation.Deny("teams_must_differ")
        }
        if (request.intent !in safeIntent) return Validation.Deny("intent_not_allowed")
        return Validation.Allow
    }

    /**
     * Build the actual Weibo query from trusted structured fields only.
     * We never forward arbitrary user prose into OpenClaw.
     */
    fun buildQueries(request: SearchRequest): List<String> {
        require(validate(request) is Validation.Allow)
        val monthDay = request.matchDate.substring(5).replace('-', '月') + "日"
        return listOf(
            "$monthDay ${request.teamA}对战${request.teamB} 首发名单",
            "$monthDay ${request.teamB}对战${request.teamA} 首发名单"
        )
    }
}

/**
 * Treat every byte returned by Weibo/OpenClaw as hostile content.
 * This is a data sanitizer, not a prompt-based defense.
 */
internal object RiftClawInjectionGuard {
    private const val MAX_FIELD = 1200

    private val suspicious = listOf(
        Regex("(?i)ignore\\s+(all\\s+)?previous\\s+instructions"),
        Regex("(?i)system\\s*prompt"),
        Regex("(?i)(execute|run)\\s+(this\\s+)?(command|shell|code)"),
        Regex("(?i)\\b(sudo|su|bash|sh|zsh|cmd|powershell|adb|magisk|shizuku)\\b"),
        Regex("(?i)\\b(rm\\s+-rf|chmod|chown|curl\\s+.*\\|\\s*sh|wget\\s+.*\\|\\s*sh)\\b"),
        Regex("(?i)(read|upload|send|exfiltrate).{0,24}(secret|token|cookie|key|credential)"),
        Regex("(?i)(install|enable).{0,24}(plugin|package|extension)")
    )

    data class SanitizedText(
        val value: String,
        val suspiciousContentRemoved: Boolean
    )

    fun sanitize(input: String?): SanitizedText {
        if (input.isNullOrBlank()) return SanitizedText("", false)
        var value = input
            .replace('\u0000', ' ')
            .replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), " ")
            .take(MAX_FIELD)
        var removed = false
        suspicious.forEach { pattern ->
            if (pattern.containsMatchIn(value)) {
                value = value.replace(pattern, "[blocked-untrusted-instruction]")
                removed = true
            }
        }
        return SanitizedText(value.trim(), removed)
    }

    fun sanitizeHit(hit: RiftClawContract.SearchHit): RiftClawContract.SearchHit = hit.copy(
        title = sanitize(hit.title).value.takeIf { it.isNotEmpty() },
        text = sanitize(hit.text).value.takeIf { it.isNotEmpty() },
        source = sanitize(hit.source).value.take(180).takeIf { it.isNotEmpty() },
        scheme = sanitize(hit.scheme).value.take(500).takeIf { it.isNotEmpty() },
        publishedAt = sanitize(hit.publishedAt).value.take(80).takeIf { it.isNotEmpty() }
    )
}
