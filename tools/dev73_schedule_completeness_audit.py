#!/usr/bin/env python3
from __future__ import annotations

import concurrent.futures
import json
import re
import sys
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
BASE = "https://esports-api.lolesports.com/persisted/gw"
API_KEY = "0TvQnueqKa5mxJntVWt0w4LpLfEkrV1Ta8rQBb9Z"
UA = "RiftLab-dev73-schedule-audit/1.0"


def fetch(operation: str, params: dict[str, str] | None = None, timeout: int = 15) -> dict[str, Any]:
    query = {"hl": "en-US"}
    if params:
        query.update({k: v for k, v in params.items() if v})
    url = f"{BASE}/{operation}?{urllib.parse.urlencode(query)}"
    request = urllib.request.Request(url, headers={"x-api-key": API_KEY, "Accept": "application/json", "User-Agent": UA})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def schedule(root: dict[str, Any]) -> dict[str, Any]:
    return root.get("data", {}).get("schedule", {}) or {}


def events(root: dict[str, Any]) -> list[dict[str, Any]]:
    return [x for x in schedule(root).get("events", []) or [] if x.get("type") == "match"]


def token(root: dict[str, Any], direction: str) -> str:
    return str(schedule(root).get("pages", {}).get(direction, "") or "")


def event_key(event: dict[str, Any]) -> str:
    match = event.get("match") or {}
    return str(event.get("id") or match.get("id") or "")


def league_of(event: dict[str, Any]) -> tuple[str, str, str]:
    league = event.get("league") or {}
    return str(league.get("id", "")), str(league.get("slug", "")), str(league.get("name", ""))


def team_count(event: dict[str, Any]) -> int:
    return len((event.get("match") or {}).get("teams", []) or [])


def collect_window(league_id: str | None, steps_each_direction: int) -> list[dict[str, Any]]:
    params = {"leagueId": league_id} if league_id else None
    center = fetch("getSchedule", params)
    pages = [center]
    seen: set[str] = set()
    for direction in ("older", "newer"):
        cursor = token(center, direction)
        for _ in range(steps_each_direction):
            if not cursor or cursor in seen:
                break
            seen.add(cursor)
            page_params = {"pageToken": cursor}
            if league_id:
                page_params["leagueId"] = league_id
            page = fetch("getSchedule", page_params)
            pages.append(page)
            cursor = token(page, direction)
    by_id: dict[str, dict[str, Any]] = {}
    for page in pages:
        for event in events(page):
            key = event_key(event)
            if key:
                by_id[key] = event
    return list(by_id.values())


def kotlin_allowlist() -> tuple[set[str], set[str], set[str]]:
    text = (ROOT / "app/src/main/java/com/riftlab/app/data/LolEsportsApiClient.kt").read_text(encoding="utf-8")
    def set_for(name: str) -> set[str]:
        m = re.search(rf"{name}\s*=\s*setOf\((.*?)\n\s*\)", text, re.S)
        if not m:
            return set()
        return set(re.findall(r'"([^"]+)"', m.group(1)))
    slugs = set_for("GLOBAL_EXCLUDED_LEAGUE_SLUGS")
    names = set_for("GLOBAL_EXCLUDED_LEAGUE_NAMES")
    return slugs, names, set()


def is_tracked(league: dict[str, Any], slugs: set[str], names: set[str]) -> bool:
    slug = str(league.get("slug", "")).lower()
    normalized = slug.replace("_", "-")
    name = str(league.get("name", "")).strip().lower()
    excluded = slug in slugs or normalized in slugs or any(name == x or x in name for x in names)
    return not excluded


def summary_row(event: dict[str, Any]) -> dict[str, Any]:
    match = event.get("match") or {}
    lid, slug, name = league_of(event)
    teams = match.get("teams", []) or []
    return {
        "id": event_key(event),
        "start": event.get("startTime", ""),
        "leagueId": lid,
        "leagueSlug": slug,
        "league": name,
        "block": event.get("blockName", ""),
        "state": event.get("state", ""),
        "teamCount": len(teams),
        "teams": [str(t.get("code") or t.get("name") or "TBD") for t in teams],
    }


