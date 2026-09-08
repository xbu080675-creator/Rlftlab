#!/usr/bin/env bash
set -euo pipefail
if ! command -v gradle >/dev/null 2>&1; then
  echo "需要 Gradle 9.6.0。也可以直接推到 GitHub，Actions 会自动构建 APK。"
  exit 1
fi
gradle :app:assembleDebug
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
