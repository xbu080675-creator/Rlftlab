#!/usr/bin/env python3
"""Run bounded roster OCR and preserve trustworthy official announcement metadata.

Lane 1 is the normalized server feed. Even when OCR/lineup parsing fails, recent
real official posts remain visible to every client as announcement metadata.
Navigation/profile shells are never evidence: raw fallback is allowed to be
unparsed, but it still has to be an actual official post candidate.
"""
from __future__ import annotations

import importlib.util
import json
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


def is_candidate_post(text: str, original_images: list[str], images: list, keywords: list[str]) -> tuple[bool, bool]:
    lowered = text.lower()
    keyword_hit = any(keyword in lowered for keyword in keywords)
    has_media = bool(original_images or images)
    shell_hits = sum(1 for marker in SHELL_MARKERS if marker in text)

    # A browser/profile shell may contain many avatars and therefore superficially
    # look like an image post. Multiple shell markers are a hard rejection.
    if shell_hits >= 2:
        return False, keyword_hit
    # Text-only rows must actually look roster-related. Image-only official posts
    # remain valid candidates because many clubs put the entire lineup in artwork.
    if not keyword_hit and not has_media:
        return False, keyword_hit
    return True, keyword_hit


def add_announcements() -> None:
    if not OUTPUT.exists() or not SOURCES.exists() or not SPOOL.exists():
        return
    cfg = json.loads(SOURCES.read_text(encoding="utf-8"))
    spool = json.loads(SPOOL.read_text(encoding="utf-8"))
    payload = json.loads(OUTPUT.read_text(encoding="utf-8"))
    browser_sources = spool.get("sources") or {}
    keywords = [str(x).lower() for x in (cfg.get("keywords") or []) if str(x).strip()]
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
            accepted, keyword_hit = is_candidate_post(text, original_images, images, keywords)
            if not accepted:
                continue
            score = 100 if keyword_hit else 0
            if images or original_images:
                score += 20
            if published:
                score += 5
            ranked.append((score, -index, post, text, images, original_images, published, keyword_hit))
        ranked.sort(reverse=True, key=lambda item: (item[0], item[1]))
        for _, _, post, text, images, original_images, published, keyword_hit in ranked[:3]:
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
                "candidateBasis": "KEYWORD" if keyword_hit else "IMAGE_ONLY",
            })

    deduped = {}
    for row in rows:
        deduped[row["id"]] = row
    payload["schemaVersion"] = max(3, int(payload.get("schemaVersion", 2)))
    payload["announcements"] = list(deduped.values())[:60]
    OUTPUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    fast.global_collector.base.main()
    add_announcements()