def main() -> int:
    slugs, names, deep = kotlin_allowlist()
    leagues_root = fetch("getLeagues")
    leagues = list(leagues_root.get("data", {}).get("leagues", []) or [])
    league_by_id = {str(x.get("id", "")): x for x in leagues if str(x.get("id", ""))}
    tracked = [x for x in leagues if is_tracked(x, slugs, names)]
    excluded = [x for x in leagues if str(x.get("id", "")) and not is_tracked(x, slugs, names)]

    # Probe the endpoint without leagueId. If Riot returns a true global schedule, this is the best
    # completeness baseline because it avoids assuming a static league catalogue.
    global_events: list[dict[str, Any]] = []
    global_error = ""
    try:
        global_events = collect_window(None, 10)
    except Exception as exc:
        global_error = repr(exc)

    def center_for(league: dict[str, Any]) -> tuple[str, list[dict[str, Any]] | None, str]:
        lid = str(league.get("id", ""))
        try:
            return lid, collect_window(lid, 0), ""
        except Exception as exc:
            return lid, None, repr(exc)

    center_by_league: dict[str, list[dict[str, Any]]] = {}
    errors: dict[str, str] = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for lid, rows, error in pool.map(center_for, leagues):
            if rows is not None:
                center_by_league[lid] = rows
            elif error:
                errors[lid] = error

    active_excluded = []
    for league in excluded:
        lid = str(league.get("id", ""))
        rows = center_by_league.get(lid, [])
        if rows:
            active_excluded.append({
                "id": lid,
                "slug": league.get("slug", ""),
                "name": league.get("name", ""),
                "centerMatches": len(rows),
                "sample": [summary_row(x) for x in rows[:3]],
            })

    # Reproduce the new Android primary path: Riot global schedule, ten pages each direction.
    # The per-league catalogue is now only a resilient fallback, so it is audited separately above.
    runtime_rows = collect_window(None, 10)
    current_events = {event_key(x): x for x in runtime_rows if event_key(x)}
    full_tracked_events = dict(current_events)
    pagination_losses = []

    # Current parser also discards every row with unresolved participants.
    global_tbd = [x for x in global_events if team_count(x) < 2]
    tracked_tbd = [x for x in full_tracked_events.values() if team_count(x) < 2]

    global_leagues = Counter((league_of(x)[1] or league_of(x)[2]) for x in global_events)
    global_ids = {event_key(x) for x in global_events}
    current_ids = set(current_events)
    missed_vs_global = [x for x in global_events if event_key(x) not in current_ids]

    report = {
        "leagueCount": len(leagues),
        "trackedLeagueCount": len(tracked),
        "excludedLeagueCount": len(excluded),
        "activeExcludedLeagues": active_excluded,
        "globalUnfiltered": {
            "error": global_error,
            "matches": len(global_events),
            "leagueDistribution": dict(global_leagues),
            "tbdOrPartialTeams": len(global_tbd),
            "sampleTbd": [summary_row(x) for x in global_tbd[:20]],
        },
        "currentTrackedMatches": len(current_events),
        "sixPageTrackedMatches": len(full_tracked_events),
        "trackedTbdOrPartialTeams": len(tracked_tbd),
        "paginationLosses": pagination_losses,
        "missingAgainstGlobalUnfiltered": {
            "count": len(missed_vs_global),
            "sample": [summary_row(x) for x in missed_vs_global[:50]],
        },
        "requestErrors": errors,
        "allExcludedLeagues": [
            {"id": x.get("id", ""), "slug": x.get("slug", ""), "name": x.get("name", "")}
            for x in excluded
        ],
    }
    out = ROOT / "schedule-audit.json"
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))

    # This is an audit, not a release gate: endpoint failures are reported instead of hiding results.
    if not leagues:
        print("FATAL: getLeagues returned no leagues", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
