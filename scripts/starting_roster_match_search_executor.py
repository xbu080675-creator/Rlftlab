#!/usr/bin/env python3
"""Actively search official social sources for a specific scheduled match.

Unlike the legacy recent-post scan, this stage starts from Riot schedule targets
and searches `match day + both teams + lineup intent`. Search hits are accepted
only when the canonical post belongs to the configured official league/team
account. Results are prepended to the browser spool; recent-post scanning remains
fallback only.
"""
from __future__ import annotations

import importlib.util
import json
import re
from pathlib import Path
from urllib.parse import quote, urlparse

from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeoutError

ROOT = Path(__file__).resolve().parent


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module


wrapper = load_module("rift_browser_four_lane", ROOT / "starting_roster_browser_four_lane.py")
browser = wrapper.browser
query_builder = load_module("rift_match_query", ROOT / "starting_roster_match_query.py")


def source_key(src: dict) -> str:
    return f"{src.get('platform','')}:{src.get('uid') or src.get('handle') or src.get('account','')}"


def target_applies(src: dict, target: dict) -> bool:
    if str(src.get("league") or "").upper() != str(target.get("league") or "").upper():
        return False
    if str(src.get("source") or "") != "TEAM_SOCIAL":
        return True
    team = str(src.get("team") or "").upper().strip()
    return team in {str(v).upper().strip() for v in target.get("teams") or []}


def weibo_owner_tokens(src: dict) -> set[str]:
    owners = set()
    uid = str(src.get("uid") or "").strip()
    if uid:
        owners.add(uid.lower())
    profile = urlparse(str(src.get("profileUrl") or ""))
    parts = [p for p in profile.path.split("/") if p]
    if parts:
        if parts[0] == "u" and len(parts) >= 2:
            owners.add(parts[1].lower())
        elif parts[0] not in {"hot", "search", "tv", "n", "status", "detail"}:
            owners.add(parts[0].lower())
    return owners


def canonical_weibo_post(url: str, src: dict) -> tuple[str, str] | None:
    raw = browser.normalize_url(url, "https://weibo.com")
    parsed = urlparse(raw)
    if parsed.netloc.lower() not in {"weibo.com", "www.weibo.com"}:
        return None
    parts = [p for p in parsed.path.split("/") if p]
    if len(parts) < 2:
        return None
    if parts[0] == "u" and len(parts) >= 3:
        owner, post_id = parts[1], parts[2]
    else:
        owner, post_id = parts[0], parts[1]
    if owner.lower() not in weibo_owner_tokens(src):
        return None
    post_id = post_id.split("?")[0]
    if not post_id.isalnum() or len(post_id) < 6:
        return None
    return f"https://weibo.com/{owner}/{post_id}", post_id


def collect_weibo_search(page, context, src: dict, target: dict, diagnostics: list[str], max_queries: int = 2) -> list[dict]:
    teams = target.get("teams") or []
    if len(teams) < 2:
        return []
    queries = query_builder.build_match_search_queries(target.get("matchDateLocal", ""), teams[0], teams[1], target.get("league", ""))[:max_queries]
    posts = []
    for query in queries:
        search_url = f"https://s.weibo.com/weibo?q={quote(query)}"
        try:
            page.goto(search_url, wait_until="domcontentloaded", timeout=30000)
            page.wait_for_timeout(2500)
            anchors = page.locator("a[href]")
            before = len(posts)
            for index in range(min(anchors.count(), 350)):
                anchor = anchors.nth(index)
                hit = canonical_weibo_post(anchor.get_attribute("href") or "", src)
                if not hit:
                    continue
                post_url, post_id = hit
                node = browser.ancestor_card(anchor)
                try:
                    text = browser.clean(node.inner_text(timeout=1200))
                except Exception:
                    text = browser.clean(anchor.inner_text(timeout=700))
                images = browser.images_from_node(node, post_url)
                if len(text) < 4 and not images:
                    continue
                post = {
                    "id": post_id,
                    "url": post_url,
                    "published": None,
                    "text": text,
                    "images": images,
                    "discovery": "MATCH_TARGET_SEARCH",
                    "searchQuery": query,
                    "targetEventId": target.get("eventId", ""),
                    "targetMatchDateLocal": target.get("matchDateLocal", ""),
                    "targetTeams": teams[:2],
                }
                posts.append(browser.materialize_post_media(context, post, diagnostics, src.get("account", "weibo")))
                posts = browser.dedupe(posts)
                if len(posts) >= 4:
                    break
            diagnostics.append(f"{src.get('account','weibo')}: match_search query='{query}' hits={len(posts)-before}")
            if posts:
                break
        except PlaywrightTimeoutError:
            diagnostics.append(f"{src.get('account','weibo')}: match_search_timeout query='{query}'")
        except Exception as exc:
            diagnostics.append(f"{src.get('account','weibo')}: match_search_{type(exc).__name__}:{str(exc)[:120]}")
    return browser.dedupe(posts)[:4]


