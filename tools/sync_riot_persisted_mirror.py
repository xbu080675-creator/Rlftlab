#!/usr/bin/env python3
from __future__ import annotations

import datetime as dt
import json
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "data" / "lpl" / "riot_persisted_mirror.json"
BASE = "https://esports-api.lolesports.com/persisted/gw"
API_KEY = "0TvQnueqKa5mxJntVWt0w4LpLfEkrV1Ta8rQBb9Z"
LPL = "98767991314006698"
UA = "RiftLab-RiotMirror/1.0 (+https://github.com/xbu080675-creator/Rlftlab)"


def now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def fetch(operation: str, params: dict[str, str] | None = None, timeout: int = 12) -> dict[str, Any]:
    query = {"hl": "en-US"}
    if params:
        query.update({k: v for k, v in params.items() if v != ""})
    url = f"{BASE}/{operation}?{urllib.parse.urlencode(query)}"
    req = urllib.request.Request(
        url,
        headers={
            "x-api-key": API_KEY,
            "Accept": "application/json",
            "User-Agent": UA,
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as response:
        if response.status < 200 or response.status >= 300:
            raise RuntimeError(f"{operation}: HTTP {response.status}")
        return json.loads(response.read().decode("utf-8"))


def schedule_token(root: dict[str, Any], direction: str) -> str:
    return str(
        root.get("data", {})
        .get("schedule", {})
        .get("pages", {})
        .get(direction, "")
        or ""
    )


def schedule_events(root: dict[str, Any]) -> list[dict[str, Any]]:
    return list(root.get("data", {}).get("schedule", {}).get("events", []) or [])


def collect_schedule() -> tuple[list[dict[str, Any]], list[dict[str, str]], list[str]]:
    center = fetch("getSchedule", {"leagueId": LPL})
    pages: list[dict[str, Any]] = [{"pageToken": "", "payload": center}]
    seen: set[str] = set()

    for direction in ("older", "newer"):
        token = schedule_token(center, direction)
        for _ in range(4):
            if not token or token in seen:
                break
            seen.add(token)
            payload = fetch("getSchedule", {"leagueId": LPL, "pageToken": token})
            pages.append({"pageToken": token, "payload": payload})
            token = schedule_token(payload, direction)

    teams: dict[str, dict[str, str]] = {}
    event_ids: list[str] = []
    for page in pages:
        for event in schedule_events(page["payload"]):
            if event.get("type") != "match":
                continue
            league = event.get("league") or {}
            if str(league.get("id", "")) not in ("", LPL) and str(league.get("slug", "")).lower() != "lpl":
                continue
            event_id = str(event.get("id", ""))
            if event_id:
                event_ids.append(event_id)
            match = event.get("match") or {}
            for team in match.get("teams", []) or []:
                tid = str(team.get("id", "")).strip()
                slug = str(team.get("slug", "")).strip()
                code = str(team.get("code", "")).strip()
                name = str(team.get("name", "")).strip()
                key = tid or slug or code or name
                if key:
                    teams[key] = {"id": tid, "slug": slug, "code": code, "name": name}
    return pages, list(teams.values()), list(dict.fromkeys(event_ids))


def collect_tournaments() -> tuple[dict[str, Any], list[str]]:
    root = fetch("getTournamentsForLeague", {"leagueId": LPL})
    tournaments: list[dict[str, Any]] = []
    for league in root.get("data", {}).get("leagues", []) or []:
        tournaments.extend(league.get("tournaments", []) or [])
    tournaments = [t for t in tournaments if str(t.get("id", ""))]
    tournaments.sort(key=lambda x: str(x.get("startDate", "")))
    # Keep a useful historical window without making the mirror enormous.
    ids = [str(t["id"]) for t in tournaments[-24:]]
    return root, ids


def normalize_lookup(value: str) -> str:
    return "".join(ch.lower() for ch in value.strip() if ch.isalnum())


def collect_team_details(team_refs: list[dict[str, str]]) -> tuple[dict[str, Any], dict[str, str]]:
    details: dict[str, Any] = {}
    lookup: dict[str, str] = {}
    for ref in team_refs:
        candidates = [ref.get("slug", ""), ref.get("id", "")]
        root: dict[str, Any] | None = None
        query_used = ""
        for query in candidates:
            if not query:
                continue
            try:
                candidate_root = fetch("getTeams", {"id": query})
            except Exception:
                continue
            teams = candidate_root.get("data", {}).get("teams", []) or []
            if teams:
                root = candidate_root
                query_used = query
                break
        if root is None:
            continue
        teams = root.get("data", {}).get("teams", []) or []
        team = teams[0]
        canonical = str(team.get("id", "") or query_used)
        details[canonical] = root
        aliases = {
            canonical,
            str(team.get("slug", "")),
            str(team.get("code", "")),
            str(team.get("name", "")),
            ref.get("id", ""),
            ref.get("slug", ""),
            ref.get("code", ""),
            ref.get("name", ""),
        }
        for alias in aliases:
            token = normalize_lookup(alias)
            if token:
                lookup[token] = canonical
    return details, lookup


def collect_standings(tournament_ids: list[str]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for tid in tournament_ids:
        try:
            result[tid] = fetch("getStandings", {"tournamentId": tid})
        except Exception:
            continue
    return result


def collect_completed_events(tournament_ids: list[str]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for tid in tournament_ids:
        try:
            result[tid] = fetch("getCompletedEvents", {"tournamentId": tid})
        except Exception:
            continue
    return result


def collect_event_details(event_ids: list[str]) -> dict[str, Any]:
    # EventDetails is mainly needed to bridge a persisted-gateway outage during current/upcoming play.
    # Fetch the newest schedule ids; direct LiveStats remains the real-time source.
    result: dict[str, Any] = {}
    for event_id in event_ids[-12:]:
        try:
            result[event_id] = fetch("getEventDetails", {"id": event_id})
        except Exception:
            continue
    return result


def main() -> int:
    pages, team_refs, event_ids = collect_schedule()
    tournaments_root, tournament_ids = collect_tournaments()
    team_details, team_lookup = collect_team_details(team_refs)
    standings = collect_standings(tournament_ids)
    completed_events = collect_completed_events(tournament_ids)
    try:
        live_root = fetch("getLive")
    except Exception:
        live_root = {}
    event_details = collect_event_details(event_ids)

    mirror = {
        "schemaVersion": 1,
        "dataset": "riftlab-riot-persisted-mirror",
        "updatedAt": now_iso(),
        "source": "Riot LoL Esports persisted gateway",
        "leagueId": LPL,
        "schedulePages": pages,
        "tournaments": tournaments_root,
        "standingsByTournament": standings,
        "completedEventsByTournament": completed_events,
        "teamLookup": team_lookup,
        "teamDetails": team_details,
        "live": live_root,
        "eventDetailsByEvent": event_details,
    }

    print(
        f"mirror candidate: pages={len(pages)} team_refs={len(team_refs)} teams={len(team_details)} "
        f"standings={len(standings)} completed={len(completed_events)} events={len(event_details)}"
    )
    if not pages:
        raise RuntimeError("refusing to replace mirror without schedule pages")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(mirror, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(
        f"mirror updated: pages={len(pages)} teams={len(team_details)} "
        f"standings={len(standings)} completed={len(completed_events)} events={len(event_details)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
