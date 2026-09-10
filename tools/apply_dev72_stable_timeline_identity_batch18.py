#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


STORE = "app/src/main/java/com/riftlab/app/data/MatchTimelineStore.kt"
CAPTURE = "app/src/main/java/com/riftlab/app/data/MatchTimelineCapture.kt"

# Provider-local game ids (Riot numeric id, COMM bmid, Cito game id, TJ fallback id) are transport
# identifiers, not the identity of a RiftLab game timeline. When the router fails over providers in
# the same G1/G2/etc, all frames must land in one timeline rather than falsely completing a game.
replace_once(
    STORE,
    '''    fun markCompleted(snapshot: LiveSnapshot) {\n        if (snapshot.game <= 0) return\n        ingest(snapshot)\n        val key = timelineKey(snapshot)\n        val updated = synchronized(lock) {\n            val current = _timelines.value[key] ?: return\n''',
    '''    fun markCompleted(snapshot: LiveSnapshot) {\n        if (snapshot.game <= 0) return\n        ingest(snapshot)\n        val existing = find(snapshot) ?: return\n        val key = existing.gameId\n        val updated = synchronized(lock) {\n            val current = _timelines.value[key] ?: return\n'''
)
replace_once(
    STORE,
    '''    private fun timelineKey(snapshot: LiveSnapshot): String = snapshot.gameId.trim().ifBlank {\n        "${token(snapshot.blue)}_${token(snapshot.red)}_G${snapshot.game}"\n    }\n''',
    '''    private fun timelineKey(snapshot: LiveSnapshot): String {\n        val target = LiveMatchTargetRegistry.snapshot()\n        if (target != null && LiveMatchTargetRegistry.snapshotBelongsTo(snapshot, target)) {\n            val targetKey = LiveMatchTargetRegistry.key(target)\n            if (targetKey.isNotBlank()) return "schedule:$targetKey:G${snapshot.game}"\n        }\n        return snapshot.gameId.trim().ifBlank {\n            "${token(snapshot.blue)}_${token(snapshot.red)}_G${snapshot.game}"\n        }\n    }\n'''
)

# A provider failover can legitimately change snapshot.gameId while the real game remains G1. Do
# not treat that as a game transition. Event-id changes still close the previous target timeline.
replace_once(
    CAPTURE,
    '''            var lastLive: LiveSnapshot? = null\n            var previousPhase = LiveSourcePhase.IDLE\n\n            combine(MatchSessionStore.live, MatchSessionStore.liveSourceStatus) { snapshot, status ->\n''',
    '''            var lastLive: LiveSnapshot? = null\n            var previousPhase = LiveSourcePhase.IDLE\n            var lastLiveEventId = ""\n\n            combine(MatchSessionStore.live, MatchSessionStore.liveSourceStatus) { snapshot, status ->\n'''
)
replace_once(
    CAPTURE,
    '''                    val switchedGame = previous != null && (\n                        previous.game != snapshot.game ||\n                            (previous.gameId.isNotBlank() && snapshot.gameId.isNotBlank() && previous.gameId != snapshot.gameId)\n                        )\n                    if (switchedGame) {\n                        MatchTimelineStore.markCompleted(previous!!)\n                    }\n                    MatchTimelineStore.ingest(snapshot)\n                    lastLive = snapshot\n''',
    '''                    val currentEventId = status.eventId.trim()\n                    val switchedTarget = previous != null && lastLiveEventId.isNotBlank() &&\n                        currentEventId.isNotBlank() && currentEventId != lastLiveEventId\n                    val switchedGame = previous != null && previous.game != snapshot.game\n                    if (switchedTarget || switchedGame) {\n                        MatchTimelineStore.markCompleted(previous!!)\n                    }\n                    MatchTimelineStore.ingest(snapshot)\n                    lastLive = snapshot\n                    if (currentEventId.isNotBlank()) lastLiveEventId = currentEventId\n'''
)
replace_once(
    CAPTURE,
    '''                    lastLive?.let(MatchTimelineStore::markCompleted)\n                    lastLive = null\n''',
    '''                    lastLive?.let(MatchTimelineStore::markCompleted)\n                    lastLive = null\n                    lastLiveEventId = ""\n'''
)

print("dev72 stable timeline identity batch18 applied")
