#!/usr/bin/env python3
"""Global multi-platform adapter layer for RiftLab starting-roster collector.

The normalized parser/OCR stays in starting_roster_collector.py. This wrapper turns
multiple official publishing platforms into one post shape and hardens the transport
against anonymous-platform throttling without changing source provenance.
"""
from __future__ import annotations

import importlib.util
import json
import os
import re
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urljoin
from zoneinfo import ZoneInfo
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
    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36 RiftLabRosterBot/2.2",
    "Accept-Language": "en-US,en;q=0.9,ko;q=0.8,zh-CN;q=0.8,ja;q=0.7",
})

BROWSER_SPOOL = Path(os.environ.get(
    "RIFTLAB_ROSTER_BROWSER_SPOOL",
    "data/global/starting_roster_browser_posts.json",
))


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


def _source_key(source):
    return f"{source.get('platform','')}:{source.get('uid') or source.get('handle') or source.get('account','')}"


def _browser_posts(source, limit=20):
    if not BROWSER_SPOOL.exists():
        return []
    try:
        payload = json.loads(BROWSER_SPOOL.read_text(encoding="utf-8"))
        rows = ((payload.get("sources") or {}).get(_source_key(source)) or [])[:limit]
    except Exception:
        return []
    posts = []
    for row in rows:
        published = row.get("published")
        posts.append(_post(
            row.get("id"),
            row.get("url"),
            row.get("text"),
            row.get("images") or [],
            parse_time(published) if isinstance(published, str) else published,
        ))
    return [p for p in posts if p.get("url") and (p.get("text") or p.get("images"))]


base.ROLE_ALIASES["TOP"].extend(["탑", "トップ"])
base.ROLE_ALIASES["JUG"].extend(["정글", "ジャングル"])
base.ROLE_ALIASES["MID"].extend(["미드", "ミッド"])
base.ROLE_ALIASES["BOT"].extend(["원딜", "ボット"])
base.ROLE_ALIASES["SUP"].extend(["서폿", "サポート"])


def unicode_norm(value):
    return "".join(ch for ch in (value or "").upper() if ch.isalnum())


base.norm = unicode_norm


def multilingual_ocr(img):
    configs = "--psm 6"
    langs = os.environ.get("RIFTLAB_OCR_LANGS", "chi_sim+eng+kor+jpn")
    text = base.pytesseract.image_to_string(img, lang=langs, config=configs)
    data = base.pytesseract.image_to_data(
        img,
        lang=langs,
        config=configs,
        output_type=base.pytesseract.Output.DICT,
    )
    words = []
    n = len(data.get("text", []))
    for i in range(n):
        token = (data["text"][i] or "").strip()
        try:
            conf = float(data["conf"][i])
        except Exception:
            conf = -1
        if token and conf >= 20:
            words.append({
                "text": token,
                "left": int(data["left"][i]),
                "top": int(data["top"][i]),
                "width": int(data["width"][i]),
                "height": int(data["height"][i]),
                "conf": conf,
            })
    return text, words, img.size


base.ocr_image = multilingual_ocr


def global_infer_date(text, published, timezone_name):
    match = re.search(r"(?<!\d)(\d{1,2})\s*[月/.-]\s*(\d{1,2})\s*日?", text or "")
    source_time = published or base.utc_now()
    try:
        zone = ZoneInfo(timezone_name or "UTC")
    except Exception:
        zone = timezone.utc
    local = source_time.astimezone(zone)
    if match:
        month, day = int(match.group(1)), int(match.group(2))
        return f"{local.year:04d}-{month:02d}-{day:02d}"
    if any(word in (text or "") for word in ("明日", "明天", "내일", "翌日", "tomorrow", "Tomorrow")):
        local += timedelta(days=1)
    return local.date().isoformat()


base.infer_date = global_infer_date


def _weibo_post(uid, mblog):
    images = []
    for item in mblog.get("pics") or []:
        large = (item.get("large") or {}).get("url")
        if large:
            images.append(large)
    infos = mblog.get("pic_infos") or {}
    for pid in mblog.get("pic_ids") or []:
        info = infos.get(str(pid)) or infos.get(pid) or {}
        large = (info.get("large") or {}).get("url") or (info.get("largest") or {}).get("url")
        if large:
            images.append(large)
    bid = mblog.get("bid") or mblog.get("mblogid") or mblog.get("id")
    return _post(
        mblog.get("id") or bid,
        f"https://weibo.com/{uid}/{bid}" if bid else f"https://weibo.com/u/{uid}",
        base.clean_html(mblog.get("text_raw") or mblog.get("text") or ""),
        images,
        base.parse_weibo_time(mblog.get("created_at")),
    )


