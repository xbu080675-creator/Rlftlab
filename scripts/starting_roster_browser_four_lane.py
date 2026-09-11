#!/usr/bin/env python3
"""Browser transport wrapper that preserves original official-media URLs.

The browser collector still caches protected images for server OCR, while clients
also receive the original media URL for their own OCR / system-AI fallback.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("rift_roster_browser", ROOT / "starting_roster_browser_collector.py")
browser = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(browser)

_original = browser.materialize_post_media


def materialize_with_origin(context, post, diagnostics, label):
    row = dict(post)
    row["originalImages"] = list(dict.fromkeys(post.get("images") or []))[:12]
    materialized = _original(context, row, diagnostics, label)
    materialized["originalImages"] = row["originalImages"]
    return materialized


browser.materialize_post_media = materialize_with_origin

if __name__ == "__main__":
    browser.main()
