#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/java/com/riftlab/app/ui"


def rewrite(path: Path, transform):
    original = path.read_text(encoding="utf-8")
    updated = transform(original)
    if updated == original:
        return False
    path.write_text(updated, encoding="utf-8")
    return True


def require_replace(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing expected {label}: {old!r}")
    return text.replace(old, new)


# 1) Stop overriding Android's font scale. Readability must come from the app's own type hierarchy,
# while larger accessibility font settings remain fully respected by Compose.
def patch_theme(text: str) -> str:
    text = text.replace("import androidx.compose.ui.platform.LocalDensity\n", "")
    text = text.replace("import androidx.compose.ui.unit.Density\n", "")
    old_block = '''/**
 * RiftLab previously mixed Material defaults with many 8–11sp labels. On a high-density phone that
 * made important metadata look like footnotes. Keep the layout density unchanged, but guarantee a
 * modest app-level minimum font scale while still honoring any larger accessibility font scale the
 * user selected in Android settings. Explicit UI labels should also stay at 10sp or above in source.
 */
private const val RIFT_MIN_FONT_SCALE = 1.12f

'''
    text = require_replace(
        text,
        old_block,
        '''/** Central RiftLab type scale. Explicit dense HUD metadata starts at 11sp; normal reading text is 12sp+. */\n''',
        "old font-scale policy"
    )
    text = require_replace(
        text,
        '''    val dark = isSystemInDarkTheme()\n    val systemDensity = LocalDensity.current\n    val readableDensity = Density(\n        density = systemDensity.density,\n        fontScale = maxOf(systemDensity.fontScale, RIFT_MIN_FONT_SCALE)\n    )\n''',
        '''    val dark = isSystemInDarkTheme()\n''',
        "forced readable density"
    )
    text = require_replace(
        text,
        '''    CompositionLocalProvider(\n        LocalDensity provides readableDensity,\n        LocalRiftTeamSkin provides skin,\n''',
        '''    CompositionLocalProvider(\n        LocalRiftTeamSkin provides skin,\n''',
        "LocalDensity provider"
    )
    return text


rewrite(UI / "RiftTheme.kt", patch_theme)

# 2) Shared visual primitives define the floor for reusable hierarchy elements.
def patch_visual_system(text: str) -> str:
    text = require_replace(text, "fontSize = 12.sp,\n            fontWeight = FontWeight.Bold,", "fontSize = 13.sp,\n            fontWeight = FontWeight.Bold,", "section-label size")
    text = require_replace(text, "fontSize = 10.sp,\n        fontWeight = FontWeight.Bold,", "fontSize = 11.sp,\n        fontWeight = FontWeight.Bold,", "status-badge size")
    return text


rewrite(UI / "RiftVisualSystem.kt", patch_visual_system)

# 3) Sweep old micro-type from the Compose UI. Consumer reading surfaces get 12sp for former 10sp
# body copy; dense score/operator surfaces use 11sp minimum to keep five-player and stat rows stable.
consumer_body_12 = {
    "RiftLabApp.kt",
    "UpdateCenterUi.kt",
}
line_height_map = {"12": "15", "14": "17", "15": "18"}

for path in UI.glob("*.kt"):
    if path.name in {"RiftTheme.kt", "RiftVisualSystem.kt"}:
        continue

    def patch_ui(text: str, name=path.name) -> str:
        text = text.replace("fontSize = 8.sp", "fontSize = 11.sp")
        text = text.replace("fontSize = 9.sp", "fontSize = 11.sp")
        text = text.replace(
            "fontSize = 10.sp",
            "fontSize = 12.sp" if name in consumer_body_12 else "fontSize = 11.sp"
        )
        text = text.replace("teamNameFontSize = 10.sp", "teamNameFontSize = 11.sp")
        text = text.replace("if (compact) 10.sp else 10.sp", "if (compact) 11.sp else 11.sp")
        # Old micro-copy used very tight line heights. Expand only the original value once.
        text = re.sub(
            r"lineHeight = (12|14|15)\.sp",
            lambda m: f"lineHeight = {line_height_map[m.group(1)]}.sp",
            text,
        )
        return text

    rewrite(path, patch_ui)

# 4) A few text-heavy dialogs deserve normal reading size rather than dense HUD size.
def promote_dialog_body(text: str) -> str:
    prose_fragments = [
        "所有 Provider Key 均使用 Android Keystore AES-GCM 加密，仅保存在本机；不会写入源码、GitHub、日志或比赛归档。",
    ]
    for fragment in prose_fragments:
        idx = text.find(fragment)
        if idx < 0:
            continue
        tail = text[idx:idx + 700]
        tail = tail.replace("fontSize = 11.sp", "fontSize = 12.sp", 1)
        text = text[:idx] + tail + text[idx + 700:]
    return text

rewrite(UI / "RealtimeSourceSettingsDialog.kt", promote_dialog_body)

# 5) Version + release note.
def patch_gradle(text: str) -> str:
    text = require_replace(text, "versionCode = 76", "versionCode = 77", "versionCode 76")
    text = require_replace(text, 'versionName = "1.0.0-dev.76"', 'versionName = "1.0.0-dev.77"', "versionName 76")
    return text

rewrite(ROOT / "app/build.gradle.kts", patch_gradle)

(ROOT / "DEV_CURRENT_CHANGELOG.txt").write_text(
    "dev.77：完成 Visual System 2.0 的可读性收口。移除 dev.76 为补偿旧 8–10sp 小字而强制注入的 1.12x LocalDensity 字体缩放，恢复完全遵循 Android 系统 fontScale；可读性改由应用自身字号层级解决。全 UI 清理遗留 8/9/10sp 微型文字：普通阅读型页面原 10sp 正文提升到 12sp，赛事中心、比赛详情、战队详情、运营面板、直播入口、赛事档案等高密度 HUD 的最小显式字号提升到 11sp，并同步放宽旧 12–15sp 紧凑行高。共享 RiftSectionLabel 提升至 13sp、RiftStatusBadge 提升至 11sp，保留标题/正文/元数据层级，不把所有文字统一放大或加粗。更新中心与 API 设置等长文本页面额外提升阅读正文，同时保持比分、五人数据表和紧凑状态栏不溢出。版本升级为 1.0.0-dev.77 / versionCode 77。",
    encoding="utf-8"
)

print("dev.77 readability pass applied")
