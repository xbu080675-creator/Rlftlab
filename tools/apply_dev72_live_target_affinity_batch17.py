#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


REGISTRY = "app/src/main/java/com/riftlab/app/data/LiveMatchTargetRegistry.kt"
ROUTER = "app/src/main/java/com/riftlab/app/data/LplOfficialLiveDataSource.kt"
RIOT = "app/src/main/java/com/riftlab/app/data/LolEsportsDataSources.kt"
CITO = "app/src/main/java/com/riftlab/app/data/CitoDataPlane.kt"
COMM = "app/src/main/java/com/riftlab/app/data/LplCommRealtimeDataSource.kt"
TJ = "app/src/main/java/com/riftlab/app/data/LplMatchDetailLiveDataSource.kt"

# One canonical target identity + team affinity check shared by every realtime adapter/router.
replace_once(
    REGISTRY,
    '''    fun snapshot(): ScheduledEsportsMatch? = current\n}\n''',
    '''    fun snapshot(): ScheduledEsportsMatch? = current\n\n    fun key(match: ScheduledEsportsMatch?): String = match?.let {\n        it.eventId.trim().ifBlank { it.matchId.trim() }.ifBlank {\n            val teams = it.teams.take(2).joinToString("|") { team ->\n                team.slug.ifBlank { team.code.ifBlank { team.name } }\n            }\n            "${it.leagueId}|${it.startTimeIso}|$teams"\n        }\n    }.orEmpty()\n\n    fun snapshotBelongsTo(snapshot: LiveSnapshot, target: ScheduledEsportsMatch?): Boolean {\n        target ?: return false\n        val expected = target.teams.take(2).map(::aliases)\n        if (expected.size < 2 || expected.any { it.isEmpty() }) return false\n        val actual = listOf(snapshot.blue, snapshot.red).map(::token)\n        if (actual.any { it.isBlank() }) return false\n        fun matches(value: String, candidates: Set<String>): Boolean = candidates.any { candidate ->\n            value == candidate ||\n                (value.length >= 4 && candidate.length >= 4 && (value.contains(candidate) || candidate.contains(value)))\n        }\n        return actual.all { value -> expected.any { matches(value, it) } } &&\n            expected.all { candidates -> actual.any { matches(it, candidates) } }\n    }\n\n    private fun aliases(team: EsportsTeamRef): Set<String> =\n        listOf(team.code, team.name, team.slug)\n            .map(::token)\n            .filter { it.isNotBlank() }\n            .toSet()\n\n    private fun token(value: String): String =\n        value.uppercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")\n}\n'''
)

