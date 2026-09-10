#!/usr/bin/env python3
from pathlib import Path

PATH = Path("app/src/main/java/com/riftlab/app/ui/MatchReplayContent.kt")
text = PATH.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"MatchReplayContent.kt: expected one match, got {count}: {old[:160]!r}")
    text = text.replace(old, new, 1)


replace_once(
    '''import com.riftlab.app.data.TimelineEventType\n''',
    '''import com.riftlab.app.data.TimelineEventEvidence\nimport com.riftlab.app.data.TimelineEventType\n'''
)

# Evidence/source metadata added in batch11 should be visible to the user, not just persisted.
replace_once(
    '''            if (anchor.detail.isNotBlank()) {\n                Text(anchor.detail, color = RiftMuted, fontSize = 8.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 2)\n            }\n        }\n''',
    '''            if (anchor.detail.isNotBlank()) {\n                Text(anchor.detail, color = RiftMuted, fontSize = 8.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 2)\n            }\n            if (anchor.source.isNotBlank()) {\n                Text(\n                    "SOURCE · ${anchor.source}",\n                    color = RiftMuted,\n                    fontSize = 7.sp,\n                    modifier = Modifier.padding(top = 3.dp),\n                    maxLines = 2\n                )\n            }\n        }\n'''
)

replace_once(
    '''                detail = event.detail,\n                source = "RiftLab 本机实时记录"\n''',
    '''                detail = event.detail,\n                source = listOf(\n                    replayEvidenceLabel(event.evidence),\n                    event.source.ifBlank { "RiftLab 本机实时记录" }\n                ).filter { it.isNotBlank() }.distinct().joinToString(" · ")\n'''
)

needle = '''private fun replayTimelineTitle(event: MatchTimelineEvent): String = when (event.type) {\n'''
helper = '''private fun replayEvidenceLabel(evidence: TimelineEventEvidence): String = when (evidence) {\n    TimelineEventEvidence.LOCAL_CAPTURE -> "本机采集"\n    TimelineEventEvidence.VERIFIED_DELTA -> "连续帧差分确认"\n    TimelineEventEvidence.DERIVED_WINDOW -> "派生窗口 · 非官方事件分类"\n    TimelineEventEvidence.PROVIDER_EXPLICIT -> "Provider 明确事件"\n}\n\n'''
if text.count(needle) != 1:
    raise SystemExit("MatchReplayContent.kt: replayTimelineTitle insertion point not found")
text = text.replace(needle, helper + needle, 1)

PATH.write_text(text, encoding="utf-8")
print("dev72 event provenance UI batch13 applied")
