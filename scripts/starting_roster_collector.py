#!/usr/bin/env python3
import argparse
import html
import io
import json
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup
from PIL import Image
import pytesseract

ROLES = ["TOP", "JUG", "MID", "BOT", "SUP"]
ROLE_ALIASES = {
    "TOP": ["TOP", "上单"],
    "JUG": ["JUG", "JUNGLE", "JGL", "打野"],
    "MID": ["MID", "中单"],
    "BOT": ["BOT", "ADC", "BOTTOM", "AD", "下路"],
    "SUP": ["SUP", "SUPPORT", "辅助"],
}
SKIP_TOKENS = {
    "TOP", "JUG", "JUNGLE", "JGL", "MID", "BOT", "ADC", "BOTTOM", "AD", "SUP", "SUPPORT",
    "上单", "打野", "中单", "下路", "辅助", "首发", "名单", "先发", "STARTING", "ROSTER", "LINEUP",
    "VS", "BO3", "BO5", "LPL", "LOL", "LEAGUE", "OF", "LEGENDS"
}

SESSION = requests.Session()
SESSION.headers.update({
    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36 RiftLabRosterBot/1.0",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
})


def utc_now():
    return datetime.now(timezone.utc)


def iso(dt):
    return dt.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_weibo_time(raw):
    if not raw:
        return None
    for fmt in ["%a %b %d %H:%M:%S %z %Y", "%Y-%m-%dT%H:%M:%S%z"]:
        try:
            return datetime.strptime(raw, fmt).astimezone(timezone.utc)
        except Exception:
            pass
    return None


def clean_html(text):
    soup = BeautifulSoup(text or "", "html.parser")
    return html.unescape(soup.get_text(" ", strip=True))


def fetch_weibo_posts(uid, limit=20):
    url = f"https://m.weibo.cn/api/container/getIndex?type=uid&value={uid}&containerid=107603{uid}"
    r = SESSION.get(url, timeout=15)
    r.raise_for_status()
    data = r.json()
    cards = ((data.get("data") or {}).get("cards") or [])
    out = []
    for card in cards:
        mblog = card.get("mblog") or {}
        if not mblog:
            continue
        pics = []
        for p in mblog.get("pics") or []:
            large = (p.get("large") or {}).get("url")
            if large:
                pics.append(large)
        bid = mblog.get("bid") or mblog.get("id")
        out.append({
            "id": str(mblog.get("id") or bid or ""),
            "url": f"https://weibo.com/{uid}/{bid}" if bid else f"https://weibo.com/u/{uid}",
            "published": parse_weibo_time(mblog.get("created_at")),
            "text": clean_html(mblog.get("text") or ""),
            "images": pics,
        })
        if len(out) >= limit:
            break
    return out


def download_image(url):
    r = SESSION.get(url, timeout=20)
    r.raise_for_status()
    return Image.open(io.BytesIO(r.content)).convert("RGB")


def ocr_image(img):
    # psm 6 works better for poster-style blocks; multilingual packs are installed in CI.
    configs = "--psm 6"
    text = pytesseract.image_to_string(img, lang="chi_sim+eng", config=configs)
    data = pytesseract.image_to_data(img, lang="chi_sim+eng", config=configs, output_type=pytesseract.Output.DICT)
    words = []
    n = len(data.get("text", []))
    for i in range(n):
        t = (data["text"][i] or "").strip()
        try:
            conf = float(data["conf"][i])
        except Exception:
            conf = -1
        if t and conf >= 20:
            words.append({
                "text": t,
                "left": int(data["left"][i]),
                "top": int(data["top"][i]),
                "width": int(data["width"][i]),
                "height": int(data["height"][i]),
                "conf": conf,
            })
    return text, words, img.size


def norm(s):
    return re.sub(r"[^A-Z0-9\u4e00-\u9fff]+", "", (s or "").upper())


def detect_teams(text, aliases):
    ntext = norm(text)
    found = []
    for team, vals in aliases.items():
        candidates = [team] + list(vals or [])
        if any(norm(v) and norm(v) in ntext for v in candidates):
            found.append(team)
    return found


def role_of(token):
    n = norm(token)
    for role, vals in ROLE_ALIASES.items():
        if any(norm(v) == n for v in vals):
            return role
    return None


def plausible_player(token):
    t = re.sub(r"^[^A-Za-z0-9\u4e00-\u9fff]+|[^A-Za-z0-9_.'\-\u4e00-\u9fff]+$", "", token or "").strip()
    if not t or norm(t) in {norm(x) for x in SKIP_TOKENS}:
        return None
    if len(t) < 2 or len(t) > 24:
        return None
    if t.isdigit():
        return None
    return t


