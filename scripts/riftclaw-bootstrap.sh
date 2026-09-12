#!/usr/bin/env bash
set -euo pipefail

PLUGIN="@wecode-ai/weibo-openclaw-plugin"
PLUGIN_ID="weibo-openclaw-plugin"
GATEWAY="http://127.0.0.1:18789"
STATE_DIR="${RIFTCLAW_STATE_DIR:-$HOME/.local/share/riftclaw}"
FALLBACK_DIR="$STATE_DIR/extensions/$PLUGIN_ID"
STATUS_FILE="$STATE_DIR/status.json"
INSTALL_TIMEOUT_SECONDS="${RIFTCLAW_INSTALL_TIMEOUT_SECONDS:-90}"

say() { printf '\n[RiftClaw] %s\n' "$*"; }
warn() { printf '\n[RiftClaw] WARN: %s\n' "$*" >&2; }
fail() { printf '\n[RiftClaw] ERROR: %s\n' "$*" >&2; write_status "failed" "$*"; exit 1; }

json_escape() {
  python3 - "$1" <<'PY' 2>/dev/null || printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
import json,sys
print(json.dumps(sys.argv[1], ensure_ascii=False)[1:-1], end='')
PY
}

write_status() {
  local state="${1:-unknown}"
  local detail="${2:-}"
  mkdir -p "$STATE_DIR"
  local version plugin_loaded skill_ready
  version="$(openclaw --version 2>/dev/null | head -n 1 || true)"
  if openclaw plugins list 2>/dev/null | grep -qi "$PLUGIN_ID"; then plugin_loaded=true; else plugin_loaded=false; fi
  if openclaw skills list 2>/dev/null | grep -qiE 'weibo[-_ ]search'; then skill_ready=true; else skill_ready=false; fi
  cat >"$STATUS_FILE" <<JSON
{
  "schemaVersion": 1,
  "state": "$(json_escape "$state")",
  "detail": "$(json_escape "$detail")",
  "gateway": "$GATEWAY",
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
    timeout "${INSTALL_TIMEOUT_SECONDS}s" openclaw plugins install "$PLUGIN"
  else
    openclaw plugins install "$PLUGIN"
  fi
}

install_fallback() {
  say "标准安装没有完成，切换到持久化本地解包 fallback"
  command -v npm >/dev/null 2>&1 || fail "fallback 需要 npm，但当前环境没有 npm"
  command -v tar >/dev/null 2>&1 || fail "fallback 需要 tar"

  local tmp tgz
  tmp="$(mktemp -d)"
  trap 'rm -rf "${tmp:-}"' RETURN
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

  # Important: --link target must be persistent. Never link to /tmp and then delete it.
  openclaw plugins install --link "$FALLBACK_DIR"
  rm -rf "$tmp"
  trap - RETURN
}

prompt_credentials() {
  local app_id="${RIFTCLAW_WEIBO_APP_ID:-}"
  local app_secret="${RIFTCLAW_WEIBO_APP_SECRET:-}"

  if [ -z "$app_id" ]; then
    read -r -p "AppID: " app_id
  fi
  [ -n "$app_id" ] || fail "AppID 不能为空"

  if [ -z "$app_secret" ]; then
    read -r -s -p "AppSecret: " app_secret
    printf '\n'
  fi
  [ -n "$app_secret" ] || fail "AppSecret 不能为空"

  openclaw config set 'channels.weibo.appId' "$app_id"
  openclaw config set 'channels.weibo.appSecret' "$app_secret"
  unset app_id app_secret RIFTCLAW_WEIBO_APP_ID RIFTCLAW_WEIBO_APP_SECRET || true
}

mkdir -p "$STATE_DIR"
write_status "starting" "preflight"

command -v openclaw >/dev/null 2>&1 || fail "未找到 openclaw。请先安装 OpenClaw Runtime"
command -v grep >/dev/null 2>&1 || fail "缺少 grep"

say "检测 OpenClaw"
openclaw --version || true

if openclaw plugins list 2>/dev/null | grep -qi "$PLUGIN_ID"; then
  say "微博插件已存在，跳过重复安装"
else
  say "安装微博插件（最多等待 ${INSTALL_TIMEOUT_SECONDS}s）"
  if ! run_install_with_timeout; then
    install_fallback
  fi
fi

if ! openclaw plugins list 2>/dev/null | grep -qi "$PLUGIN_ID"; then
  fail "插件安装后仍未在 plugins list 中出现"
fi

say "锁定插件白名单：只允许微博插件"
openclaw config set 'plugins.allow' '["weibo-openclaw-plugin"]'

say "配置微博龙虾凭据"
prompt_credentials

say "验证微博搜索 Skill"
if openclaw skills list 2>/dev/null | grep -qiE 'weibo[-_ ]search'; then
  printf '%s\n' "- weibo-search: ready/discovered"
else
  warn "暂未从 skills list 发现 weibo-search。通常需要重启 Gateway 后才会注册。"
fi

say "安全基线"
printf '%s\n' \
  "- Gateway 只允许 localhost / 127.0.0.1 / ::1" \
  "- RiftLab 只调用 weibo_search" \
  "- 禁止 shell / filesystem / delete / install / plugin 管理" \
  "- 微博正文、评论、OCR、智搜摘要全部按不可信数据处理" \
  "- 搜索命中还要经过官方来源 + 日期 + 对阵 + 5+5 校验" \
  "- 任何异常都回退官网/官方源/OCR，不扩大 OpenClaw 权限"

say "模型是可选增强，不是微博检索权限的一部分"
printf '%s\n' \
  "- 推荐：RiftLab 只把结构化比赛条件交给 RiftClaw" \
  "- 本地模型可用于结果整理，例如 llama.cpp http://127.0.0.1:18080/v1" \
  "- 模型无权新增工具、改策略、读密钥或执行命令" \
  "- OpenClaw 插件较多时，本地模型上下文建议至少 16K；32K 需留意 KV Cache 内存"

write_status "configured" "restart_gateway_required"
say "配置完成"
printf 'Gateway: %s\n' "$GATEWAY"
printf '状态文件: %s\n' "$STATUS_FILE"
printf '%s\n' "请在 OpenClaw 宿主界面停止并重新启动 Gateway，使插件和凭据配置生效。"
printf '%s\n' "重启后验证：openclaw plugins list | grep -i weibo"
printf '%s\n' "重启后验证：openclaw skills list | grep -i weibo-search"
