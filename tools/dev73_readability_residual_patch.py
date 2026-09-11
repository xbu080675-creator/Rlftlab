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
        ("fontSize = 6.sp, lineHeight = 9.sp", "fontSize = 10.sp, lineHeight = 14.sp"),
        ("fontSize = 6.sp", "fontSize = 10.sp"),
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

# Catch the bug the first font pass missed: indirect font-size parameters and every branch of
# conditional font-size expressions. Ignore unrelated sp values such as letterSpacing.
violations = []
for path in (ROOT / "app/src/main/java").rglob("*.kt"):
    text = path.read_text(encoding="utf-8")
    for line_no, line in enumerate(text.splitlines(), 1):
        if "fontSize" not in line and "teamNameFontSize" not in line:
            continue
        for raw in re.findall(r"(\d+(?:\.\d+)?)\.sp", line):
            if float(raw) < 10:
                violations.append(f"{path.relative_to(ROOT)}:{line_no}: {line.strip()}")
                break
if violations:
    raise SystemExit("residual font-size branches below 10sp:\n" + "\n".join(violations))

print("dev73 residual readability patch applied; no explicit font-size branch below 10sp remains")