def extract_from_lines(text):
    per_role = {r: [] for r in ROLES}
    lines = [re.sub(r"\s+", " ", x).strip() for x in (text or "").splitlines() if x.strip()]
    for line in lines:
        tokens = line.split()
        role_positions = [(i, role_of(tok)) for i, tok in enumerate(tokens)]
        role_positions = [(i, r) for i, r in role_positions if r]
        if not role_positions:
            continue
        for idx, role in role_positions:
            tail = []
            for tok in tokens[idx + 1: idx + 6]:
                if role_of(tok):
                    break
                p = plausible_player(tok)
                if p:
                    tail.append(p)
            for p in tail[:2]:
                if norm(p) not in {norm(x) for x in per_role[role]}:
                    per_role[role].append(p)
    return per_role


def extract_from_columns(words, image_size):
    width, _ = image_size
    result = {"left": {r: [] for r in ROLES}, "right": {r: [] for r in ROLES}}
    if not words:
        return result
    # Use role labels as anchors and collect words on the same visual row.
    for anchor in words:
        role = role_of(anchor["text"])
        if not role:
            continue
        ay = anchor["top"] + anchor["height"] / 2
        row = []
        for w in words:
            wy = w["top"] + w["height"] / 2
            if abs(wy - ay) <= max(18, anchor["height"] * 1.5):
                p = plausible_player(w["text"])
                if p and not role_of(w["text"]):
                    row.append((w["left"] + w["width"] / 2, p))
        row.sort()
        for x, p in row:
            side = "left" if x < width / 2 else "right"
            if norm(p) not in {norm(v) for v in result[side][role]}:
                result[side][role].append(p)
    return result


def choose_lineups(line_roles, column_roles):
    def complete(mapping):
        return all(mapping.get(r) for r in ROLES)

    candidates = []
    for side in ["left", "right"]:
        m = column_roles[side]
        if complete(m):
            candidates.append({r: m[r][0] for r in ROLES})
    if len(candidates) >= 2:
        return candidates[:2]

    if all(len(line_roles[r]) >= 2 for r in ROLES):
        return [
            {r: line_roles[r][0] for r in ROLES},
            {r: line_roles[r][1] for r in ROLES},
        ]
    if all(len(line_roles[r]) >= 1 for r in ROLES):
        return [{r: line_roles[r][0] for r in ROLES}]
    return candidates


def infer_date(text, published, timezone_name):
    # Output local calendar date. Zone conversion is deliberately minimal here because all current
    # LPL collector sources use Asia/Shanghai; non-LPL sources should supply explicit dates in text.
    m = re.search(r"(?<!\d)(\d{1,2})\s*[月/.-]\s*(\d{1,2})\s*日?", text or "")
    base = published or utc_now()
    if m:
        month, day = int(m.group(1)), int(m.group(2))
        year = base.year
        try:
            return f"{year:04d}-{month:02d}-{day:02d}"
        except Exception:
            pass
    if "明日" in (text or "") or "明天" in (text or ""):
        base += timedelta(days=1)
    # Shanghai is UTC+8; this keeps the collector dependency-free.
    local = base + timedelta(hours=8 if timezone_name == "Asia/Shanghai" else 0)
    return local.date().isoformat()


def evidence_key(e):
    return (e.get("matchDateLocal"), norm(e.get("league")), norm(e.get("team")), norm(e.get("opponent")), e.get("sourceUrl"))


def make_evidence(source, team, opponent, lineup, match_date, published, source_url, evidence_type, confidence):
    return {
        "matchDateLocal": match_date,
        "timezone": source.get("timezone", "Asia/Shanghai"),
        "league": source.get("league", ""),
        "team": team,
        "opponent": opponent,
        "source": source.get("source", "OTHER_OFFICIAL"),
        "platform": source.get("platform", "OFFICIAL"),
        "account": source.get("account", ""),
        "publishedAt": iso(published or utc_now()),
        "observedAt": iso(utc_now()),
        "evidenceType": evidence_type,
        "confidence": round(float(confidence), 3),
        "sourceUrl": source_url,
        "starters": [{"role": r, "id": lineup[r]} for r in ROLES],
    }


