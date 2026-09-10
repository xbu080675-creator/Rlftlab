#!/usr/bin/env python3
from pathlib import Path

p = Path('app/src/main/java/com/riftlab/app/ui/MatchOperationsContent.kt')
s = p.read_text(encoding='utf-8')
old = '''            MetricRow(\n                "${selected.blue} ${formatGold(selected.blueGold)}",\n                "Δ ${signedGold(selected.goldDiff)}",\n                if (selected.blueXp > 0 || selected.redXp > 0) "XP Δ ${signedGold(selected.blueXp - selected.redXp)}" else "XP —",\n                "${selected.red} ${formatGold(selected.redGold)}"\n            )'''
new = '''            OperatorScrubMetricRow(\n                "${selected.blue} ${formatGold(selected.blueGold)}",\n                "Δ ${signedGold(selected.goldDiff)}",\n                if (selected.blueXp > 0 || selected.redXp > 0) "XP Δ ${signedGold(selected.blueXp - selected.redXp)}" else "XP —",\n                "${selected.red} ${formatGold(selected.redGold)}"\n            )'''
if old not in s:
    raise SystemExit('scrubber MetricRow call not found')
s = s.replace(old, new, 1)
anchor = '@Composable\nprivate fun PlayerOperatorTable'
idx = s.index(anchor)
helper = '''@Composable\nprivate fun OperatorScrubMetricRow(left: String, centerLeft: String, centerRight: String, right: String) {\n    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {\n        Text(left, color = RiftCyan, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)\n        Text(centerLeft, color = RiftText, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)\n        Text(centerRight, color = RiftMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)\n        Text(right, color = RiftRed, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)\n    }\n}\n\n'''
s = s[:idx] + helper + s[idx:]
p.write_text(s, encoding='utf-8')
print('replay metric visibility fix applied')
