package com.riftlab.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Canonical team identity + shared logo hydration for schedule/standings surfaces.
 *
 * Riot can expose the same organisation through different historical/current team ids. UI identity
 * should therefore prefer the stable team code/name instead of raw ids. Team logos are resolved
 * once per canonical team, stored in EsportsAssetCache and then written back into every match so
 * all score surfaces can render the same artwork without waiting for Match Detail to be opened.
 */
internal object TeamAssetCatalog {
    private val provider = RiotTeamAssetProvider()
    private val semaphore = Semaphore(4)

    fun canonicalKey(team: EsportsTeamRef): String {
        val code = token(team.code)
        if (code.isNotBlank() && code !in setOf("TBD", "NA", "NONE")) return "CODE:$code"
        val name = token(team.name)
        if (name.isNotBlank() && name !in setOf("TBD", "NA", "NONE")) return "NAME:$name"
        val slug = token(team.slug)
        if (slug.isNotBlank()) return "SLUG:$slug"
        return "ID:${token(team.id)}"
    }

    fun aliases(team: EsportsTeamRef): Array<String> = arrayOf(
        canonicalKey(team), team.id, team.code, team.name, team.slug
    )

    suspend fun enrichMatches(matches: List<ScheduledEsportsMatch>): List<ScheduledEsportsMatch> {
        if (matches.isEmpty()) return matches

        val variantsByKey = matches
            .flatMap { it.teams }
            .filter { token(it.code) !in setOf("", "TBD", "NA", "NONE") }
            .groupBy(::canonicalKey)

        // Reuse any artwork that already arrived in Schedule/another provider before touching network.
        variantsByKey.values.flatten().forEach { team ->
            val existing = EsportsAssetCache.normalize(team.imageUrl)
            if (existing.isNotBlank()) EsportsAssetCache.putTeam(existing, *aliases(team))
        }

        coroutineScope {
            variantsByKey.map { (_, variants) ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val representative = variants.maxByOrNull { teamQuality(it) } ?: return@withPermit
                        val cached = EsportsAssetCache.team(*aliases(representative))
                        if (cached.isNotBlank()) return@withPermit
                        val resolved = runCatching { provider.resolve(representative) }.getOrDefault("")
                        if (resolved.isNotBlank()) {
                            variants.forEach { EsportsAssetCache.putTeam(resolved, *aliases(it)) }
                        }
                    }
                }
            }.awaitAll()
        }

        return matches.map { match ->
            match.copy(
                teams = match.teams.map { team ->
                    val image = EsportsAssetCache.normalize(team.imageUrl)
                        .ifBlank { EsportsAssetCache.team(*aliases(team)) }
                    if (image == team.imageUrl) team else team.copy(imageUrl = image)
                }
            )
        }
    }

    private fun teamQuality(team: EsportsTeamRef): Int =
        (if (team.id.isNotBlank()) 4 else 0) +
            (if (team.slug.isNotBlank()) 3 else 0) +
            (if (team.code.isNotBlank()) 2 else 0) +
            (if (team.name.isNotBlank()) 1 else 0) +
            (if (EsportsAssetCache.normalize(team.imageUrl).isNotBlank()) 8 else 0)

    private fun token(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
}
