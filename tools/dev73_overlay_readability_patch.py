#!/usr/bin/env python3
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
overlay = root / "app/src/main/java/com/riftlab/app/overlay"
changed = []
for path in overlay.glob("*.kt"):
    text = path.read_text(encoding="utf-8")
    updated = re.sub(r",\s*[89]f,", ", 10f,", text)
    updated = updated.replace("textSize = if (value.length > 2) 9f else 16f", "textSize = if (value.length > 2) 10f else 16f")
    if updated != text:
        path.write_text(updated, encoding="utf-8")
        changed.append(path.name)

combined = "\n".join(p.read_text(encoding="utf-8") for p in overlay.glob("*.kt"))
assert not re.search(r",\s*[89]f,", combined)
assert "textSize = if (value.length > 2) 9f else 16f" not in combined
print("overlay readability patched:", ", ".join(changed) or "no changes")
