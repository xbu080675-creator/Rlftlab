#!/usr/bin/env python3
from pathlib import Path

path = Path(__file__).resolve().parents[1] / "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt"
text = path.read_text(encoding="utf-8")

old = '''    private val noLeagueIdNoSlugByName = indexed
        .filter { it.match.leagueId.isBlank() && it.match.leagueSlug.isBlank() && it.match.league.isNotBlank() }
        .groupBy { normalizeLeagueToken(it.match.league) }
    private val noSlugByName = indexed
'''
new = '''    private val noLeagueIdByName = indexed
        .filter { it.match.leagueId.isBlank() && it.match.league.isNotBlank() }
        .groupBy { normalizeLeagueToken(it.match.league) }
    private val noLeagueIdNoSlugByName = indexed
        .filter { it.match.leagueId.isBlank() && it.match.leagueSlug.isBlank() && it.match.league.isNotBlank() }
        .groupBy { normalizeLeagueToken(it.match.league) }
    private val noSlugByName = indexed
'''
if text.count(old) != 1:
    raise SystemExit("index declaration target not found exactly once")
text = text.replace(old, new, 1)

old = '''            leagueId.isNotBlank() -> buildList {
                addAll(byLeagueId[leagueId].orEmpty())
                if (leagueSlug.isNotBlank()) addAll(noLeagueIdBySlug[leagueSlug].orEmpty())
                if (leagueName.isNotBlank()) addAll(noLeagueIdNoSlugByName[leagueName].orEmpty())
            }
'''
new = '''            leagueId.isNotBlank() -> buildList {
                addAll(byLeagueId[leagueId].orEmpty())
                if (leagueSlug.isNotBlank()) {
                    addAll(noLeagueIdBySlug[leagueSlug].orEmpty())
                    if (leagueName.isNotBlank()) addAll(noLeagueIdNoSlugByName[leagueName].orEmpty())
                } else if (leagueName.isNotBlank()) {
                    // Preserve sameLeague(): if the tournament has no slug, a match without a leagueId
                    // is allowed to fall back to the league name even when the match itself has a slug.
                    addAll(noLeagueIdByName[leagueName].orEmpty())
                }
            }
'''
if text.count(old) != 1:
    raise SystemExit("candidate semantics target not found exactly once")
text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")
assert "private val noLeagueIdByName" in text
assert "addAll(noLeagueIdByName[leagueName].orEmpty())" in text
print("schedule index fallback semantics preserved")
