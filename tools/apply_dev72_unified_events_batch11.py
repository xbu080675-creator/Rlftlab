#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:160]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


PATH = "app/src/main/java/com/riftlab/app/data/MatchTimelineStore.kt"

# Establish the dev.72 unified event vocabulary now, while only emitting event types that current
# normalized provider snapshots can actually prove. Reserved types remain dormant until a provider
# supplies the corresponding fact (objective subtype, inventory, pause state, etc.).
replace_once(
    PATH,
    '''enum class TimelineEventType {\n    GAME_START,\n    KILL,\n    TOWER,\n    DRAGON,\n    BARON,\n    GOLD_SWING,\n    GAME_END\n}\n\ndata class MatchTimelineEvent(\n    val seconds: Int,\n    val type: TimelineEventType,\n    val team: String = "",\n    val title: String,\n    val detail: String = "",\n    val amount: Int = 1\n)\n''',
    '''enum class TimelineEventType {\n    GAME_START,\n    KILL,\n    MULTI_KILL_WINDOW,\n    TEAM_FIGHT_WINDOW,\n    TOWER,\n    DRAGON,\n    SOUL,\n    ELDER_DRAGON,\n    HERALD,\n    ATAKHAN,\n    BARON,\n    GOLD_LEAD_CHANGE,\n    GOLD_SWING,\n    ITEM_SPIKE,\n    PLAYER_LEVEL_CHANGE,\n    PLAYER_CS_CHANGE,\n    PLAYER_KDA_CHANGE,\n    GAME_PAUSE,\n    GAME_RESUME,\n    GAME_END\n}\n\nenum class TimelineEventEvidence {\n    LOCAL_CAPTURE,\n    VERIFIED_DELTA,\n    DERIVED_WINDOW,\n    PROVIDER_EXPLICIT\n}\n\ndata class MatchTimelineEvent(\n    val seconds: Int,\n    val type: TimelineEventType,\n    val team: String = "",\n    val title: String,\n    val detail: String = "",\n    val amount: Int = 1,\n    val evidence: TimelineEventEvidence = TimelineEventEvidence.VERIFIED_DELTA,\n    val source: String = ""\n)\n'''
)

replace_once(
    PATH,
    '''                        type = TimelineEventType.GAME_START,\n                        title = "GAME START",\n                        detail = "G${snapshot.game} · ${snapshot.blue} vs ${snapshot.red}"\n''',
    '''                        type = TimelineEventType.GAME_START,\n                        title = "GAME START",\n                        detail = "G${snapshot.game} · ${snapshot.blue} vs ${snapshot.red}",\n                        evidence = TimelineEventEvidence.LOCAL_CAPTURE,\n                        source = snapshot.source\n'''
)

replace_once(
    PATH,
    '''                type = TimelineEventType.GAME_END,\n                title = "GAME END",\n                detail = "本地记录到最终实时帧 · ${formatClock(current.durationSeconds)}"\n''',
    '''                type = TimelineEventType.GAME_END,\n                title = "GAME END",\n                detail = "本地记录到最终实时帧 · ${formatClock(current.durationSeconds)}",\n                evidence = TimelineEventEvidence.LOCAL_CAPTURE,\n                source = snapshot.source\n'''
)

# Replace delta derivation as one unit so all event classes share the same evidence/source rules.
start = '''    private fun deriveEvents(previous: LiveSnapshot, current: LiveSnapshot): List<MatchTimelineEvent> = buildList {\n'''
end = '''    private fun appendKillEvents(\n'''
p = Path(PATH)
text = p.read_text(encoding="utf-8")
start_pos = text.find(start)
end_pos = text.find(end, start_pos + 1)
if start_pos < 0 or end_pos < 0:
    raise SystemExit("MatchTimelineStore.kt: deriveEvents block not found")
