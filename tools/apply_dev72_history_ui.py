#!/usr/bin/env python3
from pathlib import Path

path = Path('app/src/main/java/com/riftlab/app/ui/TournamentEditionArchiveUi.kt')
text = path.read_text(encoding='utf-8')

def once(old, new):
    global text
    if old not in text:
        raise SystemExit(f'pattern missing: {old[:120]!r}')
    text = text.replace(old, new, 1)

once('import androidx.compose.runtime.getValue\n', 'import androidx.compose.runtime.getValue\nimport androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.remember\nimport androidx.compose.runtime.setValue\n')
once('''    val selected = state.selected\n    val qualification = qualificationCenter.snapshotsByTournamentId[state.selectedTournamentId]\n''', '''    val selected = state.selected\n    val qualification = qualificationCenter.snapshotsByTournamentId[state.selectedTournamentId]\n    var showAllHistory by remember { mutableStateOf(false) }\n''')
once('''                Text("TOURNAMENT EDITIONS / 年度赛事档案", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)\n                Text("旧届次追加保留，不因上游分页滚动被新赛事覆盖", color = RiftMuted, fontSize = 8.sp)''', '''                Text("TOURNAMENT EDITIONS / 年度赛事档案", color = RiftCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)\n                Text("旧届次追加保留，不因上游分页滚动被新赛事覆盖", color = RiftMuted, fontSize = 9.sp)''')
once('''                Text("${state.editions.size} EDITIONS", color = RiftText, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)''', '''                Text("${state.editions.size} EDITIONS", color = RiftText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)''')
once('''        val trail = recentTrail(state.editions, state.selectedTournamentId)\n        trail.forEach { edition ->''', '''        val completeTrail = fullHistoryTrail(state.editions, selected?.edition)\n        if (completeTrail.size > 6) {\n            Text(\n                if (showAllHistory) "收起 · 最近 6 届" else "查看全部历史 · ${completeTrail.size} 届",\n                color = RiftCyan,\n                fontSize = 10.sp,\n                fontWeight = FontWeight.SemiBold,\n                modifier = Modifier.clickable { showAllHistory = !showAllHistory }.padding(vertical = 6.dp)\n            )\n        }\n        val trail = if (showAllHistory) completeTrail else recentTrail(state.editions, state.selectedTournamentId)\n        trail.forEach { edition ->''')
# Phone readability fixes in the archive itself.
text = text.replace('fontSize = 9.sp,\n                    fontWeight = if (active)', 'fontSize = 11.sp,\n                    fontWeight = if (active)', 1)
text = text.replace('color = RiftMuted,\n                    fontSize = 8.sp\n                )', 'color = RiftMuted,\n                    fontSize = 9.sp\n                )', 1)
text = text.replace('fontSize = 10.sp,\n                fontWeight = FontWeight.SemiBold', 'fontSize = 12.sp,\n                fontWeight = FontWeight.SemiBold', 1)
text = text.replace('color = RiftMuted,\n                fontSize = 8.sp\n            )', 'color = RiftMuted,\n                fontSize = 9.sp\n            )', 1)
text = text.replace('Text(slot.label, color = RiftText, fontSize = 8.sp', 'Text(slot.label, color = RiftText, fontSize = 9.sp', 1)
text = text.replace('fontSize = 8.sp\n                            )', 'fontSize = 9.sp\n                            )', 1)
text = text.replace('Text(displayDetail, color = RiftMuted, fontSize = 7.sp, lineHeight = 10.sp', 'Text(displayDetail, color = RiftMuted, fontSize = 8.sp, lineHeight = 12.sp', 1)

anchor = '''private fun recentTrail(\n'''
idx = text.index(anchor)
helper = '''private fun fullHistoryTrail(\n    editions: List<TournamentEditionArchiveRecord>,\n    selected: TournamentEditionArchiveRecord?\n): List<TournamentEditionArchiveRecord> {\n    if (selected == null) return editions.sortedByDescending { it.startDate }\n    val contextual = editions.filter { candidate ->\n        when {\n            selected.family.isNotBlank() && selected.family != "OTHER" -> candidate.family == selected.family\n            selected.leagueId.isNotBlank() -> candidate.leagueId == selected.leagueId\n            selected.leagueSlug.isNotBlank() -> candidate.leagueSlug.equals(selected.leagueSlug, ignoreCase = true)\n            else -> true\n        }\n    }\n    return contextual.sortedByDescending { it.startDate }\n}\n\n'''
text = text[:idx] + helper + text[idx:]
path.write_text(text, encoding='utf-8')
print('dev72 full-history UI patch applied')