def collect_x_search(page, context, src: dict, target: dict, diagnostics: list[str], max_queries: int = 2) -> list[dict]:
    teams = target.get("teams") or []
    handle = str(src.get("handle") or "").lstrip("@")
    if len(teams) < 2 or not handle:
        return []
    queries = query_builder.build_match_search_queries(target.get("matchDateLocal", ""), teams[0], teams[1], target.get("league", ""))[:max_queries]
    posts = []
    for query in queries:
        scoped = f"{query} from:{handle}"
        search_url = f"https://x.com/search?q={quote(scoped)}&src=typed_query&f=live"
        try:
            page.goto(search_url, wait_until="domcontentloaded", timeout=30000)
            page.wait_for_timeout(3000)
            articles = page.locator("article")
            before = len(posts)
            for index in range(min(articles.count(), 40)):
                article = articles.nth(index)
                links = article.locator(f'a[href*="/{handle}/status/"]')
                if not links.count():
                    continue
                href = links.first.get_attribute("href") or ""
                match = re.search(rf"/{re.escape(handle)}/status/(\d+)", href, re.I)
                if not match:
                    continue
                post_url = browser.normalize_url(href.split("?")[0], "https://x.com")
                try:
                    text = browser.clean(article.inner_text(timeout=1200))
                except Exception:
                    text = ""
                images = browser.images_from_node(article, post_url)
                published = None
                times = article.locator("time")
                if times.count():
                    published = times.first.get_attribute("datetime")
                post = {
                    "id": match.group(1),
                    "url": post_url,
                    "published": published,
                    "text": text,
                    "images": images,
                    "discovery": "MATCH_TARGET_SEARCH",
                    "searchQuery": query,
                    "targetEventId": target.get("eventId", ""),
                    "targetMatchDateLocal": target.get("matchDateLocal", ""),
                    "targetTeams": teams[:2],
                }
                posts.append(browser.materialize_post_media(context, post, diagnostics, src.get("account", "x")))
                posts = browser.dedupe(posts)
                if len(posts) >= 4:
                    break
            diagnostics.append(f"{src.get('account','x')}: match_search query='{query}' hits={len(posts)-before}")
            if posts:
                break
        except PlaywrightTimeoutError:
            diagnostics.append(f"{src.get('account','x')}: match_search_timeout query='{query}'")
        except Exception as exc:
            diagnostics.append(f"{src.get('account','x')}: match_search_{type(exc).__name__}:{str(exc)[:120]}")
    return browser.dedupe(posts)[:4]


def main() -> None:
    sources_path = Path("data/global/starting_roster_sources.json")
    targets_path = Path("data/global/starting_roster_match_targets.json")
    spool_path = Path("data/global/starting_roster_browser_posts.json")
    cfg = json.loads(sources_path.read_text(encoding="utf-8"))
    targets_doc = json.loads(targets_path.read_text(encoding="utf-8")) if targets_path.exists() else {"targets": []}
    spool = json.loads(spool_path.read_text(encoding="utf-8")) if spool_path.exists() else {"schemaVersion": 2, "sources": {}, "diagnostics": []}
    spool.setdefault("sources", {})
    diagnostics = spool.setdefault("diagnostics", [])
    targets = targets_doc.get("targets") or []
    diagnostics.extend(targets_doc.get("diagnostics") or [])
    diagnostics.append(f"match_target_search targets={len(targets)}")
    if not targets:
        spool_path.write_text(json.dumps(spool, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return

    sources = list(cfg.get("leagues", [])) + list(cfg.get("teams", []))
    with sync_playwright() as playwright:
        chromium = playwright.chromium.launch(headless=True, args=["--disable-blink-features=AutomationControlled", "--no-sandbox", "--disable-dev-shm-usage"])
        context = chromium.new_context(
            locale="en-US",
            timezone_id="UTC",
            viewport={"width": 1440, "height": 1400},
            user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        )
        context.set_extra_http_headers({"Accept-Language": "en-US,en;q=0.9,zh-CN;q=0.8,ko;q=0.7,ja;q=0.7"})
        for src in sources:
            applicable = [target for target in targets if target_applies(src, target)][:4]
            if not applicable:
                continue
            key = source_key(src)
            search_posts = []
            page = context.new_page()
            try:
                for target in applicable:
                    kind = src.get("kind")
                    if kind == "WEIBO_MOBILE":
                        search_posts.extend(collect_weibo_search(page, context, src, target, diagnostics))
                    elif kind == "X_SYNDICATION":
                        search_posts.extend(collect_x_search(page, context, src, target, diagnostics))
                    search_posts = browser.dedupe(search_posts)
                    if len(search_posts) >= 6:
                        break
            finally:
                page.close()
            existing = spool["sources"].get(key) or []
            merged = browser.dedupe(search_posts + existing)
            spool["sources"][key] = merged[:24]
            diagnostics.append(f"{src.get('account',key)}: match_search_posts={len(search_posts)} merged_posts={len(merged[:24])}")
        context.close()
        chromium.close()

    spool["schemaVersion"] = max(int(spool.get("schemaVersion") or 0), 3)
    spool["matchTargetSearch"] = {"targets": targets, "priority": "SEARCH_FIRST_RECENT_FALLBACK"}
    spool_path.write_text(json.dumps(spool, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"targets": len(targets), "diagnostics": diagnostics[-20:]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
