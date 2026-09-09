#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import html
import json
import re
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data" / "lpl"
PROFILES_PATH = DATA / "team_profiles.json"
REGISTRY_PATH = DATA / "source_registry.json"
WATCH_PATH = DATA / "team_watch_state.json"

BING_RSS = "https://www.bing.com/search?format=rss&q={}"
LEAGUEPEDIA_CARGO = "https://lol.fandom.com/api.php"
USER_AGENT = "RiftLab-TeamSync/1.0 (+https://github.com/xbu080675-creator/Rlftlab)"

ROLE_MAP = {
    "主教练": "HEAD_COACH",
    "助理教练": "ASSISTANT_COACH",
    "教练": "COACH",
    "分析师": "ANALYST",
    "经理": "MANAGER",
    "总经理": "GENERAL_MANAGER",
    "领队": "LEADER",
    "监督": "SUPERVISOR",
    "董事长": "CHAIRMAN",
    "电竞负责人": "HEAD_OF_ESPORTS",
    "赛训总监": "ESPORTS_DIRECTOR",
    "电竞总经理": "ESPORTS_GENERAL_MANAGER",
    "赛训总经理": "ESPORTS_GENERAL_MANAGER",
}
MANAGEMENT_ROLES = {
    "MANAGER", "GENERAL_MANAGER", "LEADER", "SUPERVISOR", "CHAIRMAN",
    "HEAD_OF_ESPORTS", "ESPORTS_DIRECTOR", "CEO", "COO", "OWNER", "CO_OWNER",
    "MANAGING_DIRECTOR", "VICE_PRESIDENT", "DEPUTY_MANAGER", "FOUNDER", "FOUNDER_AND_CEO",
}
LEAGUEPEDIA_STAFF_ROLES = {
    "head coach": "HEAD_COACH",
    "coach": "COACH",
    "assistant coach": "ASSISTANT_COACH",
    "strategic coach": "STRATEGIC_COACH",
    "analyst": "ANALYST",
    "head analyst": "ANALYST",
    "performance coach": "COACH",
}
DEPARTURE_WORDS = ("离队", "离任", "转会至", "不再担任", "结束任职", "合同到期离开")
# Current-management removal/archive is allowed only when one of these explicit departure phrases is present.
JOIN_WORDS = ("加入", "加盟", "担任", "出任", "正式成为", "大名单", "新赛段名单", "新赛季名单")
IMPORTANT_WORDS = (
    "人员变动公告", "大名单", "离队", "加入", "加盟", "转会", "主教练", "经理", "领队",
    "监督", "董事长", "重组", "收购", "运营主体", "运营方", "旗下", "更名"
)


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def dump_json(path: Path, value: dict[str, Any]) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def today() -> str:
    return dt.datetime.now(dt.timezone.utc).date().isoformat()


def fetch_text(url: str, timeout: int = 12) -> str:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "application/json,text/xml,application/rss+xml,text/html;q=0.8,*/*;q=0.5",
        },
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read().decode("utf-8", errors="replace")


def clean_html(value: str) -> str:
    value = re.sub(r"<[^>]+>", " ", value or "")
    value = html.unescape(value)
    return re.sub(r"\s+", " ", value).strip()


def event_id(code: str, link: str, title: str) -> str:
    payload = f"{code}|{link}|{title}".encode("utf-8")
    return hashlib.sha256(payload).hexdigest()[:24]


def official_search(code: str, cfg: dict[str, Any]) -> list[dict[str, str]]:
    official_name = cfg.get("officialName", code)
    query = f'site:weibo.com "{official_name}" ("人员变动公告" OR "大名单" OR "离队" OR "加入" OR "转会" OR "重组" OR "收购" OR "运营主体")'
    url = BING_RSS.format(urllib.parse.quote_plus(query))
    xml_text = fetch_text(url)
    root = ET.fromstring(xml_text)
    items: list[dict[str, str]] = []
    for item in root.findall("./channel/item")[:10]:
        title = clean_html(item.findtext("title") or "")
        link = (item.findtext("link") or "").strip()
        description = clean_html(item.findtext("description") or "")
        published = clean_html(item.findtext("pubDate") or "")
        merged = f"{title} {description}"
        if "weibo.com" not in link.lower():
            continue
        if official_name.lower() not in merged.lower() and not any(
            marker.lower() in link.lower() or marker.lower() in merged.lower()
            for marker in cfg.get("accountMarkers", [])
        ):
            continue
        if not any(word in merged for word in IMPORTANT_WORDS):
            continue
        items.append({
            "id": event_id(code, link, title),
            "title": title,
            "link": link,
            "description": description,
            "published": published,
        })
    return items


