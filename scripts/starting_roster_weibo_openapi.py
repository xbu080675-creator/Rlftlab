#!/usr/bin/env python3
"""Discover LPL starting-roster posts through Weibo's Open API.

Search transport semantics are intentionally aligned with wangcch/weibo-mcp
(MIT, Copyright (c) 2026 wangcch): app_id/app_secret -> ws_token, then
GET /open/wis/search_query?query=...&token=.... RiftLab keeps the raw intelligent
search response (msg/msg_json/scheme/reference metadata), resolves any cited
canonical official posts it can find, and only then hands media to RiftLab's
existing OCR/evidence validator.
"""
from __future__ import annotations

import importlib.util
import json
import os
import re
import time
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import requests
from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeoutError

ROOT = Path(__file__).resolve().parent
SOURCES = Path("data/global/starting_roster_sources.json")
TARGETS = Path("data/global/starting_roster_match_targets.json")
SPOOL = Path("data/global/starting_roster_browser_posts.json")
TOKEN_ENDPOINT = os.environ.get("WEIBO_TOKEN_ENDPOINT", "https://open-im.api.weibo.com/open/auth/ws_token")
REFRESH_TOKEN_ENDPOINT = os.environ.get("WEIBO_REFRESH_TOKEN_ENDPOINT", "https://open-im.api.weibo.com/open/auth/refresh_token")
SEARCH_ENDPOINT = os.environ.get("WEIBO_SEARCH_ENDPOINT", "https://open-im.api.weibo.com/open/wis/search_query")
REQUEST_TIMEOUT = 15
TOKEN_EXPIRE_FALLBACK_SECONDS = 7200
TOKEN_REFRESH_BUFFER_SECONDS = 60
TRANSIENT_STATUS = {408, 425, 429, 500, 502, 503, 504}


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module


wrapper = load_module("rift_browser_four_lane_openapi", ROOT / "starting_roster_browser_four_lane.py")
browser = wrapper.browser
query_builder = load_module("rift_match_query_openapi", ROOT / "starting_roster_match_query.py")


class WeiboOpenApiClient:
    """Small Python port of the relevant weibo-mcp client behavior."""

    def __init__(self, app_id: str, app_secret: str):
        self.app_id = app_id
        self.app_secret = app_secret
        self.session = requests.Session()
        self.token: str | None = None
        self.token_acquired_at = 0.0
        self.token_expires_in = TOKEN_EXPIRE_FALLBACK_SECONDS

    def _token_valid(self) -> bool:
        if not self.token:
            return False
        expires_at = self.token_acquired_at + self.token_expires_in - TOKEN_REFRESH_BUFFER_SECONDS
        return time.time() < expires_at

    def _request_with_retry(self, method: str, url: str, **kwargs):
        last = None
        for attempt in range(3):
            try:
                response = self.session.request(method, url, timeout=REQUEST_TIMEOUT, **kwargs)
                if response.status_code in TRANSIENT_STATUS and attempt < 2:
                    time.sleep(min(1.0 * (2 ** attempt), 4.0))
                    continue
                return response
            except requests.RequestException as exc:
                last = exc
                if attempt >= 2:
                    raise
                time.sleep(min(1.0 * (2 ** attempt), 4.0))
        if last:
            raise last
        raise RuntimeError("weibo_request_retry_exhausted")

    def get_token(self, force_refresh: bool = False) -> str:
        if not force_refresh and self._token_valid():
            assert self.token
            return self.token
        response = self._request_with_retry(
            "POST",
            TOKEN_ENDPOINT,
            json={"app_id": self.app_id, "app_secret": self.app_secret},
            headers={"Content-Type": "application/json"},
        )
        if not response.ok:
            text = response.text[:300] if response.text else ""
            raise RuntimeError(f"token_http_{response.status_code}:{text}")
        body = response.json()
        data = body.get("data") if isinstance(body, dict) else None
        token = data.get("token") if isinstance(data, dict) else None
        if not token:
            raise RuntimeError("token_response_missing_data.token")
        expires = data.get("expire_in") if isinstance(data, dict) else None
        self.token = str(token)
        self.token_acquired_at = time.time()
        try:
            self.token_expires_in = max(60, int(expires)) if expires is not None else TOKEN_EXPIRE_FALLBACK_SECONDS
        except Exception:
            self.token_expires_in = TOKEN_EXPIRE_FALLBACK_SECONDS
        return self.token

    def refresh_token(self) -> bool:
        if not self.token:
            return False
        try:
            response = self._request_with_retry(
                "POST",
                REFRESH_TOKEN_ENDPOINT,
                json={"token": self.token},
                headers={"Content-Type": "application/json"},
            )
            if not response.ok:
                return False
            body = response.json()
            data = body.get("data") if isinstance(body, dict) else None
            new_token = data.get("token") if isinstance(data, dict) else None
            if new_token:
                self.token = str(new_token)
                self.token_acquired_at = time.time()
                expires = data.get("expire_in")
                if expires is not None:
                    self.token_expires_in = max(60, int(expires))
            return True
        except Exception:
            return False

    def search(self, query: str) -> dict:
        token = self.get_token()
        response = self._request_with_retry(
            "GET",
            SEARCH_ENDPOINT,
            params={"query": query, "token": token},
            headers={"Content-Type": "application/json"},
        )
        if response.status_code in {401, 403}:
            self.token = None
            token = self.get_token(force_refresh=True)
            response = self._request_with_retry(
                "GET",
                SEARCH_ENDPOINT,
                params={"query": query, "token": token},
                headers={"Content-Type": "application/json"},
            )
        if not response.ok:
            text = response.text[:300] if response.text else ""
            raise RuntimeError(f"search_http_{response.status_code}:{text}")
        body = response.json()
        if not isinstance(body, dict):
            raise RuntimeError("search_response_not_object")
        return body


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


