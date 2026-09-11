#!/usr/bin/env python3
"""Bounded global starting-roster OCR runner with compact recognition traces."""
from __future__ import annotations

import importlib.util
import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent
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


def _clean_preview(value: str) -> str:
    value = re.sub(r"\s+", " ", str(value or "")).strip()
    if len(value) > TRACE_PREVIEW_CHARS:
        value = value[:TRACE_PREVIEW_CHARS] + "…"
    return value


def _trace(message: str):
    if len(_TRACE) < TRACE_MAX_LINES_PER_SOURCE:
        _TRACE.append(message)


def _budget_posts(posts, cfg=None):
    keywords = [str(x).lower() for x in ((cfg or {}).get("keywords") or [])]
    ranked = []
    for index, post in enumerate(posts or []):
        row = dict(post)
        text = str(row.get("text") or "")
        images = list(row.get("images") or [])[:MAX_IMAGES_PER_POST]
        row["images"] = images
        score = 0
        lowered = text.lower()
        if any(keyword and keyword in lowered for keyword in keywords):
            score += 100
        if images:
            score += 20
        if row.get("published"):
            score += 5
        ranked.append((score, -index, row))
    ranked.sort(reverse=True, key=lambda item: (item[0], item[1]))
    return [row for _, _, row in ranked[:MAX_POSTS_PER_SOURCE]]


# Trace parser decisions without changing the parser's behavior.
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
    global _TRACE
    bounded = _budget_posts(posts, cfg)
    _TRACE = []
    evidence, diagnostics = _original_process_posts(source, cfg, bounded, transport_label)
    account = source.get("account")
    trace_lines = [f"{account}: trace {line}" for line in _TRACE]
    diagnostics = [
        f"{account}: ocr_budget posts={len(bounded)}/{len(posts or [])} images_per_post<={MAX_IMAGES_PER_POST} timeout={OCR_TIMEOUT_SECONDS:g}s",
        *trace_lines,
    ] + diagnostics
    return evidence, diagnostics


global_collector._process_posts = _bounded_process_posts

_original_weibo_fetch = global_collector.base.fetch_weibo_posts


def _bounded_weibo_fetch(uid, limit=20):
    posts = _original_weibo_fetch(uid, min(limit, MAX_POSTS_PER_SOURCE))
    return _budget_posts(posts, None)


global_collector.base.fetch_weibo_posts = _bounded_weibo_fetch


def fast_multilingual_ocr(img):
    """One Tesseract pass per image, with resize, hard timeout and compact trace."""
    langs = os.environ.get("RIFTLAB_OCR_LANGS", "chi_sim+eng+kor+jpn")
    work = img.copy()
    original_size = work.size
    if max(work.size) > OCR_MAX_DIMENSION:
        work.thumbnail((OCR_MAX_DIMENSION, OCR_MAX_DIMENSION))
    data = global_collector.base.pytesseract.image_to_data(
        work,
        lang=langs,
        config="--psm 6",
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
            "conf": confidence,
        })
        key = (
            int(data.get("block_num", [0] * count)[i]),
            int(data.get("par_num", [0] * count)[i]),
            int(data.get("line_num", [0] * count)[i]),
        )
        lines.setdefault(key, []).append(token)
    text = "\n".join(" ".join(tokens) for _, tokens in sorted(lines.items()))
    _trace(
        f"ocr size={original_size[0]}x{original_size[1]}->{work.size[0]}x{work.size[1]} words={len(words)} text={_clean_preview(text)!r}"
    )
    return text, words, work.size


global_collector.base.ocr_image = fast_multilingual_ocr

if __name__ == "__main__":
    global_collector.base.main()
