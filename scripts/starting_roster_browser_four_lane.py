#!/usr/bin/env python3
"""Browser transport wrapper for the four-lane roster pipeline.

The underlying collector caches protected images for server OCR. This wrapper
also preserves original official-media URLs for Android OCR/system-AI fallback,
rejects profile/navigation-shell links that are not real source posts, and drops
obvious avatar/UI assets before they can enter OCR.
"""
from __future__ import annotations

import importlib.util
import re
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("rift_roster_browser", ROOT / "starting_roster_browser_collector.py")
browser = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(browser)

_original_materialize = browser.materialize_post_media
_original_weibo = browser.collect_weibo


COMMON_MEDIA_MARKERS = (
    "/vvip_",
    "h5.sinaimg.cn/upload/",
    "icon",
    "avatar",
)


def _looks_like_post_media(url: str) -> bool:
    """Reject common avatars, tiny crops and page chrome before OCR.

    Weibo commonly exposes 180x180 profile crops inside every feed card. Those
    images are visually valid but semantically unrelated to the post and were the
    reason roster OCR ended up reading club logos/sponsor artwork.
    """
    lowered = str(url or "").lower()
    if not lowered.startswith("http"):
        return True
    if any(marker in lowered for marker in COMMON_MEDIA_MARKERS):
        return False
    if re.search(r"/crop\.[^/]*\.180/", lowered):
        return False
    if re.search(r"(?:^|[._/-])180(?:[x._/-]|$)", lowered) and "mw2000" not in lowered:
        return False
    return True


def materialize_with_origin(context, post, diagnostics, label):
    row = dict(post)
    original = list(dict.fromkeys(post.get("images") or []))[:12]
    kept = [url for url in original if _looks_like_post_media(str(url))]
    dropped = len(original) - len(kept)
    if dropped:
        diagnostics.append(f"{label}: rejected_common_media={dropped}")
    row["images"] = kept
    row["originalImages"] = kept
    materialized = _original_materialize(context, row, diagnostics, label)
    materialized["originalImages"] = kept
    return materialized


def _real_weibo_post_for_source(post, src):
    """Only keep canonical posts owned by the configured official account."""
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
