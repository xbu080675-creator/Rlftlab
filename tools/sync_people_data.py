#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import html
import json
import re
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data" / "lpl"
PROFILES_PATH = DATA / "team_profiles.json"
PEOPLE_PATH = DATA / "people.json"
BING_RSS = "https://www.bing.com/search?format=rss&q={}"
USER_AGENT = "RiftLab-PeopleSync/1.1 (+https://github.com/xbu080675-creator/Rlftlab)"

AVATAR_PRIORITY = {
    "TEAM_OFFICIAL": 100,
    "VERIFIED_SOCIAL": 90,
    "RIFTLAB_MIRROR": 80,
    "ESPORTS_CHARTS": 40,
}


def now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def today() -> str:
    return dt.datetime.now(dt.timezone.utc).date().isoformat()


def load_json(path: Path, default: dict[str, Any]) -> dict[str, Any]:
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def dump_json(path: Path, value: dict[str, Any]) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def token(value: str) -> str:
    return "".join(ch for ch in (value or "").upper() if ch.isalnum())


def clean(value: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(value or "")).strip()


def fetch_text(url: str, timeout: int = 12) -> str:
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "text/html,application/xhtml+xml,application/xml,application/rss+xml;q=0.9,*/*;q=0.7",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return response.read().decode("utf-8", errors="replace")


def stable_person_id(name: str, real_name: str, team_code: str) -> str:
    basis = token(real_name) or f"{team_code}|{token(name)}"
    return "p_" + hashlib.sha1(basis.encode("utf-8")).hexdigest()[:12]


def aliases_for(row: dict[str, Any]) -> list[str]:
    values = [str(row.get("name", "")).strip(), str(row.get("realName", "")).strip()]
    result: list[str] = []
    for value in values:
        if not value:
            continue
        result.append(value)
        for chunk in re.findall(r"[A-Za-z][A-Za-z .'-]{1,40}|[\u4e00-\u9fff]{2,10}", value):
            chunk = clean(chunk).strip("()（）")
            if len(chunk) >= 2:
                result.append(chunk)
    return list(dict.fromkeys(result))


def lookup_person_id(people: dict[str, Any], team_code: str, row: dict[str, Any]) -> str:
    lookup = people.setdefault("lookup", {})
    for alias in aliases_for(row):
        team_key = f"{team_code}|{token(alias)}"
        global_key = f"*|{token(alias)}"
        if team_key in lookup:
            return str(lookup[team_key])
        if global_key in lookup:
            return str(lookup[global_key])
    return stable_person_id(str(row.get("name", "")), str(row.get("realName", "")), team_code)


def employment_base_key(team: str, role: str, current: bool) -> tuple[str, str, bool]:
    return team.upper(), role.upper(), bool(current)


def stint_id(team: str, role: str, current: bool, since: str, until: str, source: str) -> str:
    raw = f"{team.upper()}|{role.upper()}|{int(current)}|{since}|{until}|{source}".encode("utf-8")
    return "st_" + hashlib.sha1(raw).hexdigest()[:12]


