#!/usr/bin/env python3
"""Bounded global starting-roster OCR runner with league-aware candidate policies."""
from __future__ import annotations

import importlib.util
import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
TRACE_OUTPUT = Path("data/global/starting_roster_ocr_trace.json")
spec = importlib.util.spec_from_file_location("rift_roster_global", ROOT / "starting_roster_global_collector.py")
global_collector = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(global_collector)

MAX_POSTS_PER_SOURCE = int(os.environ.get("RIFTLAB_ROSTER_MAX_POSTS_PER_SOURCE", "6"))
MAX_IMAGES_PER_POST = int(os.environ.get("RIFTLAB_ROSTER_MAX_IMAGES_PER_POST", "2"))
OCR_TIMEOUT_SECONDS = float(os.environ.get("RIFTLAB_ROSTER_OCR_TIMEOUT_SECONDS", "10"))
OCR_MAX_DIMENSION = int(os.environ.get("RIFTLAB_ROSTER_OCR_MAX_DIMENSION", "2200"))
TRACE_PREVIEW_CHARS = int(os.environ.get("RIFTLAB_ROSTER_TRACE_PREVIEW_CHARS", "220"))
TRACE_MAX_LINES_PER_SOURCE = int(os.environ.get("RIFTLAB_ROSTER_TRACE_MAX_LINES", "8"))
_TRACE = []
_TRACE_RECORDS = []
_ACTIVE_SOURCE = {}
_ACTIVE_CFG = {}
_ACTIVE_OCR_INDEX = 0

LPL_MATCH_LINE_RE = re.compile(
    r"(?:约\s*)?(?P<time>\d{1,2}:\d{2})\s*#?\s*(?P<a>[A-Za-z0-9]{2,8})\s*对战\s*(?P<b>[A-Za-z0-9]{2,8})\s*#?",
    re.I,
)


def _clean_preview(value: str) -> str:
    value = re.sub(r"\s+", " ", str(value or "")).strip()
    if len(value) > TRACE_PREVIEW_CHARS:
        value = value[:TRACE_PREVIEW_CHARS] + "…"
    return value


def _trace(message: str):
    if len(_TRACE) < TRACE_MAX_LINES_PER_SOURCE:
        _TRACE.append(message)


def _policy(cfg: dict | None, source: dict | None) -> dict:
    league = str((source or {}).get("league") or "").upper()
    return dict((((cfg or {}).get("leaguePolicies") or {}).get(league)) or {})


def _lineup_keywords(cfg: dict | None, source: dict | None) -> list[str]:
    policy = _policy(cfg, source)
    values = policy.get("lineupKeywords") or (cfg or {}).get("keywords") or []
    return [str(value).lower() for value in values if str(value).strip()]


def _alias_hit(text: str, alias: str) -> bool:
    alias = str(alias or "").strip()
    if not alias:
        return False
    if re.fullmatch(r"[A-Za-z0-9]+", alias) and len(alias) <= 3:
        return re.search(rf"(?<![A-Za-z0-9]){re.escape(alias)}(?![A-Za-z0-9])", text, re.I) is not None
    return alias.lower() in text.lower()


def _team_hits(text: str, cfg: dict | None) -> list[str]:
    hits = []
    for code, aliases in (((cfg or {}).get("teamAliases") or {}).items()):
        candidates = [code, *(aliases or [])]
        if any(_alias_hit(text, candidate) for candidate in candidates):
            hits.append(str(code).upper())
    return list(dict.fromkeys(hits))


def _split_lpl_daily_roster_posts(posts, source, cfg):
    policy = _policy(cfg, source)
    if policy.get("adapter") != "LPL_DAILY_ROSTER":
        return list(posts or [])

    out = []
    for post in posts or []:
        text = str(post.get("text") or "")
        matches = list(LPL_MATCH_LINE_RE.finditer(text))
        if "首发名单" not in text or not matches:
            out.append(post)
            continue

        images = list(post.get("images") or [])
        prefix_match = re.search(r"(?:20\d{2}[^\n]{0,32})?\d{1,2}月\d{1,2}日\s*首发名单", text)
        prefix = prefix_match.group(0).strip() if prefix_match else "首发名单"
        ordered = bool(policy.get("imageOrderFollowsMatchupOrder"))

        for index, match in enumerate(matches):
            row = dict(post)
            team_a = match.group("a").upper()
            team_b = match.group("b").upper()
            row["id"] = f"{post.get('id') or 'post'}-match-{index + 1}-{team_a}-{team_b}"
            row["text"] = f"{prefix}\n{match.group(0).strip()}"
            if ordered and index < len(images):
                row["images"] = [images[index]]
            else:
                row["images"] = images[:1]
            row["matchupHint"] = [team_a, team_b]
            row["template"] = "LPL_DAILY_STARTING_ROSTER"
            row["candidateBasis"] = "LEAGUE_TEMPLATE_MATCHUP"
            out.append(row)
        _trace(f"lpl_daily_split post={post.get('id')} matchups={len(matches)} images={len(images)}")
    return out


