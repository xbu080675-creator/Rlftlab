package com.riftlab.app.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Shared in-memory image cache for esports assets discovered by any provider.
 *
 * A provider that already has a canonical team/player image should publish it here so UI code does
 * not need to rediscover the same asset through another API. Values are normalized before storage;
 * invalid placeholders such as `null` / `undefined` are never cached.
 */
internal object EsportsAssetCache {
    private val teamImages = ConcurrentHashMap<String, String>()
    private val playerImages = ConcurrentHashMap<String, String>()

    fun putTeam(imageUrl: String, vararg aliases: String) {
        val image = normalize(imageUrl)
        if (image.isBlank()) return
        aliases.map(::token).filter { it.isNotBlank() }.forEach { teamImages[it] = image }
    }

    fun team(vararg aliases: String): String {
        return aliases.asSequence()
            .map(::token)
            .filter { it.isNotBlank() }
            .mapNotNull { teamImages[it] }
            .map(::normalize)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    fun putPlayer(playerName: String, team: String, imageUrl: String) {
        val image = normalize(imageUrl)
        val player = token(playerName)
        if (image.isBlank() || player.isBlank()) return

        val teamToken = token(team)
        if (teamToken.isNotBlank()) playerImages["$teamToken|$player"] = image
        playerImages.putIfAbsent("|$player", image)
    }

    fun player(playerName: String, team: String = ""): String {
        val player = token(playerName)
        if (player.isBlank()) return ""
        val teamToken = token(team)
        if (teamToken.isNotBlank()) {
            normalize(playerImages["$teamToken|$player"].orEmpty()).takeIf { it.isNotBlank() }?.let { return it }
        }
        return normalize(playerImages["|$player"].orEmpty())
    }

    fun normalize(raw: String): String {
        val value = raw.trim()
        if (value.isBlank() || value.equals("null", true) || value.equals("undefined", true)) return ""
        return when {
            value.startsWith("https://", true) || value.startsWith("http://", true) -> value
            value.startsWith("//") -> "https:$value"
            else -> ""
        }
    }

    private fun token(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
}
