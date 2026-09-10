#!/usr/bin/env python3
from pathlib import Path

path = Path(__file__).resolve().with_name("apply_dev71_pre_context.py")
text = path.read_text(encoding="utf-8")
old = '.append("\\n")'
new = '.append("\\\\n")'
count = text.count(old)
if count < 3:
    raise SystemExit(f"expected PRE generator newline literals, found {count}")
path.write_text(text.replace(old, new), encoding="utf-8")
print(f"fixed {count} generated Kotlin newline literals")
