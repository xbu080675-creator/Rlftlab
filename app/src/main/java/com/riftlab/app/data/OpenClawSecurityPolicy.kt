package com.riftlab.app.data

import java.net.URI

/**
 * Hard security boundary for any future RiftLab -> local OpenClaw integration.
 *
 * Design goals:
 * - loopback-only transport; RiftLab must never expose or call a remote OpenClaw gateway;
 * - deny-by-default capability model;
 * - no generic shell / filesystem / plugin / permission APIs;
 * - no delete, write, install, privilege escalation or arbitrary command execution;
 * - payload validation happens before any network call;
 * - audit output is metadata-only and must not contain credentials or result bodies.
 *
 * This file intentionally does not provide a generic "execute" escape hatch.
 */
internal object OpenClawSecurityPolicy {

    enum class Capability {
        GATEWAY_HEALTH,
        WEIBO_SEARCH
    }

    data class Request(
        val capability: Capability,
        val query: String? = null,
        val metadata: Map<String, String> = emptyMap()
    )

    sealed interface Decision {
        data object Allow : Decision
        data class Deny(val reason: String) : Decision
    }

    private val forbiddenKeys = setOf(
        "cmd", "command", "exec", "execute", "shell", "sh",
        "file", "files", "filepath", "path", "write", "overwrite",
        "delete", "remove", "rm", "unlink", "move", "rename",
        "install", "plugin", "plugins", "package",
        "permission", "permissions", "root", "su", "sudo",
        "adb", "shizuku", "magisk", "intent", "broadcast",
        "download", "upload", "script", "process"
    )

    private val forbiddenTextPatterns = listOf(
        Regex("(?i)(^|\\s)(sudo|su|sh|bash|zsh|fish)(\\s|$)"),
        Regex("(?i)\\brm\\s+-[a-z]*r[a-z]*f?\\b"),
        Regex("(?i)\\b(chmod|chown|mount|umount|pm\\s+install|am\\s+start)\\b"),
        Regex("(?i)\\b(magisk|shizuku|adb)\\b")
    )

    fun validateEndpoint(endpoint: String): Decision {
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull()
            ?: return Decision.Deny("gateway_url_invalid")
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme !in setOf("http", "https", "ws", "wss")) {
            return Decision.Deny("gateway_scheme_not_allowed")
        }
        val host = uri.host?.lowercase().orEmpty()
        if (host !in setOf("127.0.0.1", "localhost", "::1", "[::1]")) {
            return Decision.Deny("gateway_must_be_loopback")
        }
        if (!uri.userInfo.isNullOrBlank()) {
            return Decision.Deny("gateway_url_must_not_embed_credentials")
        }
        return Decision.Allow
    }

    fun evaluate(endpoint: String, request: Request): Decision {
        val endpointDecision = validateEndpoint(endpoint)
        if (endpointDecision is Decision.Deny) return endpointDecision

        if (request.metadata.keys.any { key -> key.trim().lowercase() in forbiddenKeys }) {
            return Decision.Deny("forbidden_payload_key")
        }

        val query = request.query?.trim().orEmpty()
        return when (request.capability) {
            Capability.GATEWAY_HEALTH -> {
                if (query.isNotEmpty() || request.metadata.isNotEmpty()) {
                    Decision.Deny("health_check_must_be_empty")
                } else {
                    Decision.Allow
                }
            }

            Capability.WEIBO_SEARCH -> {
                if (query.isEmpty()) return Decision.Deny("weibo_query_blank")
                if (query.length > 256) return Decision.Deny("weibo_query_too_long")
                if (forbiddenTextPatterns.any { it.containsMatchIn(query) }) {
                    return Decision.Deny("weibo_query_contains_command_like_content")
                }
                if (request.metadata.isNotEmpty()) {
                    return Decision.Deny("weibo_search_metadata_not_allowed")
                }
                Decision.Allow
            }
        }
    }

    /**
     * Safe audit line for local diagnostics. Never include query text, secrets or response content.
     */
    fun audit(capability: Capability, allowed: Boolean, reason: String? = null): String = buildString {
        append("OPENCLAW_POLICY ")
        append(capability.name)
        append(' ')
        append(if (allowed) "ALLOW" else "DENY")
        if (!reason.isNullOrBlank()) {
            append(' ')
            append(reason.take(80))
        }
    }
}
