# RiftLab 中国大陆 OTA 架构

> 当前基线：`1.0.0-dev.66`
>
> 目标：APP 内检查更新和下载 APK 在中国大陆网络下尽可能直接、稳定，同时不让镜像站成为版本真源。

## 1. 角色划分

RiftLab dev.66 把更新链固定为两层来源、三类传输通道：

```text
GitHub main / GitHub Actions
        │
        ├─ 构建同一份固定签名 APK + latest.json
        │
        ├─ ① GitHub dev-latest（canonical / 必须成功）
        │
        └─ ② Gitee dev-latest（大陆 Release 镜像 / 尽力同步）

APP 检查与下载：
Gitee 国内 OTA
        ↓ 失败
GitHub 官方 Release
        ↓
GitHub 直连 + GitHub-only 加速节点真实 APK 测速/自动换线
```

- **GitHub `xbu080675-creator/Rlftlab`**：唯一代码真源、版本真源、构建源和 canonical `dev-latest` Release。
- **Gitee `xiaobaiaaa1/Rlftlab`**：中国大陆 Release 镜像。它不编译 APK、不决定版本号、不反向覆盖 GitHub 历史。
- **GitHub 更新加速节点**：只在 APP 访问 RiftLab 官方 GitHub `dev-latest` 资源时按请求使用；不是 VPN，不创建 `VpnService`，不修改系统代理，也不接管赛事数据、直播、回放或其他 APP 流量。

因此“国内优先”只改变 APK 的传输路径，不改变谁拥有版本真相。

## 2. APP 检查更新顺序

默认国内 manifest：

```text
https://gitee.com/xiaobaiaaa1/Rlftlab/releases/download/dev-latest/latest.json
```

canonical GitHub manifest：

```text
https://github.com/xbu080675-creator/Rlftlab/releases/download/dev-latest/latest.json
```

检查逻辑：

1. 优先读取 Gitee `latest.json`；
2. Gitee 不可达、超时、manifest 非法或来源不在固定白名单时，回退 GitHub；
3. GitHub manifest 先尝试官方直连；直连失败再通过 GitHub-only 加速节点获取；
4. manifest 必须是 `channel=dev`，包含合法 `versionName`、正整数 `versionCode`、APK 引用和 64 位 SHA-256；
5. Gitee manifest 只能引用 `xiaobaiaaa1/Rlftlab/releases/download/dev-latest/` 下的 APK；GitHub manifest 只能引用 `xbu080675-creator/Rlftlab/releases/download/dev-latest/` 下的 APK。

APP 不接受任意远程 manifest 把下载地址改到第三方 APK。

## 3. 下载与跨源断点续传

### 3.1 Gitee 正常时

APP 直接下载 Gitee Release 中的同版本 APK，并保留 `.part` 文件支持 HTTP Range 断点续传。

### 3.2 Gitee 下载失败时

APP 会重新读取 canonical GitHub manifest。只有确认 Gitee 与 GitHub 指向的是同一构建时，才允许拿现有 `.part` 从 GitHub 继续：

- `versionName` 一致；
- `versionCode` 一致；
- SHA-256 一致；
- 两边都提供文件长度时，长度一致。

任何一项不一致都不能把两个不同 APK 的字节拼在一起。

### 3.3 GitHub 兜底仍沿用 dev.65 自适应传输

GitHub APK 下载不会固定押一个公共节点。APP 会对真实 RiftLab Release APK 做小范围 Range 并发测速，把 GitHub 直连与当前节点池按实际吞吐排序，再从最快通道开始下载。

默认节点池：

- GH LLKK
- iSteed
- XMLY
- DDLC
- GHFast
- GHProxy.net
- GitHub 直连

节点失败或下载速度长期显著低于测速结果时，保留 `.part` 并自动换下一条通道。版本化 APK 请求允许 CDN/反代缓存；`latest.json` 始终 `no-cache`，避免拿到旧 manifest。

## 4. 安装前安全校验

无论 APK 来自 Gitee、GitHub 直连还是 GitHub 更新加速，安装前全部执行同一组校验：

1. 原始 manifest / APK 地址必须为 HTTPS 且位于 RiftLab 固定 Release 白名单；
2. 下载完成文件 SHA-256 必须与 `latest.json` 完全一致；
3. Android 包名必须为 `com.riftlab.app`；
4. APK `versionCode` 必须与 manifest 一致；
5. APK 签名证书 SHA-256 必须匹配 RiftLab 固定 DEV 证书；
6. 全部通过后才通过 `FileProvider` 拉起 Android 系统安装器。

