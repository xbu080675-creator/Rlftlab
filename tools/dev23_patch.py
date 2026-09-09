from pathlib import Path


def replace_exact(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected block not found in {path}: {old[:140]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


ui = "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt"
standings = "app/src/main/java/com/riftlab/app/data/LolEsportsStandingsClient.kt"
gradle = "app/build.gradle.kts"

replace_exact(
    ui,
    '''import com.riftlab.app.data.MatchDetailRepository\nimport com.riftlab.app.data.MatchSessionStore\nimport com.riftlab.app.data.ScheduleActivityState''',
    '''import com.riftlab.app.data.LplChampionshipPoints2026\nimport com.riftlab.app.data.LplWorldsStatus\nimport com.riftlab.app.data.MatchDetailRepository\nimport com.riftlab.app.data.MatchSessionStore\nimport com.riftlab.app.data.ScheduleActivityState'''
)

replace_exact(
    ui,
    '''private enum class EventCenterTab(val label: String) {\n    SCHEDULE("赛程"),\n    STANDINGS("排名"),\n    BRACKET("淘汰赛"),\n    TEAMS("战队")\n}''',
    '''private enum class EventCenterTab(val label: String) {\n    SCHEDULE("赛程"),\n    STANDINGS("排名"),\n    POINTS("积分"),\n    BRACKET("淘汰赛"),\n    TEAMS("战队")\n}'''
)

replace_exact(
    ui,
    '''                            EventCenterTab.STANDINGS -> StandingsView(selectedStandings)\n                            EventCenterTab.BRACKET -> BracketView(''',
    '''                            EventCenterTab.STANDINGS -> StandingsView(selectedStandings)\n                            EventCenterTab.POINTS -> ChampionshipPointsView()\n                            EventCenterTab.BRACKET -> BracketView('''
)

replace_exact(
    ui,
    '''                    TableText("胜/负", 1f, RiftMuted, FontWeight.Medium)\n                    TableText("积分", 0.8f, RiftMuted, FontWeight.Medium, end = true)''',
    '''                    TableText("胜/负", 1f, RiftMuted, FontWeight.Medium)\n                    TableText("组内积分", 0.8f, RiftMuted, FontWeight.Medium, end = true)'''
)

replace_exact(
    ui,
    '''        TableText("${row.wins}/${row.losses}", 1f, RiftText, FontWeight.Normal)\n        TableText((row.points ?: row.wins).toString(), 0.8f, RiftText, FontWeight.SemiBold, end = true)''',
    '''        TableText("${row.wins}/${row.losses}", 1f, RiftText, FontWeight.Normal)\n        TableText(row.points?.toString() ?: "—", 0.8f, RiftText, FontWeight.SemiBold, end = true)'''
)

insert_before = '''@Composable\nprivate fun BracketView(standings: TournamentStandings?, scheduleMatches: List<ScheduledEsportsMatch>) {'''
points_view = '''@Composable\nprivate fun ChampionshipPointsView() {\n    val rows = LplChampionshipPoints2026.rows\n    Column(Modifier.fillMaxSize()) {\n        Column(\n            Modifier.fillMaxWidth()\n                .background(RiftPanel, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))\n                .border(1.dp, RiftCyan.copy(alpha = 0.34f), CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))\n                .padding(14.dp)\n        ) {\n            Text(\n                "${LplChampionshipPoints2026.season} 全球总决赛实时积分",\n                color = RiftText,\n                fontSize = 16.sp,\n                fontWeight = FontWeight.Bold\n            )\n            Spacer(Modifier.height(4.dp))\n            Text(\n                "更新至 ${LplChampionshipPoints2026.updatedThrough}",\n                color = RiftCyan,\n                fontSize = 10.sp,\n                fontWeight = FontWeight.SemiBold\n            )\n            Spacer(Modifier.height(4.dp))\n            Text(\n                LplChampionshipPoints2026.note,\n                color = RiftMuted,\n                fontSize = 9.sp,\n                lineHeight = 14.sp\n            )\n            Spacer(Modifier.height(4.dp))\n            Text(LplChampionshipPoints2026.sourceLabel, color = RiftMuted, fontSize = 8.sp)\n        }\n\n        Spacer(Modifier.height(10.dp))\n        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {\n            TableText("#", 0.42f, RiftMuted, FontWeight.Medium)\n            TableText("战队", 0.92f, RiftMuted, FontWeight.Medium)\n            TableText("S1", 0.52f, RiftMuted, FontWeight.Medium, end = true)\n            TableText("S2", 0.52f, RiftMuted, FontWeight.Medium, end = true)\n            TableText("S3保底", 0.76f, RiftMuted, FontWeight.Medium, end = true)\n            TableText("总分", 0.70f, RiftMuted, FontWeight.Medium, end = true)\n        }\n\n        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {\n            items(rows, key = { "${it.rank}-${it.teamCode}" }) { row ->\n                val statusColor = when (row.status) {\n                    LplWorldsStatus.WORLDS_LOCKED -> RiftCyan\n                    LplWorldsStatus.REGIONAL_LOCKED -> RiftText\n                    LplWorldsStatus.ELIMINATED -> RiftMuted\n                }\n                Column(\n                    Modifier.fillMaxWidth()\n                        .background(RiftPanel, CutCornerShape(topEnd = 8.dp, bottomStart = 5.dp))\n                        .border(\n                            1.dp,\n                            if (row.status == LplWorldsStatus.WORLDS_LOCKED) RiftCyan.copy(alpha = 0.38f) else RiftLine,\n                            CutCornerShape(topEnd = 8.dp, bottomStart = 5.dp)\n                        )\n                        .padding(horizontal = 10.dp, vertical = 10.dp)\n                ) {\n                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n                        TableText(row.rank.toString(), 0.42f, RiftMuted, FontWeight.SemiBold)\n                        TableText(row.teamCode, 0.92f, RiftText, FontWeight.Bold)\n                        TableText(row.split1.toString(), 0.52f, RiftText, FontWeight.Normal, end = true)\n                        TableText(row.split2.toString(), 0.52f, RiftText, FontWeight.Normal, end = true)\n                        TableText(row.split3Floor.toString(), 0.76f, RiftText, FontWeight.Normal, end = true)\n                        TableText(row.total.toString(), 0.70f, RiftCyan, FontWeight.Bold, end = true)\n                    }\n                    Spacer(Modifier.height(4.dp))\n                    Text(\n                        row.status.label,\n                        color = statusColor,\n                        fontSize = 9.sp,\n                        fontWeight = FontWeight.SemiBold,\n                        modifier = Modifier.padding(start = 34.dp)\n                    )\n                }\n            }\n            item {\n                Text(\n                    "注：本页为年度 Championship Points；“排名”页中的组内积分属于当前 Tournament Standings，两者不是同一个积分体系。",\n                    color = RiftMuted,\n                    fontSize = 9.sp,\n                    lineHeight = 14.sp,\n                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)\n                )\n            }\n        }\n    }\n}\n\n@Composable\nprivate fun BracketView(standings: TournamentStandings?, scheduleMatches: List<ScheduledEsportsMatch>) {'''
replace_exact(ui, insert_before, points_view)

replace_exact(
    standings,
    '''                            // Current LPL split standings expose W/L but no separate points field.\n                            // The official event UI uses series wins as points for this stage.\n                            points = wins''',
    '''                            // Tournament/group standing points shown by the Riot standings surface.\n                            // This is deliberately NOT annual Championship Points for Worlds qualification.\n                            points = wins'''
)

replace_exact(
    gradle,
    '''        versionCode = 22\n        versionName = "1.0.0-dev.22"''',
    '''        versionCode = 23\n        versionName = "1.0.0-dev.23"'''
)
replace_exact(
    gradle,
    '''// dev.22: distinguish event/broadcast start from actual game-live and between-games states.''',
    '''// dev.23: separate tournament standings points from annual LPL Championship Points.'''
)

print("dev23 migration applied successfully")
