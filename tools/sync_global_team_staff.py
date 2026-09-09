#!/usr/bin/env python3
import csv
import io
import json
import sys
import urllib.request
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

SHEET_ID = "1Y7k5kQ2AegbuyiGwEPsa62e883FYVtHqr6UVut9RC4o"
# Riot's public LoL League-Recognized Contract Database tabs.
TABS = {
    "AME": "0",
    "CN": "594163931",
    "EMEA": "148326031",
    "KR": "905624073",
    "LCP": "1177719586",
}
OUT = Path("data/global/team_staff.json")
SOURCE = "Riot LoL Global Contract Database (GCD)"


def token(value: str) -> str:
    return "".join(ch for ch in str(value).upper() if ch.isalnum())


def fetch_csv(gid: str):
    url = (
        f"https://docs.google.com/spreadsheets/u/1/d/{SHEET_ID}/pub"
        f"?gid={gid}&single=true&output=csv"
    )
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "RiftLab-RiotGCDMirror/1.0",
            "Accept": "text/csv,text/plain,*/*",
        },
    )
    with urllib.request.urlopen(req, timeout=25) as response:
        text = response.read().decode("utf-8-sig", "replace")
    return list(csv.reader(io.StringIO(text)))


def find_header(rows):
    for i, row in enumerate(rows[:20]):
        keys = [token(x) for x in row]
        if "TEAM" in keys and any("SUMMONER" in x for x in keys):
            return i, row
    raise RuntimeError("GCD header row not found")


def col_index(header, *needles):
    keys = [token(x) for x in header]
    for needle in needles:
        n = token(needle)
        for i, key in enumerate(keys):
            if key == n or n in key:
                return i
    return -1


def cell(row, idx):
    return row[idx].strip() if idx >= 0 and idx < len(row) else ""


def normalize_role(raw: str):
    key = token(raw)
    if not key:
        return ""
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
    return ""


def add_unique(bucket, item):
    key = (token(item.get("name", "")), token(item.get("role", "")))
    if key[0] and not any((token(x.get("name", "")), token(x.get("role", ""))) == key for x in bucket):
        bucket.append(item)


def parse_tab(region, rows, teams):
    header_i, header = find_header(rows)
    team_i = col_index(header, "Team")
    summoner_i = col_index(header, "Official Summoner Name", "Summoner Name")
    first_i = col_index(header, "Legal First Name", "First Name")
    last_i = col_index(header, "Legal Family Name", "Family Name", "Last Name")
    role_i = col_index(header, "Role")
    contact_i = col_index(header, "Team Contact Information", "Team Contact")

    current_team = ""
    staff_section = False
    parsed_staff = 0
    parsed_contacts = 0

    for row in rows[header_i + 1:]:
        if not any(str(x).strip() for x in row):
            # The KR sheet has no Role column. Riot separates each team's player rows from its
            # coaching-staff rows with a blank line; a later team change resets this flag.
            if region == "KR" and current_team:
                staff_section = True
            continue

        team_name = cell(row, team_i)
        if not team_name:
            continue
        if token(team_name) != token(current_team):
            current_team = team_name
            staff_section = False

        summoner = cell(row, summoner_i)
        legal_name = " ".join(x for x in (cell(row, first_i), cell(row, last_i)) if x).strip()
        raw_role = cell(row, role_i)
        role = normalize_role(raw_role)

        # Published GCD tabs include a short team code in a trailing helper column even where the
        # header is blank. Keep it as an alias when it looks like a tricode/short code.
        short = ""
        for value in reversed(row):
            value = value.strip()
            if value and value not in {team_name, summoner, legal_name} and 2 <= len(value) <= 8 and "@" not in value:
                if value.upper() == value or value.isalnum():
                    short = value
                    break

        team_key = token(team_name)
        if not team_key:
            continue
        node = teams[team_key]
        node["name"] = team_name
        if short and not node.get("short"):
            node["short"] = short
        node["aliases"] = sorted({x for x in node.get("aliases", []) + [team_name, short] if x})
        node["regions"] = sorted(set(node.get("regions", []) + [region]))

        contact = cell(row, contact_i)
        if contact and "@" in contact:
            add_unique(
                node["management"],
                {
                    "name": f"{short or team_name} 官方战队联系人",
                    "realName": contact,
                    "role": "TEAM_CONTACT",
                    "source": SOURCE,
                },
            )
            parsed_contacts += 1

        is_staff = bool(role)
        if region == "KR" and role_i < 0:
            is_staff = staff_section and bool(summoner)
            if is_staff:
                # KR GCD intentionally has no Role column. Preserve only the fact Riot registers
                # the person as coaching staff; do not guess Head/Assistant Coach.
                role = "COACHING_STAFF"

        if not is_staff or not summoner:
            continue
        add_unique(
            node["staff"],
            {
                "name": summoner,
                "realName": legal_name if legal_name and token(legal_name) != token(summoner) else "",
                "role": role or "COACHING_STAFF",
                "source": SOURCE,
            },
        )
        parsed_staff += 1

    return parsed_staff, parsed_contacts


def main():
    teams = defaultdict(lambda: {
        "name": "",
        "short": "",
        "page": "",
        "aliases": [],
        "regions": [],
        "management": [],
        "staff": [],
    })
    total_staff = 0
    total_contacts = 0
    failures = []

    for region, gid in TABS.items():
        try:
            rows = fetch_csv(gid)
            staff, contacts = parse_tab(region, rows, teams)
            total_staff += staff
            total_contacts += contacts
            print(f"{region}: rows={len(rows)} staff={staff} contacts={contacts}")
        except Exception as exc:
            failures.append(f"{region}: {exc}")
            print(f"WARN {region}: {exc}")

    useful = {
        key: value
        for key, value in teams.items()
        if value["staff"] or value["management"]
    }
    if not useful:
        print("Riot GCD sync returned no staff/contact rows; keeping previous mirror")
        if failures:
            print("; ".join(failures))
        return 0

    payload = {
        "schemaVersion": 2,
        "updatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "source": SOURCE,
        "officialSource": "https://competitiveops.riotgames.com/en-US/league-of-legends",
        "teams": dict(sorted(useful.items())),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(useful)} teams / {total_staff} coaching rows / {total_contacts} team contacts")
    if failures:
        print("partial sync warnings: " + "; ".join(failures))
    return 0


if __name__ == "__main__":
    sys.exit(main())
