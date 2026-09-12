#!/usr/bin/env bash
set -euo pipefail

PLUGIN="@wecode-ai/weibo-openclaw-plugin"
PLUGIN_ID="weibo-openclaw-plugin"
GATEWAY="http://127.0.0.1:18789"

say() { printf '\n[RiftClaw] %s\n' "$*"; }
fail() { printf '\n[RiftClaw] ERROR: %s\n' "$*" >&2; exit 1; }

command -v openclaw >/dev/null 2>&1 || fail "未找到 openclaw。请先安装 OpenClaw Runtime。"
command -v tar >/dev/null 2>&1 || fail "缺少 tar。"

say "检测 OpenClaw"
openclaw --version || true

say "安装微博插件"
if ! openclaw plugins install "$PLUGIN"; then
  say "标准安装失败，启用本地解包 fallback"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  cd "$tmp"
  command -v npm >/dev/null 2>&1 || fail "fallback 需要 npm，但当前环境没有 npm。"
  npm pack "$PLUGIN" >/dev/null
  tgz="$(find . -maxdepth 1 -name '*.tgz' | head -n 1)"
  [ -n "$tgz" ] || fail "npm pack 未生成 tgz"
  mkdir package
  tar -xzf "$tgz" -C package --strip-components=1
  openclaw plugins install --link "$tmp/package"
fi

say "锁定插件白名单：只允许微博插件"
openclaw config set 'plugins.allow' '["weibo-openclaw-plugin"]'

say "配置微博龙虾凭据"
read -r -p "AppID: " WEIBO_APP_ID
[ -n "${WEIBO_APP_ID:-}" ] || fail "AppID 不能为空"
read -r -s -p "AppSecret: " WEIBO_APP_SECRET
printf '\n'
[ -n "${WEIBO_APP_SECRET:-}" ] || fail "AppSecret 不能为空"
openclaw config set 'channels.weibo.appId' "$WEIBO_APP_ID"
openclaw config set 'channels.weibo.appSecret' "$WEIBO_APP_SECRET"
unset WEIBO_APP_ID WEIBO_APP_SECRET

say "安全基线"
printf '%s\n' \
  "- Gateway 必须只绑定 localhost" \
  "- RiftLab 只调用 weibo_search" \
  "- 禁止 shell / filesystem / delete / install / plugin 管理" \
  "- 微博正文、评论、OCR 文本全部按不可信数据处理" \
  "- 搜索结果必须再经过官方账号 + 日期 + 对阵 + 5+5 校验"

say "模型选择"
printf '%s\n' \
  "1) 直搜模式（推荐）：RiftLab 直接调用 weibo_search，不依赖模型" \
  "2) 本地 OpenAI-compatible：例如 llama.cpp http://127.0.0.1:18080/v1" \
  "3) 云端模型：仅用于整理结果，不拥有额外工具权限"

say "安装完成"
printf 'Gateway: %s\n' "$GATEWAY"
printf '%s\n' "请用你的 OpenClaw 宿主界面停止并重新启动 Gateway，使配置生效。"
printf '%s\n' "验证：openclaw plugins list | grep -i weibo"
