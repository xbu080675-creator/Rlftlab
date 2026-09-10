#!/usr/bin/env python3
"""Build RiftLab's small non-Riot international-event mirror from public event pages.

The mirror is deliberately evidence-preserving: it only serializes rows that are present in the
server-rendered provider payload, keeps the provider URL on every event, and never invents teams,
scores, timestamps or match states. If the upstream structure stops being parseable the script
fails instead of replacing a good mirror with guessed/empty data.
"""

from __future__ import annotations

import datetime as dt
import html
import json
import re
import sys
import urllib.request
from pathlib import Path
from typing import Any, Iterable

OUTPUT = Path("data/global/international_events.json")
USER_AGENT = "RiftLab-InternationalMirrorSync/1"
SOURCE_PAGES = (
    "https://rft.gg/event/wsci-2026",
    "https://rft.gg/event/wsci-2026/matches",
)


def fetch(url: str) -> str:
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "text/html,application/xhtml+xml",
            "Cache-Control": "no-cache",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        if response.status < 200 or response.status >= 300:
            raise RuntimeError(f"{url}: HTTP {response.status}")
        return response.read().decode("utf-8", errors="replace")


def next_rsc_text(raw: str) -> str:
    """Decode the JSON argument of Next.js self.__next_f.push([1, "..."]) records."""
    chunks: list[str] = []
    for body in re.findall(r"<script[^>]*>(.*?)</script>", raw, re.I | re.S):
        body = html.unescape(body).strip()
        marker = "self.__next_f.push("
        if not body.startswith(marker) or not body.endswith(")"):
            continue
        argument = body[len(marker) : -1]
        try:
            value = json.loads(argument)
        except json.JSONDecodeError:
            continue
        if isinstance(value, list) and len(value) >= 2 and isinstance(value[1], str):
            chunks.append(value[1])
    return "\n".join(chunks)


def json_values_for_key(text: str, key: str) -> Iterable[Any]:
    """Yield JSON values that follow an exact object key inside decoded RSC text."""
    needle = json.dumps(key) + ":"
    decoder = json.JSONDecoder()
    pos = 0
    while True:
        hit = text.find(needle, pos)
        if hit < 0:
            return
        start = hit + len(needle)
        while start < len(text) and text[start].isspace():
            start += 1
        try:
            value, used = decoder.raw_decode(text[start:])
        except json.JSONDecodeError:
            pos = start
            continue
        yield value
        pos = start + max(used, 1)


def candidate_rows(raw: str) -> list[dict[str, Any]]:
    rsc = next_rsc_text(raw)
    rows: list[dict[str, Any]] = []
    for key in ("allMatches", "matches"):
        for value in json_values_for_key(rsc, key):
            if not isinstance(value, list):
                continue
            for row in value:
                if not isinstance(row, dict):
                    continue
                if not isinstance(row.get("match"), dict):
                    continue
                if not isinstance(row.get("team1"), dict) or not isinstance(row.get("team2"), dict):
                    continue
                rows.append(row)
    return rows


def merge_dict(base: dict[str, Any], extra: dict[str, Any]) -> dict[str, Any]:
    out = dict(base)
    for key, value in extra.items():
        if isinstance(value, dict) and isinstance(out.get(key), dict):
            out[key] = merge_dict(out[key], value)
        elif value not in (None, "", [], {}):
            out[key] = value
        elif key not in out:
            out[key] = value
    return out


