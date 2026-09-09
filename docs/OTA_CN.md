# RiftLab 中国大陆 OTA 镜像

RiftLab dev.58 起采用双通道更新：

1. 中国大陆对象存储/CDN `latest.json`（优先）
2. GitHub `dev-latest` Release（自动兜底）

客户端只接受 HTTPS。APK 下载完成后会校验 manifest 中的 SHA-256、RiftLab 包名、versionCode 与固定 DEV 签名证书，再交给 Android 系统安装器。

## GitHub Actions 配置

Repository Variables：

- `RIFTLAB_OTA_CN_MANIFEST_URL`：公开 HTTPS 地址，例如 `https://<cdn-domain>/riftlab/dev/latest.json`
- `RIFTLAB_OTA_CN_S3_ENDPOINT`：S3-compatible endpoint
- `RIFTLAB_OTA_CN_BUCKET`：bucket 名
- `RIFTLAB_OTA_CN_PREFIX`：可选，默认 `riftlab/dev`
- `RIFTLAB_OTA_CN_REGION`：签名 region

Repository Secrets：

- `RIFTLAB_OTA_CN_ACCESS_KEY_ID`
- `RIFTLAB_OTA_CN_SECRET_ACCESS_KEY`

`.github/workflows/ota-direct.yml` 会先上传带版本号的 APK，再最后上传 `latest.json`。这样客户端不会先看到一个指向尚未上传 APK 的新版本清单。

未配置这些值时，CI 会明确跳过国内镜像发布，客户端使用 GitHub 备用源；不会写入虚假的国内地址。

建议使用中国大陆可直连、支持 HTTPS 且提供 S3-compatible API 的 COS、OSS、OBS 或同类对象存储。`latest.json` 应禁用缓存或使用极短缓存，带版本号的 APK 可以长期缓存。
