# dev.73 verification

The dev.73 remediation candidate was verified on `dev72-postrelease-audit` before opening the final PR.

- Run `34562023369`: post-release contract patch, clean Android build, fixed DEV signature verification and candidate artifact all succeeded.
- Run `34562286343`: permanent Compile Diagnostics workflow completed successfully after being changed to fail on a real Gradle error.
- Run `34562543856`: native overlay/Draft HUD font-floor patch, clean Android build and fixed DEV signature verification all succeeded.

The temporary patch workflows and patch scripts used for those isolated checks were removed from the release tree. Main is intentionally not changed until the final PR is explicitly merged.
