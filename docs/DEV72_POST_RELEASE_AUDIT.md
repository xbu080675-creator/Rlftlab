# dev.72 Post-release audit

Audit baseline: `main` at dev.72 merge `e5289c6b928a5fd4f640eacb3e3f6238014546fa`.
Remediation branch: `dev72-postrelease-audit`.

## Scope

The audit re-checks dev.72 against its actual product contract: global LoL esports first, LPL only as a regional supplement; unified schedule/live/history identities; conservative source provenance; edition-specific tournament governance; mobile readability; and a release pipeline that fails when the Android build actually fails.

## Confirmed present in dev.72

- Unified Schedule merges Riot global schedule, Cito supplement and International Mirror, then performs cross-provider series de-duplication.
- Homepage default subscription is `GLOBAL`; regional subscriptions remain optional user filters.
- Schedule Center keeps regional leagues and international competitions separated, with WSCI and WSCL as distinct identities.
- Provider-only international events do not fabricate Riot IDs, standings, rosters or LiveStats history.
- Live routing uses a stable schedule target and per-game identity instead of provider-local game IDs.
- Live/history player snapshots retain team and side identity where the provider exposes them.
- Timeline provenance distinguishes local capture, verified deltas, derived navigation windows and provider-explicit events.
- Late attachment is labelled `CAPTURE START`; unknown dragon type stays unknown and does not infer soul/elder.
- Tournament editions keep edition-specific patch/rules/draw/qualification data; qualification mechanisms are separated instead of flattened.
- Historical replay/timeline storage preserves target identity and source labels.

## Post-release omissions found

### 1. Mobile text was still too small

`RiftTheme` had no app typography and many screens contained 8–11sp hard-coded labels. The published dev.72 therefore did not include the readability fix that had been requested. A second pass also found that the floating RiftScreen and Draft HUD use native `TextView`s, so Compose typography alone would never affect their 8–9sp labels.

Remediation:
- add app Typography,
- enforce a modest minimum font scale without changing dp density,
- keep any larger Android accessibility font scale,
- raise the smallest 8–9sp Compose labels and high-frequency subscription controls,
- raise native overlay/Draft HUD 8–9sp text to at least 10sp as well.

### 2. Home POST recovery was still LPL-centric

The global schedule was correct, but `MatchSessionStore` only proactively reconstructed the latest completed series when it was LPL. Non-LPL match detail already had an explicitly labelled third-party global supplement path, but the home POST surface did not use it.

Remediation:
- keep LPL TJStats reconstruction,
- for non-LPL completed series, use only a matched, meaningful, explicitly sourced global supplement result,
- publish it to the same `CompletedGameArchive`,
- keep missing data unavailable rather than synthesizing a result,
- expose a provider-neutral POST status message.

### 3. A production class was still named `MockAiInsightEngine`

The implementation did not generate mock match data; it only converted a verified gold differential into short local commentary. The name contradicted the app's `NO MOCK FALLBACK` contract and made the architecture misleading.

Remediation: rename it to `LocalLiveInsightEngine` and document that it is interpretation only, never a data provider.

### 4. Compile Diagnostics could report green after a failed Gradle build

The workflow captured the Gradle exit code but deliberately exited zero and never asserted the stored code afterwards. That made it useful as a log collector but unsafe as a release-quality signal.

Remediation: make the workflow fail when `compile.exit` is non-zero and broaden its source-path trigger. The permanent Android build remains the final clean/signature gate.

### 5. Permanent Android artifact naming was tied to dev.72

The build artifact name was `RiftLab-dev72-global`, which becomes stale on the first follow-up release.

Remediation: use a version-independent artifact name while version metadata remains in the APK/OTA manifest.

## Verification performed on remediation branch

- dev73 source-contract checks passed.
- clean `:app:assembleDebug` passed after the global POST/readability remediation.
- fixed DEV signing certificate SHA-256 verification passed.
- a second clean Android build/signature pass succeeded after the native overlay font-floor fix.
- the repaired permanent Compile Diagnostics workflow was triggered independently and completed successfully, including its new fail-on-nonzero assertion.

## Release rule

These are remediation changes after dev.72 was already published. They must ship with a monotonically newer Android version (`1.0.0-dev.73`, `versionCode 73`) so existing dev.72 installations can receive the OTA. No merge/release is considered complete until clean Android build and fixed DEV signature verification both pass.
