#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
UI_ROOT = ROOT / "app/src/main/java/com/riftlab/app/ui"

# The first readability pass only matched simple `fontSize = 8.sp` forms and missed conditional
# expressions / indirect component parameters. This pass is deliberately syntax-scoped: only the
# value assigned to fontSize/teamNameFontSize is normalized, never unrelated `letterSpacing`.

def normalize_font_assignment(line: str) -> str:
    marker = re.search(r"\b(?:fontSize|teamNameFontSize)\s*=", line)
    if not marker:
        return line
    start = marker.start()
    # Named arguments in these Compose call sites end at the next comma. Keep later parameters such
    # as letterSpacing untouched even when they share the same source line.
    comma = line.find(",", marker.end())
    end = len(line) if comma == -1 else comma
    segment = line[start:end]
    segment = re.sub(r"(?<![0-9.])(?:6|7|8|9)\.sp\b", "10.sp", segment)
    return line[:start] + segment + line[end:]

changed = []
for path in UI_ROOT.glob("*.kt"):
    text = path.read_text(encoding="utf-8")
    lines = []
    for raw in text.splitlines(keepends=True):
        updated = normalize_font_assignment(raw)
        # When an old micro-label used a 9/10sp line height, lift it with the new 10sp font so glyphs
        # are not clipped. This is limited to lines that actually declare a fontSize.
        if "fontSize" in updated:
            updated = re.sub(r"\blineHeight\s*=\s*(?:8|9|10)\.sp\b", "lineHeight = 14.sp", updated)
        lines.append(updated)
    output = "".join(lines)
    if output != text:
        path.write_text(output, encoding="utf-8")
        changed.append(path.name)

# Verify direct and conditional font-size expressions. Ignore letterSpacing and other sp-valued args.
violations = []
for path in UI_ROOT.glob("*.kt"):
    text = path.read_text(encoding="utf-8")
    for line_no, line in enumerate(text.splitlines(), 1):
        marker = re.search(r"\b(?:fontSize|teamNameFontSize)\s*=", line)
        if not marker:
            continue
        comma = line.find(",", marker.end())
        end = len(line) if comma == -1 else comma
        segment = line[marker.start():end]
        tiny = re.findall(r"(?<![0-9.])([0-9]+(?:\.[0-9]+)?)\.sp\b", segment)
        if any(float(value) < 10 for value in tiny):
            violations.append(f"{path.relative_to(ROOT)}:{line_no}: {segment.strip()}")
if violations:
    raise SystemExit("residual font-size assignments below 10sp:\n" + "\n".join(violations))

print("dev73 readability residual patch applied:", ", ".join(changed) or "no changes")
print("no explicit fontSize/teamNameFontSize assignment below 10sp remains")
