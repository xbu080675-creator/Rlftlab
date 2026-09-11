#!/usr/bin/env python3
"""Browser transport for official starting-roster sources worldwide.

This stage is intentionally platform-agnostic at the output boundary: every
supported official source becomes the same post shape and every protected image
is materialized while the authenticated/browser context is still alive.  The
normalizer/OCR stage consumes the spool afterwards.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urljoin, urlparse

from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeoutError

MEDIA_DIR = Path("data/global/starting_roster_browser_media")


def now_iso():
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def clean(value):
    return re.sub(r"\s+", " ", value or "").strip()


def dedupe(items):
    out, seen = [], set()
    for item in items:
        key = item.get("url") or item.get("id")
        if not key or key in seen:
            continue
        seen.add(key)
        out.append(item)
    return out


def normalize_url(url, base):
    if not url:
        return ""
    if url.startswith("//"):
        return "https:" + url
    return urljoin(base, url)


def image_like(url):
    lowered = (url or "").lower()
    return any(host in lowered for host in (
        "sinaimg", "twimg.com", "fbcdn.net", "cdninstagram.com", "instagram.com",
        "ytimg.com", "ggpht.com", "imgur.com", "cloudfront.net",
    )) or re.search(r"\.(?:png|jpe?g|webp)(?:\?|$)", lowered) is not None


def cache_image(context, url, referer, diagnostics, label):
    """Fetch protected media while browser cookies/headers are still available."""
    url = normalize_url(url, referer)
    if not url or not url.startswith("http") or not image_like(url):
        return url
    MEDIA_DIR.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256(url.encode("utf-8")).hexdigest()[:24]
    target = MEDIA_DIR / f"{digest}.img"
    if target.exists() and target.stat().st_size > 1024:
        return str(target)
    try:
        response = context.request.get(
            url,
            headers={
                "Referer": referer,
                "Accept": "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            },
            timeout=20000,
        )
        if not response.ok:
            diagnostics.append(f"{label}: media_http={response.status}")
            return url
        body = response.body()
        if len(body) < 512:
            diagnostics.append(f"{label}: media_too_small={len(body)}")
            return url
        target.write_bytes(body)
        return str(target)
    except Exception as exc:
        diagnostics.append(f"{label}: media_{type(exc).__name__}")
        return url


def materialize_post_media(context, post, diagnostics, label):
    post_url = post.get("url") or "https://example.invalid/"
    cached = []
    for image in (post.get("images") or [])[:12]:
        value = cache_image(context, image, post_url, diagnostics, label)
        if value and value not in cached:
            cached.append(value)
    post["images"] = cached
    return post


def ancestor_card(anchor):
    selectors = [
        "xpath=ancestor::article[1]",
        "xpath=ancestor::*[@role='article'][1]",
        "xpath=ancestor::*[contains(@class,'Feed')][1]",
        "xpath=ancestor::*[contains(@class,'card')][1]",
        "xpath=ancestor::*[contains(@class,'Card')][1]",
        "xpath=ancestor::div[6]",
    ]
    for selector in selectors:
        node = anchor.locator(selector)
        if node.count():
            return node
    return anchor


def images_from_node(node, base_url):
    images = []
    try:
        imgs = node.locator("img")
        for index in range(min(imgs.count(), 24)):
            img = imgs.nth(index)
            candidates = [
                img.get_attribute("src"),
                img.get_attribute("data-src"),
                img.get_attribute("data-original"),
            ]
            srcset = img.get_attribute("srcset") or ""
            if srcset:
                candidates.extend(part.strip().split(" ")[0] for part in srcset.split(","))
            for candidate in candidates:
                url = normalize_url(candidate or "", base_url)
                if url.startswith("http") and image_like(url) and url not in images:
                    images.append(url)
    except Exception:
        pass
    return images


def collect_weibo(page, context, src, diagnostics, limit=24):
    uid = str(src.get("uid") or "")
    profile = src.get("profileUrl") or f"https://weibo.com/u/{uid}"
    page.goto(profile, wait_until="domcontentloaded", timeout=40000)
    page.wait_for_timeout(5000)
    for _ in range(5):
        page.mouse.wheel(0, 1500)
        page.wait_for_timeout(900)

    # Weibo uses both numeric UID URLs and vanity account URLs.  Search all
    # plausible status anchors instead of assuming one path form.
    anchors = page.locator("a[href]")
    posts = []
    for index in range(min(anchors.count(), 500)):
        anchor = anchors.nth(index)
        href = anchor.get_attribute("href") or ""
        absolute = normalize_url(href, "https://weibo.com")
        parsed = urlparse(absolute)
        if "weibo.com" not in parsed.netloc:
            continue
        path = parsed.path.rstrip("/")
        patterns = [
            r"/(?:u/)?\d+/([A-Za-z0-9]{6,})$",
            r"/[^/]+/([A-Za-z0-9]{6,})$",
            r"/status/([A-Za-z0-9]{6,})$",
            r"/detail/([A-Za-z0-9]{6,})$",
        ]
        match = next((m for pattern in patterns if (m := re.search(pattern, path))), None)
        if not match:
            continue
        post_url = absolute.split("?")[0]
        node = ancestor_card(anchor)
        try:
            text = clean(node.inner_text(timeout=1500))
        except Exception:
            text = clean(anchor.inner_text(timeout=800))
        images = images_from_node(node, post_url)
        post = {"id": match.group(1), "url": post_url, "published": None, "text": text, "images": images}
        posts.append(materialize_post_media(context, post, diagnostics, src.get("account", "weibo")))
        posts = dedupe(posts)
        if len(posts) >= limit:
            break

    # Last resort: cards can render without a canonical status anchor. Keep a
    # synthetic URL only when the card actually contains useful text/media.
    if not posts:
        cards = page.locator("article, [role='article'], [class*='Feed'], [class*='card']")
        for index in range(min(cards.count(), 80)):
            node = cards.nth(index)
            try:
                text = clean(node.inner_text(timeout=1000))
            except Exception:
                continue
            images = images_from_node(node, profile)
            if len(text) < 8 and not images:
                continue
            post_url = f"{profile}#browser-card-{index}"
            post = {"id": f"browser-{index}", "url": post_url, "published": None, "text": text, "images": images}
            posts.append(materialize_post_media(context, post, diagnostics, src.get("account", "weibo")))
            if len(posts) >= limit:
                break
    return dedupe(posts)[:limit]


def collect_x(page, context, src, diagnostics, limit=24):
    handle = str(src.get("handle") or "").lstrip("@")
    profile = src.get("profileUrl") or f"https://x.com/{handle}"
    page.goto(profile, wait_until="domcontentloaded", timeout=40000)
    page.wait_for_timeout(5500)
    for _ in range(5):
        page.mouse.wheel(0, 1500)
        page.wait_for_timeout(900)

    posts = []
    articles = page.locator("article")
    for index in range(min(articles.count(), 60)):
        article = articles.nth(index)
        try:
            text = clean(article.inner_text(timeout=1500))
        except Exception:
            continue
        links = article.locator('a[href*="/status/"]')
        if not links.count():
            continue
        href = links.first.get_attribute("href") or ""
        match = re.search(r"/status/(\d+)", href)
        if not match:
            continue
        url = normalize_url(href.split("?")[0], "https://x.com")
        images = images_from_node(article, url)
        published = None
        times = article.locator("time")
        if times.count():
            published = times.first.get_attribute("datetime")
        post = {"id": match.group(1), "url": url, "published": published, "text": text, "images": images}
        posts.append(materialize_post_media(context, post, diagnostics, src.get("account", "x")))
        if len(posts) >= limit:
            break
    return dedupe(posts)


def collect_generic(page, context, src, diagnostics, limit=24):
    profile = src.get("profileUrl") or src.get("url")
    if not profile:
        return []
    page.goto(profile, wait_until="domcontentloaded", timeout=40000)
    page.wait_for_timeout(4500)
    for _ in range(4):
        page.mouse.wheel(0, 1300)
        page.wait_for_timeout(800)

    host = urlparse(profile).netloc
    posts = []
    containers = page.locator("article, [role='article'], main section, [class*='post'], [class*='Post'], [class*='card'], [class*='Card']")
    for index in range(min(containers.count(), 100)):
        node = containers.nth(index)
        try:
            text = clean(node.inner_text(timeout=1200))
        except Exception:
            continue
        links = node.locator("a[href]")
        href = ""
        for j in range(min(links.count(), 20)):
            candidate = normalize_url(links.nth(j).get_attribute("href") or "", profile)
            if candidate.startswith("http") and urlparse(candidate).netloc.endswith(host.split(":")[0]):
                href = candidate.split("?")[0]
                break
        url = href or f"{profile}#browser-card-{index}"
        images = images_from_node(node, url)
        if len(text) < 8 and not images:
            continue
        post = {"id": hashlib.sha1(url.encode()).hexdigest()[:16], "url": url, "published": None, "text": text, "images": images}
        posts.append(materialize_post_media(context, post, diagnostics, src.get("account", "generic")))
        if len(posts) >= limit:
            break
    return dedupe(posts)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sources", default="data/global/starting_roster_sources.json")
    parser.add_argument("--output", default="data/global/starting_roster_browser_posts.json")
    args = parser.parse_args()

    cfg = json.loads(Path(args.sources).read_text(encoding="utf-8"))
    sources = list(cfg.get("leagues", [])) + list(cfg.get("teams", []))
    result = {"schemaVersion": 2, "updatedAt": now_iso(), "sources": {}, "diagnostics": []}

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(
            headless=True,
            args=["--disable-blink-features=AutomationControlled", "--no-sandbox", "--disable-dev-shm-usage"],
        )
        context = browser.new_context(
            locale="en-US",
            timezone_id="UTC",
            viewport={"width": 1440, "height": 1400},
            user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        )
        context.set_extra_http_headers({"Accept-Language": "en-US,en;q=0.9,zh-CN;q=0.8,ko;q=0.7,ja;q=0.7"})

        for src in sources:
            kind = src.get("kind")
            key = f"{src.get('platform','')}:{src.get('uid') or src.get('handle') or src.get('account','')}"
            page = context.new_page()
            label = src.get("account") or key
            try:
                if kind == "WEIBO_MOBILE":
                    posts = collect_weibo(page, context, src, result["diagnostics"])
                elif kind == "X_SYNDICATION":
                    posts = collect_x(page, context, src, result["diagnostics"])
                elif kind in {"INSTAGRAM_BROWSER", "OFFICIAL_HTML", "GENERIC_BROWSER"}:
                    posts = collect_generic(page, context, src, result["diagnostics"])
                else:
                    continue
                result["sources"][key] = posts
                local_media = sum(1 for post in posts for image in post.get("images", []) if not str(image).startswith("http"))
                result["diagnostics"].append(f"{label}: browser_posts={len(posts)} cached_media={local_media}")
            except PlaywrightTimeoutError:
                result["diagnostics"].append(f"{label}: browser_timeout")
            except Exception as exc:
                result["diagnostics"].append(f"{label}: browser_{type(exc).__name__}:{str(exc)[:180]}")
            finally:
                page.close()
        context.close()
        browser.close()

    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"sources": len(result["sources"]), "diagnostics": result["diagnostics"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
