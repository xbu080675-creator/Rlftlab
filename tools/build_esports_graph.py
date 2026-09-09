#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = ROOT / "data" / "lpl" / "team_archive.json"
OUT = ROOT / "data" / "esports" / "esports_graph.json"

ORG_ROWS = [
    ("org-all-gamers", "All Gamers", "AG", "CN"),
    ("org-bilibili", "哔哩哔哩 / Bilibili", "Bilibili", "CN"),
    ("org-topsports", "滔搏 / Topsports", "Topsports", "CN"),
    ("org-jd", "京东 / JD.com", "JD", "CN"),
    ("org-lgd", "LGD电子竞技俱乐部 / LGD Gaming", "LGD", "CN"),
    ("org-supergen-edg", "超竞集团 / EDG电子竞技俱乐部", "EDG", "CN"),
    ("org-quwan", "趣丸科技 / TT电竞", "TT电竞", "CN"),
    ("org-yangwang", "氧望体育", "氧望体育", "CN"),
    ("org-huya", "虎牙直播", "虎牙", "CN"),
    ("org-li-ning", "李宁 / Li-Ning", "Li-Ning", "CN"),
    ("org-nip-group", "NIP Group", "NIP Group", "GLOBAL"),
    ("org-weibo", "微博 / Weibo Corporation", "Weibo", "CN"),
    ("org-team-we", "WE电子竞技俱乐部 / Team WE", "Team WE", "CN"),
    ("org-xian-qujiang", "西安曲江", "西安曲江", "CN"),
]

PRIMARY_ORG = {
    "AL": "org-all-gamers", "BLG": "org-bilibili", "TES": "org-topsports", "JDG": "org-jd",
    "LGD": "org-lgd", "EDG": "org-supergen-edg", "TT": "org-quwan", "IG": "org-yangwang",
    "LNG": "org-li-ning", "NIP": "org-nip-group", "WBG": "org-weibo", "WE": "org-team-we",
}

TEAM_NAMES = {
    "AL": "Anyone's Legend", "BLG": "Bilibili Gaming", "TES": "Top Esports", "JDG": "JD Gaming",
    "LGD": "LGD Gaming", "EDG": "EDward Gaming", "TT": "ThunderTalk Gaming", "IG": "Invictus Gaming",
    "LNG": "LNG Esports", "NIP": "Ninjas in Pyjamas.CN", "WBG": "Weibo Gaming", "WE": "Team WE",
}

ORG_LINKS = {
    "AL": [("org-all-gamers", "OWNER_OR_PARENT", "AG / AL organization relationship")],
    "BLG": [("org-bilibili", "OWNER_OR_PARENT", "Bilibili acquired I May and formed BLG")],
    "TES": [("org-topsports", "OWNER_OR_PARENT", "Top Esports public history")],
    "JDG": [("org-jd", "OWNER_OR_PARENT", "JD Gaming public history")],
    "LGD": [("org-lgd", "OPERATOR", "LGD public club record")],
    "EDG": [("org-supergen-edg", "OPERATOR_OR_PARENT", "EDG organization record")],
    "TT": [("org-quwan", "OWNER_OR_OPERATOR", "趣丸集团官网 TT电竞公开信息")],
    "IG": [
        ("org-yangwang", "OPERATOR", "iG 2024-11-30 重组公告"),
        ("org-huya", "STRATEGIC_PARTNER", "iG 2024-11-30 重组公告"),
    ],
    "LNG": [("org-li-ning", "OWNER_OR_PARENT", "LNG public history")],
    "NIP": [("org-nip-group", "OWNER_OR_OPERATOR", "NIP Group official history")],
    "WBG": [("org-weibo", "OWNER_OR_PARENT", "WBG public history and official account")],
    "WE": [
        ("org-team-we", "OPERATOR", "WE官方微博"),
        ("org-xian-qujiang", "CO_BRAND_HOME_PARTNER", "西安曲江WE官方微博命名"),
    ],
}

RESULT_URLS = {
    "AL": "https://liquipedia.net/leagueoflegends/Anyone%27s_Legend/Results",
    "BLG": "https://liquipedia.net/leagueoflegends/Bilibili_Gaming/Results",
    "TES": "https://liquipedia.net/leagueoflegends/Top_Esports/Results",
    "JDG": "https://liquipedia.net/leagueoflegends/JD_Gaming/Results",
    "LGD": "https://liquipedia.net/leagueoflegends/LGD_Gaming/Results",
    "EDG": "https://liquipedia.net/leagueoflegends/EDward_Gaming/Results",
    "TT": "https://liquipedia.net/leagueoflegends/ThunderTalk_Gaming/Results",
    "IG": "https://liquipedia.net/leagueoflegends/Invictus_Gaming/Results",
    "LNG": "https://liquipedia.net/leagueoflegends/LNG_Esports/Results",
    "NIP": "https://liquipedia.net/leagueoflegends/Ninjas_in_Pyjamas",
    "WBG": "https://liquipedia.net/leagueoflegends/Weibo_Gaming/Results",
    "WE": "https://liquipedia.net/leagueoflegends/Team_WE/Results",
}

