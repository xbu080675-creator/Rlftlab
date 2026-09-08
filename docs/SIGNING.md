# RiftLab signing policy

## Development builds

All `1.0.0-dev.*` APKs use the fixed public development signing identity stored under `signing/`.

Purpose: make GitHub Actions test builds upgrade-compatible so testers can install a newer dev APK over the previous dev APK without uninstalling.

Development certificate SHA-256:

`769d9be3aa3af3fd4bb647bed8ffe4a8f7cfe2e7a9ad4489b260395b13575a24`

GitHub Actions verifies this fingerprint after every build and fails if it changes.

This key is intentionally a development-only identity and is not a security boundary because the repository is public.

## Production releases

Do **not** use the development key for a public production release. Before the first stable release, generate a separate private release keystore and inject it through protected CI secrets. Never commit the production private key or its passwords to the repository.

## Migration note

Builds produced before the fixed development identity was introduced were signed with ephemeral/default debug keys. The first fixed-signed dev build therefore requires uninstalling an older RiftLab test build once. Subsequent fixed-signed dev builds can update in place as long as the application ID remains `com.riftlab.app` and versionCode increases.
