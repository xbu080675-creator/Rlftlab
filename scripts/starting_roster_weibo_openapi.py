#!/usr/bin/env python3
"""Discover LPL starting-roster posts through Weibo's Open API.

This adapter is based on the public API contract demonstrated by wangcch/weibo-mcp
(MIT, Copyright (c) 2026 wangcch). RiftLab keeps its own normalized output and
uses the API only as a discovery transport; official-source, matchup, date and
roster validation still happen in RiftLab.
"""
from __future__ import annotations

import importlib.util
import json
import os
import re
import time
from pathlib import Path
from urllib.parse import urlparse

import requests
from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeoutError

ROOT = Path(__file__).resolve().parent
SOURCES = Path("data/global/starting_roster_sources.json")
TARGETS = Path("data/global/starting_roster_match_targets.json")
SPOOL = Path("data/global/starting_roster_browser_posts.json")
TOKEN_ENDPOINT = os.environ.get("WEIBO_TOKEN_ENDPOINT", "https://open-im.api.weibo.com/open/auth/ws_token")
SEARCH_ENDPOINT = os.environ.get("WEIBO_SEARCH_ENDPOINT", "https://open-im.api.weibo.com/open/wis/search_query")
REQUEST_TIMEOUT = 15


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module


wrapper = load_module("rift_browser_four_lane_openapi", ROOT / "starting_roster_browser_four_lane.py")
browser = wrapper.browser
query_builder = load_module("rift_match_query_openapi", ROOT / "starting_roster_match_query.py")


def source_key(src: dict) -> str:
    return f"{src.get('platform','')}:{src.get('uid') or src.get('handle') or src.get('account','')}"


def target_applies(src: dict, target: dict) -> bool:
    if str(src.get("league") or "").upper() != "LPL" or str(target.get("league") or "").upper() != "LPL":
        return False
    if str(src.get("source") or "") != "TEAM_SOCIAL":
        return True
    team = str(src.get("team") or "").upper().strip()
    return team in {str(v).upper().strip() for v in target.get("teams") or []}


def owner_tokens(src: dict) -> set[str]:
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


def canonical_post(url: str, src: dict) -> tuple[str, str] | None:
    raw = browser.normalize_url(str(url or ""), "https://weibo.com")
    parsed = urlparse(raw)
    if parsed.netloc.lower() not in {"weibo.com", "www.weibo.com", "m.weibo.cn"}:
        return None
    parts = [p for p in parsed.path.split("/") if p]
    if len(parts) < 2:
        return None
    if parts[0] in {"u", "status", "detail"} and len(parts) >= 3:
        owner, post_id = parts[1], parts[2]
    else:
        owner, post_id = parts[0], parts[1]
    if owner.lower() not in owner_tokens(src):
        return None
    post_id = post_id.split("?")[0]
    if not post_id.isalnum() or len(post_id) < 6:
        return None
    return f"https://weibo.com/{owner}/{post_id}", post_id


def iter_strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for child in value.values():
            yield from iter_strings(child)
    elif isinstance(value, list):
        for child in value:
            yield from iter_strings(child)


def response_strings(payload: dict) -> list[str]:
    values = list(iter_strings(payload))
    data = payload.get("data") if isinstance(payload, dict) else None
    if isinstance(data, dict):
        raw = data.get("msg_json")
        if isinstance(raw, str) and raw.strip():
            try:
                values.extend(iter_strings(json.loads(raw)))
            except Exception:
                pass
    return values


def extract_source_posts(payload: dict, src: dict) -> list[tuple[str, str]]:
    out, seen = [], set()
    url_re = re.compile(r"https?://(?:www\.)?(?:weibo\.com|m\.weibo\.cn)/[^\s\"'<>]+", re.I)
    for value in response_strings(payload):
        for match in url_re.findall(value):
            hit = canonical_post(match.rstrip(".,);]"), src)
            if not hit or hit[0] in seen:
                continue
            seen.add(hit[0])
            out.append(hit)
    return out


def acquire_token(app_id: str, app_secret: str) -> str:
    response = requests.post(
        TOKEN_ENDPOINT,
        json={"app_id": app_id, "app_secret": app_secret},
        headers={"Content-Type": "application/json"},
        timeout=REQUEST_TIMEOUT,
    )
    response.raise_for_status()
    body = response.json()
    token = ((body.get("data") or {}).get("token") if isinstance(body, dict) else None)
    if not token:
        raise RuntimeError("token_response_missing_data.token")
    return str(token)


def search(query: str, token: str) -> dict:
    response = requests.get(
        SEARCH_ENDPOINT,
        params={"query": query, "token": token},
        headers={"Accept": "application/json"},
        timeout=REQUEST_TIMEOUT,
    )
    response.raise_for_status()
    body = response.json()
    if not isinstance(body, dict):
        raise RuntimeError("search_response_not_object")
    return body


