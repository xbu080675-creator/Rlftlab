# dev.73 verification

The dev.73 remediation candidate is verified on `dev72-postrelease-audit`; `main` remains unchanged until PR #37 is explicitly approved for merge.

Successful release-quality checks:

- Run `34562023369`: initial post-release contract patch, clean Android build, fixed DEV signature verification and candidate artifact succeeded.
- Run `34562286343`: permanent Compile Diagnostics completed successfully after being changed to fail on a real Gradle error.
- Run `34562543856`: native overlay / Draft HUD font-floor repair, clean Android build and fixed DEV signature verification succeeded.
- Run `34566605630`: first end-to-end global schedule validation passed contract probe, clean build and fixed signature; its live comparison showed the initial six-page global horizon should be widened further.
- Run `34566863619`: final Riot global schedule validation with the 10-page older/newer horizon succeeded. The live audit reported 48 Riot catalogue entries, 47 LoL competitions tracked, 916/916 global schedule rows matched, 0 missing rows, 0 unresolved-team rows in the audited window and 0 request errors; clean Android build, fixed signature and artifact upload also succeeded.
- Run `34568117351`: final residual-readability remediation succeeded after detecting labels missed by the first regex pass. Explicit user-facing `fontSize` / `teamNameFontSize` assignments below 10sp were normalized, the global-schedule contract was re-checked, clean Android build succeeded and the fixed DEV signature matched before the source-fix commit was pushed.

Diagnostic audit runs that failed before compilation were intentionally useful: they exposed additional 6–9sp labels and a too-narrow first audit matcher. Those failures were audit assertions, not Android compilation failures; the corrected final run above passed the actual clean build/signature gates.

Temporary dev73 audit workflows, patch scripts and generated audit JSON used for isolated checks are removed from the release tree after verification. Release metadata remains `1.0.0-dev.73` / `versionCode 73`.
