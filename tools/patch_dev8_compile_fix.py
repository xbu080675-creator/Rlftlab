from pathlib import Path

# Add a safe per-match resolve() API without returning unrelated global archive state.
p = Path('app/src/main/java/com/riftlab/app/data/LplHistoricalPostMatchResolver.kt')
s = p.read_text(encoding='utf-8')

anchor = '''    private var lastResolvedScheduleKey: String = ""
'''
replacement = '''    private var lastResolvedScheduleKey: String = ""
    private val resolvedByScheduleKey = linkedMapOf<String, CompletedSeriesSnapshot>()

    /** Resolve one explicit schedule match and return only that match's terminal series snapshot. */
    suspend fun resolve(match: ScheduledEsportsMatch): CompletedSeriesSnapshot? {
        val key = scheduleKeyFor(match)
        refresh(match)
        return resolvedByScheduleKey[key]
    }

    private fun scheduleKeyFor(match: ScheduledEsportsMatch): String =
        match.eventId.ifBlank { match.matchId }.ifBlank {
            match.teams.take(2).joinToString("|") { team -> team.code.ifBlank { team.name } } + "|" + match.startTimeIso
        }
'''
if anchor not in s:
    raise SystemExit('resolver field anchor missing')
s = s.replace(anchor, replacement, 1)

old = '''        val scheduleKey = match.eventId.ifBlank { match.matchId }.ifBlank {
            match.teams.take(2).joinToString("|") { team -> team.code.ifBlank { team.name } } + "|" + match.startTimeIso
        }
'''
new = '''        val scheduleKey = scheduleKeyFor(match)
'''
if old not in s:
    raise SystemExit('schedule key anchor missing')
s = s.replace(old, new, 1)

old = '''        if (already != null && already.seriesFinished && lastResolvedScheduleKey == scheduleKey) {
            _status.value = "POST · 已恢复 ${already.teamA} ${already.scoreA}:${already.scoreB} ${already.teamB} · ${already.games.size} 局"
            return
        }
'''
new = '''        if (already != null && already.seriesFinished && lastResolvedScheduleKey == scheduleKey) {
            resolvedByScheduleKey[scheduleKey] = already
            _status.value = "POST · 已恢复 ${already.teamA} ${already.scoreA}:${already.scoreB} ${already.teamB} · ${already.games.size} 局"
            return
        }
'''
if old not in s:
    raise SystemExit('already cache anchor missing')
s = s.replace(old, new, 1)

old = '''        CompletedGameArchive.publishSeries(snapshot)
        lastResolvedScheduleKey = scheduleKey
'''
new = '''        CompletedGameArchive.publishSeries(snapshot)
        resolvedByScheduleKey[scheduleKey] = snapshot
        lastResolvedScheduleKey = scheduleKey
'''
if old not in s:
    raise SystemExit('publish anchor missing')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# Keep updater implementation internal to the app module so its internal StateFlow type is legal.
p = Path('app/src/main/java/com/riftlab/app/update/AppUpdateManager.kt')
s = p.read_text(encoding='utf-8')
s = s.replace('''object AppUpdateManager {''', '''internal object AppUpdateManager {''', 1)
p.write_text(s, encoding='utf-8')

print('patched dev8 compile errors')