def _reader_weibo(uid, limit=20):
    errors = []
    for target in (f"weibo.com/u/{uid}", f"m.weibo.cn/u/{uid}"):
        try:
            response = SESSION.get(f"https://r.jina.ai/http://{target}", timeout=25, headers={"Accept": "text/plain"})
            response.raise_for_status()
            body = response.text
            status_re = re.compile(
                rf"https?://(?:www\.)?weibo\.com/(?:u/)?{re.escape(str(uid))}/([A-Za-z0-9]+)",
                re.I,
            )
            matches = list(status_re.finditer(body))
            posts = []
            for index, match in enumerate(matches[:limit]):
                start = max(0, match.start() - 900)
                end = matches[index + 1].start() if index + 1 < len(matches) else min(len(body), match.end() + 1600)
                chunk = body[start:end]
                images = re.findall(r"https?://[^\s)]+(?:sinaimg\.cn|sinaimg\.com)[^\s)]*", chunk)
                posts.append(_post(match.group(1), match.group(0), chunk, images, None))
            if posts:
                return posts
            errors.append(f"reader:{target}:empty")
        except Exception as exc:
            errors.append(f"reader:{target}:{type(exc).__name__}")
    raise RuntimeError(";".join(errors))


def fetch_weibo_robust(uid, limit=20):
    errors = []
    try:
        SESSION.get(
            f"https://m.weibo.cn/u/{uid}",
            timeout=10,
            headers={"Referer": f"https://m.weibo.cn/u/{uid}"},
        )
    except Exception:
        pass
    routes = [
        (
            "mobile",
            f"https://m.weibo.cn/api/container/getIndex?type=uid&value={uid}&containerid=107603{uid}",
            {"Referer": f"https://m.weibo.cn/u/{uid}", "X-Requested-With": "XMLHttpRequest"},
        ),
        (
            "desktop",
            f"https://weibo.com/ajax/statuses/mymblog?uid={uid}&page=1&feature=0",
            {"Referer": f"https://weibo.com/u/{uid}", "X-Requested-With": "XMLHttpRequest"},
        ),
    ]
    for label, url, headers in routes:
        try:
            response = SESSION.get(url, timeout=15, headers=headers)
            response.raise_for_status()
            content_type = (response.headers.get("content-type") or "").lower()
            body = response.text.lstrip()
            if "json" not in content_type and not body.startswith(("{", "[")):
                errors.append(f"{label}:non_json:{response.status_code}:{content_type[:32]}")
                continue
            data = response.json()
            if label == "mobile":
                rows = []
                for card in ((data.get("data") or {}).get("cards") or []):
                    mblog = card.get("mblog") or {}
                    if mblog:
                        rows.append(mblog)
            else:
                rows = ((data.get("data") or {}).get("list") or [])
            posts = [_weibo_post(uid, row) for row in rows[:limit] if row]
            if posts:
                return posts
            errors.append(f"{label}:empty")
        except Exception as exc:
            errors.append(f"{label}:{type(exc).__name__}:{exc}")
    try:
        return _reader_weibo(uid, limit)
    except Exception as exc:
        errors.append(f"reader:{exc}")
    raise RuntimeError("weibo_all_routes_failed[" + " | ".join(errors) + "]")


base.fetch_weibo_posts = fetch_weibo_robust


def _reader_x(handle: str, limit: int = 20):
    handle = handle.lstrip("@")
    errors = []
    for host in ("x.com", "twitter.com"):
        try:
            response = SESSION.get(
                f"https://r.jina.ai/http://{host}/{handle}",
                timeout=25,
                headers={"Accept": "text/plain"},
            )
            response.raise_for_status()
            body = response.text
            status_re = re.compile(
                rf"https?://(?:x\.com|twitter\.com)/{re.escape(handle)}/status/(\d+)",
                re.I,
            )
            matches = list(status_re.finditer(body))
            posts = []
            for index, match in enumerate(matches[:limit]):
                start = max(0, match.start() - 1000)
                end = matches[index + 1].start() if index + 1 < len(matches) else min(len(body), match.end() + 1800)
                chunk = body[start:end]
                images = re.findall(r"https?://pbs\.twimg\.com/media/[^\s)]+", chunk)
                posts.append(_post(match.group(1), f"https://x.com/{handle}/status/{match.group(1)}", chunk, images, None))
            if posts:
                return posts
            errors.append(f"{host}:empty")
        except Exception as exc:
            errors.append(f"{host}:{type(exc).__name__}")
    raise RuntimeError("x_reader_failed:" + ",".join(errors))