new_derive = '''    private fun deriveEvents(previous: LiveSnapshot, current: LiveSnapshot): List<MatchTimelineEvent> = buildList {\n        val second = current.elapsedSeconds\n        val intervalSeconds = (current.elapsedSeconds - previous.elapsedSeconds).coerceAtLeast(0)\n        val blueKillDelta = (current.blueKills - previous.blueKills).coerceAtLeast(0)\n        val redKillDelta = (current.redKills - previous.redKills).coerceAtLeast(0)\n\n        appendKillEvents(\n            output = this,\n            second = second,\n            team = current.blue,\n            teamDelta = blueKillDelta,\n            previousPlayers = previous.bluePlayers,\n            currentPlayers = current.bluePlayers,\n            source = current.source\n        )\n        appendKillEvents(\n            output = this,\n            second = second,\n            team = current.red,\n            teamDelta = redKillDelta,\n            previousPlayers = previous.redPlayers,\n            currentPlayers = current.redPlayers,\n            source = current.source\n        )\n\n        // A burst of >=3 total kills in one <=20s capture interval is useful as a navigation hint,\n        // but is not official proof that Riot classified the sequence as a team fight.\n        val combatKills = blueKillDelta + redKillDelta\n        if (combatKills >= 3 && intervalSeconds in 1..20) {\n            add(\n                MatchTimelineEvent(\n                    seconds = second,\n                    type = TimelineEventType.TEAM_FIGHT_WINDOW,\n                    title = "团战窗口候选 · +$combatKills 击杀",\n                    detail = "连续实时帧在 ${intervalSeconds}s 内记录到双方合计 +$combatKills 击杀；仅作为 DERIVED_WINDOW 导航锚点，不声称官方事件分类。",\n                    amount = combatKills,\n                    evidence = TimelineEventEvidence.DERIVED_WINDOW,\n                    source = current.source\n                )\n            )\n        }\n\n        appendObjectiveDelta(this, second, current.blue, "防御塔", TimelineEventType.TOWER, current.blueTowers - previous.blueTowers, current.source)\n        appendObjectiveDelta(this, second, current.red, "防御塔", TimelineEventType.TOWER, current.redTowers - previous.redTowers, current.source)\n        appendObjectiveDelta(this, second, current.blue, "小龙（类型未知）", TimelineEventType.DRAGON, current.blueDragons - previous.blueDragons, current.source)\n        appendObjectiveDelta(this, second, current.red, "小龙（类型未知）", TimelineEventType.DRAGON, current.redDragons - previous.redDragons, current.source)\n        appendObjectiveDelta(this, second, current.blue, "男爵", TimelineEventType.BARON, current.blueBarons - previous.blueBarons, current.source)\n        appendObjectiveDelta(this, second, current.red, "男爵", TimelineEventType.BARON, current.redBarons - previous.redBarons, current.source)\n\n        appendPlayerDeltaEvents(this, second, current.blue, previous.bluePlayers, current.bluePlayers, current.source)\n        appendPlayerDeltaEvents(this, second, current.red, previous.redPlayers, current.redPlayers, current.source)\n\n        val oldDiff = previous.goldDiff\n        val newDiff = current.goldDiff\n        val leadChangedHands = (oldDiff > 250 && newDiff < -250) || (oldDiff < -250 && newDiff > 250)\n        val suddenSwing = abs(newDiff - oldDiff) >= 1800 && intervalSeconds in 1..20\n        if (leadChangedHands || suddenSwing) {\n            val leader = when {\n                newDiff > 0 -> current.blue\n                newDiff < 0 -> current.red\n                else -> "双方"\n            }\n            add(\n                MatchTimelineEvent(\n                    seconds = second,\n                    type = if (leadChangedHands) TimelineEventType.GOLD_LEAD_CHANGE else TimelineEventType.GOLD_SWING,\n                    team = if (newDiff > 0) current.blue else if (newDiff < 0) current.red else "",\n                    title = if (leadChangedHands) "经济领先易手" else "经济快速摆动",\n                    detail = "$leader · ${signedGold(newDiff)} · 本段 ${signedGold(newDiff - oldDiff)}",\n                    evidence = TimelineEventEvidence.VERIFIED_DELTA,\n                    source = current.source\n                )\n            )\n        }\n    }\n\n'''
text = text[:start_pos] + new_derive + text[end_pos:]
p.write_text(text, encoding="utf-8")

# Kill deltas remain verified numeric facts. A player gaining >=2 kills inside one sampling interval
# also receives a DERIVED_WINDOW anchor; it is deliberately not called Double/Triple Kill.
replace_once(
    PATH,
    '''        previousPlayers: List<LivePlayerSnapshot>,\n        currentPlayers: List<LivePlayerSnapshot>\n    ) {\n''',
    '''        previousPlayers: List<LivePlayerSnapshot>,\n        currentPlayers: List<LivePlayerSnapshot>,\n        source: String\n    ) {\n'''
)
replace_once(
    PATH,
    '''                    detail = "由连续实时 KDA 数值差分确认；不推断未提供的受害者配对",\n                    amount = amount\n                )\n            }\n''',
    '''                    detail = "由连续实时 KDA 数值差分确认；不推断未提供的受害者配对",\n                    amount = amount,\n                    evidence = TimelineEventEvidence.VERIFIED_DELTA,\n                    source = source\n                )\n                if (amount >= 2) {\n                    output += MatchTimelineEvent(\n                        seconds = second,\n                        type = TimelineEventType.MULTI_KILL_WINDOW,\n                        team = team,\n                        title = "$team · $name 采样窗口 +$amount 击杀",\n                        detail = "连续实时帧确认该选手在同一采样窗口新增 $amount 个击杀；不等同官方 Double / Triple / Quadra / Penta Kill 判定。",\n                        amount = amount,\n                        evidence = TimelineEventEvidence.DERIVED_WINDOW,\n                        source = source\n                    )\n                }\n            }\n'''
)
replace_once(
    PATH,
    '''                title = "$team +$teamDelta 击杀",\n                detail = "由队伍击杀总数差分确认",\n                amount = teamDelta\n''',
    '''                title = "$team +$teamDelta 击杀",\n                detail = "由队伍击杀总数差分确认",\n                amount = teamDelta,\n                evidence = TimelineEventEvidence.VERIFIED_DELTA,\n                source = source\n'''
)