def process_source(source, cfg):
    if source.get("kind") != "WEIBO_MOBILE" or not source.get("uid"):
        return [], []
    diagnostics = []
    try:
        posts = fetch_weibo_posts(source["uid"])
    except Exception as e:
        return [], [f"{source.get('account')}: fetch {type(e).__name__}: {e}"]

    keywords = [x.lower() for x in cfg.get("keywords", [])]
    lookback = timedelta(hours=int(cfg.get("lookbackHours", 36)))
    cutoff = utc_now() - lookback
    aliases = cfg.get("teamAliases", {})
    emitted = []

    for post in posts:
        published = post.get("published")
        if published and published < cutoff:
            continue
        plain = post.get("text") or ""
        keyword_hit = any(k in plain.lower() for k in keywords)
        # Team posts may use image-only announcements, so still OCR recent image posts.
        if not keyword_hit and not post.get("images"):
            continue

        ocr_texts = []
        ocr_payloads = []
        for image_url in (post.get("images") or [])[:4]:
            try:
                img = download_image(image_url)
                txt, words, size = ocr_image(img)
                ocr_texts.append(txt)
                ocr_payloads.append((words, size))
            except Exception as e:
                diagnostics.append(f"{source.get('account')}: OCR {type(e).__name__}")

        combined = "\n".join([plain] + ocr_texts)
        if not any(k in combined.lower() for k in keywords):
            continue
        teams = detect_teams(combined, aliases)
        fixed_team = source.get("team")
        if fixed_team and fixed_team not in teams:
            teams.insert(0, fixed_team)
        teams = list(dict.fromkeys(teams))
        if len(teams) < 2:
            diagnostics.append(f"{source.get('account')}: post {post['id']} has <2 team identities")
            continue

        line_roles = extract_from_lines(combined)
        column_roles = {"left": {r: [] for r in ROLES}, "right": {r: [] for r in ROLES}}
        for words, size in ocr_payloads:
            part = extract_from_columns(words, size)
            for side in ["left", "right"]:
                for role in ROLES:
                    column_roles[side][role].extend(part[side][role])
        lineups = choose_lineups(line_roles, column_roles)
        if not lineups:
            diagnostics.append(f"{source.get('account')}: post {post['id']} roster parse incomplete")
            continue

        match_date = infer_date(combined, published, source.get("timezone", "Asia/Shanghai"))
        evidence_type = "TEXT" if keyword_hit and not ocr_texts else "IMAGE_OCR"
        confidence = 0.98 if evidence_type == "TEXT" else 0.90

        if fixed_team:
            opponent = next((t for t in teams if t != fixed_team), "")
            if opponent and lineups:
                emitted.append(make_evidence(source, fixed_team, opponent, lineups[0], match_date, published, post["url"], evidence_type, confidence))
        elif len(lineups) >= 2:
            # League official two-column poster: split once into two independent official evidence rows.
            emitted.append(make_evidence(source, teams[0], teams[1], lineups[0], match_date, published, post["url"], evidence_type, confidence))
            emitted.append(make_evidence(source, teams[1], teams[0], lineups[1], match_date, published, post["url"], evidence_type, confidence))
        else:
            diagnostics.append(f"{source.get('account')}: post {post['id']} only one lineup for league source")
    return emitted, diagnostics


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sources", default="data/global/starting_roster_sources.json")
    ap.add_argument("--output", default="data/global/starting_rosters.json")
    ap.add_argument("--diagnostics", default="data/global/starting_roster_collector_status.json")
    args = ap.parse_args()

    cfg = json.loads(Path(args.sources).read_text(encoding="utf-8"))
    out_path = Path(args.output)
    current = json.loads(out_path.read_text(encoding="utf-8")) if out_path.exists() else {"schemaVersion": 2, "evidence": []}

    all_sources = []
    for src in cfg.get("leagues", []):
        s = dict(src)
        s.setdefault("timezone", "Asia/Shanghai")
        all_sources.append(s)
    for src in cfg.get("teams", []):
        s = dict(src)
        s.setdefault("timezone", "Asia/Shanghai")
        all_sources.append(s)

    fresh = []
    diagnostics = []
    for source in all_sources:
        ev, diag = process_source(source, cfg)
        fresh.extend(ev)
        diagnostics.extend(diag)

    existing = list(current.get("evidence") or [])
    merged = {evidence_key(e): e for e in existing}
    for e in fresh:
        merged[evidence_key(e)] = e

    # Keep history bounded; official rows older than 60 days are no longer useful to the client.
    cutoff_date = (utc_now() - timedelta(days=60)).date().isoformat()
    evidence = [e for e in merged.values() if (e.get("matchDateLocal") or "9999-99-99") >= cutoff_date]
    evidence.sort(key=lambda e: (e.get("matchDateLocal", ""), e.get("publishedAt", ""), e.get("team", "")))

    current["schemaVersion"] = max(2, int(current.get("schemaVersion", 2)))
    current["updatedAt"] = iso(utc_now())
    current["collector"] = {
        "mode": "official-social-text-plus-image-ocr",
        "lastRunAt": iso(utc_now()),
        "freshEvidence": len(fresh),
        "sourceCount": len(all_sources),
    }
    current["evidence"] = evidence
    out_path.write_text(json.dumps(current, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    status = {
        "schemaVersion": 1,
        "updatedAt": iso(utc_now()),
        "sourceCount": len(all_sources),
        "freshEvidence": len(fresh),
        "diagnostics": diagnostics[-80:],
    }
    Path(args.diagnostics).write_text(json.dumps(status, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(status, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"collector fatal: {type(exc).__name__}: {exc}", file=sys.stderr)
        raise