def cargo_staff(cfg: dict[str, Any]) -> list[dict[str, str]]:
    team_name = cfg.get("leaguepediaTeam", "").strip()
    if not team_name:
        return []
    params = {
        "action": "cargoquery",
        "format": "json",
        "tables": "ListplayerCurrent",
        "fields": "ID,Name,Role,Team",
        "where": f'Team="{team_name.replace(chr(34), chr(92)+chr(34))}"',
        "limit": "50",
    }
    url = LEAGUEPEDIA_CARGO + "?" + urllib.parse.urlencode(params)
    payload = json.loads(fetch_text(url, timeout=15))
    rows = payload.get("cargoquery", [])
    parsed: list[dict[str, str]] = []
    for row in rows:
        title = row.get("title", {}) if isinstance(row, dict) else {}
        role_raw = str(title.get("Role", "")).strip()
        role = LEAGUEPEDIA_STAFF_ROLES.get(role_raw.lower())
        if not role:
            continue
        name = str(title.get("ID", "")).strip() or str(title.get("Name", "")).strip()
        real_name = str(title.get("Name", "")).strip()
        if not name:
            continue
        parsed.append({
            "name": name,
            "role": role,
            "realName": real_name,
            "source": "Leaguepedia Cargo · auto-sync",
        })
    dedup: dict[tuple[str, str], dict[str, str]] = {}
    for row in parsed:
        dedup[(row["name"].lower(), row["role"])] = row
    return list(dedup.values())


def extract_role_people(text: str) -> list[tuple[str, str, str]]:
    found: list[tuple[str, str, str]] = []
    role_group = "|".join(sorted(map(re.escape, ROLE_MAP), key=len, reverse=True))
    patterns = [
        re.compile(rf"(?P<role>{role_group})\s*[：:]\s*(?P<name>[A-Za-z0-9_.-]{{2,28}}|[\u4e00-\u9fff]{{2,8}})(?:\s*[（(](?P<real>[^）)]{{2,30}})[）)])?"),
        re.compile(rf"(?P<role>{role_group})\s*(?P<name>[A-Za-z][A-Za-z0-9_.-]{{1,27}})\s*[（(]?(?P<real>[\u4e00-\u9fff]{{2,8}})?[）)]?"),
    ]
    seen: set[tuple[str, str]] = set()
    for pattern in patterns:
        for match in pattern.finditer(text):
            cn_role = match.group("role")
            role = ROLE_MAP[cn_role]
            name = match.group("name").strip(" .,:：")
            real = (match.groupdict().get("real") or "").strip()
            key = (name.lower(), role)
            if len(name) < 2 or key in seen:
                continue
            seen.add(key)
            found.append((role, name, real))
    return found


def contains_identity(text: str, row: dict[str, Any]) -> bool:
    name = str(row.get("name", "")).strip()
    real_name = str(row.get("realName", "")).strip()
    if name and name.lower() in text.lower():
        return True
    chinese = "".join(re.findall(r"[\u4e00-\u9fff]{2,8}", real_name))
    return bool(chinese and chinese in text)


def _operator_name(value: str) -> str:
    value = clean_html(value).strip(" ，。；;：:（）()[]【】")
    value = re.sub(r"(?:电子竞技俱乐部|电竞俱乐部)$", "", value).strip()
    return value[:40]


def extract_operators(text: str) -> list[str]:
    found: list[str] = []
    patterns = [
        re.compile(r"运营主体(?:变更为|为|[:：])\s*(?P<op>[^，。；;]{2,30})"),
        re.compile(r"运营方(?:变更为|为|[:：])\s*(?P<op>[^，。；;]{2,30})"),
        re.compile(r"由(?P<op>[\u4e00-\u9fffA-Za-z0-9·]{2,24})(?:负责)?运营"),
        re.compile(r"(?:正式)?被(?P<op>[\u4e00-\u9fffA-Za-z0-9·]{2,24})(?:完成)?收购"),
        re.compile(r"成为(?P<op>[\u4e00-\u9fffA-Za-z0-9·]{2,24})旗下"),
    ]
    for pattern in patterns:
        for match in pattern.finditer(text):
            name = _operator_name(match.group("op"))
            if len(name) >= 2:
                found.append(name)

    reorg = re.search(
        r"与(?P<a>[\u4e00-\u9fffA-Za-z0-9·]{2,20}?)(?:及关联方)?、(?P<b>[\u4e00-\u9fffA-Za-z0-9·]{2,20}?)(?:就|共同).*?重组",
        text,
    )
    if reorg:
        for key in ("a", "b"):
            name = _operator_name(reorg.group(key))
            if len(name) >= 2:
                found.append(name)
    return list(dict.fromkeys(found))


