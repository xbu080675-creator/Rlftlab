#!/usr/bin/env python3
import json
import sys
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

API = "https://lol.fandom.com/api.php"
OUT = Path("data/global/team_staff.json")


def token(value: str) -> str:
    return "".join(ch for ch in value.upper() if ch.isalnum())


def normalized_role(raw: str):
    key = token(raw)
    if not key:
        return None
    if "HEADCOACH" in key:
        return "HEAD_COACH"
    if "ASSISTANTCOACH" in key or "ASSISTCOACH" in key:
        return "ASSISTANT_COACH"
    if "STRATEGICCOACH" in key or "STRATEGYCOACH" in key:
        return "STRATEGIC_COACH"
    if "POSITIONALCOACH" in key:
        return "POSITIONAL_COACH"
    if "COACH" in key:
        return "COACH"
    if "ANALYST" in key:
        return "ANALYST"
    if "GENERALMANAGER" in key:
        return "GENERAL_MANAGER"
    if "ASSISTANTMANAGER" in key:
        return "ASSISTANT_MANAGER"
    if "MANAGER" in key:
        return "MANAGER"
    if key == "LEADER" or "TEAMLEADER" in key:
        return "LEADER"
    if "SUPERVISOR" in key:
        return "SUPERVISOR"
    if "ESPORTSDIRECTOR" in key:
        return "ESPORTS_DIRECTOR"
    if key == "DIRECTOR" or key.endswith("DIRECTOR"):
        return "DIRECTOR"
    if "MANAGINGDIRECTOR" in key:
        return "MANAGING_DIRECTOR"
    if key == "CEO" or "CHIEFEXECUTIVEOFFICER" in key:
        return "CEO"
    if key == "COO" or "CHIEFOPERATINGOFFICER" in key:
        return "COO"
    if "COOWNER" in key:
        return "CO_OWNER"
    if key == "OWNER":
        return "OWNER"
    if "FOUNDER" in key and "CEO" in key:
        return "FOUNDER_AND_CEO"
    if "FOUNDER" in key:
        return "FOUNDER"
    if "HEADOFESPORTS" in key:
        return "HEAD_OF_ESPORTS"
    if "HEADOFLOL" in key or "HEADOFLEAGUEOFLEGENDS" in key:
        return "HEAD_OF_LOL"
    return None


def is_management(role: str) -> bool:
    key = token(role)
    return "MANAGER" in key or key in {
        "LEADER", "SUPERVISOR", "DIRECTOR", "ESPORTSDIRECTOR", "MANAGINGDIRECTOR",
        "CEO", "COO", "OWNER", "COOWNER", "FOUNDER", "FOUNDERANDCEO", "HEADOFESPORTS", "HEADOFLOL"
    }


def fetch_rows():
    where = (
        '(LPC.Role LIKE "%Coach%" OR LPC.Role LIKE "%Manager%" OR '
        'LPC.Role LIKE "%Analyst%" OR LPC.Role LIKE "%Director%" OR '
        'LPC.Role LIKE "%Supervisor%" OR LPC.Role LIKE "%Leader%" OR '
        'LPC.Role LIKE "%Owner%" OR LPC.Role LIKE "%Founder%" OR '
        'LPC.Role="CEO" OR LPC.Role="COO")'
    )
    params = {
        "action": "cargoquery",
        "format": "json",
        "limit": "500",
        "tables": "ListplayerCurrent=LPC,Teams=T",
        "join_on": "LPC.Team=T._pageName",
        "fields": "LPC.ID=ID,LPC.Name=Name,LPC.Role=Role,LPC.Team=Page,T.Name=TeamName,T.Short=Short",
        "where": where,
    }
    req = urllib.request.Request(
        API + "?" + urllib.parse.urlencode(params),
        headers={"User-Agent": "RiftLab-GlobalStaffMirror/1.0", "Accept": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=20) as response:
        root = json.load(response)
    if root.get("error"):
        raise RuntimeError(f"Leaguepedia error: {root['error'].get('code')} {root['error'].get('info', '')}")
    return [item.get("title", item) for item in root.get("cargoquery", [])]


def main():
    try:
        rows = fetch_rows()
    except Exception as exc:
        print(f"global staff sync skipped: {exc}")
        return 0
    if not rows:
        print("global staff sync returned zero rows; keeping previous mirror")
        return 0

    teams = defaultdict(lambda: {"name": "", "short": "", "page": "", "aliases": [], "management": [], "staff": []})
    for row in rows:
        role = normalized_role(str(row.get("Role", "")))
        if not role:
            continue
        page = str(row.get("Page", "")).strip()
        team_name = str(row.get("TeamName", "")).strip() or page
        short = str(row.get("Short", "")).strip()
        key = token(team_name or page or short)
        if not key:
            continue
        node = teams[key]
        node["name"] = team_name
        node["short"] = short
        node["page"] = page
        node["aliases"] = sorted({x for x in (team_name, short, page) if x})
        ident = str(row.get("ID", "")).strip()
        real_name = str(row.get("Name", "")).strip()
        name = ident or real_name
        if not name:
            continue
        person = {
            "name": name,
            "realName": real_name if real_name and real_name.lower() != name.lower() else "",
            "role": role,
            "source": "Leaguepedia ListplayerCurrent mirror",
        }
        bucket = "management" if is_management(role) else "staff"
        if not any(token(p["name"]) == token(name) and token(p["role"]) == token(role) for p in node[bucket]):
            node[bucket].append(person)

    payload = {
        "schemaVersion": 1,
        "updatedAt": datetime.now(timezone.utc).date().isoformat(),
        "source": "Leaguepedia ListplayerCurrent mirror",
        "teams": dict(sorted(teams.items())),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(payload['teams'])} teams / {len(rows)} staff rows")
    return 0


if __name__ == "__main__":
    sys.exit(main())