def _generic_relevance(source: dict, row: dict, cfg: dict) -> tuple[int, str, list[str]]:
    text = str(row.get("text") or "")
    lowered = text.lower()
    keywords = _lineup_keywords(cfg, source)
    keyword_hit = any(keyword in lowered for keyword in keywords)
    hits = _team_hits(text, cfg)
    own_team = str(source.get("team") or "").upper()
    team_source = str(source.get("source") or "").upper() == "TEAM_SOCIAL" or bool(own_team)
    opponents = [team for team in hits if team != own_team]
    matchup_hit = bool(opponents) if team_source else len(set(hits)) >= 2
    media = bool(row.get("images"))
    policy = _policy(cfg, source)

    if keyword_hit and matchup_hit:
        score, basis = 700, "MATCH_TARGET_PLUS_LINEUP"
    elif matchup_hit and media:
        score, basis = 360, "MATCH_TARGET_MEDIA"
    elif keyword_hit and media:
        score, basis = 220, "LINEUP_MEDIA_FALLBACK"
    elif team_source and media and policy.get("allowImageOnlyTeamFallback", True):
        score, basis = 35, "TEAM_IMAGE_ONLY_FALLBACK"
    else:
        score, basis = 0, "LOW_CONFIDENCE"

    if row.get("published"):
        score += 10
    teams = ([own_team] if own_team else []) + opponents if team_source else hits
    return score, basis, list(dict.fromkeys([team for team in teams if team]))[:3]


def _budget_posts(posts, cfg=None, source=None):
    source = source or {}
    cfg = cfg or {}
    posts = _split_lpl_daily_roster_posts(posts, source, cfg)
    policy = _policy(cfg, source)
    ranked = []
    for index, post in enumerate(posts or []):
        row = dict(post)
        row["images"] = list(row.get("images") or [])[:MAX_IMAGES_PER_POST]
        if row.get("template") == "LPL_DAILY_STARTING_ROSTER":
            score, basis, teams = 1200, "LEAGUE_TEMPLATE_MATCHUP", list(row.get("matchupHint") or [])
        else:
            score, basis, teams = _generic_relevance(source, row, cfg)
        row["candidateScore"] = score
        row["candidateBasis"] = basis
        row["candidateTeams"] = teams
        row["adapter"] = policy.get("adapter") or "MATCH_TARGET_LINEUP"
        ranked.append((score, -index, row))
    ranked.sort(reverse=True, key=lambda item: (item[0], item[1]))

    strong = [row for score, _, row in ranked if score >= 700]
    chosen = strong if strong else [row for _, _, row in ranked]
    return chosen[:MAX_POSTS_PER_SOURCE]


_original_detect_teams = global_collector.base.detect_teams
_original_extract_from_lines = global_collector.base.extract_from_lines
_original_choose_lineups = global_collector.base.choose_lineups


def _trace_detect_teams(text, aliases):
    teams = _original_detect_teams(text, aliases)
    _trace(f"teams={teams or []}")
    return teams


def _trace_extract_from_lines(text):
    roles = _original_extract_from_lines(text)
    compact = {key: value[:3] for key, value in roles.items() if value}
    _trace(f"line_roles={compact}")
    return roles


def _trace_choose_lineups(line_roles, column_roles):
    lineups = _original_choose_lineups(line_roles, column_roles)
    compact_columns = {
        side: {role: values[:2] for role, values in mapping.items() if values}
        for side, mapping in column_roles.items()
    }
    _trace(f"column_roles={compact_columns}")
    _trace(f"lineups={lineups or []}")
    return lineups


global_collector.base.detect_teams = _trace_detect_teams
global_collector.base.extract_from_lines = _trace_extract_from_lines
global_collector.base.choose_lineups = _trace_choose_lineups

_original_process_posts = global_collector._process_posts


def _bounded_process_posts(source, cfg, posts, transport_label):
    global _TRACE, _ACTIVE_SOURCE, _ACTIVE_CFG, _ACTIVE_OCR_INDEX
    _TRACE = []
    bounded = _budget_posts(posts, cfg, source)
    previous_source = _ACTIVE_SOURCE
    previous_cfg = _ACTIVE_CFG
    previous_index = _ACTIVE_OCR_INDEX
    _ACTIVE_SOURCE = dict(source or {})
    _ACTIVE_CFG = dict(cfg or {})
    _ACTIVE_OCR_INDEX = 0
    try:
        evidence, diagnostics = _original_process_posts(source, cfg, bounded, transport_label)
    finally:
        _ACTIVE_SOURCE = previous_source
        _ACTIVE_CFG = previous_cfg
        _ACTIVE_OCR_INDEX = previous_index
    account = source.get("account")
    basis_summary = ",".join(str(row.get("candidateBasis") or "?") for row in bounded[:3])
    adapter = _policy(cfg, source).get("adapter") or "MATCH_TARGET_LINEUP"
    trace_lines = [f"{account}: trace {line}" for line in _TRACE]
    diagnostics = [
        f"{account}: adapter={adapter} ocr_budget posts={len(bounded)}/{len(posts or [])} "
        f"images_per_post<={MAX_IMAGES_PER_POST} timeout={OCR_TIMEOUT_SECONDS:g}s basis={basis_summary}",
        *trace_lines,
    ] + diagnostics
    return evidence, diagnostics