所以 Gitee 和公共 GitHub 加速节点都只是“搬运字节”，没有权限决定什么 APK 可以安装。

## 5. GitHub Actions 发布顺序

`.github/workflows/ota-direct.yml` 只在 GitHub Actions 构建。Gitee 不启用 CI。

canonical GitHub 发布顺序：

```text
构建 APK
→ 校验固定 DEV 签名
→ 计算 SHA-256 / size
→ 生成 latest.json
→ 上传新 APK 到 GitHub dev-latest
→ 最后切换 GitHub latest.json
→ 清理 GitHub 旧 APK
```

随后执行大陆镜像：

```text
查找/创建 Gitee dev-latest Release
→ 上传同一份 APK
→ APK 成功后再切换 Gitee latest.json
→ 清理 Gitee 旧 APK
```

关键原则仍然是 **APK 先到，manifest 后切**。这样客户端不会先看到新版本号，却下载不到对应 APK。

Gitee 镜像步骤采用尽力同步：Gitee API 临时不可用、Token 未配置或附件超限时，不回滚已经成功的 GitHub canonical Release。客户端仍可进入 GitHub 自适应兜底链。

## 6. Secrets 与配置

Gitee Personal Access Token 只放 GitHub Actions Secret：

```text
GITEE_TOKEN
```

Token 不写进 APK、不提交源码、不放 `latest.json`，也不需要发送给任何客户端。

可选 Actions/Gradle 配置：

```text
RIFTLAB_OTA_PRIMARY_MANIFEST_URL
RIFTLAB_GITHUB_ACCELERATOR_BASE_URLS
RIFTLAB_GITHUB_ACCELERATOR_BASE_URL
```

默认情况下无需配置第一项，DEV 构建已经内置 RiftLab 官方 Gitee `dev-latest` manifest。GitHub 加速节点也有默认池；变量主要用于以后替换节点而不大改更新器代码。

## 7. Release 文件结构

GitHub 与 Gitee 的 `dev-latest` 都使用同一 manifest schema：

```json
{
  "schemaVersion": 1,
  "channel": "dev",
  "versionName": "1.0.0-dev.66",
  "versionCode": 66,
  "apk": "RiftLab-1.0.0-dev.66.apk",
  "sha256": "<64-hex-sha256>",
  "size": 74238412,
  "publishedAt": "2026-09-10T00:00:00Z",
  "changelog": "..."
}
```

`apk` 使用相对文件名，客户端基于当前经过白名单验证的 manifest URL 解析绝对 APK URL，然后再次验证来源。

## 8. Gitee 附件体积边界

当前 Gitee Release 单附件存在约 100 MB 的平台上限。dev.65 APK 约 74 MB，现阶段仍能镜像；工作流也会在 APK 接近/超过该边界时跳过 Gitee 大文件上传，避免拖垮 canonical GitHub 发布。

这意味着 Gitee 是当前中国大陆分发优化层，不是未来必须永久绑定的基础设施。如果 APK 继续增长到平台限制附近，应迁移大陆二进制源到对象存储/CDN，而 APP 的“国内第一源 → GitHub 自适应兜底”模型可以继续保持。

## 9. dev.58 ～ dev.66 的方案演化

- **dev.58**：第一次建立大陆优先双通道 OTA；
- **dev.59 早期**：尝试由 GitHub Actions 上传 Gitee Release；经历空 Release、API 超时和 multipart 鉴权修复；
- **dev.59 最终**：为避免镜像大文件成为发布阻塞，改为 GitHub 唯一 Release + APP 请求级 GitHub 加速；
- **dev.61**：固定代理节点在大 APK 上卡顿，改成多节点故障切换；
- **dev.65**：升级为对真实 APK 并发 Range 测速，按实际吞吐自动选路；
- **dev.66**：把 Gitee Release 重新放回“大陆第一传输源”，但这次不再让它承担版本真源或构建职责，并完整保留 dev.65 GitHub 自适应兜底。

当前方案的重点不是押注某一家镜像，而是让 APP 在国内网络里拥有一条快路径，同时始终保留可验证、可恢复的官方退路。
