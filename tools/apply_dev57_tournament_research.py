from pathlib import Path

ui = Path('app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt')
s = ui.read_text()

# Imports.
s = s.replace(
    'import com.riftlab.app.data.TournamentGovernanceProvider\n',
    'import com.riftlab.app.data.TournamentGovernanceProvider\nimport com.riftlab.app.data.TournamentResearchProvider\nimport com.riftlab.app.data.ResearchEvidence\n'
)

# Annual research becomes the landing tab for every tournament edition.
s = s.replace(
    'private enum class EventCenterTab(val label: String) {\n    SCHEDULE("赛程"),',
    'private enum class EventCenterTab(val label: String) {\n    RESEARCH("研究"),\n    SCHEDULE("赛程"),'
)

# Event family tabs should not themselves carry a year; year lives in edition cards.
s = s.replace(
    'private enum class InternationalCompetitionMenu(val label: String) {\n    WORLDS("2026 全球总决赛"),',
    'private enum class InternationalCompetitionMenu(val label: String) {\n    WORLDS("全球总决赛"),'
)

# Mark synthetic annual research entries without pretending they have a schedule.
s = s.replace(
    '    val tournamentId: String? = null,\n    val tournament: EsportsTournamentRef? = null\n)',
    '    val tournamentId: String? = null,\n    val tournament: EsportsTournamentRef? = null,\n    val researchOnly: Boolean = false\n)'
)

# Header subtitle for research-only future editions.
s = s.replace(
    '                    } else {\n                        competitionRange(selectedBucket.matches)\n                    },',
    '                    } else {\n                        if (selectedBucket.researchOnly) "年度赛事研究 · 版本 / 规则 / 抽签 / 赛程" else competitionRange(selectedBucket.matches)\n                    },',
    1
)

# Add research tab dispatch.
needle = '''                        when (EventCenterTab.entries[tabIndex]) {
                            EventCenterTab.SCHEDULE -> CompetitionMatches('''
replacement = '''                        when (EventCenterTab.entries[tabIndex]) {
                            EventCenterTab.RESEARCH -> ResearchView(
                                bucket = selectedBucket,
                                standings = selectedStandings,
                                onOpenSchedule = { tabIndex = EventCenterTab.SCHEDULE.ordinal }
                            )
                            EventCenterTab.SCHEDULE -> CompetitionMatches('''
if needle not in s:
    raise SystemExit('tab dispatch anchor not found')
s = s.replace(needle, replacement, 1)

# Summary card: do not call an empty future research slot "finished".
s = s.replace(
    '''                when {
                    hasCurrent -> currentActivity?.let(::scheduleActivityText) ?: "进行中"
                    hasNext -> "当前赛段"
                    bucket.matches.all { MatchSessionStore.schedulePhase(it) == ScheduleMatchPhase.COMPLETED } -> "已结束"
                    else -> "赛事"
                },''',
    '''                when {
                    bucket.researchOnly -> "年度研究档案"
                    hasCurrent -> currentActivity?.let(::scheduleActivityText) ?: "进行中"
                    hasNext -> "当前赛段"
                    bucket.matches.isNotEmpty() && bucket.matches.all { MatchSessionStore.schedulePhase(it) == ScheduleMatchPhase.COMPLETED } -> "已结束"
                    else -> "赛事"
                },''',
    1
)
s = s.replace(
    '            Text("${bucket.matches.size} 场", color = RiftMuted, fontSize = 10.sp)\n',
    '            Text(if (bucket.researchOnly) "RESEARCH" else "${bucket.matches.size} 场", color = RiftMuted, fontSize = 10.sp)\n',
    1
)
s = s.replace(
    '        Text(competitionRange(bucket.matches), color = RiftMuted, fontSize = 10.sp)\n',
    '        Text(if (bucket.researchOnly) "赛程待可信源发布 · 研究档案先行" else competitionRange(bucket.matches), color = RiftMuted, fontSize = 10.sp)\n',
    1
)

# International directory cards: yearly research edition is explicit.
s = s.replace(
    '                Text(competitionRange(bucket.matches), color = RiftMuted, fontSize = 10.sp)\n',
    '                Text(if (bucket.researchOnly) "年度研究档案 · 赛程待同步" else competitionRange(bucket.matches), color = RiftMuted, fontSize = 10.sp)\n',
    1
)
s = s.replace(
    '                Text("${bucket.matches.size} 场 · 已结束 $completed", color = RiftMuted, fontSize = 9.sp)\n',
    '                Text(if (bucket.researchOnly) "VERSION · RULES · DRAW · SCHEDULE" else "${bucket.matches.size} 场 · 已结束 $completed", color = RiftMuted, fontSize = 9.sp)\n',
    1
)

