from pathlib import Path

p = Path('app/src/main/java/com/riftlab/app/data/LplHistoricalPostMatchResolver.kt')
s = p.read_text(encoding='utf-8')

old = '''    suspend fun refresh(match: ScheduledEsportsMatch) {
        try {
            refreshInternal(match)
        } catch (t: Throwable) {
            _status.value = "POST · 历史赛后恢复失败 · ${t.message?.take(180) ?: t::class.java.simpleName}"
        }
    }

    private suspend fun refreshInternal(match: ScheduledEsportsMatch) {
'''
new = '''    suspend fun refresh(match: ScheduledEsportsMatch) {
        resolve(match)?.let(CompletedGameArchive::publishSeries)
    }

    suspend fun resolve(match: ScheduledEsportsMatch): CompletedSeriesSnapshot? {
        return try {
            resolveInternal(match)
        } catch (t: Throwable) {
            _status.value = "POST · 历史赛后恢复失败 · ${t.message?.take(180) ?: t::class.java.simpleName}"
            null
        }
    }

    private suspend fun resolveInternal(match: ScheduledEsportsMatch): CompletedSeriesSnapshot? {
'''
if old not in s: raise SystemExit('resolver header anchor not found')
s = s.replace(old, new, 1)

old = '''        if (already != null && already.seriesFinished && lastResolvedScheduleKey == scheduleKey) {
            _status.value = "POST · 已恢复 ${already.teamA} ${already.scoreA}:${already.scoreB} ${already.teamB} · ${already.games.size} 局"
            return
        }
'''
new = '''        if (already != null && already.seriesFinished && lastResolvedScheduleKey == scheduleKey) {
            _status.value = "POST · 已恢复 ${already.teamA} ${already.scoreA}:${already.scoreB} ${already.teamB} · ${already.games.size} 局"
            return already
        }
'''
if old not in s: raise SystemExit('resolver already anchor not found')
s = s.replace(old, new, 1)

old = '''        if (ref == null) {
            _status.value = "POST · 未在 LPL 历史 BMatch 列表找到 ${teamsLabel(match)} · ${localDate(match.startTimeIso)}"
            return
        }
'''
new = '''        if (ref == null) {
            _status.value = "POST · 未在 LPL 历史 BMatch 列表找到 ${teamsLabel(match)} · ${localDate(match.startTimeIso)}"
            return null
        }
'''
if old not in s: raise SystemExit('resolver ref anchor not found')
s = s.replace(old, new, 1)

old = '''        if (finals.isEmpty()) {
            _status.value = "POST · bmid=${ref.bmid} 已定位，但终局解析为空 · games=${games.size} · ${parsedGameSummary(gameArray, games)} · ${schemaSummary(data)}"
            return
        }
'''
new = '''        if (finals.isEmpty()) {
            _status.value = "POST · bmid=${ref.bmid} 已定位，但终局解析为空 · games=${games.size} · ${parsedGameSummary(gameArray, games)} · ${schemaSummary(data)}"
            return null
        }
'''
if old not in s: raise SystemExit('resolver finals anchor not found')
s = s.replace(old, new, 1)

old = '''        CompletedGameArchive.publishSeries(snapshot)
        lastResolvedScheduleKey = scheduleKey
        _status.value = "POST · 已恢复 ${snapshot.teamA} ${snapshot.scoreA}:${snapshot.scoreB} ${snapshot.teamB} · ${snapshot.games.size} 局 · bmid=${ref.bmid}"
    }
'''
new = '''        lastResolvedScheduleKey = scheduleKey
        _status.value = "POST · 已恢复 ${snapshot.teamA} ${snapshot.scoreA}:${snapshot.scoreB} ${snapshot.teamB} · ${snapshot.games.size} 局 · bmid=${ref.bmid}"
        return snapshot
    }
'''
if old not in s: raise SystemExit('resolver tail anchor not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

p = Path('app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt')
s = p.read_text(encoding='utf-8')
old = '''        RiftLabApp()
        ScheduleCenterLauncher(
'''
new = '''        RiftLabApp()
        MatchDetailDialogHost()
        ScheduleCenterLauncher(
'''
if old not in s: raise SystemExit('root host anchor not found')
s = s.replace(old, new, 1)
old = '''                            onMatchClick = { match ->
                                MatchSessionStore.selectScheduleMatch(match.matchId)
                                onClose()
                            }
'''
new = '''                            onMatchClick = { match ->
                                MatchSessionStore.selectScheduleMatch(match.matchId)
                                com.riftlab.app.data.MatchDetailRepository.open(match)
                                onClose()
                            }
'''
if old not in s: raise SystemExit('schedule click anchor not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

print('patched match detail architecture')
