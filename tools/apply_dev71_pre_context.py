#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


models = "app/src/main/java/com/riftlab/app/data/Models.kt"
store = "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt"
ui = "app/src/main/java/com/riftlab/app/ui/RiftLabApp.kt"
audit = "docs/RIFTLAB_DEV71_DATA_COVERAGE_AUDIT.md"

# PRE model: keep confirmed starters separate from roster pool and add provider-backed context.
patch(
    models,
    '''data class PlayerCard(
    val role: String,
    val id: String,
    val rank: String,
    val recent: String
)

data class PreMatchInfo(''',
    '''data class PlayerCard(
    val role: String,
    val id: String,
    val rank: String,
    val recent: String
)

data class PreRecentSeries(
    val eventId: String,
    val opponentCode: String,
    val scoreFor: Int,
    val scoreAgainst: Int,
    val outcome: String,
    val startTimeIso: String,
    val source: String
)

data class PreMatchInfo('''
)
patch(
    models,
    '''    val blueRoster: List<PlayerCard>,
    val redRoster: List<PlayerCard>,
    val rosterNote: String
)''',
    '''    val blueRoster: List<PlayerCard>,
    val redRoster: List<PlayerCard>,
    val rosterNote: String,
    val blueRosterPool: List<PlayerCard> = emptyList(),
    val redRosterPool: List<PlayerCard> = emptyList(),
    val blueStaff: List<EsportsStaffRef> = emptyList(),
    val redStaff: List<EsportsStaffRef> = emptyList(),
    val blueRecentSeries: List<PreRecentSeries> = emptyList(),
    val redRecentSeries: List<PreRecentSeries> = emptyList(),
    val recentHeadToHead: List<PreRecentSeries> = emptyList()
)'''
)

# Populate roster pool/staff/recent official series without guessing starters or transfer/absence status.
patch(
    store,
    '''        val leftRoster = leftUniqueFive ?: emptyList()
        val rightRoster = rightUniqueFive ?: emptyList()
        val connectedCount = listOf(leftDetails != null, rightDetails != null).count { it }
        val autoStarterCount = listOf(leftUniqueFive != null, rightUniqueFive != null).count { it }

        _preMatch.value = PreMatchInfo(''',
    '''        val leftRoster = leftUniqueFive ?: emptyList()
        val rightRoster = rightUniqueFive ?: emptyList()
        val leftStaff = staffForPre(leftDetails)
        val rightStaff = staffForPre(rightDetails)
        val leftRecent = recentCompletedSeries(left, target, limit = 5)
        val rightRecent = recentCompletedSeries(right, target, limit = 5)
        val recentH2h = recentHeadToHead(left, right, target, limit = 5)
        val connectedCount = listOf(leftDetails != null, rightDetails != null).count { it }
        val autoStarterCount = listOf(leftUniqueFive != null, rightUniqueFive != null).count { it }

        _preMatch.value = PreMatchInfo('''
)
patch(
    store,
    '''            blueRoster = leftRoster,
            redRoster = rightRoster,
            rosterNote = when {
                connectedCount == 2 && autoStarterCount == 2 ->
                    "两队 Riot getTeams roster 已连接，五位置均唯一；当前显示 Riot roster 五人。Rank 将接独立 Ranked 数据源。"
                connectedCount == 2 ->
                    "两队 Riot team roster 已连接；存在替补或位置歧义时保持空缺，不使用任何场次专属缓存。"
                connectedCount == 1 ->
                    "一侧 Riot roster 已连接；另一侧保持空缺，不使用战队或场次硬编码。Rank 暂未接入。"
                else ->
                    "Riot Schedule 已连接，但当前队伍 roster 暂不可用；没有独立核实的数据就保持空缺。Rank 暂未接入。"
            }
        )''',
    '''            blueRoster = leftRoster,
            redRoster = rightRoster,
            rosterNote = when {
                connectedCount == 2 && autoStarterCount == 2 ->
                    "两队 roster 已连接且五位置均唯一；可作为当前 roster 五人展示，但仍不把 roster pool 额外成员擅自标成替补。Rank 继续等待独立 Ranked 数据源。"
                connectedCount == 2 ->
                    "两队 roster pool 已连接；存在同位置多人或位置歧义时，首发保持未确认，不从名单顺序猜首发/替补。"
                connectedCount == 1 ->
                    "一侧 roster pool 已连接；另一侧保持空缺。未取得公开确认前，不填首发变化、伤病/缺席或转会结论。"
                else ->
                    "Schedule 已连接，但当前 roster 暂不可用；没有独立核实的数据就保持空缺。Rank 暂未接入。"
            },
            blueRosterPool = leftRiotRoster,
            redRosterPool = rightRiotRoster,
            blueStaff = leftStaff,
            redStaff = rightStaff,
            blueRecentSeries = leftRecent,
            redRecentSeries = rightRecent,
            recentHeadToHead = recentH2h
        )'''
)

