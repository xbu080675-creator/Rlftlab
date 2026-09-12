# RiftClaw Security Boundary

RiftClaw is an optional companion for RiftLab. It is intentionally **not** a general-purpose OpenClaw agent.

## Allowed capability

Only `weibo_search` is allowed for RiftLab-facing requests.

RiftLab sends structured match fields only: date, league, team A, team B, and the fixed intent `starting_roster`. RiftLab builds the search query itself from those fields. Arbitrary user prose is never forwarded as a tool request.

## Denied capabilities

RiftClaw must not expose or grant RiftLab access to shell execution, filesystem read/write/delete, process control, plugin installation, package installation, browser automation, SSH, ADB, Shizuku, Magisk, root/su, message sending, cron, or arbitrary network tools.

`plugins.allow` must contain only `weibo-openclaw-plugin` for the dedicated RiftClaw profile.

## Network boundary

The Gateway is loopback-only. RiftLab accepts only localhost endpoints (`127.0.0.1`, `localhost`, or `::1`). Do not bind the dedicated Gateway to LAN or public interfaces.

## Prompt-injection boundary

All Weibo content is untrusted data, including post text, comments, intelligent-search answers, references, image OCR, and quoted text.

Injection resistance is enforced outside the model:

1. The request schema does not carry arbitrary instructions.
2. Only one capability exists: `weibo_search`.
3. Search output is sanitized before any downstream model sees it.
4. Instruction-like strings are treated as data and can never request a second tool call.
5. Search answers are discovery intelligence, never direct official evidence.
6. Starting-roster publication still requires official account/source validation, exact match date and matchup, and a valid 5+5 roster structure.

A model may optionally summarize sanitized search output. The model never receives authority to expand capabilities, edit policy, install plugins, read secrets, or execute commands.

## Credentials and logging

Weibo AppID/AppSecret stay on the local OpenClaw host. RiftLab does not need the secret when using RiftClaw mode.

Logs must not contain AppSecret, access tokens, cookies, full search bodies, or raw private messages. Metadata-only diagnostics are acceptable: request ID, capability, allow/deny result, hit count, validation outcome, and timing.

## Failure behavior

Failure must be closed, not open. If RiftClaw is unavailable, plugin integrity cannot be verified, policy validation fails, or the response schema is invalid, RiftLab falls back to its normal official-site/social/OCR acquisition chain. It must never broaden OpenClaw permissions to recover functionality.
