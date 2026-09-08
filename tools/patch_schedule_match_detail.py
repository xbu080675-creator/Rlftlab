from pathlib import Path

p = Path('app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt')
s = p.read_text(encoding='utf-8')

s = s.replace(
    'import com.riftlab.app.data.MatchSessionStore\n',
    'import com.riftlab.app.data.MatchDetailRepository\nimport com.riftlab.app.data.MatchSessionStore\n',
    1
)

old = '''    var selectedBucketKey by remember { mutableStateOf<String?>(null) }
    var tabIndex by remember { mutableIntStateOf(0) }
    val selectedBucket = buckets.firstOrNull { it.key == selectedBucketKey }
'''
new = '''    var selectedBucketKey by remember { mutableStateOf<String?>(null) }
    var selectedDetailMatch by remember { mutableStateOf<ScheduledEsportsMatch?>(null) }
    var tabIndex by remember { mutableIntStateOf(0) }
    val selectedBucket = buckets.firstOrNull { it.key == selectedBucketKey }
'''
if old not in s:
    raise SystemExit('state anchor not found')
s = s.replace(old, new, 1)

old = '''                ScheduleCenterHeader(
                    title = selectedBucket?.title ?: "英雄联盟赛事",
                    subtitle = if (selectedBucket == null) {
                        "按官方 Tournament 整理"
                    } else {
                        competitionRange(selectedBucket.matches)
                    },
                    canGoBack = selectedBucket != null,
                    onBack = {
                        selectedBucketKey = null
                        tabIndex = 0
                    },
                    onClose = onClose
                )

                Spacer(Modifier.height(12.dp))
                if (selectedBucket == null) {
'''
new = '''                ScheduleCenterHeader(
                    title = selectedDetailMatch?.let(::matchLabel) ?: selectedBucket?.title ?: "英雄联盟赛事",
                    subtitle = selectedDetailMatch?.let { match ->
                        "${match.blockName.ifBlank { match.league }} · BO${match.bestOf} · ${MatchSessionStore.scheduleDateKey(match)}"
                    } ?: if (selectedBucket == null) {
                        "按官方 Tournament 整理"
                    } else {
                        competitionRange(selectedBucket.matches)
                    },
                    canGoBack = selectedDetailMatch != null || selectedBucket != null,
                    onBack = {
                        if (selectedDetailMatch != null) {
                            selectedDetailMatch = null
                        } else {
                            selectedBucketKey = null
                            tabIndex = 0
                        }
                    },
                    onClose = onClose
                )

                Spacer(Modifier.height(12.dp))
                if (selectedDetailMatch != null) {
                    MatchDetailContent()
                } else if (selectedBucket == null) {
'''
if old not in s:
    raise SystemExit('header/body anchor not found')
s = s.replace(old, new, 1)

old = '''                        onSelect = { bucket ->
                            selectedBucketKey = bucket.key
                            tabIndex = 0
                            bucket.tournamentId?.let(StandingsCenterStore::selectTournament)
                        }
'''
new = '''                        onSelect = { bucket ->
                            selectedDetailMatch = null
                            selectedBucketKey = bucket.key
                            tabIndex = 0
                            bucket.tournamentId?.let(StandingsCenterStore::selectTournament)
                        }
'''
if old not in s:
    raise SystemExit('bucket select anchor not found')
s = s.replace(old, new, 1)

old = '''                            onMatchClick = { match ->
                                MatchSessionStore.selectScheduleMatch(match.matchId)
                                onClose()
                            }
'''
new = '''                            onMatchClick = { match ->
                                MatchSessionStore.selectScheduleMatch(match.matchId)
                                MatchDetailRepository.open(match)
                                selectedDetailMatch = match
                            }
'''
if old not in s:
    raise SystemExit('match click anchor not found')
s = s.replace(old, new, 1)

p.write_text(s, encoding='utf-8')
print('patched ScheduleCenterUi -> Match Detail navigation')
