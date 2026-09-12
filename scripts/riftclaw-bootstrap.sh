#!/usr/bin/env bash
set -euo pipefail

PLUGIN="@wecode-ai/weibo-openclaw-plugin"
PLUGIN_ID="weibo-openclaw-plugin"
PROFILE="riftclaw"
BRIDGE_URL="http://127.0.0.1:18790"
OPENCLAW_URL="http://127.0.0.1:18791"
STATE_DIR="${RIFTCLAW_STATE_DIR:-$HOME/.local/share/riftclaw}"
FALLBACK_DIR="$STATE_DIR/extensions/$PLUGIN_ID"
STATUS_FILE="$STATE_DIR/status.json"
SECRETS_FILE="$STATE_DIR/secrets.env"
BRIDGE_FILE="$STATE_DIR/riftclaw-bridge.mjs"
INSTALL_TIMEOUT_SECONDS="${RIFTCLAW_INSTALL_TIMEOUT_SECONDS:-90}"
RAW_BASE="https://raw.githubusercontent.com/xbu080675-creator/Rlftlab/main"
OC=(openclaw --profile "$PROFILE")

say() { printf '\n[RiftClaw] %s\n' "$*"; }
warn() { printf '\n[RiftClaw] WARN: %s\n' "$*" >&2; }
fail() { printf '\n[RiftClaw] ERROR: %s\n' "$*" >&2; write_status "failed" "$*"; exit 1; }

json_escape() {
  python3 - "$1" <<'PY' 2>/dev/null || printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
import json,sys
print(json.dumps(sys.argv[1], ensure_ascii=False)[1:-1], end='')
PY
}

random_token() {
  node -e 'process.stdout.write(require("crypto").randomBytes(32).toString("hex"))'
}

write_status() {
  local state="${1:-unknown}" detail="${2:-}"
  mkdir -p "$STATE_DIR"
  local version plugin_loaded skill_ready
  version="$(openclaw --version 2>/dev/null | head -n 1 || true)"
  if "${OC[@]}" plugins list 2>/dev/null | grep -qi "$PLUGIN_ID"; then plugin_loaded=true; else plugin_loaded=false; fi
  if "${OC[@]}" skills list 2>/dev/null | grep -qiE 'weibo[-_ ]search'; then skill_ready=true; else skill_ready=false; fi
  cat >"$STATUS_FILE" <<JSON
{
  "schemaVersion": 2,
  "state": "$(json_escape "$state")",
  "detail": "$(json_escape "$detail")",
  "profile": "$PROFILE",
  "bridge": "$BRIDGE_URL",
  "openclawGateway": "$OPENCLAW_URL",
  "plugin": "$PLUGIN_ID",
  "pluginLoaded": $plugin_loaded,
  "weiboSearchSkillReady": $skill_ready,
  "openclawVersion": "$(json_escape "$version")"
}
JSON
  chmod 600 "$STATUS_FILE" 2>/dev/null || true
}

run_install_with_timeout() {
  if command -v timeout >/dev/null 2>&1; then
    timeout "${INSTALL_TIMEOUT_SECONDS}s" "${OC[@]}" plugins install "$PLUGIN"
  else
    "${OC[@]}" plugins install "$PLUGIN"
  fi
}

install_fallback() {
  say "标准安装没有完成，切换到持久化本地解包 fallback"
  command -v npm >/dev/null 2>&1 || fail "fallback 需要 npm，但当前环境没有 npm"
  command -v tar >/dev/null 2>&1 || fail "fallback 需要 tar"
  local tmp tgz
  tmp="$(mktemp -d)"
  (
    cd "$tmp"
    npm pack "$PLUGIN" >/dev/null
  )
  tgz="$(find "$tmp" -maxdepth 1 -name '*.tgz' | head -n 1)"
  [ -n "$tgz" ] || fail "npm pack 未生成 tgz"
  rm -rf "$FALLBACK_DIR"
  mkdir -p "$FALLBACK_DIR"
  tar -xzf "$tgz" -C "$FALLBACK_DIR" --strip-components=1
  [ -f "$FALLBACK_DIR/openclaw.plugin.json" ] || fail "解包完成但缺少 openclaw.plugin.json"
  "${OC[@]}" plugins install --link "$FALLBACK_DIR"
  rm -rf "$tmp"
}

prompt_credentials() {
  local app_id="${RIFTCLAW_WEIBO_APP_ID:-}" app_secret="${RIFTCLAW_WEIBO_APP_SECRET:-}"
  [ -n "$app_id" ] || read -r -p "AppID: " app_id
  [ -n "$app_id" ] || fail "AppID 不能为空"
  if [ -z "$app_secret" ]; then
    read -r -s -p "AppSecret: " app_secret
    printf '\n'
  fi
  [ -n "$app_secret" ] || fail "AppSecret 不能为空"
  "${OC[@]}" config set 'channels.weibo.appId' "$app_id"
  "${OC[@]}" config set 'channels.weibo.appSecret' "$app_secret"
  unset app_id app_secret RIFTCLAW_WEIBO_APP_ID RIFTCLAW_WEIBO_APP_SECRET || true
}

fetch_bridge() {
  say "安装窄权限 RiftClaw Bridge"
  if command -v curl >/dev/null 2>&1; then
    curl -fL "$RAW_BASE/scripts/riftclaw-bridge.mjs" -o "$BRIDGE_FILE"
  elif command -v wget >/dev/null 2>&1; then
    wget -qO "$BRIDGE_FILE" "$RAW_BASE/scripts/riftclaw-bridge.mjs"
  else
    fail "需要 curl 或 wget 下载 RiftClaw Bridge"
  fi
  chmod 700 "$BRIDGE_FILE"
}

