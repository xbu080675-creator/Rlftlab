from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def run(*args: str) -> None:
    subprocess.run(args, cwd=ROOT, check=True)


# Restore the known-good GitHub-only OTA runtime/publisher from main.
run("git", "fetch", "origin", "main")
run(
    "git", "checkout", "origin/main", "--",
    "app/src/main/java/com/riftlab/app/update/AppUpdateManager.kt",
    "app/src/main/java/com/riftlab/app/ui/UpdateCenterUi.kt",
    "app/build.gradle.kts",
    ".github/workflows/ota-direct.yml",
    "docs/OTA_CN.md",
)

# Keep dev.66 as the architecture/documentation lock release.
gradle = ROOT / "app/build.gradle.kts"
text = gradle.read_text(encoding="utf-8")
text = text.replace("versionCode = 65", "versionCode = 66", 1)
text = text.replace('versionName = "1.0.0-dev.65"', 'versionName = "1.0.0-dev.66"', 1)
if "// dev.66:" not in text:
    text = text.rstrip() + (
        "\n\n// dev.66: keep Gitee OTA retired. Continue GitHub canonical dev-latest + "
        "request-scoped adaptive GitHub acceleration; refresh project/development documentation.\n"
    )
gradle.write_text(text, encoding="utf-8")

# README: keep the refreshed product overview, but remove the accidental Gitee OTA architecture.
readme = ROOT / "README.md"
text = readme.read_text(encoding="utf-8")
ota = """## APP 内 OTA

当前架构（dev.59 起，dev.65 完成自适应测速，dev.66 锁定决策）：

```text
GitHub canonical dev-latest Release
→ GitHub 直连 + GitHub-only 加速节点并发测速
→ 按真实 APK 吞吐选择最快路径
→ 失败/过慢时保留断点自动换线
```

**Gitee OTA 已废弃。** Gitee 不参与 RiftLab APK / `latest.json` 分发，不需要 `GITEE_TOKEN`，也不得重新成为 APP 运行时或发布工作流的更新依赖；如保留 Gitee 仓库，只作为源码镜像使用。

安装前继续强制验证 SHA-256、包名、versionCode 和固定 DEV 签名证书。详见 `docs/OTA_CN.md`。
"""
text, count = re.subn(r"## APP 内 OTA\n.*?(?=\n## 构建环境)", ota.rstrip() + "\n", text, flags=re.S)
if count != 1:
    raise RuntimeError(f"README OTA section replacement count={count}")
readme.write_text(text, encoding="utf-8")

# Development history: dev.66 is the decision lock, not a Gitee reintroduction.
history = ROOT / "docs/RIFTLAB_DEVELOPMENT_HISTORY_DEV31_66.md"
text = history.read_text(encoding="utf-8")
dev66 = """## 9. dev.66 — OTA 架构锁定与开发文档补全

这一版不再引入新的分发平台，而是把 dev.59～dev.65 已经跑通的更新方案正式写死，避免后续开发再次误把已淘汰方案接回来。

```text
GitHub = code / version / build / canonical Release truth
APP = GitHub direct + request-scoped GitHub acceleration + verification + fallback
Gitee = source mirror only (optional), NOT OTA
```

客户端继续完整继承 dev.65：

- 官方 `dev-latest` 的 `latest.json` 与 APK 都来自 GitHub Release；
- 下载前对 GitHub 直连与多个 GitHub-only 节点使用真实 APK Range 并发测速；
- 按当前网络实际吞吐排序，优先最快路径；
- 节点失败或实际速度长期明显低于测速预期时保留 `.part` 自动换线；
- APK 允许 CDN/反代缓存，manifest 保持 no-cache；
- SHA-256、包名、versionCode 和固定 DEV 签名继续强制校验。

### 已废弃：Gitee OTA

Gitee Release / `latest.json` / APK 镜像分发方案已经废弃：

- 不在 APP 中读取 Gitee OTA manifest；
- 不从 Gitee 下载更新 APK；
- GitHub Actions 不上传 APK 或 `latest.json` 到 Gitee；
- 不需要 `GITEE_TOKEN`；
- 后续不得把 Gitee 重新引入 OTA 运行时或 canonical 发布链。

若保留 Gitee 仓库，仅用于源码镜像和国内代码浏览，不参与版本判断、APK 发布、更新检查或安装。

这一版同时补齐 dev.31～dev.66 的真实开发履历，并从 dev.67 重新排定后续路线，解决旧 dev.59～78 计划版本号已被实际开发占用的问题。
"""
text, count = re.subn(r"## 9\. dev\.66 .*?(?=\n---\n\n# 10\.)", dev66.rstrip() + "\n", text, flags=re.S)
if count != 1:
    raise RuntimeError(f"history dev66 section replacement count={count}")
