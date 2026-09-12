# RiftClaw Local Protocol v1

RiftLab does not call generic OpenClaw APIs. The optional RiftClaw companion exposes a tiny loopback-only protocol whose only tool capability is `weibo_search`.

## Transport

- Default endpoint: `http://127.0.0.1:18789`
- Loopback only: `127.0.0.1`, `localhost`, or `::1`
- No embedded credentials in URLs
- No LAN/public binding for the RiftLab-facing service
- JSON request/response bodies only

## `GET /v1/status`

Expected response:

```json
{
  "service": "riftclaw",
  "protocolVersion": 1,
  "ready": true,
  "capabilities": ["weibo_search"],
  "detail": "ready"
}
```

RiftLab fails closed if `protocolVersion` differs or if unexpected capabilities are advertised.

## `POST /v1/weibo/search`

RiftLab sends structured match fields only. It never forwards arbitrary user prose.

```json
{
  "protocolVersion": 1,
  "requestId": "roster-20260912-al-ig-0001",
  "matchDate": "2026-09-12",
  "league": "LPL",
  "teamA": "AL",
  "teamB": "IG",
  "intent": "starting_roster"
}
```

RiftClaw builds the concrete search terms itself from those trusted fields and invokes only the Weibo search capability.

Expected response:

```json
{
  "protocolVersion": 1,
  "requestId": "roster-20260912-al-ig-0001",
  "source": "riftclaw-weibo",
  "hits": [
    {
      "title": "...",
      "text": "...",
      "source": "...",
      "scheme": "...",
      "publishedAt": "..."
    }
  ]
}
```

The response is discovery intelligence only. RiftLab sanitizes every field before optional model processing and does not publish a starting roster until official-source, match-date, matchup, and 5+5 roster validation succeeds.

## Prompt-injection handling

Weibo text, comments, OCR, search summaries, references, and quoted content are untrusted data. They cannot request a second tool call, change policy, add capabilities, read secrets, execute shell commands, access files, install plugins, send messages, or modify the Gateway.

The optional model is downstream of the sanitizer and has no authority to expand the capability set.

## Failure behavior

Any protocol mismatch, invalid response, unexpected capability, unavailable companion, or policy failure causes RiftLab to fall back to its normal official-site/social/OCR acquisition chain. Permission broadening is never a recovery mechanism.
