#!/usr/bin/env python3
"""Derive imminent starting-roster search targets from Riot's global schedule.

This is the server-side bridge between schedule truth and social discovery:
Riot tells us *which match* is happening; social search only answers *who starts*.
"""
from __future__ import annotations

import argparse
import json
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

import requests

PERSISTED = "https://esports-api.lolesports.com/persisted/gw"
# Riot's public esports web-client key. Override in CI if Riot rotates it.
DEFAULT_API_KEY = "0TvQnueqKa5mxJntVWt0w4LpLfEkrV1Ta8rQBb9Z"
UNRESOLVED_TEAM_TOKENS = {
    "TBD", "TBA", "TBC", "TBD1", "TBD2", "TBD3", "TBD4", "待定", "待确认", "UNKNOWN", "BYE", "-", "—"
}


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_instant(value: str) -> datetime | None:
    raw = str(value or "").strip()
    if not raw:
        return None
    try:
        return datetime.fromisoformat(raw.replace("Z", "+00:00")).astimezone(timezone.utc)
    except Exception:
        return None


def league_index(cfg: dict) -> dict[str, dict]:
    out: dict[str, dict] = {}
    for row in cfg.get("leagues", []):
        league = str(row.get("league") or "").upper().strip()
        if league:
            out[league] = row
    return out


def canonical_league(event: dict, configured: set[str]) -> str:
    league = event.get("league") or {}
    slug = str(league.get("slug") or "").upper().replace("-", "").replace("_", "")
    name = str(league.get("name") or "").upper()
    candidates = {
        "LPL": ("LPL", "PRO LEAGUE"),
        "LCK": ("LCK", "CHAMPIONS KOREA"),
        "LEC": ("LEC", "EMEA CHAMPIONSHIP"),
        "LCS": ("LCS", "CHAMPIONSHIP SERIES"),
        "LCP": ("LCP", "PACIFIC"),
    }
    for code, needles in candidates.items():
        if code not in configured:
            continue
        if any(n.replace(" ", "") in slug or n in name for n in needles):
            return code
    return ""


def fetch_schedule(api_key: str) -> dict:
    response = requests.get(
        f"{PERSISTED}/getSchedule?hl=en-US",
        headers={"x-api-key": api_key, "Accept": "application/json"},
        timeout=15,
    )
    response.raise_for_status()
    return response.json()


def unresolved_team(code: str, name: str) -> bool:
    values = {str(code or "").strip().upper(), str(name or "").strip().upper()}
    if values & UNRESOLVED_TEAM_TOKENS:
        return True
    return any(value.startswith("TBD") or value.startswith("TBA") for value in values if value)


def build_targets(cfg: dict, root: dict, hours_before: int, hours_after: int, limit: int) -> list[dict]:
    now = datetime.now(timezone.utc)
    low, high = now - timedelta(hours=hours_before), now + timedelta(hours=hours_after)
    league_rows = league_index(cfg)
    configured = set(league_rows)
    events = (((root.get("data") or {}).get("schedule") or {}).get("events") or [])
    targets: list[dict] = []
    for event in events:
        if str(event.get("type") or "").lower() != "match":
            continue
        start = parse_instant(event.get("startTime"))
        if start is None or start < low or start > high:
            continue
        league = canonical_league(event, configured)
        if not league:
            continue
        match = event.get("match") or {}
        teams = match.get("teams") or []
        if len(teams) < 2:
            continue
        team_codes = []
        team_names = []
        unresolved = False
        for team in teams[:2]:
            code = str(team.get("code") or "").strip().upper()
            name = str(team.get("name") or "").strip()
            if not code:
                code = "".join(ch for ch in name.upper() if ch.isalnum())[:4]
            if unresolved_team(code, name):
                unresolved = True
            team_codes.append(code)
            team_names.append(name or code)
        if unresolved or any(not code for code in team_codes):
            continue
        tz_name = str(league_rows[league].get("timezone") or "UTC")
        try:
            local = start.astimezone(ZoneInfo(tz_name))
        except Exception:
            local = start
            tz_name = "UTC"
        targets.append({
            "eventId": str(event.get("id") or match.get("id") or ""),
            "matchId": str(match.get("id") or ""),
            "league": league,
            "timezone": tz_name,
            "startTimeIso": start.isoformat().replace("+00:00", "Z"),
            "matchDateLocal": local.date().isoformat(),
            "teams": team_codes,
            "teamNames": team_names,
            "state": str(event.get("state") or ""),
        })
    targets.sort(key=lambda row: row["startTimeIso"])
    deduped = []
    seen = set()
    for row in targets:
        key = row["eventId"] or f"{row['league']}|{row['matchDateLocal']}|{'|'.join(row['teams'])}"
        if key in seen:
            continue
        seen.add(key)
        deduped.append(row)
        if len(deduped) >= limit:
            break
    return deduped


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sources", default="data/global/starting_roster_sources.json")
    parser.add_argument("--output", default="data/global/starting_roster_match_targets.json")
    parser.add_argument("--hours-before", type=int, default=6)
    parser.add_argument("--hours-after", type=int, default=36)
    parser.add_argument("--limit", type=int, default=12)
    args = parser.parse_args()

    cfg = json.loads(Path(args.sources).read_text(encoding="utf-8"))
    api_key = os.environ.get("RIFTLAB_LOLESPORTS_API_KEY", DEFAULT_API_KEY).strip()
    result = {"schemaVersion": 1, "updatedAt": now_iso(), "targets": [], "diagnostics": []}
    try:
        root = fetch_schedule(api_key)
        result["targets"] = build_targets(cfg, root, args.hours_before, args.hours_after, args.limit)
        result["diagnostics"].append(f"riot_schedule_targets={len(result['targets'])}")
    except Exception as exc:
        result["diagnostics"].append(f"riot_schedule_{type(exc).__name__}:{str(exc)[:180]}")

    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"targets": len(result["targets"]), "diagnostics": result["diagnostics"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