text = text.replace(
    "+ Gitee 大陆第一源 + GitHub 自适应兜底",
    "+ GitHub canonical OTA + 自适应 GitHub 加速兜底",
)
history.write_text(text, encoding="utf-8")

# OTA document: restore main's GitHub-only design, then update the dev.65 accelerator details and lock the deprecation decision.
ota_doc = ROOT / "docs/OTA_CN.md"
text = ota_doc.read_text(encoding="utf-8")
first_para = "RiftLab `dev.59` 起采用 **GitHub 唯一正式 Release + APP 内 GitHub 更新加速** 的更新架构。\n"
lock = (
    "\n> **架构锁定（dev.66）**：Gitee OTA / Gitee Release 二进制镜像链路已经废弃。"
    "Gitee 不参与 APK 或 `latest.json` 分发，不需要 `GITEE_TOKEN`，不得重新接入 APP 更新运行时或 canonical 发布 workflow；"
    "如保留 Gitee 仓库，仅作为源码镜像。\n"
)
if lock.strip() not in text:
    text = text.replace(first_para, first_para + lock, 1)
config = """## 更新加速配置

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
"""
text, count = re.subn(r"## 更新加速配置\n.*?(?=\n## 客户端安全校验)", config.rstrip() + "\n", text, flags=re.S)
if count != 1:
    raise RuntimeError(f"OTA config replacement count={count}")
ota_doc.write_text(text, encoding="utf-8")

changelog = (
    "dev.66：锁定 RiftLab OTA 架构并补齐开发文档。撤销误重新引入的 Gitee OTA / Gitee Release 二进制镜像依赖；"
    "APP 恢复并继续使用 GitHub canonical dev-latest + GitHub 直连/多个 GitHub-only 加速节点真实 APK Range 自适应测速、断点续传和自动换线；"
    "GitHub Actions 不上传 APK/latest.json 到 Gitee，不需要 GITEE_TOKEN。SHA-256、包名、versionCode 与固定 DEV 签名校验保持不变。"
    "补齐 dev.31～dev.66 开发履历、更新 README/OTA 文档，并从 dev.67 重新排定后续路线。版本升级至 1.0.0-dev.66 / versionCode 66。"
)
(ROOT / "DEV_CURRENT_CHANGELOG.txt").write_text(changelog + "\n", encoding="utf-8")
full = ROOT / "DEV_CHANGELOG.txt"
old = full.read_text(encoding="utf-8")
if not old.startswith("dev.66："):
    full.write_text(changelog + "\n\n" + old, encoding="utf-8")

# Runtime/publisher hard gate: documentation may mention Gitee only to mark it retired.
for rel in [
    "app/src/main/java/com/riftlab/app/update/AppUpdateManager.kt",
    "app/src/main/java/com/riftlab/app/ui/UpdateCenterUi.kt",
    ".github/workflows/ota-direct.yml",
    "app/build.gradle.kts",
]:
    body = (ROOT / rel).read_text(encoding="utf-8")
    bad = [needle for needle in ("gitee", "GITEE_TOKEN", "OTA_PRIMARY_MANIFEST_URL") if needle.lower() in body.lower()]
    if bad:
        raise RuntimeError(f"deprecated Gitee OTA reference remains in {rel}: {bad}")

print("dev66 cleanup prepared: Gitee OTA removed from runtime and publisher")