# Recognize Chinese family labels too; synthetic research editions depend on this.
s = s.replace(
    '        identity.contains("first stand") || identity.contains("first-stand") || identity.contains("first_stand") -> InternationalCompetitionMenu.FIRST_STAND\n',
    '        identity.contains("first stand") || identity.contains("first-stand") || identity.contains("first_stand") || identity.contains("全球先锋赛") -> InternationalCompetitionMenu.FIRST_STAND\n'
)
s = s.replace(
    '        identity.contains("mid-season") || Regex("(^|[^a-z])msi([^a-z]|$)").containsMatchIn(identity) -> InternationalCompetitionMenu.MSI\n',
    '        identity.contains("mid-season") || Regex("(^|[^a-z])msi([^a-z]|$)").containsMatchIn(identity) || identity.contains("季中冠军赛") -> InternationalCompetitionMenu.MSI\n'
)
s = s.replace(
    '        identity.contains("americas cup") || identity.contains("america cup") -> InternationalCompetitionMenu.AMERICAS_CUP\n',
    '        identity.contains("americas cup") || identity.contains("america cup") || identity.contains("美洲杯") -> InternationalCompetitionMenu.AMERICAS_CUP\n'
)
s = s.replace(
    '        identity.contains("emea masters") -> InternationalCompetitionMenu.EMEA_MASTERS\n',
    '        identity.contains("emea masters") || identity.contains("emea 大师赛") -> InternationalCompetitionMenu.EMEA_MASTERS\n'
)

# Empty future schedule should explain itself rather than render a blank list.
needle = '''private fun CompetitionMatches(
    bucket: ScheduleCompetitionBucket,
    selectedMatchId: String?,
    onMatchClick: (ScheduledEsportsMatch) -> Unit
) {
    val groups = remember(bucket.key, bucket.matches) {'''
replacement = '''private fun CompetitionMatches(
    bucket: ScheduleCompetitionBucket,
    selectedMatchId: String?,
    onMatchClick: (ScheduledEsportsMatch) -> Unit
) {
    if (bucket.matches.isEmpty()) {
        EmptyData("该年度赛事赛程尚未由可信源发布；研究档案会先保存版本、规则、抽签和赛事更新，赛程发布后自动并入同一年度页面。")
        return
    }
    val groups = remember(bucket.key, bucket.matches) {'''
if needle not in s:
    raise SystemExit('CompetitionMatches anchor not found')
s = s.replace(needle, replacement, 1)

# Insert the actual research surface before the detailed rules view.
marker = '@Composable\nprivate fun RulesView(bucket: ScheduleCompetitionBucket, standings: TournamentStandings?) {'
research_view = r'''@Composable
private fun ResearchView(
    bucket: ScheduleCompetitionBucket,
    standings: TournamentStandings?,
    onOpenSchedule: () -> Unit
) {
    val governance = remember(bucket.key, standings?.tournamentId, standings?.stages, bucket.matches) {
        TournamentGovernanceProvider.resolve(bucket.tournament, bucket.title, bucket.matches, standings)
    }
    val research = remember(bucket.key, standings?.tournamentId, standings?.stages, bucket.matches, governance) {
        TournamentResearchProvider.resolve(
            tournament = bucket.tournament,
            competitionTitle = bucket.title,
            matches = bucket.matches,
            standings = standings,
            governance = governance
        )
    }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(RiftPanel, CutCornerShape(topEnd = 16.dp, bottomStart = 10.dp))
                    .border(1.dp, RiftCyan.copy(alpha = 0.45f), CutCornerShape(topEnd = 16.dp, bottomStart = 10.dp))
                    .padding(14.dp)
            ) {
                Text("TOURNAMENT RESEARCH / 赛事研究档案", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(research.title, color = RiftText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text("${research.year} · ${research.scope} · ${research.family}", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("同一赛事按年份独立归档。版本、赛事更新、规则、抽签、赛程、排名和运营历史都挂在这一年度 Edition 下，不用跨页面找。", color = RiftMuted, fontSize = 9.sp, lineHeight = 14.sp)
                Spacer(Modifier.height(5.dp))
                Text("SOURCE · ${research.sourceSummary}", color = RiftMuted, fontSize = 8.sp)
            }
        }

        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(RiftPanel, CutCornerShape(topEnd = 11.dp, bottomStart = 7.dp))
                    .border(1.dp, if (research.version.evidence == ResearchEvidence.PENDING) RiftLine else RiftCyan.copy(alpha = 0.30f), CutCornerShape(topEnd = 11.dp, bottomStart = 7.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("VERSION / 赛事版本", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(researchEvidenceLabel(research.version.evidence), color = researchEvidenceColor(research.version.evidence), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text(research.version.versionLabel, color = RiftText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(research.version.detail, color = RiftMuted, fontSize = 9.sp, lineHeight = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("SOURCE · ${research.version.source}", color = RiftMuted, fontSize = 8.sp)
            }
        }

        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(RiftPanel, CutCornerShape(topEnd = 11.dp, bottomStart = 7.dp))
                    .padding(12.dp)
            ) {
                Text("ARCHIVE COVERAGE / 年度档案覆盖", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                research.coverage.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.label, color = RiftText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(item.state, color = if (item.state.contains("待")) RiftMuted else RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(item.detail, color = RiftMuted, fontSize = 8.sp)
                    if (index != research.coverage.lastIndex) Spacer(Modifier.height(7.dp))
                }
            }
        }

        item {
            Text("EVENT UPDATE / 赛事更新", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp, start = 2.dp))
        }
        items(research.updates, key = { "${it.category}-${it.title}-${it.source}" }) { update ->
            Column(
                Modifier.fillMaxWidth()
                    .background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
                    .border(1.dp, if (update.evidence == ResearchEvidence.VERIFIED) RiftCyan.copy(alpha = 0.30f) else RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(update.category, color = RiftCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(researchEvidenceLabel(update.evidence), color = researchEvidenceColor(update.evidence), fontSize = 8.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(update.title, color = RiftText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(update.detail, color = RiftMuted, fontSize = 9.sp, lineHeight = 14.sp)
                if (update.effectiveAt.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(update.effectiveAt, color = RiftMuted, fontSize = 8.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text("SOURCE · ${update.source}", color = RiftMuted, fontSize = 8.sp)
            }
        }

        item {
            Column(
                Modifier.fillMaxWidth()
                    .clickable(onClick = onOpenSchedule)
                    .background(RiftPanelAlt, CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
                    .border(1.dp, RiftCyan.copy(alpha = 0.35f), CutCornerShape(topEnd = 12.dp, bottomStart = 8.dp))
                    .padding(13.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SCHEDULE / 年度赛程", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(if (bucket.matches.isEmpty()) "赛程待可信源发布" else "${bucket.matches.size} 场 · ${competitionRange(bucket.matches)}", color = RiftText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        Text("进入该年度赛事的完整赛程；后续比赛详情、运营历史和回放仍沿用同一个赛事 Edition。", color = RiftMuted, fontSize = 8.sp, lineHeight = 13.sp)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = RiftCyan)
                }
            }
        }
    }
}

private fun researchEvidenceLabel(value: ResearchEvidence): String = when (value) {
    ResearchEvidence.VERIFIED -> "已核实"
    ResearchEvidence.PROVIDER -> "数据源确认"
    ResearchEvidence.PENDING -> "待同步"
}

private fun researchEvidenceColor(value: ResearchEvidence) = when (value) {
    ResearchEvidence.VERIFIED -> RiftCyan
    ResearchEvidence.PROVIDER -> RiftText
    ResearchEvidence.PENDING -> RiftMuted
}

'''
if marker not in s:
    raise SystemExit('RulesView marker not found')