def fetch_x_syndication(handle: str, limit: int = 20):
    handle = handle.lstrip("@")
    url = f"https://syndication.twitter.com/srv/timeline-profile/screen-name/{handle}"
    errors = []
    for attempt in range(3):
        try:
            response = SESSION.get(url, timeout=20)
            if response.status_code == 429:
                retry_after = response.headers.get("retry-after")
                try:
                    wait = min(5.0, max(1.0, float(retry_after))) if retry_after else 1.5 * (attempt + 1)
                except Exception:
                    wait = 1.5 * (attempt + 1)
                errors.append(f"syndication:429:{wait:.1f}s")
                time.sleep(wait)
                continue
            response.raise_for_status()
            soup = BeautifulSoup(response.text, "html.parser")
            posts = []
            nodes = soup.select("article, .timeline-Tweet, [data-tweet-id]")
            for node in nodes[:limit]:
                text = node.get_text(" ", strip=True)
                link = node.find("a", href=re.compile(r"/status/\d+"))
                href = urljoin("https://x.com", link.get("href")) if link else f"https://x.com/{handle}"
                pidm = re.search(r"/status/(\d+)", href)
                images = []
                for img in node.find_all("img"):
                    src = img.get("src") or img.get("data-src")
                    if src and ("pbs.twimg.com" in src or "twimg.com" in src):
                        images.append(src)
                time_node = node.find("time")
                posts.append(_post(
                    pidm.group(1) if pidm else href,
                    href,
                    text,
                    images,
                    parse_time(time_node.get("datetime") if time_node else None),
                ))
            if posts:
                return posts
            errors.append("syndication:empty")
            break
        except Exception as exc:
            errors.append(f"syndication:{type(exc).__name__}:{exc}")
            break
    try:
        return _reader_x(handle, limit)
    except Exception as exc:
        errors.append(str(exc))
    raise RuntimeError("x_all_routes_failed[" + " | ".join(errors) + "]")


def fetch_youtube_atom(channel_id: str, limit: int = 20):
    url = f"https://www.youtube.com/feeds/videos.xml?channel_id={channel_id}"
    response = SESSION.get(url, timeout=20)
    response.raise_for_status()
    root = ET.fromstring(response.text)
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
    url = source.get("url") or source.get("profileUrl")
    if not url:
        return []
    response = SESSION.get(url, timeout=20)
    response.raise_for_status()
    soup = BeautifulSoup(response.text, "html.parser")
    keys = [key.lower() for key in source.get("keywords", [])]
    if not keys:
        keys = ["starting", "lineup", "roster", "首发", "선발", "先発"]
    links = []
    for anchor in soup.find_all("a", href=True):
        label = (anchor.get_text(" ", strip=True) + " " + anchor["href"]).lower()
        if any(key in label for key in keys):
            href = urljoin(url, anchor["href"])
            if href not in links:
                links.append(href)
        if len(links) >= limit:
            break
    posts = []
    for href in links:
        try:
            detail = SESSION.get(href, timeout=20)
            detail.raise_for_status()
            detail_soup = BeautifulSoup(detail.text, "html.parser")
            text = detail_soup.get_text(" ", strip=True)
            images = []
            for img in detail_soup.find_all("img"):
                src = img.get("src") or img.get("data-src")
                if src:
                    images.append(urljoin(href, src))
            posts.append(_post(href, href, text, images[:8], None))
        except Exception:
            continue
    return posts


_original_process = base.process_source
_robust_weibo_fetch = base.fetch_weibo_posts


def _process_posts(source, cfg, posts, transport_label):
    try:
        base.fetch_weibo_posts = lambda _uid, limit=20: posts[:limit]
        shim = dict(source)
        shim["kind"] = "WEIBO_MOBILE"
        shim["uid"] = "adapter"
        evidence, diagnostics = _original_process(shim, cfg)
        diagnostics = [f"{source.get('account')}: transport={transport_label}:posts={len(posts)}"] + diagnostics
        return evidence, diagnostics
    finally:
        base.fetch_weibo_posts = _robust_weibo_fetch


def process_source(source, cfg):
    browser_posts = _browser_posts(source)
    if browser_posts:
        return _process_posts(source, cfg, browser_posts, "browser")

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
    except Exception as exc:
        return [], [f"{source.get('account')}: {kind} {type(exc).__name__}: {exc}"]

    return _process_posts(source, cfg, posts, kind.lower())


base.process_source = process_source

if __name__ == "__main__":
    base.main()
