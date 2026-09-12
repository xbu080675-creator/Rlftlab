# RiftClaw Local Protocol v1

RiftLab does not call generic OpenClaw APIs. The optional RiftClaw companion exposes a tiny loopback-only protocol whose only tool capability is `weibo_search`.

## Transport and trust boundary

- RiftLab-facing Bridge: `http://127.0.0.1:18790`
- Isolated OpenClaw profile Gateway: `http://127.0.0.1:18791`
- Both are loopback only: `127.0.0.1`, `localhost`, or `::1`
- RiftLab never receives the OpenClaw Gateway operator token and never calls `/tools/invoke` directly
- The Bridge owns the operator token privately and calls exactly one upstream tool: `weibo_search`
- `POST /v1/weibo/search` requires `Authorization: Bearer <RiftClaw pairing token>`
- The pairing token is separate from the OpenClaw operator token and is stored by RiftLab with Android Keystore
- No embedded credentials in URLs; no LAN/public binding

This separation is intentional because an OpenClaw shared Gateway token is an operator credential, not a narrow per-tool credential. RiftLab must therefore never possess it.

## `GET /v1/status`

Status is a read-only loopback probe and does not expose secrets.

```json
{
  "service": "riftclaw",
  "protocolVersion": 1,
  "ready": true,
  "capabilities": ["weibo_search"],
  "detail": "bridge_ready"
}
```

RiftLab fails closed if `protocolVersion` differs or if any unexpected capability is advertised.

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

RiftClaw builds concrete search terms from those trusted fields and invokes upstream OpenClaw with a fixed request equivalent to:

```json
{
  "tool": "weibo_search",
  "args": { "query": "9月12日 AL对战IG 首发名单" }
}
```

The caller cannot choose another tool, URL, header, command, file path, plugin, or OpenClaw method.

Expected Bridge response:

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

The response is discovery intelligence only. RiftLab sanitizes every field again and does not publish a starting roster until official-source, match-date, matchup, and 5+5 roster validation succeeds.

## OpenClaw isolation

The bootstrap creates a separate `--profile riftclaw` state tree. It does not reuse the user's normal OpenClaw profile. Defense in depth is configured as:

- `plugins.allow = ["weibo-openclaw-plugin"]`
- `tools.allow = ["weibo_search"]`
- Weibo DM channel disabled
- Gateway binds loopback on port `18791`
- Gateway operator token stored only in RiftClaw's private state
- Bridge binds loopback on port `18790`

## Prompt-injection handling

Weibo text, comments, OCR, search summaries, references, and quoted content are untrusted data. They cannot request a second tool call, change policy, add capabilities, read secrets, execute shell commands, access files, install plugins, send messages, or modify either Gateway or Bridge.

The Bridge sanitizes returned content before handing it to RiftLab. RiftLab sanitizes it again. An optional model is downstream of both policy and sanitization layers and has no authority to expand the capability set.

## Model independence

Weibo search does **not** require an Agent/model turn. RiftClaw invokes the registered `weibo_search` tool directly through the isolated OpenClaw Gateway. A local/cloud model may be added later for presentation or summarization only; API-key failures, context-window limits, or model loading failures must never disable the underlying Weibo search path.

## Failure behavior

Any protocol mismatch, invalid response, unexpected capability, unavailable companion, auth failure, or policy failure causes RiftLab to fall back to its normal official-site/social/OCR acquisition chain. Permission broadening is never a recovery mechanism.