def upsert_employment(person: dict[str, Any], team_code: str, row: dict[str, Any], current: bool) -> None:
    role = str(row.get("role", row.get("formerRole", ""))).strip()
    if not role:
        return
    entries = person.setdefault("employments", [])
    source = str(row.get("source", "")).strip()
    display_role = str(row.get("displayRole", "")).strip()
    since = str(row.get("since", row.get("from", row.get("startDate", "")))).strip()
    until = str(row.get("until", row.get("to", row.get("endDate", "")))).strip()
    key = employment_base_key(team_code, role, current)

    candidates = [
        item for item in entries
        if employment_base_key(
            str(item.get("team", "")),
            str(item.get("role", "")),
            bool(item.get("current", False)),
        ) == key
    ]

    match: dict[str, Any] | None = None
    if current:
        # One current stint for the same team/role is updated in-place. A historical
        # stint with the same role is deliberately a different row.
        match = candidates[0] if candidates else None
    else:
        # Repeated historical stints are kept separately whenever dates or source
        # distinguish them. This is important for people who leave and later return.
        for item in candidates:
            item_since = str(item.get("startDate", "")).strip()
            item_until = str(item.get("endDate", "")).strip()
            item_source = str(item.get("source", "")).strip()
            if since or until:
                if item_since == since and item_until == until:
                    match = item
                    break
            elif source and item_source == source:
                match = item
                break
        if match is None and not since and not until and not source and len(candidates) == 1:
            match = candidates[0]

    if match is None:
        match = {
            "stintId": stint_id(team_code, role, current, since, "" if current else until, source),
            "team": team_code,
            "role": role,
            "displayRole": display_role,
            "current": current,
            "startDate": since,
            "endDate": "" if current else until,
            "source": source,
        }
        entries.append(match)
    else:
        match.setdefault("stintId", stint_id(team_code, role, current, since, "" if current else until, source))
        match["displayRole"] = display_role or str(match.get("displayRole", ""))
        match["current"] = current
        if since:
            match["startDate"] = since
        if current:
            match["endDate"] = ""
        elif until:
            match["endDate"] = until
        if source:
            match["source"] = source


def best_current_team(person: dict[str, Any]) -> str:
    current = [item for item in person.get("employments", []) if item.get("current")]
    return str(current[0].get("team", "")) if current else ""


def parse_rss_results(xml_text: str) -> list[tuple[str, str, str]]:
    root = ET.fromstring(xml_text)
    results: list[tuple[str, str, str]] = []
    for item in root.findall("./channel/item"):
        title = clean(item.findtext("title") or "")
        link = (item.findtext("link") or "").strip()
        desc = clean(re.sub(r"<[^>]+>", " ", item.findtext("description") or ""))
        results.append((title, link, desc))
    return results


def page_matches_person(page: str, name: str, latin_real: str) -> bool:
    plain = clean(re.sub(r"<[^>]+>", " ", page))
    name_token = token(name)
    real_token = token(latin_real)
    name_hit = bool(name_token and name_token in token(plain))
    real_hit = bool(real_token and real_token in token(plain))
    # Short/generic handles such as May, Ben, River need real-name confirmation.
    if len(name_token) <= 4 and real_token:
        return name_hit and real_hit
    if real_token:
        return name_hit or real_hit
    return name_hit


def avatar_search_queries(person: dict[str, Any]) -> list[str]:
    name = str(person.get("displayName", "")).strip()
    real_name = str(person.get("realName", "")).strip()
    team = best_current_team(person)
    latin_real = re.sub(r"[（(].*?[）)]", "", real_name).strip()
    queries: list[str] = []
    if name:
        queries.append(f'site:escharts.com/players "{name}"')
        if team:
            queries.append(f'site:escharts.com/players "{name}" {team}')
    if latin_real and latin_real.lower() != name.lower():
        queries.append(f'site:escharts.com/players "{latin_real}"')
    return list(dict.fromkeys(queries))


