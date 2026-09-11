#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ui = ROOT / "app/src/main/java/com/riftlab/app/ui/MatchOperationsContent.kt"
gradle = ROOT / "app/build.gradle.kts"
changelog = ROOT / "DEV_CURRENT_CHANGELOG.txt"

text = ui.read_text(encoding="utf-8")
old = '''    var selectedIndex by remember(current.gameId, current.game, snapshots.size) {
        mutableIntStateOf((snapshots.size - 1).coerceAtLeast(0))
    }
    LaunchedEffect(snapshots.size, phase) {
        if (snapshots.isNotEmpty()) {
            selectedIndex = if (phase == ScheduleMatchPhase.LIVE) snapshots.lastIndex else selectedIndex.coerceIn(0, snapshots.lastIndex)
        }
    }
'''
new = '''    // Keep the scrub selection anchored to game time, not to the current list index. Historical
    // backfill can append/prepend frames after the user releases the slider; keying state by
    // snapshots.size used to recreate the state and snap the thumb straight back to the final frame.
    var selectedElapsedSecond by remember(current.gameId, current.game) {
        mutableIntStateOf(-1)
    }
'''
if text.count(old) != 1:
    raise SystemExit(f"expected one old scrub-state block, found {text.count(old)}")
text = text.replace(old, new, 1)

old2 = '''            val safeIndex = selectedIndex.coerceIn(0, snapshots.lastIndex)
            val selected = snapshots[safeIndex]
'''
new2 = '''            val safeIndex = when {
                selectedElapsedSecond < 0 -> snapshots.lastIndex
                else -> snapshots.indices.minByOrNull { index ->
                    abs(snapshots[index].elapsedSeconds - selectedElapsedSecond)
                } ?: snapshots.lastIndex
            }
            val selected = snapshots[safeIndex]
'''
if text.count(old2) != 1:
    raise SystemExit(f"expected one safe-index block, found {text.count(old2)}")
text = text.replace(old2, new2, 1)

old3 = '''            Slider(
                value = safeIndex.toFloat(),
                onValueChange = { selectedIndex = it.roundToInt().coerceIn(0, snapshots.lastIndex) },
                valueRange = 0f..snapshots.lastIndex.toFloat(),
                steps = (snapshots.size - 2).coerceAtLeast(0)
            )
'''
new3 = '''            Slider(
                value = safeIndex.toFloat(),
                onValueChange = { rawIndex ->
                    val index = rawIndex.roundToInt().coerceIn(0, snapshots.lastIndex)
                    selectedElapsedSecond = snapshots[index].elapsedSeconds
                },
                valueRange = 0f..snapshots.lastIndex.toFloat(),
                steps = (snapshots.size - 2).coerceAtLeast(0)
            )
'''
if text.count(old3) != 1:
    raise SystemExit(f"expected one slider block, found {text.count(old3)}")
text = text.replace(old3, new3, 1)
ui.write_text(text, encoding="utf-8")

gtext = gradle.read_text(encoding="utf-8")
gtext = gtext.replace("        versionCode = 74\n", "        versionCode = 75\n", 1)
gtext = gtext.replace('        versionName = "1.0.0-dev.74"\n', '        versionName = "1.0.0-dev.75"\n', 1)
if 'versionCode = 75' not in gtext or 'versionName = "1.0.0-dev.75"' not in gtext:
    raise SystemExit("failed to bump dev75 version metadata")
gradle.write_text(gtext, encoding="utf-8")

changelog.write_text(
    "dev.75：修复历史经济曲线拖动后松手立即跳回终局的问题。根因是 GoldHistoryPanel 把拖动位置按 snapshots.size 作为 remember key 保存，Riot / OP.GG 历史回填在后台继续补帧时，帧数量一变化就会重建 selectedIndex 并重置到最后一帧。现在拖动选择改为按比赛 elapsedSeconds 锚定：默认仍定位最新/终局节点；用户一旦拖到历史时刻，就以该游戏时间为稳定锚点，即使后台新增、前插或重建历史帧，也只寻找最接近同一时间的真实节点，不再把滑块强制拉回结束位置。版本升级至 1.0.0-dev.75 / versionCode 75。\n",
    encoding="utf-8"
)

verify = ui.read_text(encoding="utf-8")
assert "remember(current.gameId, current.game, snapshots.size)" not in verify
assert "selectedElapsedSecond" in verify
assert "abs(snapshots[index].elapsedSeconds - selectedElapsedSecond)" in verify
assert "selectedElapsedSecond = snapshots[index].elapsedSeconds" in verify
print("dev75 history economy scrub fix applied")
