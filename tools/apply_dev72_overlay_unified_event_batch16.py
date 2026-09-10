#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


OVERLAY = "app/src/main/java/com/riftlab/app/overlay/RiftOverlayView.kt"

replace_once(
    OVERLAY,
    '''import com.riftlab.app.data.MatchSessionStore\n''',
    '''import com.riftlab.app.data.MatchSessionStore\nimport com.riftlab.app.data.MatchTimelineStore\n'''
)
replace_once(
    OVERLAY,
    '''    private val event = text("STATUS · 等待 Riot 数据源", 10f, 0xFF8C98AA.toInt())\n''',
    '''    private val event = text("STATUS · 等待实时 Provider", 10f, 0xFF8C98AA.toInt())\n'''
)
replace_once(
    OVERLAY,
    '''        val isLive = status.phase == LiveSourcePhase.LIVE\n        val scheduledLeft = target?.teams?.getOrNull(0)?.displayCode().orEmpty()\n''',
    '''        val isLive = status.phase == LiveSourcePhase.LIVE\n        val unifiedEvent = if (isLive && snapshot.game > 0) {\n            MatchTimelineStore.find(snapshot)?.events\n                ?.lastOrNull { it.seconds <= snapshot.elapsedSeconds }\n        } else null\n        val scheduledLeft = target?.teams?.getOrNull(0)?.displayCode().orEmpty()\n'''
)
replace_once(
    OVERLAY,
    '''            event.text = "EVENT · ${snapshot.latestEvent}"\n''',
    '''            event.text = unifiedEvent?.let { current ->\n                "EVENT · ${current.type.name} · ${current.title}"\n            } ?: "EVENT · 统一事件等待可核实节点"\n'''
)

print("dev72 overlay unified event batch16 applied")
