# RiftLab 中国大陆 OTA 镜像

RiftLab `dev.59` 起采用 **GitHub 主仓库 + Gitee 国内镜像** 的双通道更新架构。

## 架构边界

- **GitHub `xbu080675-creator/Rlftlab`**：唯一代码真源、版本真源、GitHub Actions 构建源、`dev-latest` 备用下载源。
- **Gitee `xiaobaiaaa1/Rlftlab`**：中国大陆镜像与 OTA 分发节点，不在 Gitee 重新构建，不反向决定版本历史。
- **S3-compatible 对象存储**：可选扩展，不是必需项，也不是版本真源。

客户端默认检查顺序：

1. Gitee `dev-latest/latest.json`
2. GitHub `dev-latest` Release API（自动兜底）

默认稳定 manifest：

`https://gitee.com/xiaobaiaaa1/Rlftlab/releases/download/dev-latest/latest.json`

Gitee 官方 Release 下载路由支持按 tag + 附件文件名形成稳定地址，因此客户端不需要保存会变化的附件数字 ID。

## GitHub Actions 发布顺序

`.github/workflows/ota-direct.yml` 在 `main` 上执行：

1. 从 GitHub 主仓库 checkout；
2. 构建固定 DEV 签名 APK；
3. 校验签名证书；
4. 生成带版本号 APK 和 `latest.json`；
5. 先更新 GitHub `dev-latest`，确保备用源已经可用；
6. 再更新 Gitee 固定 `dev-latest` Release；
7. Gitee 先上传新 APK，旧 `latest.json` 此时仍指向旧 APK；
8. 最后替换 `latest.json`；
9. 验证稳定 manifest 已返回本次 versionCode 后，再清理旧 APK 附件。

这样不会出现 manifest 已宣布新版本、但新 APK 尚未上传的顺序错误。替换 `latest.json` 本身存在极短窗口时，客户端会自动回退 GitHub。

## 必需 Secret

在 GitHub 仓库：

`Settings → Secrets and variables → Actions → New repository secret`

创建：

- `GITEE_TOKEN`：Gitee Personal Access Token

Token **不要写入源码、Issue、日志或聊天**。Workflow 只通过 `${{ secrets.GITEE_TOKEN }}` 读取。

如果没有配置 `GITEE_TOKEN`，Gitee 发布步骤会明确跳过，但 GitHub `dev-latest` 仍会正常发布，因此不会把更新链整体打断。

## Gitee Release 结构

固定 tag / Release：

- `dev-latest`

附件：

- `RiftLab-1.0.0-dev.xx.apk`
- `latest.json`

`latest.json` 示例结构：

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

APK 使用相对文件名，因此同一 `dev-latest` 下载目录即可解析出 APK 地址。

## 客户端安全校验

RiftLab 不因为使用镜像就降低校验标准。下载完成后继续验证：

- HTTPS；
- SHA-256；
- 包名 `com.riftlab.app`；
- manifest 中的 `versionCode`；
- 固定 DEV 签名证书 SHA-256。

国内镜像 APK 下载失败、哈希不一致或身份校验失败时，客户端会要求 GitHub 备用源与国内 manifest 的版本号和 SHA-256 一致后才继续安装。

## 可选 S3 扩展

原 S3-compatible 发布能力保留为可选扩展。若以后同时使用 COS / OSS / OBS，可继续配置：

Repository Variables：

- `RIFTLAB_OTA_CN_MANIFEST_URL`
- `RIFTLAB_OTA_CN_S3_ENDPOINT`
- `RIFTLAB_OTA_CN_BUCKET`
- `RIFTLAB_OTA_CN_PREFIX`
- `RIFTLAB_OTA_CN_REGION`

Repository Secrets：

- `RIFTLAB_OTA_CN_ACCESS_KEY_ID`
- `RIFTLAB_OTA_CN_SECRET_ACCESS_KEY`

注意：如果设置了 `RIFTLAB_OTA_CN_MANIFEST_URL`，它会覆盖客户端内置的 Gitee 默认 manifest 地址。仅在确实要把另一国内 CDN 提升为客户端首选源时配置该变量。