s = s.replace(marker, research_view + marker, 1)

# Build current-year research-only editions where the event is known but the schedule has not been published yet.
old = '''    val used = official.flatMap { it.matches }.map(::scheduleIdentity).toSet()
    val fallback = buildFallbackBuckets(matches.filterNot { scheduleIdentity(it) in used })
    return (official + fallback)
        .distinctBy { it.key }
        .sortedBy { it.firstEpochMs }
}
'''
new = '''    val used = official.flatMap { it.matches }.map(::scheduleIdentity).toSet()
    val fallback = buildFallbackBuckets(matches.filterNot { scheduleIdentity(it) in used })
    val base = (official + fallback)
        .distinctBy { it.key }
        .sortedBy { it.firstEpochMs }
    return addAnnualResearchPlaceholders(base)
        .distinctBy { it.key }
        .sortedBy { it.firstEpochMs }
}

private fun addAnnualResearchPlaceholders(
    base: List<ScheduleCompetitionBucket>
): List<ScheduleCompetitionBucket> {
    val year = LocalDate.now().year
    val required = buildList {
        add(InternationalCompetitionMenu.WORLDS to "$year 全球总决赛")
        if (year == 2026) add(InternationalCompetitionMenu.DEMACIA_GLOBAL to "2026 德杯国际邀请赛")
    }
    val output = base.toMutableList()
    required.forEach { (kind, title) ->
        val exists = output.any { bucket ->
            internationalCompetitionKind(bucket) == kind && researchEditionYear(bucket) == year
        }
        if (!exists) {
            output += ScheduleCompetitionBucket(
                key = "research-${kind.name.lowercase()}-$year",
                title = title,
                matches = emptyList(),
                firstEpochMs = runCatching { Instant.parse("$year-01-01T00:00:00Z").toEpochMilli() }.getOrDefault(Long.MAX_VALUE),
                researchOnly = true
            )
        }
    }
    return output
}

private fun researchEditionYear(bucket: ScheduleCompetitionBucket): Int? {
    bucket.tournament?.startDate?.take(4)?.toIntOrNull()?.let { return it }
    bucket.matches.firstOrNull()?.let(::matchStartDate)?.year?.let { return it }
    return Regex("(?:19|20)\\d{2}").find(bucket.title)?.value?.toIntOrNull()
}
'''
if old not in s:
    raise SystemExit('bucket builder tail not found')
s = s.replace(old, new, 1)

ui.write_text(s)

# Version bump.
build = Path('app/build.gradle.kts')
b = build.read_text()
b = b.replace('versionCode = 56', 'versionCode = 57')
b = b.replace('versionName = "1.0.0-dev.56"', 'versionName = "1.0.0-dev.57"')
b += '\n// dev.57: annual Tournament Research editions: version/update/rules/draw/schedule unified per year for international events and regional leagues.\n'
build.write_text(b)
