from pathlib import Path

# 1) Current subscriptions: LDL is stopped in 2026, so remove it from the active/home catalog.
p = Path('app/src/main/java/com/riftlab/app/ui/LeagueSubscriptionUi.kt')
s = p.read_text()
s = s.replace('    LeagueSubscriptionOption("LDL", "LDL"),\n', '')
old = '''        val stored = prefs.getStringSet(KEY, null)
            ?.map(::leagueSubscriptionKey)
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()'''
new = '''        val allowed = LeagueSubscriptionOptions.map { leagueSubscriptionKey(it.key) }.toSet()
        val stored = prefs.getStringSet(KEY, null)
            ?.map(::leagueSubscriptionKey)
            ?.filter { it.isNotBlank() && it in allowed }
            ?.toSet()
            .orEmpty()'''
if old not in s:
    raise SystemExit('LeagueSubscriptionStore migration block not found')
p.write_text(s.replace(old, new, 1))

# 2) Active Riot discovery: remove LDL, add current international aliases when Riot exposes them.
p = Path('app/src/main/java/com/riftlab/app/data/LolEsportsApiClient.kt')
s = p.read_text()
s = s.replace(
    '"worlds", "msi", "first-stand", "first_stand", "firststand", "ewc", "esports-world-cup", "americas-cup", "emea-masters",',
    '"worlds", "msi", "first-stand", "first_stand", "firststand", "ewc", "esports-world-cup", "americas-cup", "emea-masters", "demacia-cup", "demacia-cup-global-invitational", "demacia-global-invitational", "wscl",'
)
s = s.replace(
    '        "lck-cl", "lck_challengers", "lcp-wild-card", "lpl-development-league", "ldl",\n',
    '        "lck-cl", "lck_challengers", "lcp-wild-card",\n'
)
s = s.replace(
    '        "americas cup", "emea masters", "fls", "lck cl", "lck challengers", "lcp wild card",\n        "lpl development league", "greek legends league", "lit", "nlc", "esports balkan league",',
    '        "americas cup", "emea masters", "demacia cup global invitational", "demacia cup", "wscl", "fls", "lck cl", "lck challengers", "lcp wild card",\n        "greek legends league", "lit", "nlc", "esports balkan league",'
)
p.write_text(s)

# 3) International event IA: Worlds 2026 first, plus Demacia Cup Global Invitational and WSCL.
p = Path('app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt')
s = p.read_text()
old = '''private enum class InternationalCompetitionMenu(val label: String) {
    FIRST_STAND("全球先锋赛"),
    MSI("季中冠军赛"),
    AMERICAS_CUP("美洲杯"),
    WORLDS("全球总决赛"),
    EWC("EWC"),
    EMEA_MASTERS("EMEA 大师赛")
}'''
new = '''private enum class InternationalCompetitionMenu(val label: String) {
    WORLDS("2026 全球总决赛"),
    DEMACIA_GLOBAL("德杯国际邀请赛"),
    WSCL("WSCL"),
    FIRST_STAND("全球先锋赛"),
    MSI("季中冠军赛"),
    AMERICAS_CUP("美洲杯"),
    EWC("EWC"),
    EMEA_MASTERS("EMEA 大师赛")
}'''
if old not in s:
    raise SystemExit('InternationalCompetitionMenu block not found')
s = s.replace(old, new, 1)

old = '''            if (selectedBuckets.isEmpty()) {
                EmptyData("${selectedInternational.label} · 当前分页暂无赛程，保留固定入口")
            } else {'''
new = '''            if (selectedBuckets.isEmpty()) {
                InternationalEventPlaceholder(selectedInternational)
            } else {'''
if old not in s:
    raise SystemExit('international empty-state block not found')
s = s.replace(old, new, 1)

