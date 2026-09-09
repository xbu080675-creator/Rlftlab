from pathlib import Path

p = Path('app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt')
s = p.read_text()

# Do not auto-jump into whichever league happens to have a current/today/next match.
# Opening the center must land on the directory, with the subscribed league chosen first.
s = s.replace('    var initialPositionResolved by remember { mutableStateOf(false) }\n', '')
old = '''    LaunchedEffect(buckets, center.currentMatch?.matchId, center.nextMatch?.matchId) {
        if (initialPositionResolved || buckets.isEmpty()) return@LaunchedEffect
        val initial = chooseInitialBucket(
            buckets = buckets,
            currentMatchId = center.currentMatch?.matchId,
            nextMatchId = center.nextMatch?.matchId,
            today = LocalDate.now()
        )
        if (initial != null) {
            selectedBucketKey = initial.key
            tabIndex = EventCenterTab.SCHEDULE.ordinal
            initial.tournamentId?.let(StandingsCenterStore::selectTournament)
        }
        initialPositionResolved = true
    }

'''
if old not in s:
    raise SystemExit('auto-open bucket block not found')
s = s.replace(old, '', 1)

old = '''    var rootIndex by remember(buckets, currentMatchId, nextMatchId) {
        mutableIntStateOf(if (activeIntl != null) 1 else 0)
    }
    val preferredLeague = activeBucket?.takeIf { internationalCompetitionKind(it) == null }?.let(::bucketLeagueLabel)
        ?: regional.firstOrNull { leagueSubscriptionKey(it.first) in subscribed }?.first
        ?: regional.firstOrNull()?.first.orEmpty()'''
new = '''    var rootIndex by remember { mutableIntStateOf(0) }
    val preferredLeague = regional.firstOrNull { leagueSubscriptionKey(it.first) in subscribed }?.first
        ?: activeBucket?.takeIf { internationalCompetitionKind(it) == null }?.let(::bucketLeagueLabel)
        ?: regional.firstOrNull()?.first.orEmpty()'''
if old not in s:
    raise SystemExit('directory default block not found')
s = s.replace(old, new, 1)

# Keep fixed high-priority catalog slots without inventing dates/venues before an upstream source confirms them.
old = '''        InternationalCompetitionMenu.WORLDS -> Triple(
            "2026 全球总决赛 · WORLDS 2026",
            "10月15日 - 11月14日 · 美国",
            "年度最高优先级赛事。入围赛：洛杉矶；瑞士轮 / 八强 / 半决赛：德州 Allen；总决赛：纽约布鲁克林 Barclays Center。具体对阵、开赛时间与战队确认后由动态赛程替换本卡。"
        )
        InternationalCompetitionMenu.DEMACIA_GLOBAL -> Triple(
            "2026 德玛西亚杯国际邀请赛",
            "10月3日 - 10月17日 · 12队 / 6赛区",
            "2026 德杯已升级为国际邀请赛。LPL、LCK、LEC、LCS、LCP、CBLOL 中未晋级 Worlds 的高顺位队伍受邀参赛；正式对阵与直播元数据进入可信赛事源后自动接管。"
        )'''
new = '''        InternationalCompetitionMenu.WORLDS -> Triple(
            "2026 全球总决赛 · WORLDS 2026",
            "年度重头赛事 · 固定一级优先入口",
            "2026 全球总决赛位置永久保留在国际赛事首位。参赛队、赛程、开赛时间、场馆和直播信息只使用 Riot / 官方赛事源动态填充；数据未发布时不伪造。"
        )
        InternationalCompetitionMenu.DEMACIA_GLOBAL -> Triple(
            "2026 德杯国际邀请赛",
            "最新国际赛事 · 固定入口",
            "德杯国际邀请赛独立归入国际赛事，不归入 LPL 常规联赛。参赛队、分组、赛程和直播信息在可信赛事源可用后自动填充。"
        )'''
if old not in s:
    raise SystemExit('Worlds/Demacia placeholder block not found')
s = s.replace(old, new, 1)

p.write_text(s)
