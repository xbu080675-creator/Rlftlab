#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


UI = "app/src/main/java/com/riftlab/app/ui/RiftLabApp.kt"

# The dev.72 contract says UI consumes one normalized live event model. Keep provider free-form
# latestEvent as a diagnostic field, but stop presenting it as the event truth on the LIVE surface.
replace_once(
    UI,
    '''import com.riftlab.app.data.MatchSessionStore\n''',
    '''import com.riftlab.app.data.MatchSessionStore\nimport com.riftlab.app.data.MatchTimelineStore\nimport com.riftlab.app.data.TimelineEventEvidence\n'''
)

# Global schedule labels must describe the actual merged source plane.
replace_once(
    UI,
    '''Text("RIOT LOL ESPORTS · SCHEDULE", color = if (target != null) RiftCyan else RiftMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)''',
    '''Text("UNIFIED SCHEDULE · RIOT / CITO / INTERNATIONAL", color = if (target != null) RiftCyan else RiftMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)'''
)
replace_once(
    UI,
    '''Text("SOURCE  Unified Schedule · Riot/Cito · 结果视角已标明", color = RiftMuted, fontSize = 9.sp)''',
    '''Text("SOURCE  Unified Schedule · Riot/Cito/International Mirror · 结果视角已标明", color = RiftMuted, fontSize = 9.sp)'''
)

# Subscribe to the event-sourced timeline. Every upstream provider first becomes a normalized
# LiveSnapshot through GlobalOfficialLiveDataSource; MatchTimelineStore then emits the common event
# vocabulary. The UI reads only that common vocabulary for event facts.
replace_once(
    UI,
    '''    val snapshot by MatchSessionStore.live.collectAsState()\n    val status by MatchSessionStore.liveSourceStatus.collectAsState()\n''',
    '''    val snapshot by MatchSessionStore.live.collectAsState()\n    val timelines by MatchTimelineStore.timelines.collectAsState()\n    val status by MatchSessionStore.liveSourceStatus.collectAsState()\n'''
)
replace_once(
    UI,
    '''    val displayBlue = if (isLive) snapshot.blue else scheduled.blue.takeUnless { it.isBlank() || it == "—" } ?: "—"\n    val displayRed = if (isLive) snapshot.red else scheduled.red.takeUnless { it.isBlank() || it == "—" } ?: "—"\n    val ai = remember { MockAiInsightEngine() }\n    var insight by remember { androidx.compose.runtime.mutableStateOf("等待 Riot 实时帧；暂不生成局势判断。") }\n''',
    '''    val displayBlue = if (isLive) snapshot.blue else scheduled.blue.takeUnless { it.isBlank() || it == "—" } ?: "—"\n    val displayRed = if (isLive) snapshot.red else scheduled.red.takeUnless { it.isBlank() || it == "—" } ?: "—"\n    val unifiedEvent = if (isLive && snapshot.game > 0) {\n        MatchTimelineStore.find(snapshot, timelines)?.events\n            ?.lastOrNull { it.seconds <= snapshot.elapsedSeconds }\n    } else null\n    val ai = remember { MockAiInsightEngine() }\n    var insight by remember { androidx.compose.runtime.mutableStateOf("等待实时 Provider 有效帧；暂不生成局势判断。") }\n'''
)
replace_once(
    UI,
    '''            "等待 Riot 实时帧；本地局势解读暂不生成，避免把占位数据当真。"\n''',
    '''            "等待实时 Provider 有效帧；本地局势解读暂不生成，避免把占位数据当真。"\n'''
)

replace_once(
    UI,
    '''                Text("LOCAL LIVE READ", color = if (isLive) RiftCyan else RiftMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)\n                }\n                Spacer(Modifier.height(8.dp))\n                Text(insight, fontWeight = FontWeight.Medium, lineHeight = 21.sp)\n                Spacer(Modifier.height(8.dp))\n                Text(snapshot.latestEvent, color = RiftMuted, fontSize = 11.sp)\n''',
    '''                Text("UNIFIED LIVE EVENT / 统一事件", color = if (isLive) RiftCyan else RiftMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)\n                }\n                Spacer(Modifier.height(8.dp))\n                Text(insight, fontWeight = FontWeight.Medium, lineHeight = 21.sp)\n                Spacer(Modifier.height(8.dp))\n                if (unifiedEvent != null) {\n                    Text(\n                        "${unifiedEvent.type.name} · ${unifiedEvent.title}",\n                        color = RiftText,\n                        fontSize = 11.sp,\n                        fontWeight = FontWeight.SemiBold\n                    )\n                    if (unifiedEvent.detail.isNotBlank()) {\n                        Text(unifiedEvent.detail, color = RiftMuted, fontSize = 9.sp, lineHeight = 14.sp)\n                    }\n                    Text(\n                        "EVIDENCE  ${liveEventEvidenceLabel(unifiedEvent.evidence)}",\n                        color = if (unifiedEvent.evidence == TimelineEventEvidence.DERIVED_WINDOW) RiftMuted else RiftCyan,\n                        fontSize = 8.sp\n                    )\n                    Text(\n                        "SOURCE  ${unifiedEvent.source.ifBlank { "统一事件模型" }}",\n                        color = RiftMuted,\n                        fontSize = 8.sp\n                    )\n                } else {\n                    Text(\n                        "统一事件流等待下一条可核实事件；Provider 自由文本只保留作诊断，不作为赛事事件事实展示。",\n                        color = RiftMuted,\n                        fontSize = 10.sp,\n                        lineHeight = 15.sp\n                    )\n                }\n'''
)

# Put evidence wording in one place so LIVE and replay can keep the evidence boundary understandable.
marker = '''@Composable\nprivate fun PostScreen() {\n'''
helper = '''private fun liveEventEvidenceLabel(evidence: TimelineEventEvidence): String = when (evidence) {\n    TimelineEventEvidence.LOCAL_CAPTURE -> "本机捕获"\n    TimelineEventEvidence.VERIFIED_DELTA -> "连续帧确认"\n    TimelineEventEvidence.DERIVED_WINDOW -> "派生导航窗口"\n    TimelineEventEvidence.PROVIDER_EXPLICIT -> "Provider 明确事件"\n}\n\n'''
p = Path(UI)
text = p.read_text(encoding="utf-8")
if text.count(marker) != 1:
    raise SystemExit("RiftLabApp.kt: PostScreen insertion marker not found")
p.write_text(text.replace(marker, helper + marker, 1), encoding="utf-8")

print("dev72 unified live event UI batch16 applied")
