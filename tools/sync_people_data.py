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
USER_AGENT = "RiftLab-PeopleSync/1.0 (+https://github.com/xbu080675-creator/Rlftlab)"


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
        key = f"{team_code}|{token(alias)}"
        if key in lookup:
            return str(lookup[key])
    return stable_person_id(str(row.get("name", "")), str(row.get("realName", "")), team_code)


def employment_key(team: str, role: str) -> tuple[str, str]:
    return team.upper(), role.upper()


def upsert_employment(person: dict[str, Any], team_code: str, row: dict[str, Any], current: bool) -> None:
    role = str(row.get("role", row.get("formerRole", ""))).strip()
    if not role:
        return
    entries = person.setdefault("employments", [])
    key = employment_key(team_code, role)
    match = next(
        (
            item for item in entries
            if employment_key(str(item.get("team", "")), str(item.get("role", ""))) == key
        ),
        None,
    )
    source = str(row.get("source", "")).strip()
    display_role = str(row.get("displayRole", "")).strip()
    since = str(row.get("since", row.get("from", ""))).strip()
    until = str(row.get("until", row.get("to", ""))).strip()
    if match is None:
        match = {
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


def avatar_from_escharts(person: dict[str, Any]) -> dict[str, Any] | None:
    name = str(person.get("displayName", "")).strip()
    real_name = str(person.get("realName", "")).strip()
    team = best_current_team(person)
    terms = [name]
    latin_real = re.sub(r"[（(].*?[）)]", "", real_name).strip()
    if latin_real and latin_real.lower() != name.lower():
        terms.append(latin_real)
    if team:
        terms.append(team)
    query = 'site:escharts.com/players ' + " ".join(f'"{term}"' for term in terms if term)
    rss = fetch_text(BING_RSS.format(urllib.parse.quote_plus(query)))
    candidates = parse_rss_results(rss)
    for title, link, desc in candidates[:5]:
        if "escharts.com/players/" not in link.lower():
            continue
        merged = f"{title} {desc}".lower()
        if name and name.lower() not in merged and token(name) not in token(merged):
            continue
        try:
            page = fetch_text(link, timeout=12)
        except Exception:
            continue
        plain = clean(re.sub(r"<[^>]+>", " ", page))
        if name and name.lower() not in plain.lower():
            continue
        if latin_real and token(latin_real) not in token(plain):
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
            "priority": 40,
        }
    return None


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
    before = json.dumps(people, ensure_ascii=False, sort_keys=True)
    people = build_people(profiles, people)

    attempts = 0
    if not args.skip_avatar:
        for pid, person in sorted(people.get("people", {}).items()):
            avatar = person.get("avatar") or {}
            if avatar.get("url"):
                continue
            probe = person.setdefault("avatarProbe", {})
            previous_attempts = int(probe.get("attempts", 0) or 0)
            if previous_attempts >= 4:
                continue
            if attempts >= max(0, args.max_avatar_lookups):
                break
            attempts += 1
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

    people["updatedAt"] = now_iso()
    validate(people)
    after = json.dumps(people, ensure_ascii=False, sort_keys=True)
    if before != after or not PEOPLE_PATH.exists():
        dump_json(PEOPLE_PATH, people)
        print(f"people_changed=true people={len(people.get('people', {}))} avatar_attempts={attempts}")
    else:
        print(f"people_changed=false people={len(people.get('people', {}))} avatar_attempts={attempts}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
