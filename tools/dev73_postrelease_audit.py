#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one replacement target, found {count}\nTARGET:\n{old[:500]}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) Complete global post-match recovery on the same archive used by the POST surface.
store = ROOT / "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt"
replace_once(
    store,
    "    private val postMatchResolver = LplHistoricalPostMatchResolver()\n",
    "    private val postMatchResolver = LplHistoricalPostMatchResolver()\n"
    "    private val globalPostMatchProvider = OpggMatchSupplementProvider()\n"
    "    private val _postSourceStatus = MutableStateFlow(\"POST MATCH · 等待可核实终局数据\")\n",
)
replace_once(
    store,
    "    val postSourceStatus: StateFlow<String> = postMatchResolver.status\n",
    "    val postSourceStatus: StateFlow<String> = _postSourceStatus.asStateFlow()\n",
)
replace_once(
    store,
    """            // Post-match recovery is independent from the live target. Always resolve the most
            // recent completed series from the schedule. LPL currently has an additional TJStats final resolver;
            // can still rebuild the complete final archive.
            val latestCompleted = matches
                .filter(::isCompletedState)
                .maxByOrNull { plannedStartEpochMs(it) ?: Long.MIN_VALUE }
            if (latestCompleted != null && latestCompleted.league.contains("LPL", ignoreCase = true)) {
                scope.launch {
                    runCatching { postMatchResolver.refresh(latestCompleted) }
                }
            }
""",
    """            // Post-match recovery is independent from the live target. Always attempt to rebuild the
            // latest completed series from a source appropriate for that competition. LPL keeps its
            // TJStats resolver; other leagues may use the explicitly labelled OP.GG supplement. A missing
            // provider result stays missing and never becomes a synthetic final.
            val latestCompleted = matches
                .filter(::isCompletedState)
                .maxByOrNull { plannedStartEpochMs(it) ?: Long.MIN_VALUE }
            if (latestCompleted != null) {
                scope.launch {
                    val lplTarget = latestCompleted.leagueSlug.equals("lpl", ignoreCase = true) ||
                        latestCompleted.league.equals("LPL", ignoreCase = true) ||
                        latestCompleted.league.contains("PRO LEAGUE", ignoreCase = true)
                    if (lplTarget) {
                        _postSourceStatus.value = "LPL POST · 正在同步可核实终局数据…"
                        runCatching { postMatchResolver.refresh(latestCompleted) }
                            .onSuccess { _postSourceStatus.value = postMatchResolver.status.value }
                            .onFailure { error ->
                                _postSourceStatus.value = "LPL POST · 同步失败 · ${error.message?.take(120) ?: error::class.java.simpleName}"
                            }
                    } else {
                        _postSourceStatus.value = "GLOBAL POST · 正在匹配可核实终局数据…"
                        runCatching { globalPostMatchProvider.fetch(latestCompleted) }
                            .onSuccess { supplement ->
                                supplement.series?.let(CompletedGameArchive::publishSeries)
                                _postSourceStatus.value = supplement.status
                            }
                            .onFailure { error ->
                                _postSourceStatus.value = "GLOBAL POST · 同步失败 · ${error.message?.take(120) ?: error::class.java.simpleName}"
                            }
                    }
                }
            }
""",
)

# 2) User-facing live/post wording and mock-name cleanup.
app = ROOT / "app/src/main/java/com/riftlab/app/ui/RiftLabApp.kt"
replace_once(
    app,
    "import com.riftlab.app.data.MockAiInsightEngine\n",
    "import com.riftlab.app.data.LocalLiveInsightEngine\n",
)
replace_once(app, "    val ai = remember { MockAiInsightEngine() }\n", "    val ai = remember { LocalLiveInsightEngine() }\n")
replace_once(
    app,
    "赛后数据会从 LPL/TJStats 终局记录重新构建，不要求比赛时一直打开 RiftLab。",
    "赛后数据会从对应赛事的可核实终局源重新构建；LPL 可使用 TJStats，其他赛事使用明确标注来源的全球补充源，缺失就保持未知。",
)

# 3) Lift every explicitly tiny UI label. 10sp is the source-code floor; RiftTheme then applies
#    a 1.12x minimum font scale while respecting larger Android accessibility settings.
ui_dir = ROOT / "app/src/main/java/com/riftlab/app/ui"
for path in ui_dir.glob("*.kt"):
    text = path.read_text(encoding="utf-8")
    updated = re.sub(r"fontSize\s*=\s*[89]\.sp", "fontSize = 10.sp", text)
    if updated != text:
        path.write_text(updated, encoding="utf-8")

# 4) Make archive documentation provider-neutral.
archive = ROOT / "app/src/main/java/com/riftlab/app/data/CompletedGameArchive.kt"
replace_once(
    archive,
    """ * A completed game can be reconstructed from Tencent/TJStats after the game has ended, so the
 * post tab does not depend on RiftLab having been open for the final live frame.
""",
    """ * A completed game can be reconstructed from an explicitly sourced post-match provider after the
 * game has ended, so the post tab does not depend on RiftLab having been open for the final live
 * frame. LPL may use TJStats; other leagues may use a labelled global supplement. Missing data stays
 * unavailable rather than being synthesized.
""",
)

# 5) Release metadata must advance because dev.72 has already been published through OTA.
gradle = ROOT / "app/build.gradle.kts"
replace_once(gradle, "        versionCode = 72\n", "        versionCode = 73\n")
replace_once(gradle, '        versionName = "1.0.0-dev.72"\n', '        versionName = "1.0.0-dev.73"\n')

# 6) Remove the misleading class name only after its verified local replacement exists.
mock = ROOT / "app/src/main/java/com/riftlab/app/data/MockAiInsightEngine.kt"
if mock.exists():
    mock.unlink()

# Contract checks for this remediation batch.
store_text = store.read_text(encoding="utf-8")
app_text = app.read_text(encoding="utf-8")
theme_text = (ui_dir / "RiftTheme.kt").read_text(encoding="utf-8")
all_ui = "\n".join(p.read_text(encoding="utf-8") for p in ui_dir.glob("*.kt"))
gradle_text = gradle.read_text(encoding="utf-8")

assert "globalPostMatchProvider.fetch(latestCompleted)" in store_text
assert "CompletedGameArchive::publishSeries" in store_text
assert "postSourceStatus: StateFlow<String> = _postSourceStatus.asStateFlow()" in store_text
assert "LocalLiveInsightEngine()" in app_text
assert "MockAiInsightEngine" not in app_text
assert "RIFT_MIN_FONT_SCALE = 1.12f" in theme_text
assert not re.search(r"fontSize\s*=\s*[89]\.sp", all_ui)
assert 'versionCode = 73' in gradle_text
assert 'versionName = "1.0.0-dev.73"' in gradle_text
assert not mock.exists()
print("dev.73 post-release audit patch applied and contract checks passed")