global_collector._process_posts = _bounded_process_posts

_original_weibo_fetch = global_collector.base.fetch_weibo_posts


def _bounded_weibo_fetch(uid, limit=20):
    posts = _original_weibo_fetch(uid, min(limit, MAX_POSTS_PER_SOURCE * 2))
    return _budget_posts(posts, _ACTIVE_CFG, _ACTIVE_SOURCE)


global_collector.base.fetch_weibo_posts = _bounded_weibo_fetch


def _langs_for_active_source() -> str:
    policy = _policy(_ACTIVE_CFG, _ACTIVE_SOURCE)
    configured = str(policy.get("preferredOcrLanguages") or "").strip()
    if configured:
        return configured
    league = str(_ACTIVE_SOURCE.get("league") or "").upper()
    timezone_name = str(_ACTIVE_SOURCE.get("timezone") or "")
    if "LPL" in league or "PCS" in league or timezone_name in {"Asia/Shanghai", "Asia/Taipei"}:
        return "eng+chi_sim"
    if "LCK" in league or timezone_name == "Asia/Seoul":
        return "eng+kor"
    if "LJL" in league or timezone_name == "Asia/Tokyo":
        return "eng+jpn"
    return "eng"


def fast_multilingual_ocr(img):
    global _ACTIVE_OCR_INDEX
    langs = _langs_for_active_source()
    work = img.copy()
    original_size = work.size
    if max(work.size) > OCR_MAX_DIMENSION:
        work.thumbnail((OCR_MAX_DIMENSION, OCR_MAX_DIMENSION))
    data = global_collector.base.pytesseract.image_to_data(
        work,
        lang=langs,
        config="--psm 11",
        output_type=global_collector.base.pytesseract.Output.DICT,
        timeout=OCR_TIMEOUT_SECONDS,
    )
    words = []
    lines = {}
    count = len(data.get("text", []))
    for i in range(count):
        token = (data["text"][i] or "").strip()
        try:
            confidence = float(data["conf"][i])
        except Exception:
            confidence = -1
        if not token or confidence < 20:
            continue
        words.append({
            "text": token,
            "left": int(data["left"][i]),
            "top": int(data["top"][i]),
            "width": int(data["width"][i]),
            "height": int(data["height"][i]),
            "conf": round(confidence, 1),
        })
        key = (
            int(data.get("block_num", [0] * count)[i]),
            int(data.get("par_num", [0] * count)[i]),
            int(data.get("line_num", [0] * count)[i]),
        )
        lines.setdefault(key, []).append(token)
    text = "\n".join(" ".join(tokens) for _, tokens in sorted(lines.items()))
    preview = _clean_preview(text)
    _ACTIVE_OCR_INDEX += 1
    policy = _policy(_ACTIVE_CFG, _ACTIVE_SOURCE)
    _TRACE_RECORDS.append({
        "league": str(_ACTIVE_SOURCE.get("league") or ""),
        "team": str(_ACTIVE_SOURCE.get("team") or ""),
        "account": str(_ACTIVE_SOURCE.get("account") or ""),
        "platform": str(_ACTIVE_SOURCE.get("platform") or ""),
        "sourceKind": str(_ACTIVE_SOURCE.get("source") or ""),
        "adapter": policy.get("adapter") or "MATCH_TARGET_LINEUP",
        "ocrIndex": _ACTIVE_OCR_INDEX,
        "languages": langs,
        "psm": 11,
        "originalSize": [original_size[0], original_size[1]],
        "processedSize": [work.size[0], work.size[1]],
        "wordCount": len(words),
        "textPreview": preview,
        "words": words[:80],
    })
    _trace(
        f"ocr adapter={policy.get('adapter') or 'MATCH_TARGET_LINEUP'} lang={langs} psm=11 "
        f"size={original_size[0]}x{original_size[1]}->{work.size[0]}x{work.size[1]} "
        f"words={len(words)} text={preview!r}"
    )
    return text, words, work.size


def write_trace_report(path: Path = TRACE_OUTPUT):
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schemaVersion": 2,
        "generatedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "recordCount": len(_TRACE_RECORDS),
        "records": _TRACE_RECORDS,
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


global_collector.base.ocr_image = fast_multilingual_ocr

if __name__ == "__main__":
    global_collector.base.main()
    write_trace_report()
