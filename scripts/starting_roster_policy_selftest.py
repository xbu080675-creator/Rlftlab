#!/usr/bin/env python3
"""Small deterministic regression test for league roster publishing policies."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CONFIG = Path("data/global/starting_roster_sources.json")

spec = importlib.util.spec_from_file_location("rift_four_lane", ROOT / "starting_roster_four_lane_collector.py")
collector = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(collector)

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