insert_before_lookup = '''    private fun teamLookupSlug(team: EsportsTeamRef): String? {
'''
helpers = '''    private fun staffForPre(details: EsportsTeamDetails?): List<EsportsStaffRef> =
        (details?.staff.orEmpty() + details?.management.orEmpty())
            .filter { it.name.isNotBlank() }
            .distinctBy { "${teamIdentityToken(it.name)}|${teamIdentityToken(it.role)}" }

    private fun recentCompletedSeries(
        team: EsportsTeamRef,
        exclude: ScheduledEsportsMatch,
        limit: Int
    ): List<PreRecentSeries> = _schedule.value
        .asSequence()
        .filter(::isCompletedState)
        .filter { candidate -> candidate.matchId != exclude.matchId && candidate.eventId != exclude.eventId }
        .filter { candidate -> candidate.teams.any { matchesTeamIdentity(it, team) } }
        .sortedByDescending { plannedStartEpochMs(it) ?: Long.MIN_VALUE }
        .mapNotNull { toPreRecentSeries(it, team) }
        .take(limit)
        .toList()

    private fun recentHeadToHead(
        left: EsportsTeamRef,
        right: EsportsTeamRef,
        exclude: ScheduledEsportsMatch,
        limit: Int
    ): List<PreRecentSeries> = _schedule.value
        .asSequence()
        .filter(::isCompletedState)
        .filter { candidate -> candidate.matchId != exclude.matchId && candidate.eventId != exclude.eventId }
        .filter { candidate ->
            candidate.teams.any { matchesTeamIdentity(it, left) } &&
                candidate.teams.any { matchesTeamIdentity(it, right) }
        }
        .sortedByDescending { plannedStartEpochMs(it) ?: Long.MIN_VALUE }
        .mapNotNull { toPreRecentSeries(it, left) }
        .take(limit)
        .toList()

    private fun toPreRecentSeries(
        match: ScheduledEsportsMatch,
        perspective: EsportsTeamRef
    ): PreRecentSeries? {
        val index = match.teams.indexOfFirst { matchesTeamIdentity(it, perspective) }
        if (index < 0) return null
        val self = match.teams[index]
        val opponent = match.teams.firstOrNull { !matchesTeamIdentity(it, perspective) } ?: return null
        val scoreFor = self.gameWins
        val scoreAgainst = opponent.gameWins
        val outcome = when {
            scoreFor > scoreAgainst -> "W"
            scoreFor < scoreAgainst -> "L"
            else -> "—"
        }
        return PreRecentSeries(
            eventId = match.eventId.ifBlank { match.matchId },
            opponentCode = opponent.code.ifBlank { opponent.name },
            scoreFor = scoreFor,
            scoreAgainst = scoreAgainst,
            outcome = outcome,
            startTimeIso = match.startTimeIso,
            source = "Unified Schedule · Riot/Cito"
        )
    }

    private fun matchesTeamIdentity(candidate: EsportsTeamRef, target: EsportsTeamRef): Boolean {
        val a = listOf(candidate.id, candidate.code, candidate.name, candidate.slug)
            .map(::teamIdentityToken)
            .filter { it.isNotBlank() }
            .toSet()
        val b = listOf(target.id, target.code, target.name, target.slug)
            .map(::teamIdentityToken)
            .filter { it.isNotBlank() }
            .toSet()
        return a.any { token -> token in b }
    }

    private fun teamIdentityToken(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

'''
patch(store, insert_before_lookup, helpers + insert_before_lookup)

