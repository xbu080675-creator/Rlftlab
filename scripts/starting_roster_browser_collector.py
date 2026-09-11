#!/usr/bin/env python3
"""Browser transport for official social roster posts.

GitHub-hosted IPs are frequently blocked by Weibo/X JSON endpoints.  This stage
uses a real Chromium page as a transport only; parsing/OCR/normalization remains
in starting_roster_collector.py.  Output is a small source->posts spool consumed
by starting_roster_global_collector.py.
"""
from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urljoin

from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeoutError


def now_iso():
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def clean(s):
    return re.sub(r"\s+", " ", s or "").strip()


def dedupe(items):
    out, seen = [], set()
    for item in items:
        key = item.get("url") or item.get("id")
        if not key or key in seen:
            continue
        seen.add(key)
        out.append(item)
    return out


def collect_weibo(page, src, limit=24):
    uid = str(src.get("uid") or "")
    profile = src.get("profileUrl") or f"https://weibo.com/u/{uid}"
    page.goto(profile, wait_until="domcontentloaded", timeout=35000)
    page.wait_for_timeout(4500)
    # Let lazy timeline/image nodes settle.
    for _ in range(3):
        page.mouse.wheel(0, 1300)
        page.wait_for_timeout(800)

    rows = page.locator(f'a[href*="/{uid}/"], a[href*="weibo.com/{uid}/"]')
    posts = []
    count = min(rows.count(), 120)
    for i in range(count):
        a = rows.nth(i)
        href = a.get_attribute("href") or ""
        m = re.search(rf"/(?:u/)?{re.escape(uid)}/([A-Za-z0-9]+)", href)
        if not m:
            continue
        post_url = urljoin("https://weibo.com", href.split("?")[0])
        # Walk to a reasonably small article/card container.
        node = a.locator("xpath=ancestor::*[self::article or @role='article' or contains(@class,'Feed') or contains(@class,'card')][1]")
        if node.count() == 0:
            node = a.locator("xpath=ancestor::div[6]")
        try:
            text = clean(node.inner_text(timeout=1200))
        except Exception:
            text = clean(a.inner_text(timeout=800))
        images = []
        try:
            imgs = node.locator("img")
            for j in range(min(imgs.count(), 16)):
                u = imgs.nth(j).get_attribute("src") or imgs.nth(j).get_attribute("data-src") or ""
                if u.startswith("//"):
                    u = "https:" + u
                if u.startswith("http") and ("sinaimg" in u or "wx" in u):
                    images.append(u)
        except Exception:
            pass
        posts.append({"id": m.group(1), "url": post_url, "published": None, "text": text, "images": list(dict.fromkeys(images))})
        if len(dedupe(posts)) >= limit:
            break
    return dedupe(posts)[:limit]


def collect_x(page, src, limit=24):
    handle = str(src.get("handle") or "").lstrip("@")
    profile = src.get("profileUrl") or f"https://x.com/{handle}"
    page.goto(profile, wait_until="domcontentloaded", timeout=35000)
    page.wait_for_timeout(5000)
    for _ in range(4):
        page.mouse.wheel(0, 1400)
        page.wait_for_timeout(900)

    posts = []
    articles = page.locator("article")
    for i in range(min(articles.count(), 40)):
        article = articles.nth(i)
        try:
            text = clean(article.inner_text(timeout=1200))
        except Exception:
            continue
        links = article.locator(f'a[href*="/{handle}/status/"]')
        if links.count() == 0:
            links = article.locator('a[href*="/status/"]')
        if links.count() == 0:
            continue
        href = links.first.get_attribute("href") or ""
        m = re.search(r"/status/(\d+)", href)
        if not m:
            continue
        url = urljoin("https://x.com", href.split("?")[0])
        images = []
        imgs = article.locator("img")
        for j in range(min(imgs.count(), 16)):
            u = imgs.nth(j).get_attribute("src") or ""
            if u.startswith("http") and "twimg.com" in u and "/profile_images/" not in u:
                images.append(u)
        published = None
        times = article.locator("time")
        if times.count():
            published = times.first.get_attribute("datetime")
        posts.append({"id": m.group(1), "url": url, "published": published, "text": text, "images": list(dict.fromkeys(images))})
        if len(posts) >= limit:
            break
    return dedupe(posts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sources", default="data/global/starting_roster_sources.json")
    ap.add_argument("--output", default="data/global/starting_roster_browser_posts.json")
    args = ap.parse_args()

    cfg = json.loads(Path(args.sources).read_text(encoding="utf-8"))
    sources = list(cfg.get("leagues", [])) + list(cfg.get("teams", []))
    result = {"schemaVersion": 1, "updatedAt": now_iso(), "sources": {}, "diagnostics": []}

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, args=["--disable-blink-features=AutomationControlled", "--no-sandbox"])
        context = browser.new_context(
            locale="zh-CN",
            timezone_id="Asia/Shanghai",
            viewport={"width": 1440, "height": 1200},
            user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        )
        for src in sources:
            kind = src.get("kind")
            key = f"{src.get('platform','')}:{src.get('uid') or src.get('handle') or src.get('account','')}"
            page = context.new_page()
            try:
                if kind == "WEIBO_MOBILE":
                    posts = collect_weibo(page, src)
                elif kind == "X_SYNDICATION":
                    posts = collect_x(page, src)
                else:
                    continue
                result["sources"][key] = posts
                result["diagnostics"].append(f"{src.get('account')}: browser_posts={len(posts)}")
            except PlaywrightTimeoutError as e:
                result["diagnostics"].append(f"{src.get('account')}: browser_timeout")
            except Exception as e:
                result["diagnostics"].append(f"{src.get('account')}: browser_{type(e).__name__}:{str(e)[:160]}")
            finally:
                page.close()
        context.close()
        browser.close()

    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"sources": len(result["sources"]), "diagnostics": result["diagnostics"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