insert_before = '''@Composable
private fun DirectorySectionHeader(title: String) {'''
placeholder = '''@Composable
private fun InternationalEventPlaceholder(menu: InternationalCompetitionMenu) {
    val (title, meta, detail) = when (menu) {
        InternationalCompetitionMenu.WORLDS -> Triple(
            "2026 全球总决赛 · WORLDS 2026",
            "10月15日 - 11月14日 · 美国",
            "年度最高优先级赛事。入围赛：洛杉矶；瑞士轮 / 八强 / 半决赛：德州 Allen；总决赛：纽约布鲁克林 Barclays Center。具体对阵、开赛时间与战队确认后由动态赛程替换本卡。"
        )
        InternationalCompetitionMenu.DEMACIA_GLOBAL -> Triple(
            "2026 德玛西亚杯国际邀请赛",
            "10月3日 - 10月17日 · 12队 / 6赛区",
            "2026 德杯已升级为国际邀请赛。LPL、LCK、LEC、LCS、LCP、CBLOL 中未晋级 Worlds 的高顺位队伍受邀参赛；正式对阵与直播元数据进入可信赛事源后自动接管。"
        )
        InternationalCompetitionMenu.WSCL -> Triple(
            "WSCL",
            "国际赛事 · 当前赛事入口保留",
            "WSCL 不归入已经停摆的 2026 LDL。赛程、比分和战队只在可信源返回后展示；不会因为参赛队曾属于次级联赛而错误归类回 LDL。"
        )
        else -> Triple(
            menu.label,
            "国际赛事 · 固定入口",
            "当前分页暂无可核实赛程；RiftLab 保留赛事入口，待 Riot / 官方赛事源返回数据后自动填充。"
        )
    }
    Column(
        Modifier.fillMaxWidth()
            .background(RiftPanel, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
            .border(1.dp, if (menu == InternationalCompetitionMenu.WORLDS) RiftCyan.copy(alpha = 0.55f) else RiftLine, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))
            .padding(14.dp)
    ) {
        Text(if (menu == InternationalCompetitionMenu.WORLDS) "SEASON FINALE · MAIN EVENT" else "INTERNATIONAL EVENT", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(title, color = RiftText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(meta, color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(detail, color = RiftMuted, fontSize = 9.sp, lineHeight = 14.sp)
    }
}

'''
if insert_before not in s:
    raise SystemExit('DirectorySectionHeader insert point not found')
s = s.replace(insert_before, placeholder + insert_before, 1)

old = '''    return when {
        identity.contains("first stand") || identity.contains("first-stand") || identity.contains("first_stand") -> InternationalCompetitionMenu.FIRST_STAND
        identity.contains("mid-season") || Regex("(^|[^a-z])msi([^a-z]|$)").containsMatchIn(identity) -> InternationalCompetitionMenu.MSI
        identity.contains("americas cup") || identity.contains("america cup") -> InternationalCompetitionMenu.AMERICAS_CUP
        identity.contains("worlds") || identity.contains("world championship") || identity.contains("全球总决赛") -> InternationalCompetitionMenu.WORLDS
        identity.contains("esports world cup") || Regex("(^|[^a-z])ewc([^a-z]|$)").containsMatchIn(identity) -> InternationalCompetitionMenu.EWC
        identity.contains("emea masters") -> InternationalCompetitionMenu.EMEA_MASTERS
        else -> null
    }'''
new = '''    return when {
        identity.contains("demacia cup") || identity.contains("德玛西亚杯") || identity.contains("demacia global invitational") -> InternationalCompetitionMenu.DEMACIA_GLOBAL
        Regex("(^|[^a-z])wscl([^a-z]|$)").containsMatchIn(identity) -> InternationalCompetitionMenu.WSCL
        identity.contains("first stand") || identity.contains("first-stand") || identity.contains("first_stand") -> InternationalCompetitionMenu.FIRST_STAND
        identity.contains("mid-season") || Regex("(^|[^a-z])msi([^a-z]|$)").containsMatchIn(identity) -> InternationalCompetitionMenu.MSI
        identity.contains("americas cup") || identity.contains("america cup") -> InternationalCompetitionMenu.AMERICAS_CUP
        identity.contains("worlds") || identity.contains("world championship") || identity.contains("全球总决赛") -> InternationalCompetitionMenu.WORLDS
        identity.contains("esports world cup") || Regex("(^|[^a-z])ewc([^a-z]|$)").containsMatchIn(identity) -> InternationalCompetitionMenu.EWC
        identity.contains("emea masters") -> InternationalCompetitionMenu.EMEA_MASTERS
        else -> null
    }'''
if old not in s:
    raise SystemExit('internationalCompetitionKind block not found')
s = s.replace(old, new, 1)
p.write_text(s)

# 4) Version bump.
p = Path('app/build.gradle.kts')
s = p.read_text()
if 'versionCode = 49' not in s or 'versionName = "1.0.0-dev.49"' not in s:
    raise SystemExit('dev49 version markers not found')
s = s.replace('versionCode = 49', 'versionCode = 50', 1)
s = s.replace('versionName = "1.0.0-dev.49"', 'versionName = "1.0.0-dev.50"', 1)
s += '\n// dev.50: retire LDL from current subscriptions, promote Worlds 2026, add Demacia Cup Global Invitational and WSCL international catalog entries.\n'
p.write_text(s)