replace_once(
    PATH,
    '''        type: TimelineEventType,\n        delta: Int\n    ) {\n''',
    '''        type: TimelineEventType,\n        delta: Int,\n        source: String\n    ) {\n'''
)
replace_once(
    PATH,
    '''            title = "$team · $label +$delta",\n            detail = "由连续实时资源计数差分确认",\n            amount = delta\n        )\n    }\n\n    private fun playerKey''',
    '''            title = "$team · $label +$delta",\n            detail = "由连续实时资源计数差分确认；若上游只提供小龙总数，不推断龙种、龙魂或远古龙。",\n            amount = delta,\n            evidence = TimelineEventEvidence.VERIFIED_DELTA,\n            source = source\n        )\n    }\n\n    private fun appendPlayerDeltaEvents(\n        output: MutableList<MatchTimelineEvent>,\n        second: Int,\n        team: String,\n        previousPlayers: List<LivePlayerSnapshot>,\n        currentPlayers: List<LivePlayerSnapshot>,\n        source: String\n    ) {\n        if (previousPlayers.isEmpty() || currentPlayers.isEmpty()) return\n        val previousById = previousPlayers.associateBy(::playerKey)\n        currentPlayers.forEach { player ->\n            val before = previousById[playerKey(player)] ?: return@forEach\n            val name = player.summonerName.ifBlank { player.role }\n            val levelDelta = player.level - before.level\n            if (levelDelta > 0) {\n                output += MatchTimelineEvent(\n                    seconds = second,\n                    type = TimelineEventType.PLAYER_LEVEL_CHANGE,\n                    team = team,\n                    title = "$name · Lv.${before.level} → Lv.${player.level}",\n                    detail = "连续实时玩家状态差分",\n                    amount = levelDelta,\n                    evidence = TimelineEventEvidence.VERIFIED_DELTA,\n                    source = source\n                )\n            }\n\n            // CS is already fully available in timeline snapshots. Emit an event only at 50-CS\n            // milestones so the event stream stays navigable instead of producing one row per minion.\n            if (player.creepScore > before.creepScore) {\n                val oldBucket = before.creepScore.coerceAtLeast(0) / 50\n                val newBucket = player.creepScore.coerceAtLeast(0) / 50\n                if (newBucket > oldBucket) {\n                    output += MatchTimelineEvent(\n                        seconds = second,\n                        type = TimelineEventType.PLAYER_CS_CHANGE,\n                        team = team,\n                        title = "$name · ${newBucket * 50} CS",\n                        detail = "连续实时 CS 数值跨越 50 刀里程碑；完整 CS 变化仍保留在状态快照中。",\n                        amount = player.creepScore - before.creepScore,\n                        evidence = TimelineEventEvidence.VERIFIED_DELTA,\n                        source = source\n                    )\n                }\n            }\n\n            val deaths = player.deaths - before.deaths\n            val assists = player.assists - before.assists\n            if (deaths > 0 || assists > 0) {\n                output += MatchTimelineEvent(\n                    seconds = second,\n                    type = TimelineEventType.PLAYER_KDA_CHANGE,\n                    team = team,\n                    title = "$name · KDA ${player.kills}/${player.deaths}/${player.assists}",\n                    detail = "连续实时 KDA 数值差分：死亡 +${deaths.coerceAtLeast(0)} · 助攻 +${assists.coerceAtLeast(0)}；不据此猜测击杀/阵亡配对。",\n                    amount = deaths.coerceAtLeast(0) + assists.coerceAtLeast(0),\n                    evidence = TimelineEventEvidence.VERIFIED_DELTA,\n                    source = source\n                )\n            }\n        }\n    }\n\n    private fun playerKey'''
)

# Persist evidence + origin, while old on-device timeline JSON remains readable through defaults.
replace_once(
    PATH,
    '''                        .put("detail", event.detail)\n                        .put("amount", event.amount)\n''',
    '''                        .put("detail", event.detail)\n                        .put("amount", event.amount)\n                        .put("evidence", event.evidence.name)\n                        .put("source", event.source)\n'''
)
replace_once(
    PATH,
    '''                        title = item.optString("title"),\n                        detail = item.optString("detail"),\n                        amount = item.optInt("amount", 1)\n                    )\n''',
    '''                        title = item.optString("title"),\n                        detail = item.optString("detail"),\n                        amount = item.optInt("amount", 1),\n                        evidence = runCatching {\n                            TimelineEventEvidence.valueOf(item.optString("evidence"))\n                        }.getOrDefault(TimelineEventEvidence.VERIFIED_DELTA),\n                        source = item.optString("source")\n                    )\n'''
)

print("dev72 unified live event model batch11 applied")
