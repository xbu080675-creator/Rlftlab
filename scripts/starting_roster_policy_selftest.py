#!/usr/bin/env python3
"""Small deterministic regression test for league roster publishing policies."""
from __future__ import annotations

import importlib.util
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CONFIG = Path("data/global/starting_roster_sources.json")

spec = importlib.util.spec_from_file_location("rift_four_lane", ROOT / "starting_roster_four_lane_collector.py")
collector = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(collector)

query_spec = importlib.util.spec_from_file_location("rift_match_query", ROOT / "starting_roster_match_query.py")
match_query = importlib.util.module_from_spec(query_spec)
assert query_spec and query_spec.loader
query_spec.loader.exec_module(match_query)

target_spec = importlib.util.spec_from_file_location("rift_match_targets", ROOT / "starting_roster_match_targets.py")
match_targets = importlib.util.module_from_spec(target_spec)
assert target_spec and target_spec.loader
target_spec.loader.exec_module(match_targets)

cfg = json.loads(CONFIG.read_text(encoding="utf-8"))
policies = cfg.get("leaguePolicies") or {}
required = {"LPL", "LCK", "LEC", "LCS", "LCP"}
missing = sorted(required - set(policies))
assert not missing, f"missing league policies: {missing}"

for league in required:
    policy = policies[league]
    assert policy.get("adapter"), f"{league}: missing adapter"
    assert policy.get("lineupKeywords"), f"{league}: missing lineupKeywords"
    assert str(policy.get("preferredOcrLanguages") or "").startswith("eng"), f"{league}: OCR must keep English player-ID lane"

cases = [
    (
        {"league": "LPL", "source": "LEAGUE_SOCIAL"},
        "#2026LPL季后赛# 9月12日 首发名单 约17:00 #AL对战IG#（BO5）",
        "LEAGUE_TEMPLATE_MATCHUP",
        {"AL", "IG"},
    ),
    (
        {"league": "LCK", "source": "LEAGUE_SOCIAL"},
        "T1 vs HLE starting lineup",
        "MATCH_TARGET_PLUS_LINEUP",
        {"T1", "HLE"},
    ),
    (
        {"league": "LEC", "source": "LEAGUE_SOCIAL"},
        "G2 vs FNC starting roster",
        "MATCH_TARGET_PLUS_LINEUP",
        {"G2", "FNC"},
    ),
    (
        {"league": "LCS", "source": "LEAGUE_SOCIAL"},
        "C9 vs TL lineup",
        "MATCH_TARGET_PLUS_LINEUP",
        {"C9", "TL"},
    ),
    (
        {"league": "LCP", "source": "LEAGUE_SOCIAL"},
        "CFO vs GAM starting lineup",
        "MATCH_TARGET_PLUS_LINEUP",
        {"CFO", "GAM"},
    ),
]

for source, text, expected_basis, expected_teams in cases:
    meta = collector.candidate_score(source, text, True, None, cfg)
    assert meta is not None, f"{source['league']}: candidate unexpectedly rejected"
    assert meta["basis"] == expected_basis, (source["league"], meta)
    assert expected_teams.issubset(set(meta["teams"])), (source["league"], meta)

# Multi-match day search must start with date + both teams + lineup intent.
lpl_queries = match_query.build_match_search_queries("2026-09-12", "IG", "AL", "LPL")
assert lpl_queries, "LPL search query builder returned nothing"
assert "9月12日" in lpl_queries[0]
assert "AL" in lpl_queries[0] and "IG" in lpl_queries[0]
assert "首发" in lpl_queries[0]
assert any("9月12日 AL对战IG 首发名单" == q for q in lpl_queries[:6]), lpl_queries[:6]

lck_queries = match_query.build_match_search_queries("2026-09-12", "T1", "HLE", "LCK")
assert "2026-09-12" in lck_queries[0]
assert "T1" in lck_queries[0] and "HLE" in lck_queries[0]
assert "lineup" in lck_queries[0].lower() or "선발" in lck_queries[0]
assert any("HLE vs T1" in q for q in lck_queries[:6])

# Schedule targets must never emit unresolved TBD/TBA matchups.
start = (datetime.now(timezone.utc) + timedelta(hours=2)).replace(microsecond=0).isoformat().replace("+00:00", "Z")
root = {
    "data": {
        "schedule": {
            "events": [
                {
                    "type": "match",
                    "id": "resolved",
                    "startTime": start,
                    "league": {"slug": "lpl", "name": "LPL"},
                    "match": {"id": "m1", "teams": [{"code": "AL", "name": "Anyone's Legend"}, {"code": "IG", "name": "Invictus Gaming"}]},
                },
                {
                    "type": "match",
                    "id": "unresolved",
                    "startTime": start,
                    "league": {"slug": "lpl", "name": "LPL"},
                    "match": {"id": "m2", "teams": [{"code": "TBD", "name": "TBD"}, {"code": "BLG", "name": "Bilibili Gaming"}]},
                },
            ]
        }
    }
}
targets = match_targets.build_targets(cfg, root, hours_before=6, hours_after=36, limit=12)
assert len(targets) == 1 and targets[0]["teams"] == ["AL", "IG"], targets

# Team-owned image-only posts remain fallback candidates, never primary facts.
image_only = collector.candidate_score(
    {"league": "LCK", "source": "TEAM_SOCIAL", "team": "T1"},
    "match day",
    True,
    None,
    cfg,
)
assert image_only is not None and image_only["basis"] == "TEAM_IMAGE_ONLY_FALLBACK"
assert image_only["score"] < 100

print("starting roster policy selftest: PASS")