write_launchers() {
  cat >"$STATE_DIR/start-openclaw.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
STATE_DIR="${RIFTCLAW_STATE_DIR:-$HOME/.local/share/riftclaw}"
set -a; . "$STATE_DIR/secrets.env"; set +a
exec openclaw --profile riftclaw gateway --port 18791 --bind loopback
SH
  cat >"$STATE_DIR/start-bridge.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
STATE_DIR="${RIFTCLAW_STATE_DIR:-$HOME/.local/share/riftclaw}"
set -a; . "$STATE_DIR/secrets.env"; set +a
exec node "$STATE_DIR/riftclaw-bridge.mjs"
SH
  cat >"$STATE_DIR/start.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
STATE_DIR="${RIFTCLAW_STATE_DIR:-$HOME/.local/share/riftclaw}"
mkdir -p "$STATE_DIR/logs"
for name in openclaw bridge; do
  pidfile="$STATE_DIR/$name.pid"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    echo "[RiftClaw] $name already running pid=$(cat "$pidfile")"
  else
    nohup "$STATE_DIR/start-$name.sh" >"$STATE_DIR/logs/$name.log" 2>&1 &
    echo $! >"$pidfile"
    echo "[RiftClaw] started $name pid=$!"
  fi
done
printf '%s\n' '[RiftClaw] Bridge: http://127.0.0.1:18790'
printf '%s\n' '[RiftClaw] Internal OpenClaw: http://127.0.0.1:18791'
SH
  cat >"$STATE_DIR/stop.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
STATE_DIR="${RIFTCLAW_STATE_DIR:-$HOME/.local/share/riftclaw}"
for name in bridge openclaw; do
  pidfile="$STATE_DIR/$name.pid"
  if [ -f "$pidfile" ]; then
    pid="$(cat "$pidfile")"
    kill "$pid" 2>/dev/null || true
    rm -f "$pidfile"
  fi
done
SH
  chmod 700 "$STATE_DIR"/*.sh
}

mkdir -p "$STATE_DIR"
chmod 700 "$STATE_DIR" 2>/dev/null || true
command -v openclaw >/dev/null 2>&1 || fail "未找到 openclaw。请先安装 OpenClaw Runtime"
command -v node >/dev/null 2>&1 || fail "未找到 Node.js"
command -v grep >/dev/null 2>&1 || fail "缺少 grep"
write_status "starting" "preflight"

say "使用独立 OpenClaw profile: $PROFILE"
openclaw --version || true

if "${OC[@]}" plugins list 2>/dev/null | grep -qi "$PLUGIN_ID"; then
  say "专用 profile 已存在微博插件，跳过重复安装"
else
  say "安装微博插件（最多等待 ${INSTALL_TIMEOUT_SECONDS}s）"
  if ! run_install_with_timeout; then install_fallback; fi
fi
"${OC[@]}" plugins list 2>/dev/null | grep -qi "$PLUGIN_ID" || fail "插件安装后仍未出现"

say "锁死专用 profile：只允许微博搜索"
"${OC[@]}" config set 'plugins.allow' '["weibo-openclaw-plugin"]'
"${OC[@]}" config set 'tools.allow' '["weibo_search"]'
"${OC[@]}" config set 'channels.weibo.enabled' false
"${OC[@]}" config set 'channels.weibo.weiboSearchEnabled' true
"${OC[@]}" config set 'gateway.port' 18791 --strict-json
"${OC[@]}" config set 'gateway.bind' loopback
"${OC[@]}" config set 'gateway.auth.mode' token

say "配置微博龙虾凭据"
prompt_credentials

GATEWAY_TOKEN="$(random_token)"
BRIDGE_TOKEN="$(random_token)"
"${OC[@]}" config set 'gateway.auth.token' "$GATEWAY_TOKEN"
cat >"$SECRETS_FILE" <<EOF
RIFTCLAW_OPENCLAW_URL=$OPENCLAW_URL
RIFTCLAW_OPENCLAW_TOKEN=$GATEWAY_TOKEN
RIFTCLAW_BRIDGE_TOKEN=$BRIDGE_TOKEN
RIFTCLAW_BRIDGE_PORT=18790
EOF
chmod 600 "$SECRETS_FILE"
unset GATEWAY_TOKEN

fetch_bridge
write_launchers

say "验证微博搜索 Skill"
if "${OC[@]}" skills list 2>/dev/null | grep -qiE 'weibo[-_ ]search'; then
  printf '%s\n' "- weibo-search: ready/discovered"
else
  warn "当前 CLI 尚未发现 weibo-search；专用 Gateway 首次启动后再检查。"
fi

write_status "configured" "ready_to_start"
say "配置完成：不需要模型即可搜索微博"
printf '%s\n' "RiftLab-facing Bridge: $BRIDGE_URL"
printf '%s\n' "Isolated OpenClaw Gateway: $OPENCLAW_URL"
printf '%s\n' "启动：$STATE_DIR/start.sh"
printf '%s\n' "停止：$STATE_DIR/stop.sh"
printf '%s\n' "日志：$STATE_DIR/logs/"
printf '\nRiftLab 配对码（仅复制到 RiftLab，本码不是 OpenClaw operator token）：\n%s\n' "$BRIDGE_TOKEN"
unset BRIDGE_TOKEN
printf '%s\n' "安全边界：RiftLab 只能访问 18790 的 weibo_search；18791 operator token 永不交给 RiftLab。"
printf '%s\n' "微博正文/评论/智搜摘要都作为不可信数据清洗；异常时回退官网/官方源/OCR。"
