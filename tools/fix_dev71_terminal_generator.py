#!/usr/bin/env python3
from pathlib import Path

path = Path(__file__).resolve().with_name("apply_dev71_terminal_results.py")
text = path.read_text(encoding="utf-8")
old = 'winner.gameWins >= loser?.gameWins ?: 0'
new = 'winner.gameWins >= (loser?.gameWins ?: 0)'
if old not in text:
    raise SystemExit("terminal comparison anchor missing")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("fixed terminal winner comparison")
