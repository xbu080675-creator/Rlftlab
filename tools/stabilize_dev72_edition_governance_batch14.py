#!/usr/bin/env python3
from pathlib import Path

p = Path("app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt")
text = p.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected one match, got {count}: {old[:140]!r}")
    text = text.replace(old, new, 1)


replace_once(
    '''        if (archived == null) return live\n        fun quality(snapshot: TournamentRulesSnapshot): Int =\n''',
    '''        if (archived == null) return live\n        val sameContent = archived.title == live.title &&\n            archived.sourceSummary == live.sourceSummary &&\n            archived.items == live.items\n        if (sameContent) return archived\n        fun quality(snapshot: TournamentRulesSnapshot): Int =\n'''
)
replace_once(
    '''        if (archived == null) return live\n        fun quality(snapshot: TournamentDrawSnapshot): Int =\n''',
    '''        if (archived == null) return live\n        val sameContent = archived.title == live.title &&\n            archived.note == live.note &&\n            archived.sourceSummary == live.sourceSummary &&\n            archived.slots == live.slots\n        if (sameContent) return archived\n        fun quality(snapshot: TournamentDrawSnapshot): Int =\n'''
)
replace_once(
    '''        if (detail.isBlank() || source.isBlank()) return archived\n        return TournamentQualificationArchive(detail = detail, source = source)\n''',
    '''        if (detail.isBlank() || source.isBlank()) return archived\n        if (archived?.detail == detail && archived.source == source) return archived\n        return TournamentQualificationArchive(detail = detail, source = source)\n'''
)

p.write_text(text, encoding="utf-8")
print("dev72 governance archive timestamp stabilization applied")
