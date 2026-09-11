#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def path(rel: str) -> Path:
    return ROOT / rel


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one target, found {count}")
    return text.replace(old, new, 1)


# Team detail: keep dense roster rows, but stop outlining every row and let the team identity/header
# and section labels carry hierarchy instead.
p = path("app/src/main/java/com/riftlab/app/ui/TeamDetailUi.kt")
s = p.read_text(encoding="utf-8")
old_header = '''        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(RiftPanel, CutCornerShape(topEnd = 16.dp, bottomStart = 10.dp))
                    .border(1.dp, RiftCyan.copy(alpha = 0.42f), CutCornerShape(topEnd = 16.dp, bottomStart = 10.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamLogo(
                        imageUrl = displayTeam.imageUrl,
                        code = displayTeam.code.ifBlank { displayTeam.name },
                        modifier = Modifier.size(58.dp)
                    )
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(displayTeam.code.ifBlank { displayTeam.name }, color = RiftText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        if (displayTeam.name.isNotBlank() && displayTeam.name != displayTeam.code) {
                            Text(displayTeam.name, color = RiftMuted, fontSize = 10.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(state.status, color = RiftMuted, fontSize = 10.sp)
                    }
                }
            }
        }
'''
new_header = '''        item {
            RiftHudPanel(accent = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamLogo(
                        imageUrl = displayTeam.imageUrl,
                        code = displayTeam.code.ifBlank { displayTeam.name },
                        modifier = Modifier.size(70.dp)
                    )
                    Spacer(Modifier.width(15.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            displayTeam.code.ifBlank { displayTeam.name },
                            color = RiftText,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (displayTeam.name.isNotBlank() && displayTeam.name != displayTeam.code) {
                            Text(displayTeam.name, color = RiftMuted, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        RiftStatusBadge("TEAM PROFILE")
                    }
                }
                if (state.status.isNotBlank()) {
                    Text(state.status, color = RiftMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
'''
s = replace_once(s, old_header, new_header, "team header")
old_title = '''@Composable
private fun TeamSectionTitle(value: String) {
    Text(value, color = RiftMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
}
'''
new_title = '''@Composable
private fun TeamSectionTitle(value: String) {
    RiftSectionLabel(value)
}
'''
s = replace_once(s, old_title, new_title, "team section title")
# Remove repetitive full-row strokes. Surface/value hierarchy remains; LIVE match cards are handled
# separately below through RiftHudPanel accent state.
for token in (
    '            .border(1.dp, RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))\n',
    '            .border(1.dp, RiftCyan.copy(alpha = 0.22f), CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))\n',
    '            .border(1.dp, RiftCyan.copy(alpha = 0.25f), CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))\n',
):
    s = s.replace(token, '')
# Same patterns when the call is chained on one line.
s = s.replace('            .border(1.dp, RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp)).padding(12.dp),\n', '            .padding(12.dp),\n')
s = s.replace('            .border(1.dp, RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp)).padding(horizontal = 11.dp, vertical = 9.dp),\n', '            .padding(horizontal = 11.dp, vertical = 9.dp),\n')
s = s.replace('            .border(1.dp, RiftCyan.copy(alpha = 0.25f), CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp)).padding(horizontal = 11.dp, vertical = 9.dp),\n', '            .padding(horizontal = 11.dp, vertical = 9.dp),\n')
s = s.replace('            .border(1.dp, RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp)).padding(11.dp)\n', '            .padding(11.dp)\n')
old_match = '''    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .border(1.dp, if (phase.name == "LIVE") RiftCyan.copy(alpha = 0.5f) else RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .padding(11.dp)
    ) {
'''
new_match = '''    RiftHudPanel(accent = phase.name == "LIVE", onClick = onClick) {
'''
s = replace_once(s, old_match, new_match, "team match card")
s = s.replace('            teamNameFontSize = 10.sp\n', '            teamNameFontSize = 12.sp\n', 1)
p.write_text(s, encoding="utf-8")


# Live broadcast: the modal should read like a broadcast source switcher rather than a stack of
# bordered form rows.
p = path("app/src/main/java/com/riftlab/app/ui/LiveBroadcastHub.kt")
s = p.read_text(encoding="utf-8")
old_region = '''    Text(title, color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(7.dp))
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        platforms.forEach { platform ->
            val shape = CutCornerShape(topEnd = 10.dp, bottomStart = 7.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(platform) }
                    .background(RiftPanel, shape)
                    .border(1.dp, RiftLine, shape)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(platform.displayName, color = RiftText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (platform.packages.isEmpty()) "网页入口" else "优先打开已安装 APP · 否则网页",
                        color = RiftMuted,
                        fontSize = 10.sp
                    )
                }
                Text("打开 ›", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
'''
new_region = '''    RiftSectionLabel(title)
    Spacer(Modifier.height(7.dp))
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        platforms.forEach { platform ->
            RiftHudPanel(onClick = { onOpen(platform) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(platform.displayName, color = RiftText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (platform.packages.isEmpty()) "网页入口" else "优先打开已安装 APP · 否则网页",
                            color = RiftMuted,
                            fontSize = 10.sp
                        )
                    }
                    RiftStatusBadge("OPEN")
                }
            }
        }
    }
'''
s = replace_once(s, old_region, new_region, "broadcast region rows")
# Launcher remains compact rather than full-width; remove the full outline and tint active state.
s = s.replace(
    '''            .background(RiftPanel, shape)
            .border(
                1.dp,
                if (gameLive) RiftRed.copy(alpha = 0.85f) else if (eventActive) RiftCyan.copy(alpha = 0.6f) else RiftLine,
                shape
            )
''',
    '''            .background(
                when {
                    gameLive -> RiftRed.copy(alpha = 0.12f)
                    eventActive -> RiftCyan.copy(alpha = 0.10f)
                    else -> RiftPanel
                },
                shape
            )
''',
    1
)
p.write_text(s, encoding="utf-8")


# Tournament edition archive: make the archive itself a HUD surface instead of one more outlined
# document card. Inner 2-column slots already use quiet fills and remain dense.
p = path("app/src/main/java/com/riftlab/app/ui/TournamentEditionArchiveUi.kt")
s = p.read_text(encoding="utf-8")
old_outer = '''    Column(
        Modifier
            .fillMaxWidth()
            .background(RiftPanelAlt, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .border(1.dp, RiftLine, CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .padding(10.dp)
    ) {
'''
new_outer = '''    RiftHudPanel(accent = selected != null) {
'''
s = replace_once(s, old_outer, new_outer, "tournament archive outer")
s = s.replace('            return@Column\n', '            return@RiftHudPanel\n', 1)
s = s.replace('Text("TOURNAMENT EDITIONS / 年度赛事档案", color = RiftCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)', 'Text("TOURNAMENT ARCHIVE / 年度赛事档案", color = RiftText, fontSize = 16.sp, fontWeight = FontWeight.Bold)', 1)
s = s.replace('Text("${state.editions.size} EDITIONS", color = RiftText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)', 'RiftStatusBadge("${state.editions.size} EDITIONS")', 1)
p.write_text(s, encoding="utf-8")

print("dev.76 second visual pass applied")
