package com.riftlab.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Resolves a player portrait from Riot getTeams and caches it for visual match-detail cards. */
internal object PlayerPortraitResolver {
    private val client = LolEsportsApiClient()
    private val mutex = Mutex()
    private val teamCache = linkedMapOf<String, EsportsTeamDetails?>()

    suspend fun resolve(
        match: ScheduledEsportsMatch,
        playerName: String,
        teamHint: String = ""
    ): String {
        val targetName = playerToken(playerName)
        if (targetName.isBlank()) return ""

        val orderedTeams = match.teams.sortedByDescending { team ->
            if (teamHint.isNotBlank() && teamMatchesHint(team, teamHint)) 1 else 0
        }

        for (team in orderedTeams) {
            val details = loadTeam(team) ?: continue
            val player = details.players.firstOrNull { ref ->
                val token = playerToken(ref.summonerName)
                token == targetName ||
                    token.endsWith(targetName) ||
                    targetName.endsWith(token)
            }
            if (player?.imageUrl?.isNotBlank() == true) return player.imageUrl
        }
        return ""
    }

    private suspend fun loadTeam(team: EsportsTeamRef): EsportsTeamDetails? {
        val key = listOf(team.id, team.slug, team.code, team.name)
            .firstOrNull { it.isNotBlank() }
            ?.uppercase()
            ?: return null

        mutex.withLock {
            if (teamCache.containsKey(key)) return teamCache[key]
        }

        val slug = team.slug.ifBlank {
            team.name.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
        }
        val loaded = if (slug.isBlank()) null else runCatching { client.fetchTeamDetails(slug) }.getOrNull()
        mutex.withLock { teamCache[key] = loaded }
        return loaded
    }

    private fun teamMatchesHint(team: EsportsTeamRef, hint: String): Boolean {
        val target = teamToken(hint)
        return listOf(team.id, team.code, team.name, team.slug)
            .map(::teamToken)
            .any { it.isNotBlank() && (it == target || it.contains(target) || target.contains(it)) }
    }

    private fun playerToken(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

    private fun teamToken(value: String): String = playerToken(value)
}
