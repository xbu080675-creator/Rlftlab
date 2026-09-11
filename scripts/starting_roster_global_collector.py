#!/usr/bin/env python3
"""Global multi-platform adapter layer for RiftLab starting-roster collector.

The normalized parser/OCR stays in starting_roster_collector.py. This wrapper only
turns different official publishing platforms into the same post shape:
{id,url,published,text,images}.
"""
from __future__ import annotations

import importlib.util
import re
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urljoin
import xml.etree.ElementTree as ET

import requests
from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("rift_roster_base", ROOT / "starting_roster_collector.py")
base = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(base)

SESSION = requests.Session()
SESSION.headers.update({
    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36 RiftLabRosterBot/2.0",
    "Accept-Language": "en-US,en;q=0.9,ko;q=0.8,zh-CN;q=0.8,ja;q=0.7",
})


def parse_time(raw: str | None):
    if not raw:
        return None
    raw = raw.strip().replace("Z", "+00:00")
    try:
        return datetime.fromisoformat(raw).astimezone(timezone.utc)
    except Exception:
        return None


def _post(pid, url, text, images=None, published=None):
    return {
        "id": str(pid or url),
        "url": url,
        "published": published,
        "text": text or "",
        "images": list(dict.fromkeys(images or [])),
    }


def fetch_x_syndication(handle: str, limit: int = 20):
    # Public embed endpoint. No login/token is required; if X blocks it the
    # caller records a source-specific diagnostic and other official sources continue.
    url = f"https://syndication.twitter.com/srv/timeline-profile/screen-name/{handle.lstrip('@')}"
    r = SESSION.get(url, timeout=20)
    r.raise_for_status()
    soup = BeautifulSoup(r.text, "html.parser")
    posts = []
    nodes = soup.select("article, .timeline-Tweet, [data-tweet-id]")
    for node in nodes[:limit]:
        text = node.get_text(" ", strip=True)
        link = node.find("a", href=re.compile(r"/status/\d+"))
        href = urljoin("https://x.com", link.get("href")) if link else f"https://x.com/{handle.lstrip('@')}"
        pidm = re.search(r"/status/(\d+)", href)
        imgs = []
        for img in node.find_all("img"):
            src = img.get("src") or img.get("data-src")
            if src and ("pbs.twimg.com" in src or "twimg.com" in src):
                imgs.append(src)
        t = node.find("time")
        posts.append(_post(pidm.group(1) if pidm else href, href, text, imgs, parse_time(t.get("datetime") if t else None)))
    if not posts:
        raise RuntimeError("x_syndication_empty_or_blocked")
    return posts


def fetch_youtube_atom(channel_id: str, limit: int = 20):
    url = f"https://www.youtube.com/feeds/videos.xml?channel_id={channel_id}"
    r = SESSION.get(url, timeout=20)
    r.raise_for_status()
    root = ET.fromstring(r.text)
    ns = {"a": "http://www.w3.org/2005/Atom", "yt": "http://www.youtube.com/xml/schemas/2015"}
    posts = []
    for entry in root.findall("a:entry", ns)[:limit]:
        vid = entry.findtext("yt:videoId", default="", namespaces=ns)
        title = entry.findtext("a:title", default="", namespaces=ns)
        published = parse_time(entry.findtext("a:published", default="", namespaces=ns))
        link = entry.find("a:link", ns)
        href = link.get("href") if link is not None else f"https://www.youtube.com/watch?v={vid}"
        thumb = f"https://i.ytimg.com/vi/{vid}/maxresdefault.jpg" if vid else None
        posts.append(_post(vid, href, title, [thumb] if thumb else [], published))
    return posts


def fetch_official_html(source: dict, limit: int = 20):
    """Best-effort official-site scanner.

    It scans recent links whose text/url mentions roster keywords, then fetches the
    detail page so the common OCR/parser can inspect text and images. A site-specific
    selector can be added in JSON without changing the Android client.
    """
    url = source.get("url") or source.get("profileUrl")
    if not url:
        return []
    r = SESSION.get(url, timeout=20)
    r.raise_for_status()
    soup = BeautifulSoup(r.text, "html.parser")
    keys = [k.lower() for k in source.get("keywords", [])]
    if not keys:
        keys = ["starting", "lineup", "roster", "首发", "선발", "先発"]
    links = []
    for a in soup.find_all("a", href=True):
        label = (a.get_text(" ", strip=True) + " " + a["href"]).lower()
        if any(k in label for k in keys):
            href = urljoin(url, a["href"])
            if href not in links:
                links.append(href)
        if len(links) >= limit:
            break
    posts = []
    for href in links:
        try:
            rr = SESSION.get(href, timeout=20)
            rr.raise_for_status()
            ss = BeautifulSoup(rr.text, "html.parser")
            text = ss.get_text(" ", strip=True)
            imgs = []
            for img in ss.find_all("img"):
                src = img.get("src") or img.get("data-src")
                if src:
                    imgs.append(urljoin(href, src))
            posts.append(_post(href, href, text, imgs[:8], None))
        except Exception:
            continue
    return posts


_original_process = base.process_source


def process_source(source, cfg):
    kind = source.get("kind")
    if kind == "WEIBO_MOBILE":
        return _original_process(source, cfg)

    try:
        if kind == "X_SYNDICATION":
            posts = fetch_x_syndication(source.get("handle", ""))
        elif kind == "YOUTUBE_ATOM":
            posts = fetch_youtube_atom(source.get("channelId", ""))
        elif kind == "OFFICIAL_HTML":
            posts = fetch_official_html(source)
        else:
            return [], [f"{source.get('account')}: unsupported_kind {kind}"]
    except Exception as e:
        return [], [f"{source.get('account')}: {kind} {type(e).__name__}: {e}"]

    # Reuse the proven normalization/OCR path by supplying the already-fetched
    # official posts through the base collector's fetch hook.
    original_fetch = base.fetch_weibo_posts
    try:
        base.fetch_weibo_posts = lambda _uid, limit=20: posts[:limit]
        shim = dict(source)
        shim["kind"] = "WEIBO_MOBILE"
        shim["uid"] = "adapter"
        return _original_process(shim, cfg)
    finally:
        base.fetch_weibo_posts = original_fetch


base.process_source = process_source

if __name__ == "__main__":
    base.main()