# PRE screen: distinguish exact starter display from roster pool, then surface staff/recent/H2H.
patch(
    ui,
    'import com.riftlab.app.data.PlayerCard\n',
    'import com.riftlab.app.data.EsportsStaffRef\nimport com.riftlab.app.data.PlayerCard\nimport com.riftlab.app.data.PreRecentSeries\n'
)
old_pre_block = '''        item { SectionTitle("STARTING ROSTER / 首发") }
        items(data.blueRoster.zip(data.redRoster)) { pair -> RosterRow(pair.first, pair.second) }
        item {
            Panel {
                Text("ROSTER / RANK STATUS", color = RiftCyan, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Text(data.rosterNote, color = RiftMuted, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
'''
new_pre_block = '''        item { SectionTitle("STARTING ROSTER / 首发") }
        val starterRows = maxOf(data.blueRoster.size, data.redRoster.size)
        if (starterRows > 0) {
            items((0 until starterRows).toList()) { index ->
                RosterRow(data.blueRoster.getOrNull(index), data.redRoster.getOrNull(index))
            }
        } else {
            item {
                Panel {
                    Text("STARTERS NOT CONFIRMED", color = RiftMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Text("Roster pool 可以已连接，但存在同位置多人时不会把名单顺序当作官方首发。", color = RiftMuted, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
        }
        item {
            Panel {
                Text("ROSTER / RANK STATUS", color = RiftCyan, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Text(data.rosterNote, color = RiftMuted, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }

        if (data.blueRosterPool.isNotEmpty() || data.redRosterPool.isNotEmpty()) {
            item { SectionTitle("ROSTER POOL / 名单池（不等于首发）") }
            item {
                Panel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(rosterPoolLabel(data.blue, data.blueRosterPool), modifier = Modifier.weight(1f), color = RiftText, fontSize = 9.sp, lineHeight = 14.sp)
                        Text(rosterPoolLabel(data.red, data.redRosterPool), modifier = Modifier.weight(1f), color = RiftText, fontSize = 9.sp, lineHeight = 14.sp)
                    }
                }
            }
        }

        if (data.blueStaff.isNotEmpty() || data.redStaff.isNotEmpty()) {
            item { SectionTitle("TEAM STAFF / 教练组与工作人员") }
            item {
                Panel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(staffLabel(data.blue, data.blueStaff), modifier = Modifier.weight(1f), color = RiftText, fontSize = 9.sp, lineHeight = 14.sp)
                        Text(staffLabel(data.red, data.redStaff), modifier = Modifier.weight(1f), color = RiftText, fontSize = 9.sp, lineHeight = 14.sp)
                    }
                }
            }
        }

        item { SectionTitle("RECENT FORM / 近期正式系列赛") }
        item {
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(recentSeriesLabel(data.blue, data.blueRecentSeries), modifier = Modifier.weight(1f), color = RiftText, fontSize = 9.sp, lineHeight = 14.sp)
                    Text(recentSeriesLabel(data.red, data.redRecentSeries), modifier = Modifier.weight(1f), color = RiftText, fontSize = 9.sp, lineHeight = 14.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("仅统计当前 Unified Schedule 历史窗口中已验证结束的 Series；不是全历史数据库。", color = RiftMuted, fontSize = 8.sp)
            }
        }

        item { SectionTitle("RECENT H2H / 近期交手") }
        item {
            Panel {
                Text(
                    if (data.recentHeadToHead.isEmpty()) "当前历史窗口没有可核实的近期直接交手。" else recentSeriesLabel(data.blue, data.recentHeadToHead),
                    color = RiftText,
                    fontSize = 9.sp,
                    lineHeight = 14.sp
                )
                Text("SOURCE  Unified Schedule · Riot/Cito", color = RiftMuted, fontSize = 8.sp)
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
'''
patch(ui, old_pre_block, new_pre_block)