def archive_management(team: dict[str, Any], row: dict[str, Any], item: dict[str, str]) -> None:
    history = team.setdefault("history", [])
    name = str(row.get("name", "")).strip()
    role = str(row.get("role", "")).strip()
    if not name or not role:
        return
    duplicate = next((old for old in history if str(old.get("name", "")).lower() == name.lower() and str(old.get("role", old.get("formerRole", ""))).upper() == role.upper()), None)
    if duplicate:
        duplicate["current"] = False
        duplicate.setdefault("honoraryTitle", "RiftLab 荣誉成员")
        duplicate["source"] = item.get("link", "")
        return
    history.append({
        "name": name,
        "realName": str(row.get("realName", "")),
        "role": role,
        "displayRole": str(row.get("displayRole", "")),
        "current": False,
        "honoraryTitle": "RiftLab 荣誉成员",
        "source": item.get("link", ""),
        "note": "离任后转入历史荣誉档案",
    })


def apply_official_event(team: dict[str, Any], item: dict[str, str]) -> list[str]:
    text = clean_html(f"{item.get('title','')} {item.get('description','')}")
    changes: list[str] = []
    departure = any(word in text for word in DEPARTURE_WORDS)
    joining = any(word in text for word in JOIN_WORDS)

    if departure:
        for bucket in ("management", "staff"):
            old_rows = list(team.get(bucket, []))
            kept = [row for row in old_rows if not contains_identity(text, row)]
            if len(kept) != len(old_rows):
                removed_rows = [row for row in old_rows if row not in kept]
                team[bucket] = kept
                for row in removed_rows:
                    name = row.get("name", "")
                    if bucket == "management":
                        archive_management(team, row, item)
                        changes.append(f"archive management:{name}")
                    else:
                        changes.append(f"remove {bucket}:{name}")

    extracted = extract_role_people(text)
    if joining and extracted:
        for role, name, real in extracted:
            bucket = "management" if role in MANAGEMENT_ROLES else "staff"
            rows = list(team.get(bucket, []))
            same_name = next((row for row in rows if str(row.get("name", "")).lower() == name.lower()), None)
            if same_name:
                old_role = same_name.get("role", "")
                if old_role == "ESPORTS_DIRECTOR_AND_MANAGER" and role in {"MANAGER", "ESPORTS_DIRECTOR"}:
                    role = old_role
                same_name["role"] = role
                if real and not same_name.get("realName"):
                    same_name["realName"] = real
                same_name["source"] = "官方公告 · auto-sync"
                if old_role != role:
                    changes.append(f"role {name}:{old_role}->{role}")
            else:
                rows.append({
                    "name": name,
                    "role": role,
                    "realName": real,
                    "source": "官方公告 · auto-sync",
                })
                team[bucket] = rows
                changes.append(f"add {bucket}:{name}/{role}")

    operator_names = extract_operators(text)
    if operator_names:
        current_names = [str(row.get("name", "")).strip() for row in team.get("operators", []) if row.get("name")]
        if current_names != operator_names:
            team["operators"] = [{"name": name, "role": "OPERATOR", "source": "官方公告 · auto-sync"} for name in operator_names]
            changes.append("operators:" + " × ".join(operator_names))

    return changes


def same_staff(a: list[dict[str, Any]], b: list[dict[str, Any]]) -> bool:
    def norm(rows: list[dict[str, Any]]) -> list[tuple[str, str, str]]:
        return sorted(
            (
                str(row.get("name", "")).strip().lower(),
                str(row.get("role", "")).strip().upper(),
                str(row.get("realName", "")).strip(),
            )
            for row in rows
            if row.get("name") and row.get("role")
        )
    return norm(a) == norm(b)


