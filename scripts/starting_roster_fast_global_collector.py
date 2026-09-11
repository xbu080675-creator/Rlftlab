#!/usr/bin/env python3
"""Bounded global starting-roster OCR runner with compact recognition traces."""
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
_ACTIVE_OCR_INDEX = 0


def _clean_preview(value: str) -> str:
    value = re.sub(r"\s+", " ", str(value or "")).strip()
    if len(value) > TRACE_PREVIEW_CHARS:
        value = value[:TRACE_PREVIEW_CHARS] + "…"
    return value


def _trace(message: str):
    if len(_TRACE) < TRACE_MAX_LINES_PER_SOURCE:
        _TRACE.append(message)


def _alias_hit(text: str, alias: str) -> bool:
    alias = str(alias or "").strip()
    if not alias:
        return False
    if re.fullmatch(r"[A-Za-z0-9]+", alias) and len(alias) <= 3:
        return re.search(rf"(?<![A-Za-z0-9]){re.escape(alias)}(?![A-Za-z0-9])", text, re.I) is not None
    return alias.lower() in text.lower()


def _team_hits(text: str, cfg: dict) -> list[str]:
    hits = []
    for code, aliases in ((cfg or {}).get("teamAliases") or {}).items():
        candidates = [code, *(aliases or [])]
        if any(_alias_hit(text, alias) for alias in candidates):
            hits.append(str(code).upper())
    return hits


def _post_relevance(source: dict, row: dict, cfg: dict) -> tuple[int, str, list[str]]:
    text = str(row.get("text") or "")
    lowered = text.lower()
    keywords = [str(x).lower() for x in ((cfg or {}).get("keywords") or []) if str(x).strip()]
    keyword_hit = any(keyword in lowered for keyword in keywords)
    hits = _team_hits(text, cfg)
    own_team = str((source or {}).get("team") or "").upper()
    is_team_source = str((source or {}).get("source") or "").upper() == "TEAM_SOCIAL" or bool(own_team)
    opponents = [team for team in hits if team != own_team]
    matchup_hit = bool(opponents) if is_team_source else len(set(hits)) >= 2

    score = 0
    basis = "LOW_CONFIDENCE"
    if keyword_hit and matchup_hit:
        score += 500
        basis = "MATCHUP_PLUS_LINEUP"
    elif matchup_hit:
        score += 260
        basis = "MATCHUP"
    elif keyword_hit:
        score += 140
        basis = "LINEUP_KEYWORD"

    if row.get("images"):
        score += 30
    if row.get("published"):
        score += 10

    teams = ([own_team] if own_team else []) + opponents if is_team_source else hits
    return score, basis, list(dict.fromkeys([team for team in teams if team]))[:3]


def _budget_posts(posts, cfg=None, source=None):
    ranked = []
    for index, post in enumerate(posts or []):
        row = dict(post)
        row["images"] = list(row.get("images") or [])[:MAX_IMAGES_PER_POST]
        score, basis, teams = _post_relevance(source or {}, row, cfg or {})
        row["candidateBasis"] = basis
        row["candidateTeams"] = teams
        row["candidateScore"] = score
        ranked.append((score, -index, row))
    ranked.sort(reverse=True, key=lambda item: (item[0], item[1]))

    # If we found proper Team A + Team B + lineup candidates, OCR those first
    # and do not let sponsor/image-only posts consume the bounded OCR budget.
    primary = [row for score, _, row in ranked if row.get("candidateBasis") == "MATCHUP_PLUS_LINEUP"]
    if primary:
        return primary[:MAX_POSTS_PER_SOURCE]
    return [row for _, _, row in ranked[:MAX_POSTS_PER_SOURCE]]


_original_detect_teams = global_collector.base.detect_teams
_original_extract_from_lines = global_collector.base.extract_from_lines
_original_choose_lineups = global_collector.base.choose_lineups


def _trace_detect_teams(text, aliases):
    teams = _original_detect_teams(text, aliases)
    _trace(f"teams={teams or []}")
    return teams


def _trace_extract_from_lines(text):
    roles = _original_extract_from_lines(text)
    compact = {k: v[:3] for k, v in roles.items() if v}
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
    global _TRACE, _ACTIVE_SOURCE, _ACTIVE_OCR_INDEX
    bounded = _budget_posts(posts, cfg, source)
    _TRACE = []
    previous_source = _ACTIVE_SOURCE
    previous_index = _ACTIVE_OCR_INDEX
    _ACTIVE_SOURCE = dict(source or {})
    _ACTIVE_OCR_INDEX = 0
    try:
        evidence, diagnostics = _original_process_posts(source, cfg, bounded, transport_label)
    finally:
        _ACTIVE_SOURCE = previous_source
        _ACTIVE_OCR_INDEX = previous_index
    account = source.get("account")
    basis_summary = ",".join(str(row.get("candidateBasis") or "?") for row in bounded[:3])
    trace_lines = [f"{account}: trace {line}" for line in _TRACE]
    diagnostics = [
        f"{account}: ocr_budget posts={len(bounded)}/{len(posts or [])} images_per_post<={MAX_IMAGES_PER_POST} timeout={OCR_TIMEOUT_SECONDS:g}s basis={basis_summary}",
        *trace_lines,
    ] + diagnostics
    return evidence, diagnostics


global_collector._process_posts = _bounded_process_posts

_original_weibo_fetch = global_collector.base.fetch_weibo_posts


def _bounded_weibo_fetch(uid, limit=20):
    posts = _original_weibo_fetch(uid, min(limit, MAX_POSTS_PER_SOURCE))
    return _budget_posts(posts, None, None)


global_collector.base.fetch_weibo_posts = _bounded_weibo_fetch


def _langs_for_active_source() -> str:
    league = str(_ACTIVE_SOURCE.get("league") or "").upper()
    timezone_name = str(_ACTIVE_SOURCE.get("timezone") or "")
    if "LPL" in league or "PCS" in league or timezone_name in {"Asia/Shanghai", "Asia/Taipei"}:
        return "eng+chi_sim"
    if "LCK" in league or timezone_name == "Asia/Seoul":
        return "eng+kor"
    if "LJL" in league or timezone_name == "Asia/Tokyo":
        return "eng+jpn"
    if "LCP" in league:
        return "eng+chi_sim+jpn+kor"
    return "eng"


def fast_multilingual_ocr(img):
    """One source-aware Tesseract pass per image with a hard timeout."""
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
    _TRACE_RECORDS.append({
        "league": str(_ACTIVE_SOURCE.get("league") or ""),
        "team": str(_ACTIVE_SOURCE.get("team") or ""),
        "account": str(_ACTIVE_SOURCE.get("account") or ""),
        "platform": str(_ACTIVE_SOURCE.get("platform") or ""),
        "sourceKind": str(_ACTIVE_SOURCE.get("source") or ""),
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
        f"ocr lang={langs} psm=11 size={original_size[0]}x{original_size[1]}->{work.size[0]}x{work.size[1]} "
        f"words={len(words)} text={preview!r}"
    )
    return text, words, work.size


def write_trace_report(path: Path = TRACE_OUTPUT):
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "recordCount": len(_TRACE_RECORDS),
        "records": _TRACE_RECORDS,
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


global_collector.base.ocr_image = fast_multilingual_ocr

if __name__ == "__main__":
    global_collector.base.main()
    write_trace_report()
