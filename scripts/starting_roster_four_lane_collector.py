#!/usr/bin/env python3
"""Run bounded roster OCR and preserve trustworthy official announcement metadata.

Candidate selection is matchup-first: prefer posts that name both sides of the
scheduled matchup and also contain lineup intent. Official source validation is
still mandatory; search semantics only rank candidates and never create facts.
"""
from __future__ import annotations

import importlib.util
import json
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA = Path("data/global")
SOURCES = DATA / "starting_roster_sources.json"
SPOOL = DATA / "starting_roster_browser_posts.json"
OUTPUT = DATA / "starting_rosters.json"

spec = importlib.util.spec_from_file_location("rift_roster_fast", ROOT / "starting_roster_fast_global_collector.py")
fast = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(fast)

SHELL_MARKERS = (
    "前方有点拥堵，请登录后使用",
    "随时随地发现新鲜事",
    "关注推荐 1/8",
    "帮助中心 微博客服",
)


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def source_key(source: dict) -> str:
    return f"{source.get('platform', '')}:{source.get('uid') or source.get('handle') or source.get('account', '')}"


def parse_time(value):
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        return datetime.fromisoformat(value.strip().replace("Z", "+00:00")).astimezone(timezone.utc)
    except Exception:
        return None


def alias_hit(text: str, alias: str) -> bool:
    alias = str(alias or "").strip()
    if not alias:
        return False
    if re.fullmatch(r"[A-Za-z0-9]+", alias) and len(alias) <= 3:
        return re.search(rf"(?<![A-Za-z0-9]){re.escape(alias)}(?![A-Za-z0-9])", text, re.I) is not None
    return alias.lower() in text.lower()


def detect_team_codes(text: str, aliases: dict) -> list[str]:
    hits = []
    for code, names in aliases.items():
        candidates = [code, *(names or [])]
        if any(alias_hit(text, candidate) for candidate in candidates):
            hits.append(str(code).upper())
    return hits


def candidate_score(source: dict, text: str, has_media: bool, published, cfg: dict):
    lowered = text.lower()
    keywords = [str(x).lower() for x in (cfg.get("keywords") or []) if str(x).strip()]
    keyword_hit = any(keyword in lowered for keyword in keywords)
    team_hits = detect_team_codes(text, cfg.get("teamAliases") or {})
    own_team = str(source.get("team") or "").upper()
    opponents = [team for team in team_hits if team != own_team]
    is_team_source = str(source.get("source") or "").upper() == "TEAM_SOCIAL" or bool(own_team)

    if is_team_source:
        matchup_hit = bool(opponents)
        matchup_teams = [own_team, *opponents[:2]] if own_team else opponents[:2]
    else:
        matchup_hit = len(set(team_hits)) >= 2
        matchup_teams = list(dict.fromkeys(team_hits))[:3]

    score = 0
    if keyword_hit and matchup_hit:
        score += 500
    elif matchup_hit:
        score += 260
    elif keyword_hit:
        score += 140
    if has_media:
        score += 30
    if published:
        score += 10

    shell_hits = sum(1 for marker in SHELL_MARKERS if marker in text)
    if shell_hits >= 2:
        return None

    # Primary path mirrors the human search query: Team A + Team B + lineup.
    # Fallbacks stay available for image-only club posts, but rank far below it.
    if keyword_hit and matchup_hit:
        basis = "MATCHUP_PLUS_LINEUP"
    elif matchup_hit and has_media:
        basis = "MATCHUP_MEDIA"
    elif keyword_hit and has_media:
        basis = "LINEUP_MEDIA_FALLBACK"
    elif is_team_source and has_media:
        basis = "TEAM_IMAGE_ONLY_FALLBACK"
        score = min(score, 35)
    else:
        return None

    return {
        "score": score,
        "keywordHit": keyword_hit,
        "matchupHit": matchup_hit,
        "teams": matchup_teams,
        "basis": basis,
    }


def add_announcements() -> None:
    if not OUTPUT.exists() or not SOURCES.exists() or not SPOOL.exists():
        return
    cfg = json.loads(SOURCES.read_text(encoding="utf-8"))
    spool = json.loads(SPOOL.read_text(encoding="utf-8"))
    payload = json.loads(OUTPUT.read_text(encoding="utf-8"))
    browser_sources = spool.get("sources") or {}
    lookback = timedelta(hours=int(cfg.get("lookbackHours", 36)))
    cutoff = datetime.now(timezone.utc) - lookback
    evidence_urls = {str(row.get("sourceUrl") or "") for row in (payload.get("evidence") or [])}

    rows = []
    all_sources = list(cfg.get("leagues") or []) + list(cfg.get("teams") or [])
    for source in all_sources:
        account = str(source.get("account") or "")
        posts = list(browser_sources.get(source_key(source)) or [])
        ranked = []
        for index, post in enumerate(posts):
            published = parse_time(post.get("published"))
            if published and published < cutoff:
                continue
            text = str(post.get("text") or "").strip()
            images = list(post.get("images") or [])
            original_images = [str(x) for x in (post.get("originalImages") or []) if str(x).startswith("http")]
            meta = candidate_score(source, text, bool(images or original_images), published, cfg)
            if meta is None:
                continue
            ranked.append((meta["score"], -index, post, text, images, original_images, published, meta))

        ranked.sort(reverse=True, key=lambda item: (item[0], item[1]))
        primary = [item for item in ranked if item[-1]["basis"] == "MATCHUP_PLUS_LINEUP"]
        chosen = (primary or ranked)[:3]
        for _, _, post, text, images, original_images, published, meta in chosen:
            url = str(post.get("url") or "")
            if not url:
                continue
            rows.append({
                "id": f"{source_key(source)}:{post.get('id') or url}",
                "league": str(source.get("league") or ""),
                "team": str(source.get("team") or ""),
                "source": str(source.get("source") or "OTHER_OFFICIAL"),
                "platform": str(source.get("platform") or "OFFICIAL"),
                "account": account,
                "publishedAt": published.isoformat().replace("+00:00", "Z") if published else "",
                "observedAt": now_iso(),
                "sourceUrl": url,
                "textSnippet": text[:360],
                "imageCount": len(original_images) or len(images),
                "imageUrls": list(dict.fromkeys(original_images))[:4],
                "parseStatus": "PARSED" if url in evidence_urls else "UNPARSED",
                "candidateBasis": meta["basis"],
                "candidateTeams": meta["teams"],
                "candidateScore": meta["score"],
            })

    deduped = {}
    for row in rows:
        deduped[row["id"]] = row
    payload["schemaVersion"] = max(3, int(payload.get("schemaVersion", 2)))
    payload["announcements"] = list(deduped.values())[:60]
    OUTPUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    fast.global_collector.base.main()
    fast.write_trace_report()
    add_announcements()