def validate_profiles(profiles: dict[str, Any]) -> None:
    if profiles.get("schemaVersion") != 1:
        raise ValueError("team_profiles schemaVersion must be 1")
    teams = profiles.get("teams")
    if not isinstance(teams, dict) or not teams:
        raise ValueError("team_profiles teams missing")
    for code, team in teams.items():
        for bucket in ("management", "staff"):
            rows = team.get(bucket, [])
            if not isinstance(rows, list):
                raise ValueError(f"{code}.{bucket} must be array")
            seen: set[tuple[str, str]] = set()
            for row in rows:
                name = str(row.get("name", "")).strip()
                role = str(row.get("role", "")).strip()
                if not name or not role:
                    raise ValueError(f"{code}.{bucket} contains empty name/role")
                key = (name.lower(), role.upper())
                if key in seen:
                    raise ValueError(f"{code}.{bucket} duplicate {name}/{role}")
                seen.add(key)
        history = team.get("history", [])
        if not isinstance(history, list):
            raise ValueError(f"{code}.history must be array")
        for row in history:
            if not str(row.get("name", "")).strip() or not str(row.get("role", row.get("formerRole", ""))).strip():
                raise ValueError(f"{code}.history contains empty name/role")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--skip-search", action="store_true")
    parser.add_argument("--skip-leaguepedia", action="store_true")
    args = parser.parse_args()

    profiles = load_json(PROFILES_PATH)
    registry = load_json(REGISTRY_PATH)
    watch = load_json(WATCH_PATH)
    now = now_iso()
    initialized = bool(watch.get("initialized"))
    profiles_changed = False
    watch_changed = False
    applied_total: list[str] = []

    teams = profiles.setdefault("teams", {})
    registry_teams = registry.get("teams", {})
    seen_map = watch.setdefault("seen", {})
    events = watch.setdefault("events", [])

    for code, cfg in registry_teams.items():
        team = teams.get(code)
        if team is None:
            continue

        if not args.skip_leaguepedia:
            try:
                current_staff = cargo_staff(cfg)
            except Exception as exc:
                print(f"[warn] {code} Leaguepedia: {exc}")
                current_staff = []
            if current_staff and not same_staff(team.get("staff", []), current_staff):
                team["staff"] = current_staff
                team["verifiedAt"] = today()
                team.setdefault("sources", []).append({
                    "type": "LEAGUEPEDIA_AUTO",
                    "label": "Leaguepedia current staff auto-sync",
                    "url": "https://lol.fandom.com/"
                })
                profiles_changed = True
                applied_total.append(f"{code}: replace staff from Leaguepedia ({len(current_staff)})")

        if args.skip_search:
            continue
        try:
            found = official_search(code, cfg)
        except Exception as exc:
            print(f"[warn] {code} official search: {exc}")
            continue

        code_seen = list(seen_map.get(code, []))
        code_seen_set = set(code_seen)
        if not initialized:
            for item in found:
                if item["id"] not in code_seen_set:
                    code_seen.append(item["id"])
                    code_seen_set.add(item["id"])
                    watch_changed = True
            seen_map[code] = code_seen[-500:]
            continue

        for item in found:
            if item["id"] in code_seen_set:
                continue
            code_seen.append(item["id"])
            code_seen_set.add(item["id"])
            changes = apply_official_event(team, item)
            if changes:
                team["verifiedAt"] = today()
                team.setdefault("sources", []).append({
                    "type": "TEAM_OFFICIAL_AUTO",
                    "label": item["title"][:120],
                    "url": item["link"]
                })
                profiles_changed = True
                applied_total.extend(f"{code}: {change}" for change in changes)
            events.append({
                "id": item["id"],
                "team": code,
                "detectedAt": now,
                "title": item["title"],
                "url": item["link"],
                "published": item.get("published", ""),
                "autoApplied": bool(changes),
                "changes": changes,
                "needsReview": not bool(changes),
            })
            watch_changed = True
        seen_map[code] = code_seen[-500:]

    if not initialized:
        watch["initialized"] = True
        watch["initializedAt"] = now
        watch_changed = True
        print("[info] bootstrap complete: existing search results marked as seen; no historical announcements applied")

    watch["lastRunAt"] = now
    watch["events"] = events[-250:]
    if profiles_changed:
        profiles["updatedAt"] = now

    validate_profiles(profiles)

    if args.dry_run:
        print(json.dumps({
            "profilesChanged": profiles_changed,
            "watchChanged": watch_changed,
            "applied": applied_total,
        }, ensure_ascii=False, indent=2))
        return 0

    if profiles_changed:
        dump_json(PROFILES_PATH, profiles)
    if watch_changed or not WATCH_PATH.exists():
        dump_json(WATCH_PATH, watch)

    for change in applied_total:
        print("[applied]", change)
    print(f"profiles_changed={profiles_changed} watch_changed={watch_changed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