# code, year, event, label, min, max, tier, stage, is_title
RESULT_ROWS = [
    ("AL",2024,"LPL 2024 Summer","5–6名",5,6,"S","Playoffs",False),
    ("AL",2024,"Demacia Cup 2024","冠军",1,1,"A","Final",True),
    ("AL",2025,"LPL 2025 Split 1","亚军",2,2,"S","Final",False),
    ("AL",2025,"LPL 2025 Split 2","冠军",1,1,"S","Final",True),
    ("AL",2025,"2025 Mid-Season Invitational","季军",3,3,"S","Lower Final",False),
    ("AL",2025,"Esports World Cup 2025","亚军",2,2,"S","Final",False),
    ("AL",2025,"LPL 2025 Split 3","季军",3,3,"S","Lower Final",False),
    ("AL",2025,"2025 World Championship","八强",5,8,"S","Quarterfinal",False),
    ("AL",2026,"LPL 2026 Split 1","第4名",4,4,"S","Playoffs",False),
    ("AL",2026,"LPL 2026 Split 2","第4名",4,4,"S","Playoffs",False),
    ("AL",2026,"Esports World Cup 2026","八强",5,8,"S","Quarterfinal",False),

    ("BLG",2023,"LPL 2023 Spring","亚军",2,2,"S","Final",False),
    ("BLG",2023,"2023 Mid-Season Invitational","亚军",2,2,"S","Final",False),
    ("BLG",2023,"LPL 2023 Summer","季军",3,3,"S","Playoffs",False),
    ("BLG",2023,"2023 World Championship","四强",3,4,"S","Semifinal",False),
    ("BLG",2024,"LPL 2024 Spring","冠军",1,1,"S","Final",True),
    ("BLG",2024,"2024 Mid-Season Invitational","亚军",2,2,"S","Final",False),
    ("BLG",2024,"Esports World Cup 2024","八强",5,8,"S","Quarterfinal",False),
    ("BLG",2024,"LPL 2024 Summer","冠军",1,1,"S","Final",True),
    ("BLG",2024,"2024 World Championship","亚军",2,2,"S","Final",False),
    ("BLG",2025,"LPL 2025 Split 1","第4名",4,4,"S","Playoffs",False),
    ("BLG",2025,"LPL 2025 Split 2","亚军",2,2,"S","Final",False),
    ("BLG",2025,"LPL 2025 Split 3","冠军",1,1,"S","Final",True),
    ("BLG",2026,"LPL 2026 Split 1","冠军",1,1,"S","Final",True),
    ("BLG",2026,"2026 First Stand Tournament","冠军",1,1,"S","Final",True),
    ("BLG",2026,"LPL 2026 Split 2","冠军",1,1,"S","Final",True),
    ("BLG",2026,"2026 Mid-Season Invitational","亚军",2,2,"S","Final",False),
    ("BLG",2026,"Esports World Cup 2026","八强",5,8,"S","Quarterfinal",False),

    ("TES",2020,"LPL 2020 Spring","亚军",2,2,"S","Final",False),
    ("TES",2020,"2020 Mid-Season Cup","冠军",1,1,"S","Final",True),
    ("TES",2020,"LPL 2020 Summer","冠军",1,1,"S","Final",True),
    ("TES",2020,"2020 World Championship","四强",3,4,"S","Semifinal",False),
    ("TES",2021,"LPL 2021 Spring","第4名",4,4,"S","Playoffs",False),
    ("TES",2021,"LPL 2021 Summer","7–8名",7,8,"S","Playoffs",False),
    ("TES",2022,"LPL 2022 Spring","亚军",2,2,"S","Final",False),
    ("TES",2022,"LPL 2022 Summer","亚军",2,2,"S","Final",False),
    ("TES",2023,"LPL 2023 Spring","7–8名",7,8,"S","Playoffs",False),
    ("TES",2023,"LPL 2023 Summer","第4名",4,4,"S","Playoffs",False),
    ("TES",2024,"LPL 2024 Spring","亚军",2,2,"S","Final",False),
    ("TES",2024,"Esports World Cup 2024","亚军",2,2,"S","Final",False),
    ("TES",2025,"LPL 2025 Split 1","冠军",1,1,"S","Final",True),
    ("TES",2025,"2025 First Stand Tournament","四强",3,4,"S","Semifinal",False),
    ("TES",2025,"LPL 2025 Split 2","5–6名",5,6,"S","Playoffs",False),
    ("TES",2025,"LPL 2025 Split 3","亚军",2,2,"S","Final",False),
    ("TES",2025,"2025 World Championship","四强",3,4,"S","Semifinal",False),
    ("TES",2026,"LPL 2026 Split 1","5–6名",5,6,"S","Playoffs",False),
    ("TES",2026,"LPL 2026 Split 2","亚军",2,2,"S","Final",False),
    ("TES",2026,"2026 Mid-Season Invitational","7–8名",7,8,"S","Playoffs",False),

    ("JDG",2020,"LPL 2020 Spring","冠军",1,1,"S","Final",True),
    ("JDG",2020,"2020 Mid-Season Cup","四强",3,4,"S","Semifinal",False),
    ("JDG",2020,"LPL 2020 Summer","亚军",2,2,"S","Final",False),
    ("JDG",2020,"2020 World Championship","八强",5,8,"S","Quarterfinal",False),
    ("JDG",2021,"LPL 2021 Spring","5–6名",5,6,"S","Playoffs",False),
    ("JDG",2022,"LPL 2022 Spring","第4名",4,4,"S","Playoffs",False),
    ("JDG",2022,"LPL 2022 Summer","冠军",1,1,"S","Final",True),
    ("JDG",2022,"2022 World Championship","四强",3,4,"S","Semifinal",False),
    ("JDG",2023,"LPL 2023 Spring","冠军",1,1,"S","Final",True),
    ("JDG",2023,"2023 Mid-Season Invitational","冠军",1,1,"S","Final",True),
    ("JDG",2023,"LPL 2023 Summer","冠军",1,1,"S","Final",True),
    ("JDG",2023,"2023 World Championship","四强",3,4,"S","Semifinal",False),
    ("JDG",2024,"LPL 2024 Spring","季军",3,3,"S","Playoffs",False),
    ("JDG",2026,"LPL 2026 Split 1","亚军",2,2,"S","Final",False),
    ("JDG",2026,"2026 First Stand Tournament","四强",3,4,"S","Semifinal",False),
    ("JDG",2026,"LPL 2026 Split 2","5–6名",5,6,"S","Playoffs",False),
    ("JDG",2026,"Esports World Cup 2026","八强",5,8,"S","Quarterfinal",False),

    ("LGD",2015,"LPL 2015 Spring","亚军",2,2,"S","Final",False),
    ("LGD",2015,"LPL 2015 Summer","冠军",1,1,"S","Final",True),
    ("LGD",2020,"LPL 2020 Summer","第4名",4,4,"S","Playoffs",False),
    ("LGD",2024,"NEST 2024","冠军",1,1,"A","Final",True),
    ("LGD",2026,"LPL 2026 Split 2","5–6名",5,6,"S","Playoffs",False),

    ("EDG",2014,"LPL 2014 Spring","冠军",1,1,"S","Final",True),
    ("EDG",2014,"LPL 2014 Summer","冠军",1,1,"S","Final",True),
    ("EDG",2014,"2014 World Championship","八强",5,8,"S","Quarterfinal",False),
    ("EDG",2015,"LPL 2015 Spring","冠军",1,1,"S","Final",True),
    ("EDG",2015,"2015 Mid-Season Invitational","冠军",1,1,"S","Final",True),
    ("EDG",2015,"LPL 2015 Summer","第4名",4,4,"S","Playoffs",False),
    ("EDG",2015,"2015 World Championship","八强",5,8,"S","Quarterfinal",False),
    ("EDG",2016,"LPL 2016 Summer","冠军",1,1,"S","Final",True),
    ("EDG",2016,"2016 World Championship","八强",5,8,"S","Quarterfinal",False),
    ("EDG",2017,"LPL 2017 Spring","季军",3,3,"S","Playoffs",False),
    ("EDG",2017,"LPL 2017 Summer","冠军",1,1,"S","Final",True),
    ("EDG",2018,"LPL 2018 Spring","亚军",2,2,"S","Final",False),
    ("EDG",2018,"2018 World Championship","八强",5,8,"S","Quarterfinal",False),
    ("EDG",2021,"LPL 2021 Spring","季军",3,3,"S","Playoffs",False),
    ("EDG",2021,"LPL 2021 Summer","冠军",1,1,"S","Final",True),
    ("EDG",2021,"2021 World Championship","冠军",1,1,"S","Final",True),
    ("EDG",2022,"LPL 2022 Spring","7–8名",7,8,"S","Playoffs",False),
    ("EDG",2022,"LPL 2022 Summer","季军",3,3,"S","Playoffs",False),
    ("EDG",2022,"2022 World Championship","八强",5,8,"S","Quarterfinal",False),
    ("EDG",2025,"LPL 2025 Split 3","7–8名",7,8,"S","Playoffs",False),
    ("EDG",2026,"LPL 2026 Split 2","7–8名",7,8,"S","Playoffs",False),

    ("TT",2022,"Demacia Cup 2022","亚军",2,2,"A","Final",False),
    ("TT",2024,"Demacia Cup 2024","四强",3,4,"A","Semifinal",False),
    ("TT",2025,"LPL 2025 Split 1","5–6名",5,6,"S","Playoffs",False),
    ("TT",2026,"LPL 2026 Split 2","7–8名",7,8,"S","Playoffs",False),

    ("IG",2018,"LPL 2018 Summer","亚军",2,2,"S","Final",False),
    ("IG",2018,"2018 World Championship","冠军",1,1,"S","Final",True),
    ("IG",2018,"Demacia Cup Winter 2018","冠军",1,1,"A","Final",True),
    ("IG",2019,"LPL 2019 Spring","冠军",1,1,"S","Final",True),
    ("IG",2019,"2019 Mid-Season Invitational","四强",3,4,"S","Semifinal",False),
    ("IG",2019,"2019 World Championship","四强",3,4,"S","Semifinal",False),
    ("IG",2025,"LPL 2025 Split 1","7–8名",7,8,"S","Playoffs",False),
    ("IG",2025,"LPL 2025 Split 2","季军",3,3,"S","Playoffs",False),
    ("IG",2025,"LPL 2025 Split 3","5–6名",5,6,"S","Playoffs",False),
    ("IG",2026,"Demacia Cup 2025","冠军",1,1,"A","Final",True),
    ("IG",2026,"LPL 2026 Split 1","5–6名",5,6,"S","Playoffs",False),

    ("LNG",2021,"LPL 2021 Summer","第4名",4,4,"S","Playoffs",False),
    ("LNG",2022,"LPL 2022 Spring","5–6名",5,6,"S","Playoffs",False),
    ("LNG",2022,"LPL 2022 Summer","第4名",4,4,"S","Playoffs",False),
    ("LNG",2023,"LPL 2023 Summer","亚军",2,2,"S","Final",False),
    ("LNG",2023,"2023 World Championship","八强",5,8,"S","Quarterfinal",False),
    ("LNG",2024,"LPL 2024 Spring","7–8名",7,8,"S","Playoffs",False),
    ("LNG",2024,"LPL 2024 Summer","第4名",4,4,"S","Playoffs",False),
    ("LNG",2024,"2024 World Championship","八强",5,8,"S","Quarterfinal",False),
    ("LNG",2026,"Demacia Cup 2025","四强",3,4,"A","Semifinal",False),

    ("NIP",2024,"LPL 2024 Spring","第4名",4,4,"S","Playoffs",False),
    ("NIP",2024,"LPL 2024 Summer","5–6名",5,6,"S","Playoffs",False),
    ("NIP",2025,"LPL 2025 Split 1","5–6名",5,6,"S","Playoffs",False),
    ("NIP",2026,"LPL 2026 Split 1","7–8名",7,8,"S","Playoffs",False),

    ("WBG",2023,"2023 World Championship","亚军",2,2,"S","Final",False),
    ("WBG",2024,"LPL 2024 Spring","5–6名",5,6,"S","Playoffs",False),
    ("WBG",2024,"LPL 2024 Summer","亚军",2,2,"S","Final",False),
    ("WBG",2024,"2024 World Championship","四强",3,4,"S","Semifinal",False),
    ("WBG",2025,"LPL 2025 Split 1","7–8名",7,8,"S","Playoffs",False),
    ("WBG",2025,"LPL 2025 Split 2","5–6名",5,6,"S","Playoffs",False),
    ("WBG",2026,"LPL 2026 Split 1","季军",3,3,"S","Playoffs",False),

    ("WE",2012,"IGN ProLeague Season 5 (IPL5)","冠军",1,1,"S","Final",True),
    ("WE",2015,"IEM Season IX - World Championship","亚军",2,2,"S","Final",False),
    ("WE",2017,"LPL 2017 Spring","冠军",1,1,"S","Final",True),
    ("WE",2017,"2017 Mid-Season Invitational","四强",3,4,"S","Semifinal",False),
    ("WE",2017,"2017 World Championship","四强",3,4,"S","Semifinal",False),
    ("WE",2024,"LPL 2024 Spring","7–8名",7,8,"S","Playoffs",False),
    ("WE",2025,"LPL 2025 Split 2","第4名",4,4,"S","Playoffs",False),
    ("WE",2026,"LPL 2026 Split 1","7–8名",7,8,"S","Playoffs",False),
    ("WE",2026,"LPL 2026 Split 2","季军",3,3,"S","Playoffs",False),
]


