# RiftLab 中国大陆更新加速

RiftLab `dev.59` 起采用 **GitHub 唯一正式 Release + APP 内 GitHub 更新加速** 的更新架构。

> **架构锁定（dev.66）**：Gitee OTA / Gitee Release 二进制镜像链路已经废弃。Gitee 不参与 APK 或 `latest.json` 分发，不需要 `GITEE_TOKEN`，不得重新接入 APP 更新运行时或 canonical 发布 workflow；如保留 Gitee 仓库，仅作为源码镜像。

## 架构边界

- **GitHub `xbu080675-creator/Rlftlab`**：唯一代码真源、版本真源、GitHub Actions 构建源和 APK 正式发布源。
- **Gitee `xiaobaiaaa1/Rlftlab`**：仅保留为中国大陆源码镜像，不承担 APK 主分发，不在 Gitee 重新构建，也不反向决定版本历史。
- **GitHub 更新加速**：只在 RiftLab 自己执行“检查更新 / 下载更新包”时按需启用，不是 VPN，不创建 `VpnService`，不修改 Android 系统代理，不接管其他 APP 流量，也不代理赛事数据、直播、回放或普通 API。

## 更新流程

客户端固定从官方 GitHub `dev-latest` Release 获取更新：

`https://github.com/xbu080675-creator/Rlftlab/releases/download/dev-latest/latest.json`

流程为：

1. 先直连 GitHub 获取 `latest.json`；
2. 直连失败或超时时，临时把同一个官方 GitHub URL 交给内置 GitHub 更新加速通道；
3. manifest 解析出的 APK 必须仍然指向 `xbu080675-creator/Rlftlab` 的 `dev-latest` Release，否则拒绝；
4. 下载 APK 时优先沿用本次可用通道；直连中断时可使用 HTTP Range 从已下载字节继续通过加速通道续传；
5. 下载完成后校验 SHA-256、包名、versionCode 和固定 DEV 签名证书；
6. 使用加速通道时每个请求都显式 `Connection: close`，请求结束后立即断开，不保留系统级代理状态。

因此更新加速层即使不可信，也不能绕过 APK 身份校验。它只能帮助转发 RiftLab 已写死白名单的 GitHub Release 资源，不能被当作任意 URL 的通用代理使用。

## GitHub Actions 发布顺序

`.github/workflows/ota-direct.yml` 在 `main` 上执行：

1. 从 GitHub 主仓库 checkout；
2. 构建固定 DEV 签名 APK；
3. 校验签名证书；
4. 生成带版本号 APK 与 `latest.json`；
5. 更新固定 tag `dev-latest`；
6. 先上传新 APK；
7. 再最后上传 / 覆盖 `latest.json`；
8. manifest 已指向新 APK 后，再清理旧 APK 附件；
9. 更新 Release 文案供旧版客户端和人工查看。

这样不会出现 manifest 已宣布新版本，但 APK 还没有上传完成的顺序错误。

## GitHub Release 结构

固定 tag / Release：

- `dev-latest`

附件：

- `RiftLab-1.0.0-dev.xx.apk`
- `latest.json`

`latest.json` 示例：

```json
{
  "schemaVersion": 1,
  "channel": "dev",
  "versionName": "1.0.0-dev.59",
  "versionCode": 59,
  "apk": "RiftLab-1.0.0-dev.59.apk",
  "sha256": "...",
  "size": 12345678,
  "publishedAt": "2026-09-10T00:00:00Z",
  "changelog": "..."
}
```

APK 使用相对文件名，客户端会基于官方 GitHub `dev-latest` manifest 地址解析，并再次检查最终得到的 APK URL 是否属于 RiftLab 官方 Release。

## 更新加速配置

DEV 构建默认内置多个 GitHub 文件加速基址，并保留 GitHub 直连。dev.65 起不固定押一个节点，而是对真实版本 APK 做小段 HTTP Range 并发测速，按当前用户网络的实际吞吐排序。

默认池：

- `https://gh.llkk.cc/`
- `https://cors.isteed.cc/`
- `https://gh.xmly.dev/`
- `https://gh.ddlc.top/`
- `https://ghfast.top/`
- `https://ghproxy.net/`

可通过 Gradle 属性 `RIFTLAB_GITHUB_ACCELERATOR_BASE_URLS` 覆盖完整节点池；兼容旧的单节点属性 `RIFTLAB_GITHUB_ACCELERATOR_BASE_URL`。这些节点只会收到 RiftLab 官方 GitHub `dev-latest` Release 白名单资源，不会收到用户账号凭据或赛事请求。

生产规模扩大后应优先使用可控或有明确服务保障的 GitHub 文件加速节点；传输层应继续支持 HTTPS GET，APK 路径最好支持 HTTP Range。

## 客户端安全校验

RiftLab 不因为使用加速通道降低校验标准。安装前必须全部通过：

- manifest 与原始 APK 目标均为 HTTPS；
- manifest 的 APK 地址只能解析到 RiftLab 官方 GitHub `dev-latest` Release；
- APK SHA-256 与 manifest 完全一致；
- Android 包名为 `com.riftlab.app`；
- APK `versionCode` 与 manifest 一致；
- APK 签名证书 SHA-256 与 RiftLab 固定 DEV 证书一致。

网络中断不会自动删除未损坏的 `.part` 文件，下次下载可以继续断点续传；哈希失败时才会丢弃损坏的部分文件重新下载。

## 与 Gitee 的关系

Gitee 仍可继续同步 GitHub 源码，方便中国大陆浏览和拉取代码，但不再作为 `dev.59+` 的 APK OTA 主链路。

这样可以避免 GitHub hosted runner 每次构建后跨境向 Gitee 上传大 APK，也避免未来安装包体积增长后被镜像平台附件限制反向约束 RiftLab 的产品架构。