patch(
    ui,
    '''private fun RosterRow(left: PlayerCard, right: PlayerCard) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(left.role, color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Text(left.id, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(left.rank, color = RiftMuted, fontSize = 10.sp)
                Text(left.recent, color = RiftMuted, fontSize = 9.sp)
            }
            Text("↔", color = RiftLine, fontSize = 18.sp)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(right.role, color = RiftRed, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Text(right.id, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(right.rank, color = RiftMuted, fontSize = 10.sp)
                Text(right.recent, color = RiftMuted, fontSize = 9.sp)
            }
        }
    }
}
''',
    '''private fun RosterRow(left: PlayerCard?, right: PlayerCard?) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(left?.role ?: right?.role ?: "—", color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Text(left?.id ?: "未确认", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (left != null) {
                    Text(left.rank, color = RiftMuted, fontSize = 10.sp)
                    Text(left.recent, color = RiftMuted, fontSize = 9.sp)
                }
            }
            Text("↔", color = RiftLine, fontSize = 18.sp)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(right?.role ?: left?.role ?: "—", color = RiftRed, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                Text(right?.id ?: "未确认", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (right != null) {
                    Text(right.rank, color = RiftMuted, fontSize = 10.sp)
                    Text(right.recent, color = RiftMuted, fontSize = 9.sp)
                }
            }
        }
    }
}

private fun rosterPoolLabel(team: String, pool: List<PlayerCard>): String = buildString {
    append(team).append("\n")
    if (pool.isEmpty()) append("名单池待同步")
    else pool.forEach { player -> append(player.role).append("  ").append(player.id).append("\n") }
}.trimEnd()

private fun staffLabel(team: String, staff: List<EsportsStaffRef>): String = buildString {
    append(team).append("\n")
    if (staff.isEmpty()) append("Staff 待同步")
    else staff.take(8).forEach { person ->
        append(person.displayRole.ifBlank { person.role }.ifBlank { "STAFF" })
            .append("  ").append(person.name).append("\n")
    }
}.trimEnd()

private fun recentSeriesLabel(team: String, rows: List<PreRecentSeries>): String = buildString {
    append(team).append("\n")
    if (rows.isEmpty()) append("当前历史窗口暂无已结束 Series")
    else rows.forEach { row ->
        append(row.outcome).append("  ")
            .append(row.scoreFor).append(':').append(row.scoreAgainst)
            .append(" vs ").append(row.opponentCode)
            .append(" · ").append(row.startTimeIso.take(10))
            .append("\n")
    }
}.trimEnd()
'''
)

# Record the scope explicitly; still no fake Rank/transfer/injury data.
p = ROOT / audit
text = p.read_text(encoding="utf-8")
addition = '''\n## 2026-09-10 · PRE context tranche\n\n- PRE now separates **confirmed/uniquely resolvable starting five** from the broader roster pool; a multi-player same-role roster is never silently treated as a starting lineup.\n- Team staff/management supplied by the existing team provider chain is surfaced with its own source fields.\n- Recent completed Series and recent H2H are derived only from the verified-completed portion of the current Unified Schedule history window, with an explicit warning that this is not the full historical database.\n- Rank, injuries/absence, transfers and lineup-change claims remain empty until an independent trustworthy source is connected.\n'''
if addition.strip() not in text:
    p.write_text(text.rstrip() + "\n" + addition, encoding="utf-8")

print("dev71 PRE context patch prepared")