def as_int(value: Any, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def clean_date(value: Any) -> str:
    text = str(value or "").strip()
    if text.startswith("$D"):
        text = text[2:]
    return text


def team_record(team: dict[str, Any], wins: int, outcome: str) -> dict[str, Any]:
    name = str(team.get("name") or "").strip()
    code = str(team.get("shortName") or team.get("code") or "").strip()
    slug = str(team.get("slug") or "").strip()
    image_url = str(team.get("imageUrl") or "").strip()
    team_id = team.get("id")
    return {
        "id": "" if team_id is None else str(team_id),
        "name": name,
        "code": code,
        "slug": slug,
        "imageUrl": image_url,
        "wins": wins,
        "outcome": outcome,
    }


def normalize_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_id: dict[str, dict[str, Any]] = {}
    for row in rows:
        match = row.get("match") or {}
        match_id = str(match.get("id") or "").strip()
        if not match_id:
            continue
        by_id[match_id] = merge_dict(by_id.get(match_id, {}), row)

    normalized: list[dict[str, Any]] = []
    for match_id, row in by_id.items():
        match = row.get("match") or {}
        team1 = row.get("team1") or {}
        team2 = row.get("team2") or {}
        event = row.get("event") or {}

        scheduled = clean_date(match.get("scheduledDate"))
        if not scheduled:
            continue
        name1 = str(team1.get("name") or "").strip()
        name2 = str(team2.get("name") or "").strip()
        if not name1 or not name2 or name1.upper() == "TBD" or name2.upper() == "TBD":
            # Keep the mirror factual and useful: unknown finalist placeholders are not teams.
            continue

        event_id = str(event.get("id") or match.get("eventId") or "").strip()
        event_name = str(event.get("name") or "WSCI 2026").strip()
        event_slug = str(event.get("slug") or "wsci-2026").strip()
        if not event_id:
            continue

        status_raw = str(row.get("status") or "").strip().lower()
        state = {
            "finished": "completed",
            "completed": "completed",
            "live": "inProgress",
            "in_progress": "inProgress",
            "in-progress": "inProgress",
            "scheduled": "unstarted",
            "upcoming": "unstarted",
        }.get(status_raw, "unstarted")

        wins1 = as_int(team1.get("wins"), 0)
        wins2 = as_int(team2.get("wins"), 0)
        outcome1 = outcome2 = ""
        if state == "completed" and wins1 != wins2 and max(wins1, wins2) > 0:
            outcome1, outcome2 = (("win", "loss") if wins1 > wins2 else ("loss", "win"))

        best_of = as_int(match.get("numberOfGames"), 0)
        stage = str(match.get("stageName") or "").strip()
        slug = str(match.get("slug") or "").strip()
        normalized.append(
            {
                "id": f"rft:{match_id}",
                "providerMatchId": match_id,
                "slug": slug,
                "scheduledAt": scheduled,
                "state": state,
                "bestOf": best_of,
                "stage": stage,
                "sourceUrl": f"https://rft.gg/match/{match_id}-{slug}" if slug else f"https://rft.gg/match/{match_id}",
                "eventId": f"rft-event:{event_id}",
                "eventName": event_name,
                "eventSlug": event_slug,
                "teams": [
                    team_record(team1, wins1, outcome1),
                    team_record(team2, wins2, outcome2),
                ],
            }
        )
    return sorted(normalized, key=lambda x: (x["scheduledAt"], x["id"]))


def build_events(matches: list[dict[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for row in matches:
        grouped.setdefault(row["eventId"], []).append(row)

    events: list[dict[str, Any]] = []
    for event_id, rows in grouped.items():
        name = rows[0]["eventName"]
        slug = rows[0]["eventSlug"]
        event_matches = []
        for row in rows:
            event_matches.append(
                {
                    "id": row["id"],
                    "providerMatchId": row["providerMatchId"],
                    "slug": row["slug"],
                    "scheduledAt": row["scheduledAt"],
                    "state": row["state"],
                    "bestOf": row["bestOf"],
                    "stage": row["stage"],
                    "sourceUrl": row["sourceUrl"],
                    "teams": row["teams"],
                }
            )
        events.append(
            {
                "id": event_id,
                "name": name,
                "slug": slug,
                "source": "RFT.gg public event page",
                "sourceUrl": f"https://rft.gg/event/{slug}",
                "matches": event_matches,
            }
        )
    return sorted(events, key=lambda x: (x["name"].lower(), x["id"]))


def main() -> int:
    all_rows: list[dict[str, Any]] = []
    for url in SOURCE_PAGES:
        raw = fetch(url)
        rows = candidate_rows(raw)
        print(f"{url}: extracted {len(rows)} candidate rows", file=sys.stderr)
        all_rows.extend(rows)

    normalized = normalize_rows(all_rows)
    if not normalized:
        raise RuntimeError("provider returned no parseable matches; refusing to replace existing mirror")
    events = build_events(normalized)
    if not events:
        raise RuntimeError("provider returned matches but no event identities; refusing to write mirror")

    previous: dict[str, Any] = {}
    if OUTPUT.exists():
        try:
            previous = json.loads(OUTPUT.read_text(encoding="utf-8"))
        except Exception:
            previous = {}

    same_payload = previous.get("events") == events and previous.get("sourcePages") == list(SOURCE_PAGES)
    generated_at = previous.get("generatedAt") if same_payload else None
    if not generated_at:
        generated_at = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")

    root = {
        "schemaVersion": 1,
        "generatedAt": generated_at,
        "provider": "RFT.gg",
        "sourcePages": list(SOURCE_PAGES),
        "policy": "Only server-rendered provider facts are mirrored; unknown fields remain unknown and parse failure never creates synthetic data.",
        "events": events,
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(root, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUTPUT}: {len(events)} events / {len(normalized)} matches", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