def decode_msg_json(data: dict) -> object | None:
    raw = data.get("msg_json")
    if not isinstance(raw, str) or not raw.strip():
        return None
    try:
        return json.loads(raw)
    except Exception:
        return None


def normalize_search_result(payload: dict) -> dict:
    """Preserve the same search semantics exposed by weibo-mcp instead of pretending it is a status list."""
    data = payload.get("data") if isinstance(payload.get("data"), dict) else {}
    return {
        "code": payload.get("code"),
        "message": payload.get("message"),
        "completed": data.get("completed"),
        "analyzing": data.get("analyzing"),
        "noContent": data.get("noContent"),
        "refused": data.get("refused"),
        "content": data.get("msg") if isinstance(data.get("msg"), str) else "",
        "contentFormat": data.get("msg_format"),
        "referenceCount": data.get("reference_num"),
        "scheme": data.get("scheme") if isinstance(data.get("scheme"), str) else "",
        "status": data.get("status"),
        "statusStage": data.get("status_stage"),
        "version": data.get("version"),
        "callTime": data.get("callTime"),
        "source": data.get("source"),
        "msgJson": decode_msg_json(data),
    }


def response_strings(payload: dict) -> list[str]:
    data = payload.get("data") if isinstance(payload, dict) else None
    values = list(iter_strings(payload))
    if isinstance(data, dict):
        decoded = decode_msg_json(data)
        if decoded is not None:
            values.extend(iter_strings(decoded))
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


def scheme_candidates(scheme: str, src: dict) -> list[tuple[str, str]]:
    if not scheme:
        return []
    hit = canonical_post(scheme, src)
    if hit:
        return [hit]
    parsed = urlparse(scheme)
    qs = parse_qs(parsed.query)
    for key in ("url", "scheme", "link"):
        for value in qs.get(key, []):
            hit = canonical_post(value, src)
            if hit:
                return [hit]
    return []


