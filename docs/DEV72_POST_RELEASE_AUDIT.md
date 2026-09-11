# dev.72 Post-release audit

Audit baseline: `main` at dev.72 merge `e5289c6b928a5fd4f640eacb3e3f6238014546fa`.
Remediation branch: `dev72-postrelease-audit`.

## Scope

The audit re-checks dev.72 against its actual product contract: global LoL esports first, LPL only as a regional supplement; complete Riot schedule discovery; unified schedule/live/history identities; conservative source provenance; edition-specific tournament governance; mobile readability; and release CI that fails on real Android build errors.

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

`RiftTheme` had no app typography and many screens contained 6–11sp hard-coded labels. The published dev.72 therefore did not include the readability fix that had been requested. A first cleanup pass still missed conditional values such as `if (...) 7.sp else 9.sp` and indirect component parameters such as `teamNameFontSize = 8.sp`. The floating RiftScreen and Draft HUD also use native `TextView`s, so Compose typography alone never affected their micro text.

Remediation:
- add app Typography,
- enforce a modest 1.12x minimum font scale without changing dp density,
- keep any larger Android accessibility font scale,
- normalize explicit Compose `fontSize` / `teamNameFontSize` values below 10sp across schedule, team, match, operations, qualification and replay surfaces,
- raise cramped line heights where the old micro-label values would clip the enlarged font,
- raise native overlay/Draft HUD 8–9sp text to at least 10sp as well,
- explicitly exclude unrelated values such as `letterSpacing` from the font-size audit.

### 2. Riot schedule coverage was incomplete

The dev.72 schedule path was global in product intent but still used a positive Riot league allowlist and shallow pagination. That silently excluded newly added or renamed competitions and truncated the schedule horizon.

Remediation:
- prefer Riot's unfiltered global `getSchedule` feed,
- follow both older and newer page tokens for 10 pages in each direction and de-duplicate by event/match identity,
- if the unfiltered endpoint fails, dynamically discover the Riot LoL competition catalogue rather than falling back to a positive allowlist,
- keep only a tiny negative exclusion for non-LoL products such as TFT,
- continue merging Cito and International Mirror after the Riot global schedule.

Final online audit at verification time:
- Riot catalogue entries: 48,
- LoL competitions tracked: 47,
- excluded non-LoL entries: 1 (TFT Esports),
- global schedule rows audited: 916,
- runtime-matched rows: 916,
- missing rows against Riot global feed: 0,
- unresolved/partial-team rows in the audited window: 0,
- request errors: 0.

The parser still conservatively requires two resolved teams for a displayed match. There were no such unresolved rows in the audited window, so this caused no current omission; future Riot TBD-bracket publication remains an explicit edge case to revisit rather than inventing participant identities.

### 3. Home POST recovery was still LPL-centric

The global schedule was correct in architecture, but `MatchSessionStore` only proactively reconstructed the latest completed series when it was LPL. Non-LPL match detail already had an explicitly labelled third-party global supplement path, but the home POST surface did not use it.

Remediation:
- keep LPL TJStats reconstruction,
- for non-LPL completed series, use only a matched, meaningful, explicitly sourced global supplement result,
- publish it to the same `CompletedGameArchive`,
- keep missing data unavailable rather than synthesizing a result,
- expose a provider-neutral POST status message.

### 4. A production class was still named `MockAiInsightEngine`

The implementation did not generate mock match data; it only converted a verified gold differential into short local commentary. The name contradicted the app's `NO MOCK FALLBACK` contract and made the architecture misleading.

Remediation: rename it to `LocalLiveInsightEngine` and document that it is interpretation only, never a data provider.

### 5. Compile Diagnostics could report green after a failed Gradle build

The workflow captured the Gradle exit code but deliberately exited zero and never asserted the stored code afterwards. That made it useful as a log collector but unsafe as a release-quality signal.

Remediation: make the workflow fail when `compile.exit` is non-zero and broaden its source-path trigger. The permanent Android build remains the final clean/signature gate.

### 6. Permanent Android artifact naming was tied to dev.72

The build artifact name was `RiftLab-dev72-global`, which becomes stale on the first follow-up release.

Remediation: use a version-independent artifact name while version metadata remains in the APK/OTA manifest.

## Verification performed on remediation branch

- Initial dev73 source-contract checks passed.
- Multiple clean `:app:assembleDebug` passes succeeded after global POST/readability remediation.
- Fixed DEV signing certificate SHA-256 verification passed on each release-candidate build.
- Permanent Compile Diagnostics was independently verified after the fail-on-nonzero repair.
- Riot global schedule completeness was probed against the live Riot catalogue/feed; the final 10-page audit reported 916/916 rows, zero missing and zero request errors.
- The final residual-readability pass re-audited explicit font-size assignments, then completed a clean Android build and fixed-signature verification before committing the source changes.
- Temporary audit workflows, patch scripts and generated audit output are removed from the release tree after their checks complete.

## Release rule

These are remediation changes after dev.72 was already published. They must ship with a monotonically newer Android version (`1.0.0-dev.73`, `versionCode 73`) so existing dev.72 installations can receive the OTA. No merge/release is considered complete until clean Android build and fixed DEV signature verification both pass. Main remains untouched until the final PR is explicitly approved for merge.