def hydrate(page, context, url: str, post_id: str, metadata: dict, diagnostics: list[str], label: str) -> dict | None:
    try:
        page.goto(url, wait_until="domcontentloaded", timeout=30000)
        page.wait_for_timeout(1800)
    except PlaywrightTimeoutError:
        diagnostics.append(f"{label}: openapi_hydrate_timeout id={post_id}")
        return None
    candidates = page.locator("article, [role='article'], [class*='Feed'], [class*='card'], [class*='Card']")
    best = None
    best_score = -1
    for i in range(min(candidates.count(), 50)):
        node = candidates.nth(i)
        try:
            text = browser.clean(node.inner_text(timeout=700))
        except Exception:
            text = ""
        images = browser.images_from_node(node, url)
        score = len(text) + len(images) * 120
        if score > best_score:
            best_score = score
            best = (text, images)
    if not best or (len(best[0]) < 4 and not best[1]):
        diagnostics.append(f"{label}: openapi_hydrate_empty id={post_id}")
        return None
    post = {
        "id": post_id,
        "url": url,
        "published": None,
        "text": best[0],
        "images": best[1],
        "discovery": "WEIBO_OPEN_API_SEARCH",
        **metadata,
    }
    return browser.materialize_post_media(context, post, diagnostics, label)


def main() -> None:
    cfg = json.loads(SOURCES.read_text(encoding="utf-8"))
    targets_doc = json.loads(TARGETS.read_text(encoding="utf-8")) if TARGETS.exists() else {"targets": []}
    spool = json.loads(SPOOL.read_text(encoding="utf-8")) if SPOOL.exists() else {"schemaVersion": 2, "sources": {}, "diagnostics": []}
    spool.setdefault("sources", {})
    diagnostics = spool.setdefault("diagnostics", [])

    app_id = os.environ.get("WEIBO_APP_ID", "").strip()
    app_secret = os.environ.get("WEIBO_APP_SECRET", "").strip()
    if not app_id or not app_secret:
        diagnostics.append("weibo_openapi=disabled_missing_credentials")
        SPOOL.write_text(json.dumps(spool, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"enabled": False, "reason": "missing_credentials"}, ensure_ascii=False))
        return

    targets = [t for t in (targets_doc.get("targets") or []) if str(t.get("league") or "").upper() == "LPL"]
    sources = [s for s in list(cfg.get("leagues", [])) + list(cfg.get("teams", [])) if str(s.get("kind") or "") == "WEIBO_MOBILE"]
    diagnostics.append(f"weibo_openapi=enabled targets={len(targets)} sources={len(sources)}")
    try:
        token = acquire_token(app_id, app_secret)
    except Exception as exc:
        diagnostics.append(f"weibo_openapi_token_{type(exc).__name__}:{str(exc)[:160]}")
        SPOOL.write_text(json.dumps(spool, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        raise

    with sync_playwright() as playwright:
        chromium = playwright.chromium.launch(headless=True, args=["--no-sandbox", "--disable-dev-shm-usage"])
        context = chromium.new_context(locale="zh-CN", timezone_id="Asia/Shanghai", viewport={"width": 1440, "height": 1400})
        for src in sources:
            applicable = [t for t in targets if target_applies(src, t)][:3]
            if not applicable:
                continue
            key = source_key(src)
            label = src.get("account") or key
            found = []
            page = context.new_page()
            try:
                for target in applicable:
                    teams = target.get("teams") or []
                    if len(teams) < 2:
                        continue
                    queries = query_builder.build_match_search_queries(target.get("matchDateLocal", ""), teams[0], teams[1], "LPL")[:6]
                    for query in queries:
                        try:
                            body = search(query, token)
                        except Exception as exc:
                            diagnostics.append(f"{label}: openapi_search_{type(exc).__name__}:{str(exc)[:120]}")
                            continue
                        hits = extract_source_posts(body, src)
                        data = body.get("data") or {}
                        diagnostics.append(
                            f"{label}: openapi_search query='{query}' refs={len(hits)} code={body.get('code')} "
                            f"completed={data.get('completed')} noContent={data.get('noContent')} refused={data.get('refused')}"
                        )
                        for url, post_id in hits:
                            hydrated = hydrate(page, context, url, post_id, {
                                "searchQuery": query,
                                "targetEventId": target.get("eventId", ""),
                                "targetMatchDateLocal": target.get("matchDateLocal", ""),
                                "targetTeams": teams[:2],
                            }, diagnostics, label)
                            if hydrated:
                                found.append(hydrated)
                                found = browser.dedupe(found)
                        if found:
                            break
                        time.sleep(0.35)
                    if found:
                        break
            finally:
                page.close()
            existing = spool["sources"].get(key) or []
            merged = browser.dedupe(found + existing)
            spool["sources"][key] = merged[:24]
            diagnostics.append(f"{label}: openapi_posts={len(found)} merged_posts={len(merged[:24])}")
        context.close()
        chromium.close()

    spool["schemaVersion"] = max(int(spool.get("schemaVersion") or 0), 4)
    spool["weiboOpenApi"] = {"enabled": True, "priority": "OPEN_API_FIRST_BROWSER_SEARCH_FALLBACK"}
    SPOOL.write_text(json.dumps(spool, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"enabled": True, "targets": len(targets)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