def avatar_from_escharts(person: dict[str, Any]) -> dict[str, Any] | None:
    name = str(person.get("displayName", "")).strip()
    real_name = str(person.get("realName", "")).strip()
    latin_real = re.sub(r"[（(].*?[）)]", "", real_name).strip()

    candidates: list[tuple[str, str, str]] = []
    seen_links: set[str] = set()
    for query in avatar_search_queries(person):
        try:
            rss = fetch_text(BING_RSS.format(urllib.parse.quote_plus(query)))
        except Exception:
            continue
        for candidate in parse_rss_results(rss)[:8]:
            if candidate[1] not in seen_links:
                seen_links.add(candidate[1])
                candidates.append(candidate)

    for title, link, desc in candidates:
        if "escharts.com/players/" not in link.lower():
            continue
        merged = f"{title} {desc}"
        if name and token(name) not in token(merged) and latin_real and token(latin_real) not in token(merged):
            continue
        try:
            page = fetch_text(link, timeout=12)
        except Exception:
            continue
        if not page_matches_person(page, name, latin_real):
            continue
        image = ""
        for pattern in (
            r'<meta[^>]+property=["\']og:image["\'][^>]+content=["\']([^"\']+)',
            r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+property=["\']og:image["\']',
            r'(https://cdnr\.escharts\.com/uploads/public/[^"\'<> ]+)',
        ):
            match = re.search(pattern, page, flags=re.I)
            if match:
                image = html.unescape(match.group(1)).replace("\\u0026", "&")
                break
        if not image.startswith("http"):
            continue
        lowered = image.lower()
        if any(bad in lowered for bad in ("logo", "game-default", "placeholder", "avatar-default")):
            continue
        return {
            "url": image,
            "source": "ESPORTS_CHARTS",
            "sourceUrl": link,
            "verifiedAt": today(),
            "priority": AVATAR_PRIORITY["ESPORTS_CHARTS"],
        }
    return None


def maybe_take_row_avatar(person: dict[str, Any], row: dict[str, Any]) -> None:
    image = str(row.get("imageUrl", row.get("avatarUrl", ""))).strip()
    if not image.startswith("http"):
        return
    source = str(row.get("avatarSource", row.get("imageSource", "TEAM_OFFICIAL"))).strip().upper() or "TEAM_OFFICIAL"
    source_url = str(row.get("avatarSourceUrl", row.get("sourceUrl", row.get("source", "")))).strip()
    priority = AVATAR_PRIORITY.get(source, 70)
    current = person.get("avatar") or {}
    current_priority = int(current.get("priority", 0) or 0)
    if current.get("url") and current_priority > priority:
        return
    person["avatar"] = {
        "url": image,
        "source": source,
        "sourceUrl": source_url,
        "verifiedAt": today(),
        "priority": priority,
    }


def build_people(profiles: dict[str, Any], people: dict[str, Any]) -> dict[str, Any]:
    people.setdefault("schemaVersion", 1)
    people.setdefault("dataset", "riftlab-lpl-person-directory")
    people.setdefault(
        "avatarPolicy",
        {
            "priority": ["TEAM_OFFICIAL", "VERIFIED_SOCIAL", "RIFTLAB_MIRROR", "ESPORTS_CHARTS"],
            "note": "Only store remote references and provenance; do not copy third-party image files into the repository.",
        },
    )
    people_map = people.setdefault("people", {})
    teams = profiles.get("teams", {})

    for team_code, team in teams.items():
        for bucket in ("management", "staff"):
            for row in team.get(bucket, []) or []:
                name = str(row.get("name", "")).strip()
                role = str(row.get("role", "")).strip()
                if not name or not role:
                    continue
                pid = lookup_person_id(people, team_code, row)
                person = people_map.setdefault(
                    pid,
                    {
                        "displayName": name,
                        "realName": str(row.get("realName", "")),
                        "aliases": [],
                        "avatar": {},
                        "employments": [],
                    },
                )
                person["displayName"] = name
                if row.get("realName"):
                    person["realName"] = str(row.get("realName"))
                person["aliases"] = list(dict.fromkeys(list(person.get("aliases", [])) + aliases_for(row)))
                maybe_take_row_avatar(person, row)
                upsert_employment(person, team_code, row, current=True)

        for row in team.get("history", []) or []:
            name = str(row.get("name", "")).strip()
            role = str(row.get("role", row.get("formerRole", ""))).strip()
            if not name or not role:
                continue
            pid = lookup_person_id(people, team_code, row)
            person = people_map.setdefault(
                pid,
                {
                    "displayName": name,
                    "realName": str(row.get("realName", "")),
                    "aliases": [],
                    "avatar": {},
                    "employments": [],
                },
            )
            person["aliases"] = list(dict.fromkeys(list(person.get("aliases", [])) + aliases_for(row)))
            maybe_take_row_avatar(person, row)
            upsert_employment(person, team_code, row, current=False)

    lookup: dict[str, str] = {}
    global_candidates: dict[str, set[str]] = {}
    for pid, person in people_map.items():
        aliases = list(dict.fromkeys([person.get("displayName", ""), person.get("realName", "")] + list(person.get("aliases", []))))
        teams_for_person = {str(item.get("team", "")).upper() for item in person.get("employments", []) if item.get("team")}
        for alias in aliases:
            t = token(str(alias))
            if not t:
                continue
            for team_code in teams_for_person:
                lookup[f"{team_code}|{t}"] = pid
            global_candidates.setdefault(t, set()).add(pid)
    for t, ids in global_candidates.items():
        if len(ids) == 1:
            lookup[f"*|{t}"] = next(iter(ids))
    people["lookup"] = dict(sorted(lookup.items()))
    return people


def validate(people: dict[str, Any]) -> None:
    if people.get("schemaVersion") != 1:
        raise ValueError("people schemaVersion must be 1")
    if not isinstance(people.get("people"), dict):
        raise ValueError("people map missing")
    for pid, person in people["people"].items():
        if not str(pid).startswith("p_"):
            raise ValueError(f"invalid person id {pid}")
        if not str(person.get("displayName", "")).strip():
            raise ValueError(f"{pid} missing displayName")
        for item in person.get("employments", []):
            if not str(item.get("team", "")).strip() or not str(item.get("role", "")).strip():
                raise ValueError(f"{pid} invalid employment")


def unresolved_sort_key(entry: tuple[str, dict[str, Any]]) -> tuple[int, str, str]:
    _, person = entry
    probe = person.get("avatarProbe") or {}
    attempts = int(probe.get("attempts", 0) or 0)
    last = str(probe.get("lastAttemptAt", ""))
    return attempts, last, str(person.get("displayName", "")).lower()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-avatar", action="store_true")
    parser.add_argument("--max-avatar-lookups", type=int, default=8)
    args = parser.parse_args()

    profiles = load_json(PROFILES_PATH, {})
    people = load_json(
        PEOPLE_PATH,
        {
            "schemaVersion": 1,
            "dataset": "riftlab-lpl-person-directory",
            "updatedAt": "",
            "avatarPolicy": {},
            "lookup": {},
            "people": {},
        },
    )

    before_core = json.dumps({k: v for k, v in people.items() if k != "updatedAt"}, ensure_ascii=False, sort_keys=True)
    people = build_people(profiles, people)

    attempts = 0
    if not args.skip_avatar:
        unresolved = [
            item for item in people.get("people", {}).items()
            if not (item[1].get("avatar") or {}).get("url")
            and int((item[1].get("avatarProbe") or {}).get("attempts", 0) or 0) < 4
        ]
        for pid, person in sorted(unresolved, key=unresolved_sort_key):
            if attempts >= max(0, args.max_avatar_lookups):
                break
            attempts += 1
            probe = person.setdefault("avatarProbe", {})
            previous_attempts = int(probe.get("attempts", 0) or 0)
            probe["lastAttemptAt"] = now_iso()
            probe["attempts"] = previous_attempts + 1
            try:
                resolved = avatar_from_escharts(person)
            except Exception as exc:
                probe["lastError"] = str(exc)[:160]
                continue
            if resolved:
                person["avatar"] = resolved
                probe["resolved"] = True
                probe.pop("lastError", None)
                print(f"[avatar] {pid} {person.get('displayName')} <- {resolved['sourceUrl']}")

    after_core = json.dumps({k: v for k, v in people.items() if k != "updatedAt"}, ensure_ascii=False, sort_keys=True)
    changed = before_core != after_core or not PEOPLE_PATH.exists()
    if changed:
        people["updatedAt"] = now_iso()

    validate(people)
    if changed:
        dump_json(PEOPLE_PATH, people)
        print(f"people_changed=true people={len(people.get('people', {}))} avatar_attempts={attempts}")
    else:
        print(f"people_changed=false people={len(people.get('people', {}))} avatar_attempts={attempts}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
