from pathlib import Path

root = Path('.')
store = root / 'app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt'
text = store.read_text(encoding='utf-8')

anchor = '    private val liveDataSource = LplOfficialLiveDataSource()\n'
if 'private val postMatchResolver = LplHistoricalPostMatchResolver()' not in text:
    text = text.replace(anchor, anchor + '    private val postMatchResolver = LplHistoricalPostMatchResolver()\n', 1)

anchor = '    val liveSourceStatus: StateFlow<LiveSourceStatus> = liveDataSource.status\n'
if 'val postSourceStatus:' not in text:
    text = text.replace(anchor, anchor + '    val postSourceStatus: StateFlow<String> = postMatchResolver.status\n', 1)

anchor = '            _scheduleStatus.value = center.statusMessage\n\n            if (selected != null) {'
insert = '''            _scheduleStatus.value = center.statusMessage\n\n            // Post-match recovery is independent from the live target. Always resolve the most\n            // recent completed LPL series from the schedule, so opening RiftLab after the match\n            // can still rebuild the complete final archive.\n            val latestCompleted = matches\n                .filter(::isCompletedState)\n                .maxByOrNull { plannedStartEpochMs(it) ?: Long.MIN_VALUE }\n            if (latestCompleted != null) {\n                scope.launch {\n                    runCatching { postMatchResolver.refresh(latestCompleted) }\n                }\n            }\n\n            if (selected != null) {'''
if 'val latestCompleted = matches' not in text:
    if anchor not in text:
        raise SystemExit('MatchSessionStore integration anchor missing')
    text = text.replace(anchor, insert, 1)

store.write_text(text, encoding='utf-8')

ui = root / 'app/src/main/java/com/riftlab/app/ui/RiftLabApp.kt'
text = ui.read_text(encoding='utf-8')
anchor = '    val latest by MatchSessionStore.completedGame.collectAsState()\n'
if 'val postStatus by MatchSessionStore.postSourceStatus.collectAsState()' not in text:
    text = text.replace(anchor, anchor + '    val postStatus by MatchSessionStore.postSourceStatus.collectAsState()\n', 1)

old = '                        latest?.latestEvent ?: "正在等待 LPL 官方赛后数据源返回；不使用 Mock MVP / 排行榜填空。",'
new = '                        latest?.latestEvent ?: postStatus,'
if old in text:
    text = text.replace(old, new, 1)

ui.write_text(text, encoding='utf-8')
print('historical post resolver integrated')
# trigger-v2