def team_id(code: str) -> str:
    return f"team-lol-{code.lower()}"


def result_id(code: str, year: int, event: str, pmin: int, pmax: int) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", event.lower()).strip("-")[:34]
    return f"{code.lower()}-{year}-{slug}-{pmin}-{pmax}"


def main() -> None:
    archive = json.loads(ARCHIVE.read_text(encoding="utf-8"))
    teams_in = archive.get("teams", {})

    graph = {
        "schemaVersion": 1,
        "dataset": "riftlab-esports-graph",
        "updatedAt": archive.get("updatedAt", ""),
        "model": {
            "root": "Organization",
            "path": "Organization -> Game -> Team -> Roster/Personnel",
            "note": "Normalized cross-game graph. UI joins by organization_id, game_id and team_id rather than LPL-specific nesting.",
        },
        "coverage": {
            "games": ["lol"],
            "teams": len(teams_in),
            "organizations": len(ORG_ROWS),
            "resultRecords": len(RESULT_ROWS),
            "resultsPolicy": "Current-brand S-tier top-8 finishes plus selected A-tier podium/title results; predecessor results are not inherited automatically.",
            "rosterPolicy": "LoL current player roster is hydrated from Riot Teams; management/coaching identity remains in the RiftLab people directory during membership migration.",
        },
        "games": [{"game_id": "game-lol", "slug": "lol", "name": "League of Legends", "publisher": "Riot Games"}],
        "organizations": [
            {"organization_id": oid, "name": name, "short_name": short, "country": country, "status": "ACTIVE"}
            for oid, name, short, country in ORG_ROWS
        ],
        "teams": [],
        "team_organization_links": [],
        "team_responsibilities": [],
        "team_lineage": [],
        "team_results": [],
        "roster_memberships": [],
    }

    for code, node in teams_in.items():
        identity = node.get("identity", {})
        graph["teams"].append({
            "team_id": team_id(code),
            "organization_id": PRIMARY_ORG[code],
            "game_id": "game-lol",
            "code": code,
            "name": TEAM_NAMES[code],
            "region": identity.get("region", ""),
            "city": identity.get("city", ""),
            "founded_at": identity.get("foundedAt", ""),
            "game_lineage_founded_at": identity.get("lolDivisionFoundedAt", ""),
            "status": identity.get("status", "ACTIVE"),
            "league": "LPL",
            "roster_source": "RIOT_TEAMS",
            "personnel_source": "RIFTLAB_PEOPLE",
        })
        for org_id, role, source in ORG_LINKS.get(code, []):
            graph["team_organization_links"].append({
                "team_id": team_id(code), "organization_id": org_id, "role": role, "display_role": "", "source": source
            })
        for person in node.get("peopleInCharge", []):
            graph["team_responsibilities"].append({
                "team_id": team_id(code),
                "name": person.get("name", ""),
                "role": person.get("role", ""),
                "display_role": person.get("displayRole", ""),
                "source": person.get("source", ""),
            })
        for row in node.get("lineage", []):
            graph["team_lineage"].append({
                "team_id": team_id(code),
                "name": row.get("name", ""),
                "from": row.get("from", ""),
                "to": row.get("to", ""),
                "relation": row.get("relation", ""),
                "scope": row.get("scope", ""),
                "operator": row.get("operator", ""),
                "note": row.get("note", ""),
                "source": row.get("source", ""),
            })

    for code, year, event, label, pmin, pmax, tier, stage, is_title in RESULT_ROWS:
        graph["team_results"].append({
            "result_id": result_id(code, year, event, pmin, pmax),
            "team_id": team_id(code),
            "year": str(year),
            "event_name": event,
            "placement_label": label,
            "placement_min": pmin,
            "placement_max": pmax,
            "stage": stage,
            "tier": tier,
            "is_title": is_title,
            "source": "Liquipedia results / RiftLab audit",
            "source_url": RESULT_URLS[code],
        })

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(graph, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUT}: {len(graph['teams'])} teams, {len(graph['organizations'])} organizations, {len(graph['team_results'])} results")


if __name__ == "__main__":
    main()