def discover_official_links_from_page(page, url: str, src: dict, diagnostics: list[str], label: str) -> list[tuple[str, str]]:
    if not url.startswith(("http://", "https://")):
        return []
    try:
        page.goto(url, wait_until="domcontentloaded", timeout=25000)
        page.wait_for_timeout(1200)
    except Exception as exc:
        diagnostics.append(f"{label}: openapi_scheme_open_{type(exc).__name__}")
        return []
    out, seen = [], set()
    anchors = page.locator("a[href]")
    for i in range(min(anchors.count(), 300)):
        try:
            href = anchors.nth(i).get_attribute("href") or ""
        except Exception:
            continue
        hit = canonical_post(href, src)
        if hit and hit[0] not in seen:
            seen.add(hit[0])
            out.append(hit)
    return out[:12]


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
        spool["weiboOpenApi"] = {"enabled": False, "reason": "missing_credentials"}
        SPOOL.write_text(json.dumps(spool, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"enabled": False, "reason": "missing_credentials"}, ensure_ascii=False))
        return

    targets = [t for t in (targets_doc.get("targets") or []) if str(t.get("league") or "").upper() == "LPL"]
    sources = [s for s in list(cfg.get("leagues", [])) + list(cfg.get("teams", [])) if str(s.get("kind") or "") == "WEIBO_MOBILE"]
    diagnostics.append(f"weibo_openapi=enabled targets={len(targets)} sources={len(sources)}")
    client = WeiboOpenApiClient(app_id, app_secret)
    try:
        client.get_token()
    except Exception as exc:
        diagnostics.append(f"weibo_openapi_token_{type(exc).__name__}:{str(exc)[:160]}")
        spool["weiboOpenApi"] = {"enabled": True, "tokenReady": False, "error": str(exc)[:240]}
        SPOOL.write_text(json.dumps(spool, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        raise

    raw_results = []
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
                            body = client.search(query)
                        except Exception as exc:
                            diagnostics.append(f"{label}: openapi_search_{type(exc).__name__}:{str(exc)[:160]}")
                            continue
                        normalized = normalize_search_result(body)
                        raw_results.append({
                            "sourceKey": key,
                            "account": label,
                            "query": query,
                            "targetEventId": target.get("eventId", ""),
                            "targetMatchDateLocal": target.get("matchDateLocal", ""),
                            "targetTeams": teams[:2],
                            **normalized,
                        })
                        code = normalized.get("code")
                        if code not in (0, "0", None):
                            diagnostics.append(f"{label}: openapi_search_api_error code={code} message={str(normalized.get('message') or '')[:120]}")
                            continue
                        if normalized.get("refused"):
                            diagnostics.append(f"{label}: openapi_search_refused query='{query}'")
                            continue
                        hits = extract_source_posts(body, src)
                        for hit in scheme_candidates(str(normalized.get("scheme") or ""), src):
                            if hit not in hits:
                                hits.append(hit)
                        if not hits and normalized.get("scheme"):
                            hits.extend(discover_official_links_from_page(page, str(normalized.get("scheme") or ""), src, diagnostics, label))
                        # Deduplicate after raw msg/msg_json/scheme discovery.
                        deduped = []
                        seen = set()
                        for hit in hits:
                            if hit[0] not in seen:
                                seen.add(hit[0])
                                deduped.append(hit)
                        hits = deduped
                        diagnostics.append(
                            f"{label}: openapi_search query='{query}' refs={len(hits)} code={code} "
                            f"completed={normalized.get('completed')} analyzing={normalized.get('analyzing')} "
                            f"noContent={normalized.get('noContent')} refused={normalized.get('refused')} "
                            f"referenceCount={normalized.get('referenceCount')} source={str(normalized.get('source') or '')[:40]}"
                        )
                        content = str(normalized.get("content") or "")
                        if content:
                            diagnostics.append(f"{label}: openapi_content='{browser.clean(content)[:180]}'")
                        for url, post_id in hits:
                            hydrated = hydrate(page, context, url, post_id, {
                                "searchQuery": query,
                                "targetEventId": target.get("eventId", ""),
                                "targetMatchDateLocal": target.get("matchDateLocal", ""),
                                "targetTeams": teams[:2],
                                "openApiReferenceCount": normalized.get("referenceCount"),
                                "openApiCallTime": normalized.get("callTime"),
                                "openApiSource": normalized.get("source"),
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

    spool["schemaVersion"] = max(int(spool.get("schemaVersion") or 0), 5)
    spool["weiboOpenApi"] = {
        "enabled": True,
        "tokenReady": True,
        "priority": "OPEN_API_FIRST_BROWSER_SEARCH_FALLBACK",
        "resultSemantics": "WEIBO_INTELLIGENT_SEARCH",
        "results": raw_results[:80],
    }
    SPOOL.write_text(json.dumps(spool, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"enabled": True, "targets": len(targets), "searchResults": len(raw_results)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
