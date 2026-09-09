from pathlib import Path

path = Path('.github/workflows/android-build.yml')
text = path.read_text(encoding='utf-8')

old = '''on:\n  workflow_dispatch:\n  push:\n    branches: [ main, master, feat/schedule-center ]\n\njobs:\n'''
new = '''on:\n  workflow_dispatch:\n  push:\n    branches: [ main, master, feat/schedule-center ]\n\npermissions:\n  contents: write\n\njobs:\n'''
if old not in text:
    raise SystemExit('android-build header target not found')
text = text.replace(old, new, 1)

old_tail = '''      - uses: actions/upload-artifact@v4\n        with:\n          name: RiftLab-dev5-schedule-center\n          path: app/build/outputs/apk/debug/app-debug.apk\n'''
new_tail = old_tail + '''      - name: Publish dev-latest OTA\n        if: github.ref == 'refs/heads/main'\n        env:\n          GH_TOKEN: ${{ github.token }}\n        shell: bash\n        run: |\n          set -euo pipefail\n          VERSION_NAME=$(sed -n 's/.*versionName = "\\([^\"]*\\)".*/\\1/p' app/build.gradle.kts | head -n1)\n          VERSION_CODE=$(sed -n 's/.*versionCode = \\([0-9][0-9]*\\).*/\\1/p' app/build.gradle.kts | head -n1)\n          APK="app/build/outputs/apk/debug/app-debug.apk"\n          OUT="/tmp/RiftLab-${VERSION_NAME}.apk"\n          cp "$APK" "$OUT"\n          SHA=$(sha256sum "$OUT" | awk '{print $1}')\n          CHANGELOG=$(python3 - <<'PY'\n          from pathlib import Path\n          text = Path('DEV_CHANGELOG.txt').read_text(encoding='utf-8').strip()\n          first = text.split('\\n\\n', 1)[0].replace('\\n', ' ').strip()\n          if len(first) > 520:\n              first = first[:519].rstrip() + '…'\n          print(first)\n          PY\n          )\n          cat > /tmp/riftlab-release.txt <<EOF\n          RIFTLAB_UPDATE\n          versionName=$VERSION_NAME\n          versionCode=$VERSION_CODE\n          sha256=$SHA\n          channel=dev\n          changelog=$CHANGELOG\n          EOF\n\n          if gh release view dev-latest >/dev/null 2>&1; then\n            gh release upload dev-latest "$OUT" --clobber\n            RID=$(gh api "repos/${GITHUB_REPOSITORY}/releases/tags/dev-latest" --jq .id)\n            gh api --method PATCH "repos/${GITHUB_REPOSITORY}/releases/$RID" \\\n              -f name="RiftLab $VERSION_NAME · DEV" \\\n              -f body="$(cat /tmp/riftlab-release.txt)" \\\n              -F prerelease=true \\\n              -f target_commitish="$GITHUB_SHA" >/dev/null\n          else\n            git tag -f dev-latest "$GITHUB_SHA"\n            git push -f origin refs/tags/dev-latest\n            for attempt in 1 2 3; do\n              if gh release create dev-latest "$OUT" \\\n                --verify-tag \\\n                --title "RiftLab $VERSION_NAME · DEV" \\\n                --notes-file /tmp/riftlab-release.txt \\\n                --prerelease; then\n                exit 0\n              fi\n              sleep $((attempt * 5))\n            done\n            exit 1\n          fi\n'''
if old_tail not in text:
    raise SystemExit('android-build tail target not found')
text = text.replace(old_tail, new_tail, 1)
path.write_text(text, encoding='utf-8')
