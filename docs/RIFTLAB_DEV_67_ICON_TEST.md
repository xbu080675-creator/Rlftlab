# RiftLab dev.67 — 图标二次更新 / OTA 下载测试

`dev.67` 是一个刻意保持最小变量的测试版本。

本版本只做：

- 更新 Android Launcher / Round Launcher 图标；
- 版本升级至 `1.0.0-dev.67` / `versionCode 67`；
- 重新构建、固定 DEV 签名、生成 `latest.json` 并通过 GitHub `dev-latest` 重新分发。

本版本明确不做：

- 不改赛事数据与数据源；
- 不改 PRE / LIVE / POST UI；
- 不改 RiftScreen；
- 不改 OTA 下载实现、节点池、测速、断点续传或安装校验；
- 不引入 Gitee OTA；
- 不引入 VPN、`VpnService`、系统代理或全局网络接管。

## 测试目的

用 dev.66 已安装客户端，在手机关闭 VPN 和系统代理的普通网络环境中检查 dev.67，验证现有更新链：

```text
GitHub canonical dev-latest
→ GitHub 直连 / GitHub-only 请求级加速节点测速
→ 真实 APK Range 下载
→ 必要时自动换线并续传
→ SHA-256 / package / versionCode / DEV signer 校验
→ Android 系统安装器
```

测试时重点记录：更新检查是否成功、最终选择的传输通道、实际下载速度、是否发生换线/断点续传，以及下载完成后是否能正常调起覆盖安装。
