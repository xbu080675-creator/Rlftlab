#!/usr/bin/env python3
from pathlib import Path
import runpy

p = Path("tools/apply_dev72_audit_batch7.py")
text = p.read_text(encoding="utf-8")

# Repair the TournamentResearch match ordering used by the first draft of the patcher.
old = '''    ''' + "'''" + '''        identity.contains("americas cup") || identity.contains("america cup") || identity.contains("美洲杯") -> "美洲杯"\\n        identity.contains("emea masters") || identity.contains("emea 大师赛") -> "EMEA 大师赛"\\n        Regex("(^|[^a-z])wsc[il]([^a-z]|$)").containsMatchIn(identity) -> "WSCL"\\n''' + "'''" + ''',
    ''' + "'''" + '''        identity.contains("americas cup") || identity.contains("america cup") || identity.contains("美洲杯") -> "美洲杯"\\n        identity.contains("emea masters") || identity.contains("emea 大师赛") -> "EMEA 大师赛"\\n        Regex("(^|[^a-z])wsci([^a-z]|$)").containsMatchIn(identity) -> "WSCI"\\n        Regex("(^|[^a-z])wscl([^a-z]|$)").containsMatchIn(identity) -> "WSCL"\\n''' + "'''" + '''
'''
new = '''    ''' + "'''" + '''        Regex("(^|[^a-z])wsc[il]([^a-z]|$)").containsMatchIn(identity) -> "WSCL"\\n        identity.contains("americas cup") || identity.contains("america cup") || identity.contains("美洲杯") -> "美洲杯"\\n        identity.contains("emea masters") || identity.contains("emea 大师赛") -> "EMEA 大师赛"\\n''' + "'''" + ''',
    ''' + "'''" + '''        Regex("(^|[^a-z])wsci([^a-z]|$)").containsMatchIn(identity) -> "WSCI"\\n        Regex("(^|[^a-z])wscl([^a-z]|$)").containsMatchIn(identity) -> "WSCL"\\n        identity.contains("americas cup") || identity.contains("america cup") || identity.contains("美洲杯") -> "美洲杯"\\n        identity.contains("emea masters") || identity.contains("emea 大师赛") -> "EMEA 大师赛"\\n''' + "'''" + '''
'''
if old not in text:
    raise SystemExit("failed to repair TournamentResearch patch block")
text = text.replace(old, new, 1)

# The live poll loop wraps its body in try/catch; preserve that structure while inserting the
# provider-id guard inside the try block.
live_old = '''    ''' + "'''" + '''        while (currentCoroutineContext().isActive) {\\n            if (currentEvent == null) {\\n''' + "'''" + ''',
    ''' + "'''" + '''        while (currentCoroutineContext().isActive) {\\n            val registeredTarget = LiveMatchTargetRegistry.snapshot()\\n            if (registeredTarget != null && isExternalProviderTarget(registeredTarget)) {\\n                currentEvent = null\\n                knownGameIds = emptyList()\\n                emittedGameIds.clear()\\n                _status.value = LiveSourceStatus(\\n                    phase = LiveSourcePhase.WAITING_FOR_MATCH,\\n                    message = "Riot LiveStats · 当前赛事使用非 Riot Event ID，等待其它实时源",\\n                    eventId = registeredTarget.eventId,\\n                    lastUpdateEpochMs = System.currentTimeMillis()\\n                )\\n                delay(5_000)\\n                continue\\n            }\\n\\n            if (currentEvent == null) {\\n''' + "'''" + '''
'''
live_new = '''    ''' + "'''" + '''        while (currentCoroutineContext().isActive) {\\n            try {\\n                if (currentEvent == null) {\\n''' + "'''" + ''',
    ''' + "'''" + '''        while (currentCoroutineContext().isActive) {\\n            try {\\n                val registeredTarget = LiveMatchTargetRegistry.snapshot()\\n                if (registeredTarget != null && isExternalProviderTarget(registeredTarget)) {\\n                    currentEvent = null\\n                    knownGames = emptyList()\\n                    currentGame = null\\n                    currentGameId = ""\\n                    previous = null\\n                    lockedFromSchedule = false\\n                    _status.value = LiveSourceStatus(\\n                        phase = LiveSourcePhase.WAITING_FOR_MATCH,\\n                        message = "Riot LiveStats · 当前赛事使用非 Riot Event ID，等待其它实时源",\\n                        eventId = registeredTarget.eventId,\\n                        lastUpdateEpochMs = System.currentTimeMillis()\\n                    )\\n                    delay(5_000)\\n                    continue\\n                }\\n\\n                if (currentEvent == null) {\\n''' + "'''" + '''
'''
if live_old not in text:
    raise SystemExit("failed to repair live-loop patch block")
text = text.replace(live_old, live_new, 1)

# The current live source has no selectScheduleEvent helper. Insert the external-provider predicate
# immediately before teamLabel instead.
helper_old = '''    ''' + "'''" + '''    private fun selectScheduleEvent(\\n''' + "'''" + ''',
    ''' + "'''" + '''    private fun isExternalProviderTarget(match: ScheduledEsportsMatch): Boolean =\\n        match.eventId.startsWith("provider:") || match.leagueId.startsWith("rft-event:")\\n\\n    private fun selectScheduleEvent(\\n''' + "'''" + '''
'''
helper_new = '''    ''' + "'''" + '''    private fun teamLabel(match: ScheduledEsportsMatch): String =\\n''' + "'''" + ''',
    ''' + "'''" + '''    private fun isExternalProviderTarget(match: ScheduledEsportsMatch): Boolean =\\n        match.eventId.startsWith("provider:") || match.leagueId.startsWith("rft-event:")\\n\\n    private fun teamLabel(match: ScheduledEsportsMatch): String =\\n''' + "'''" + '''
'''
if helper_old not in text:
    raise SystemExit("failed to repair external-provider helper patch block")
text = text.replace(helper_old, helper_new, 1)

p.write_text(text, encoding="utf-8")
runpy.run_path(str(p), run_name="__main__")
