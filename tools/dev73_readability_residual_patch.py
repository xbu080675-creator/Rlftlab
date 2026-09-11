#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

replacements = {
    "app/src/main/java/com/riftlab/app/ui/TeamDetailUi.kt": [
        ("fontSize = if (compact) 7.sp else 9.sp", "fontSize = 10.sp"),
        ("teamNameFontSize = 9.sp", "teamNameFontSize = 10.sp"),
    ],
    "app/src/main/java/com/riftlab/app/ui/MatchDetailUi.kt": [
        ("teamNameFontSize = 9.sp", "teamNameFontSize = 10.sp"),
    ],
    "app/src/main/java/com/riftlab/app/ui/MatchReplayContent.kt": [
        ("fontSize = if (fullscreen) 11.sp else 9.sp", "fontSize = if (fullscreen) 11.sp else 10.sp"),
    ],
    "app/src/main/java/com/riftlab/app/ui/QualificationPathUi.kt": [
        ("fontSize = 6.sp", "fontSize = 10.sp"),
        ("fontSize = 6.sp, lineHeight = 9.sp", "fontSize = 10.sp, lineHeight = 14.sp"),
    ],
    "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt": [
        ("teamNameFontSize = 8.sp", "teamNameFontSize = 10.sp"),
    ],
}

for relative, pairs in replacements.items():
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    original = text
    for old, new in pairs:
        if old not in text:
            raise SystemExit(f"missing expected target in {relative}: {old}")
        text = text.replace(old, new)
    if text != original:
        path.write_text(text, encoding="utf-8")

# Catch the bug the first font pass missed: indirect font-size parameters and conditional sizes.
violations = []
for path in (ROOT / "app/src/main/java").rglob("*.kt"):
    text = path.read_text(encoding="utf-8")
    for m in re.finditer(r"(?:fontSize|teamNameFontSize)\s*=\s*(?:if\s*\([^\n]+?\)\s*)?(\d+(?:\.\d+)?)\.sp", text):
        if float(m.group(1)) < 10:
            line = text.count("\n", 0, m.start()) + 1
            violations.append(f"{path.relative_to(ROOT)}:{line}: {m.group(0)}")
if violations:
    raise SystemExit("residual font sizes below 10sp:\n" + "\n".join(violations))

print("dev73 residual readability patch applied; no explicit fontSize/teamNameFontSize below 10sp remains")
