#!/usr/bin/env python3
"""Run bounded roster OCR and always preserve official announcement metadata.

Lane 1 is the normalized server feed. Even when OCR/lineup parsing fails, recent
official posts remain visible to every client as announcement metadata instead of
silently disappearing. Device-side OCR/system AI/local vision can then enhance it.
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
            score = 0
            lowered = text.lower()
            if any(k in lowered for k in keywords):
                score += 100
            if images:
                score += 20
            if published:
                score += 5
            ranked.append((score, -index, post, text, images, published))
        ranked.sort(reverse=True, key=lambda item: (item[0], item[1]))
        for _, _, post, text, images, published in ranked[:3]:
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
                "imageCount": len(images),
                "parseStatus": "PARSED" if url in evidence_urls else "UNPARSED",
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
