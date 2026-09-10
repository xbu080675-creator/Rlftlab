#!/usr/bin/env python3
from pathlib import Path

paths = [
    Path("app/src/main/java/com/riftlab/app/data/LiveMatchTargetRegistry.kt"),
    Path("app/src/main/java/com/riftlab/app/data/LplMatchDetailLiveDataSource.kt"),
]

bad = 'value.uppercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")'
good = 'value.uppercase().filter { it.isLetterOrDigit() }'

changed = 0
for path in paths:
    text = path.read_text(encoding="utf-8")
    if bad in text:
        path.write_text(text.replace(bad, good), encoding="utf-8")
        changed += 1

if changed != 2:
    raise SystemExit(f"expected to repair 2 generated Kotlin tokenizers, repaired {changed}")

print("repaired Kotlin unicode tokenizers without regex escape ambiguity")
