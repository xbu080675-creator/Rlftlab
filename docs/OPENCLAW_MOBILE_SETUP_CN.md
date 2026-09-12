# RiftLab：OpenClaw / “龙虾”部署指南（中文）

> 目标：让普通用户知道“为什么要养龙虾、怎么部署、怎么接微博龙虾、怎么保持最小权限”。
>
> RiftLab 的官网/官方源轮询始终保留。OpenClaw 只是可选的低延迟增强通道；没有 OpenClaw 也能使用 RiftLab。

## 1. 先说清楚架构

RiftLab 不会把微博 AppID / AppSecret 上传到 GitHub，也不会要求把凭证写进仓库。

推荐架构：

`RiftLab -> 本机 OpenClaw Gateway -> 微博 OpenClaw 插件 -> 微博`

RiftLab 侧只允许非常窄的本地能力，例如 Gateway 健康检查和微博首发搜索。Shell、文件写入、删除、插件安装、Root / SU / ADB / Shizuku / Magisk 等能力默认拒绝。

## 2. 两种部署方式

### A. 官方支持路线：电脑 / Linux 主机运行 Gateway

OpenClaw 官方 Android App 是 companion node，官方文档明确说明 Android App 本身不承载 Gateway。官方支持在 macOS、Linux 或 Windows WSL2 上运行 Gateway。

已有 Node.js 22.22.3+ / 24.15+ / 25.9+ 时，可使用：

```bash
npm install -g openclaw@latest
openclaw onboard
openclaw gateway --port 18789
```

如果手机和 Gateway 不在同一台设备上，不要把未经认证的 Gateway 直接暴露到公网。优先使用 OpenClaw 官方文档推荐的受认证 LAN / Tailscale / TLS 方案。

### B. 手机单机路线：Termux（实验 / 社区路线）

OpenClaw 官方当前不把“Android 本机承载 Gateway”列为正式支持平台，但社区已经有 Android + Termux 的部署方案。RiftLab 可以兼容这种本机 Gateway，只要最终监听在手机回环地址，例如：

`127.0.0.1:18789`

建议使用 F-Droid / GitHub 正版 Termux，不要使用已经长期停止维护的旧 Play Store 版。

先准备基础环境：

```bash
pkg update -y
pkg install -y nodejs git curl
node -v
npm -v
```

确认 Node 版本满足 OpenClaw 当前要求后，再安装：

```bash
npm install -g openclaw@latest
openclaw onboard
```

Termux 上不要依赖桌面 Linux 的 systemd / launchd 守护方式。优先直接前台运行 Gateway：

```bash
openclaw gateway --port 18789
```

另开一个 Termux 会话运行其他命令。RiftLab 只连接 `127.0.0.1` / `localhost`，不需要把 Gateway 绑到 `0.0.0.0`。

## 3. 安装微博龙虾插件

在运行 Gateway 的同一个 OpenClaw 环境中执行：

```bash
openclaw plugins install @wecode-ai/weibo-openclaw-plugin
```

## 4. 获取微博 AppID / AppSecret

在微博客户端：

1. 登录微博账号；
2. 搜索并关注“微博龙虾助手”；
3. 点击“连接龙虾”；
4. 龙虾助手会返回 AppID 与 AppSecret。

把这两个值当作密码，不要发到群聊、截图、Issue、GitHub Commit 或日志里。

## 5. 把凭证配置给 OpenClaw

只在本机 OpenClaw 环境中执行：

```bash
openclaw config set 'channels.weibo.appId' '你的AppID'
openclaw config set 'channels.weibo.appSecret' '你的AppSecret'
```

配置完成后按插件提示重启 / 重载 Gateway。如果 Gateway 没自动恢复，可手动启动：

```bash
openclaw gateway --port 18789
```

## 6. RiftLab 如何使用

RiftLab 的策略是：

1. 官网 / 官方源轮询持续工作，所有用户都能用；
2. 如果本机检测到 OpenClaw Gateway，则启用“龙虾提前量”；
3. RiftLab 只允许调用微博首发搜索这类白名单能力；
4. 搜索结果必须继续经过比赛日、对阵、官方账号、5+5 首发结构校验；
5. 微博先命中时可先显示“官方社媒已确认”；
6. 官网稍后命中时升级为“官网已交叉确认”。

## 7. 必须遵守的安全设置

为了避免 Agent 误删文件、越权、提示注入或插件横向访问：

- 不要用 Root / SU 身份启动 Gateway；
- 不要把 Shizuku / Magisk / ADB shell 权限交给 RiftLab 的 OpenClaw 通道；
- Gateway 优先只监听 `127.0.0.1`；
- RiftLab 只开放 `GATEWAY_HEALTH` 和 `WEIBO_SEARCH` 等白名单能力；
- 禁止 RiftLab 侧调用 `shell / exec / cmd / file / write / delete / install / plugin`；
- 删除、批量写入、插件安装等高风险操作必须由用户在 OpenClaw 自己的界面显式确认；
- 不把微博 AppSecret、GitHub Token、SSH Key 等凭证写入日志；
- 外部微博正文 / 网页内容永远只是“不可信输入”，不能触发权限升级。

## 8. 常见问题

### RiftLab 显示“没有检测到龙虾”

先在 Termux / Gateway 主机确认：

```bash
openclaw gateway status
```

如果你走手机本机方案，确认 Gateway 实际监听的是本机地址和预期端口（默认 18789）。

### 插件装好了但微博搜索没结果

先确认：

- AppID / AppSecret 是微博龙虾助手发给你的；
- 配置写入的是运行 Gateway 的同一个 OpenClaw 实例；
- Gateway 在配置后已经重启；
- 插件没有报鉴权失败；
- RiftLab 的官网轮询仍然会继续，不会因为 OpenClaw 失败而失效。

### 能不能把 Gateway 开到公网？

不建议裸开。若必须远程访问，使用 OpenClaw 官方支持的认证与 TLS / Tailscale 方案，不要直接把无认证的 `0.0.0.0:18789` 暴露到互联网。

## 9. 产品原则

OpenClaw 是“增强通道”，不是 RiftLab 的前置依赖。

**没有龙虾：官网 / 官方源照常工作。**

**有龙虾：可以更早收到微博官方首发信息。**

**龙虾挂了：自动回退，不影响基础观赛功能。**
