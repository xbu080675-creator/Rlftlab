#!/usr/bin/env python3
"""Build match-target search queries for starting-roster discovery.

Search order is intentionally match-day first. On days with multiple matches,
`date + team A + team B + lineup intent` is the primary key so results for other
matches on the same day do not crowd the candidate set.
"""
from __future__ import annotations

from datetime import date, datetime


def _date_tokens(match_date: str, league: str) -> list[str]:
    try:
        parsed = datetime.fromisoformat(match_date).date()
    except Exception:
        try:
            parsed = date.fromisoformat(match_date)
        except Exception:
            return [str(match_date).strip()] if str(match_date).strip() else []

    league = (league or "").upper()
    if league == "LPL":
        return [f"{parsed.month}月{parsed.day}日", parsed.isoformat()]
    if league in {"LCK", "LCP"}:
        return [parsed.isoformat(), f"{parsed.month}/{parsed.day}"]
    return [parsed.isoformat(), parsed.strftime("%b %-d")]


def build_match_search_queries(match_date: str, team_a: str, team_b: str, league: str) -> list[str]:
    """Return ordered queries, strongest first.

    Rule: date + both teams + lineup intent comes before every broader fallback.
    This matters most on multi-match days and is safe for single-match days too.
    """
    league = (league or "").upper().strip()
    a = str(team_a or "").strip()
    b = str(team_b or "").strip()
    dates = [token for token in _date_tokens(match_date, league) if token]
    if not a or not b:
        return []

    if league == "LPL":
        intents = ["首发名单", "首发", "先发"]
        matchup_forms = [f"{a} {b}", f"{a}对战{b}", f"{a} vs {b}"]
    elif league == "LCK":
        intents = ["starting lineup", "선발 명단", "라인업", "starting roster"]
        matchup_forms = [f"{a} vs {b}", f"{a} {b}"]
    elif league == "LCP":
        intents = ["starting lineup", "首发", "先発", "선발", "roster"]
        matchup_forms = [f"{a} vs {b}", f"{a} {b}"]
    else:
        intents = ["starting lineup", "starting roster", "lineup", "roster"]
        matchup_forms = [f"{a} vs {b}", f"{a} {b}"]

    queries: list[str] = []
    # Strongest path: match day + exact matchup + lineup intent.
    for dt in dates[:2]:
        for matchup in matchup_forms[:2]:
            for intent in intents[:2]:
                queries.append(f"{dt} {matchup} {intent}")

    # Broader fallbacks are deliberately later.
    for matchup in matchup_forms:
        for intent in intents[:3]:
            queries.append(f"{matchup} {intent}")

    out: list[str] = []
    seen = set()
    for query in queries:
        normalized = " ".join(query.split())
        key = normalized.lower()
        if normalized and key not in seen:
            seen.add(key)
            out.append(normalized)
    return out


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("date")
    parser.add_argument("team_a")
    parser.add_argument("team_b")
    parser.add_argument("league")
    args = parser.parse_args()
    for query in build_match_search_queries(args.date, args.team_a, args.team_b, args.league):
        print(query)