# Router must prove both status-event affinity and team-set affinity before selecting a live frame.
replace_once(
    ROUTER,
    '''                                status?.phase == LiveSourcePhase.LIVE &&\n                                    status.lastUpdateEpochMs > 0L &&\n                                    now - status.lastUpdateEpochMs <= LIVE_STATUS_STALE_MS &&\n                                    providerSnapshots[candidate.name]?.let(::isMeaningful) == true\n''',
    '''                                status?.phase == LiveSourcePhase.LIVE &&\n                                    status.lastUpdateEpochMs > 0L &&\n                                    now - status.lastUpdateEpochMs <= LIVE_STATUS_STALE_MS &&\n                                    providerMatchesCurrentTargetLocked(candidate, requireFrame = true) &&\n                                    providerSnapshots[candidate.name]?.let(::isMeaningful) == true\n'''
)
replace_once(
    ROUTER,
    '''            s?.phase == LiveSourcePhase.LIVE &&\n                s.lastUpdateEpochMs > 0L &&\n                now - s.lastUpdateEpochMs <= LIVE_STATUS_STALE_MS &&\n                providerSnapshots[provider.name]?.let(::isMeaningful) == true\n''',
    '''            s?.phase == LiveSourcePhase.LIVE &&\n                s.lastUpdateEpochMs > 0L &&\n                now - s.lastUpdateEpochMs <= LIVE_STATUS_STALE_MS &&\n                providerMatchesCurrentTargetLocked(provider, requireFrame = true) &&\n                providerSnapshots[provider.name]?.let(::isMeaningful) == true\n'''
)
replace_once(
    ROUTER,
    '''        val between = eligibleProvidersLocked().firstOrNull {\n            providerStatuses[it.name]?.phase == LiveSourcePhase.BETWEEN_GAMES\n        }\n''',
    '''        val between = eligibleProvidersLocked().firstOrNull { provider ->\n            providerStatuses[provider.name]?.phase == LiveSourcePhase.BETWEEN_GAMES &&\n                providerMatchesCurrentTargetLocked(provider, requireFrame = false)\n        }\n'''
)
replace_once(
    ROUTER,
    '''            s != null && s.phase != LiveSourcePhase.IDLE &&\n                (s.lastUpdateEpochMs == 0L || now - s.lastUpdateEpochMs <= PROVIDER_STATUS_MAX_AGE_MS)\n''',
    '''            s != null && s.phase != LiveSourcePhase.IDLE &&\n                providerMatchesCurrentTargetLocked(provider, requireFrame = false) &&\n                (s.lastUpdateEpochMs == 0L || now - s.lastUpdateEpochMs <= PROVIDER_STATUS_MAX_AGE_MS)\n'''
)
replace_once(
    ROUTER,
    '''            val s = providerStatuses[provider.name] ?: return@all true\n            s.phase == LiveSourcePhase.ERROR ||\n''',
    '''            val s = providerStatuses[provider.name] ?: return@all true\n            !providerMatchesCurrentTargetLocked(provider, requireFrame = false) ||\n                s.phase == LiveSourcePhase.ERROR ||\n'''
)
marker = '''    private fun eligibleProvidersLocked(): List<Provider> = providers.filter {\n'''
helper = '''    private fun providerMatchesCurrentTargetLocked(provider: Provider, requireFrame: Boolean): Boolean {\n        val target = LiveMatchTargetRegistry.snapshot() ?: return false\n        val status = providerStatuses[provider.name] ?: return false\n        val targetEventId = target.eventId.trim()\n        val providerEventId = status.eventId.trim()\n        if (targetEventId.isNotBlank()) {\n            if (providerEventId.isNotBlank() && providerEventId != targetEventId) return false\n            if (providerEventId.isBlank() && status.phase in setOf(LiveSourcePhase.LIVE, LiveSourcePhase.BETWEEN_GAMES)) {\n                return false\n            }\n        }\n        if (!requireFrame) return true\n        val snapshot = providerSnapshots[provider.name] ?: return false\n        return LiveMatchTargetRegistry.snapshotBelongsTo(snapshot, target)\n    }\n\n'''
p = Path(ROUTER)
text = p.read_text(encoding="utf-8")
if text.count(marker) != 1:
    raise SystemExit("router provider helper marker not found")
p.write_text(text.replace(marker, helper + marker, 1), encoding="utf-8")

# Riot adapter: switching schedule targets must invalidate the previously locked Riot event/game.
replace_once(
    RIOT,
    '''        var previous: LiveSnapshot? = null\n        var lockedFromSchedule = false\n\n        while (currentCoroutineContext().isActive) {\n            try {\n                val registeredTarget = LiveMatchTargetRegistry.snapshot()\n''',
    '''        var previous: LiveSnapshot? = null\n        var lockedFromSchedule = false\n        var observedTargetKey = ""\n\n        while (currentCoroutineContext().isActive) {\n            try {\n                val registeredTarget = LiveMatchTargetRegistry.snapshot()\n                val nextTargetKey = LiveMatchTargetRegistry.key(registeredTarget)\n                if (nextTargetKey != observedTargetKey) {\n                    observedTargetKey = nextTargetKey\n                    currentEvent = null\n                    knownGames = emptyList()\n                    currentGame = null\n                    currentGameId = ""\n                    previous = null\n                    lockedFromSchedule = false\n                }\n'''
)

# Cito adapter: provider-local match/game ids cannot survive a target switch. Also require a known
# gameId before consuming opportunistic websocket frames; REST remains the safe fallback otherwise.
replace_once(
    CITO,
    '''        var lastEmission = ""\n        var lastWsPayload: JSONObject? = null\n''',
    '''        var lastEmission = ""\n        var lastWsPayload: JSONObject? = null\n        var observedTargetKey = ""\n'''
)
replace_once(
    CITO,
    '''                val matchKey = MatchLifecycleArchive.keyFor(target)\n                if (citoMatchId.isBlank()) {\n''',
    '''                val nextTargetKey = LiveMatchTargetRegistry.key(target)\n                if (nextTargetKey != observedTargetKey) {\n                    observedTargetKey = nextTargetKey\n                    citoMatchId = ""\n                    gameId = ""\n                    gameNumber = 1\n                    lastEmission = ""\n                    lastWsPayload = null\n                }\n                val matchKey = MatchLifecycleArchive.keyFor(target)\n                if (citoMatchId.isBlank()) {\n'''
)
replace_once(
    CITO,
    '''                if (ws != null) {\n                    val candidate = CitoJson.parseLiveBoard(ws, target, gameNumber)\n                    if (candidate != null && CitoJson.meaningful(candidate) &&\n                        (gameId.isBlank() || candidate.gameId.isBlank() || candidate.gameId == gameId)\n                    ) {\n''',
    '''                if (ws != null && gameId.isNotBlank()) {\n                    val candidate = CitoJson.parseLiveBoard(ws, target, gameNumber)\n                    if (candidate != null && CitoJson.meaningful(candidate) && candidate.gameId == gameId) {\n'''
)

