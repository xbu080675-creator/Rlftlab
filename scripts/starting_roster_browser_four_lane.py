#!/usr/bin/env python3
"""Browser transport wrapper for the four-lane roster pipeline.

The underlying collector caches protected images for server OCR. This wrapper
also preserves original official-media URLs for Android OCR/system-AI fallback
and rejects profile/navigation-shell links that are not real source posts.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("rift_roster_browser", ROOT / "starting_roster_browser_collector.py")
browser = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(browser)

_original_materialize = browser.materialize_post_media
_original_weibo = browser.collect_weibo


def materialize_with_origin(context, post, diagnostics, label):
    row = dict(post)
    row["originalImages"] = list(dict.fromkeys(post.get("images") or []))[:12]
    materialized = _original_materialize(context, row, diagnostics, label)
    materialized["originalImages"] = row["originalImages"]
    return materialized


def _real_weibo_post_for_source(post, src):
    """Only keep canonical posts owned by the configured official account.

    Weibo profile pages contain global nav links such as /hot/list/... and
    recommendation cards from unrelated accounts. The old broad two-segment
    regex accepted those as if they were posts from the source account, which
    polluted raw-announcement fallback and caused OCR to inspect random images.
    """
    raw_url = str(post.get("url") or "")
    if not raw_url.startswith("http") or "#browser-card-" in raw_url:
        return False
    parsed = urlparse(raw_url)
    if parsed.netloc.lower() not in {"weibo.com", "www.weibo.com"}:
        return False
    segments = [segment for segment in parsed.path.split("/") if segment]
    if len(segments) < 2:
        return False

    uid = str(src.get("uid") or "").strip()
    profile = urlparse(str(src.get("profileUrl") or ""))
    profile_segments = [segment for segment in profile.path.split("/") if segment]
    owners = {uid} if uid else set()
    if profile_segments:
        if profile_segments[0] == "u" and len(profile_segments) >= 2:
            owners.add(profile_segments[1])
        elif profile_segments[0] not in {"hot", "search", "tv", "n", "status", "detail"}:
            owners.add(profile_segments[0])

    if segments[0] == "u" and len(segments) >= 3:
        owner, post_id = segments[1], segments[2]
    else:
        owner, post_id = segments[0], segments[1]

    if owner not in owners:
        return False
    if not post_id.isalnum() or len(post_id) < 6:
        return False
    return True


def collect_weibo_strict(page, context, src, diagnostics, limit=24):
    # Ask the base collector for extra candidates because shell links may occupy
    # the first positions, then enforce source ownership before returning rows.
    candidates = _original_weibo(page, context, src, diagnostics, limit=max(limit * 3, 48))
    kept = [post for post in candidates if _real_weibo_post_for_source(post, src)]
    dropped = len(candidates) - len(kept)
    if dropped:
        diagnostics.append(f"{src.get('account', 'weibo')}: rejected_nonpost_shells={dropped}")
    return browser.dedupe(kept)[:limit]


browser.materialize_post_media = materialize_with_origin
browser.collect_weibo = collect_weibo_strict

if __name__ == "__main__":
    browser.main()
