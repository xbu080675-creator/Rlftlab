# dev.61 — GitHub 更新加速可靠性修复

## 问题

dev.59/dev.60 默认使用 `gh-proxy.com` 作为请求级 GitHub 更新加速。实机可成功拿到 `latest.json`，但 70MB 级 Release APK 下载出现长时间 0 B，说明该节点在当前网络对 Release 大文件并不可靠。

## dev.61 调整

- GitHub 仍是唯一正式 Release / APK / manifest 源；
- APP 仍然只做请求级 GitHub 更新加速，不创建 VPNService、不修改系统代理；
- 默认主加速节点切换为 `ghfast.top`；
- 第二加速节点使用 `ghproxy.net`，主节点超时/失败时自动切换；
- GitHub 直连、GHFast、GHProxy.net 三条链路按当前状态自动回退；
- 所有加速 URL 仍只允许 RiftLab 官方 `dev-latest` Release 路径；
- 下载继续保留 Range 断点续传、SHA-256、包名、versionCode 和固定签名证书校验；
- UI 显示实际使用的加速节点，避免只显示笼统的“GitHub 更新加速”。

## 节点选择依据

近期公开测试显示：对真实 GitHub Release asset 做 Range 请求时，`ghfast.top` 与 `ghproxy.net` 能返回 HTTP 206，而 `gh-proxy.com` 在同类测试中出现超时。因此 dev.61 不再把 `gh-proxy.com` 作为默认大文件下载节点。

公共加速节点的可用性可能变化，所以 dev.61 不把单一第三方节点视为永久依赖，而是保留多节点自动回退和完整安装包校验。