# LPL Comm adapter already has target-aware candidate scoring; invalidate the cached bMatchId/route
# whenever the shared target changes.
replace_once(
    COMM,
    '''        var cachedRouteKey = ""\n        var previous: LiveSnapshot? = null\n\n        while (currentCoroutineContext().isActive) {\n            try {\n                if (ref == null) {\n''',
    '''        var cachedRouteKey = ""\n        var previous: LiveSnapshot? = null\n        var observedTargetKey = ""\n\n        while (currentCoroutineContext().isActive) {\n            try {\n                val nextTargetKey = LiveMatchTargetRegistry.key(LiveMatchTargetRegistry.snapshot())\n                if (nextTargetKey != observedTargetKey) {\n                    observedTargetKey = nextTargetKey\n                    ref = null\n                    cachedRoute = null\n                    cachedRouteKey = ""\n                    previous = null\n                }\n                if (ref == null) {\n'''
)

# TJ matchDetail previously chose the first item from the public LPL live list. With simultaneous
# LPL series that can be the wrong match. Score against the registry target and refuse ambiguity.
replace_once(
    TJ,
    '''        var previous: LiveSnapshot? = null\n        var lastBo = 0\n\n        while (currentCoroutineContext().isActive) {\n            try {\n                if (ref == null) {\n''',
    '''        var previous: LiveSnapshot? = null\n        var lastBo = 0\n        var observedTargetKey = ""\n\n        while (currentCoroutineContext().isActive) {\n            try {\n                val nextTargetKey = LiveMatchTargetRegistry.key(LiveMatchTargetRegistry.snapshot())\n                if (nextTargetKey != observedTargetKey) {\n                    observedTargetKey = nextTargetKey\n                    ref = null\n                    previous = null\n                    lastBo = 0\n                }\n                if (ref == null) {\n'''
)
# Add event id to the LIVE status so router can prove affinity.
replace_once(
    TJ,
    '''                    message = "LPL Official · matchDetail LIVE · bmid=${active.bmid} · G$bo · gameStatus=${current.status} · teamInfos=${current.teams.size} · players=${blueState.players.size + redState.players.size}",\n                    gameId = snapshot.gameId,\n''',
    '''                    message = "LPL Official · matchDetail LIVE · bmid=${active.bmid} · G$bo · gameStatus=${current.status} · teamInfos=${current.teams.size} · players=${blueState.players.size + redState.players.size}",\n                    eventId = targetEventId(),\n                    gameId = snapshot.gameId,\n'''
)
replace_once(
    TJ,
    '''        return candidates.firstOrNull { it.teamAName.isNotBlank() && it.teamBName.isNotBlank() }\n            ?: candidates.firstOrNull()\n    }\n\n    private fun parseRef''',
    '''        val target = LiveMatchTargetRegistry.snapshot()\n        if (target != null && target.teams.size >= 2) {\n            val ranked = candidates.map { it to matchScore(it, target) }.sortedByDescending { it.second }\n            ranked.firstOrNull()?.takeIf { it.second >= 95 }?.let { return it.first }\n            return null\n        }\n        return candidates.singleOrNull()\n    }\n\n    private fun matchScore(ref: MatchRef, target: ScheduledEsportsMatch): Int {\n        val left = target.teams.getOrNull(0) ?: return 0\n        val right = target.teams.getOrNull(1) ?: return 0\n        val direct = teamMatches(ref.teamAName, left) && teamMatches(ref.teamBName, right)\n        val swapped = teamMatches(ref.teamAName, right) && teamMatches(ref.teamBName, left)\n        return when {\n            direct -> 100\n            swapped -> 95\n            else -> 0\n        }\n    }\n\n    private fun teamMatches(upstreamName: String, team: EsportsTeamRef): Boolean {\n        val upstream = teamKey(upstreamName)\n        if (upstream.isBlank()) return false\n        return listOf(team.code, team.name, team.slug).map(::teamKey).filter { it.isNotBlank() }.any { candidate ->\n            upstream == candidate ||\n                (upstream.length >= 4 && candidate.length >= 4 && (upstream.contains(candidate) || candidate.contains(upstream)))\n        }\n    }\n\n    private fun teamKey(value: String): String =\n        value.uppercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")\n\n    private fun targetEventId(): String = LiveMatchTargetRegistry.snapshot()?.eventId.orEmpty()\n\n    private fun parseRef'''
)

print("dev72 live target affinity batch17 applied")
