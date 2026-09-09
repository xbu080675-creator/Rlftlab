from pathlib import Path


def replace_exact(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected block not found in {path}: {old[:120]!r}")
    text = text.replace(old, new, 1)
    p.write_text(text, encoding="utf-8")


# 1) Keep the existing coarse phase for compatibility, but add a presentation/activity
# phase that distinguishes broadcast/event start from an actual in-game live frame.
replace_exact(
    "app/src/main/java/com/riftlab/app/data/Models.kt",
    '''enum class ScheduleMatchPhase {\n    LIVE,\n    UPCOMING,\n    COMPLETED\n}\n\ndata class ScheduleCenterState(''',
    '''enum class ScheduleMatchPhase {\n    LIVE,\n    UPCOMING,\n    COMPLETED\n}\n\nenum class ScheduleActivityState {\n    GAME_LIVE,\n    EVENT_LIVE,\n    BETWEEN_GAMES,\n    UPCOMING,\n    COMPLETED\n}\n\ndata class ScheduleCenterState('''
)

# 2) MatchSessionStore: only timestamp an actual small-game LIVE detection, expose the
# richer activity state, and make timing text explicitly separate event start from game start.
replace_exact(
    "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt",
    '''        val key = scheduleKey(liveMatch)\n        val detected = if (key in center.liveDetectedAtEpochMs) {\n            center.liveDetectedAtEpochMs\n        } else {\n            center.liveDetectedAtEpochMs + (key to System.currentTimeMillis())\n        }''',
    '''        val key = scheduleKey(liveMatch)\n        val detected = if (\n            status.phase == LiveSourcePhase.LIVE &&\n            key !in center.liveDetectedAtEpochMs\n        ) {\n            center.liveDetectedAtEpochMs + (key to System.currentTimeMillis())\n        } else {\n            center.liveDetectedAtEpochMs\n        }'''
)

replace_exact(
    "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt",
    '''        val currentText = current?.let { "LIVE ${teamsLabel(it)}" } ?: "NO LIVE"''',
    '''        val currentText = current?.let { "${scheduleActivityLabel(it)} ${teamsLabel(it)}" } ?: "NO ACTIVE EVENT"'''
)

replace_exact(
    "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt",
    '''    fun schedulePhase(match: ScheduledEsportsMatch): ScheduleMatchPhase = when {\n        _scheduleCenter.value.currentMatch?.matchId == match.matchId -> ScheduleMatchPhase.LIVE\n        isLiveState(match) -> ScheduleMatchPhase.LIVE\n        isCompletedState(match) -> ScheduleMatchPhase.COMPLETED\n        else -> ScheduleMatchPhase.UPCOMING\n    }\n\n    fun scheduleScore(match: ScheduledEsportsMatch): String {''',
    '''    fun schedulePhase(match: ScheduledEsportsMatch): ScheduleMatchPhase = when {\n        _scheduleCenter.value.currentMatch?.matchId == match.matchId -> ScheduleMatchPhase.LIVE\n        isLiveState(match) -> ScheduleMatchPhase.LIVE\n        isCompletedState(match) -> ScheduleMatchPhase.COMPLETED\n        else -> ScheduleMatchPhase.UPCOMING\n    }\n\n    fun scheduleActivity(match: ScheduledEsportsMatch): ScheduleActivityState {\n        if (isCompletedState(match)) return ScheduleActivityState.COMPLETED\n\n        val current = _scheduleCenter.value.currentMatch\n        val isCurrent = current?.let { candidate ->\n            candidate.matchId == match.matchId ||\n                (match.eventId.isNotBlank() && candidate.eventId == match.eventId)\n        } == true\n        val status = liveDataSource.status.value\n        val statusTargetsMatch = isCurrent && (\n            status.eventId.isBlank() ||\n                status.eventId == match.eventId ||\n                status.eventId == match.matchId\n            )\n\n        if (statusTargetsMatch) {\n            when (status.phase) {\n                LiveSourcePhase.LIVE -> return ScheduleActivityState.GAME_LIVE\n                LiveSourcePhase.BETWEEN_GAMES -> return ScheduleActivityState.BETWEEN_GAMES\n                else -> Unit\n            }\n        }\n\n        if (isLiveState(match)) return ScheduleActivityState.EVENT_LIVE\n        return ScheduleActivityState.UPCOMING\n    }\n\n    fun scheduleActivityLabel(match: ScheduledEsportsMatch): String = when (scheduleActivity(match)) {\n        ScheduleActivityState.GAME_LIVE -> "小局进行中"\n        ScheduleActivityState.EVENT_LIVE -> "赛事进行中"\n        ScheduleActivityState.BETWEEN_GAMES -> "局间"\n        ScheduleActivityState.UPCOMING -> "待开"\n        ScheduleActivityState.COMPLETED -> "已结束"\n    }\n\n    fun scheduleScore(match: ScheduledEsportsMatch): String {'''
)

replace_exact(
    "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt",
    '''    fun scheduleTimingNote(match: ScheduledEsportsMatch): String {\n        val detectedAt = _scheduleCenter.value.liveDetectedAtEpochMs[scheduleKey(match)] ?: return "计划 ${formatLocalStart(match.startTimeIso)}"\n        val planned = plannedStartEpochMs(match) ?: return "LIVE 已检测"\n        val deltaMs = planned - detectedAt\n        return if (deltaMs >= 60_000L) {\n            "计划 ${formatLocalStart(match.startTimeIso)} · 提前约 ${deltaMs / 60_000L} 分钟检测到 LIVE"\n        } else {\n            "计划 ${formatLocalStart(match.startTimeIso)} · LIVE 已检测"\n        }\n    }''',
    '''    fun scheduleTimingNote(match: ScheduledEsportsMatch): String = when (scheduleActivity(match)) {\n        ScheduleActivityState.UPCOMING -> "计划 ${formatLocalStart(match.startTimeIso)}"\n        ScheduleActivityState.EVENT_LIVE -> "赛事已开始 · 等待小局数据"\n        ScheduleActivityState.BETWEEN_GAMES -> "局间 · 等待下一小局"\n        ScheduleActivityState.COMPLETED -> "已结束"\n        ScheduleActivityState.GAME_LIVE -> {\n            val plannedLabel = formatLocalStart(match.startTimeIso)\n            val detectedAt = _scheduleCenter.value.liveDetectedAtEpochMs[scheduleKey(match)]\n                ?: return@when "赛事计划 $plannedLabel · 小局进行中"\n            val planned = plannedStartEpochMs(match)\n                ?: return@when "小局进行中"\n            val deltaMinutes = (detectedAt - planned) / 60_000L\n            when {\n                deltaMinutes >= 1L -> "赛事计划 $plannedLabel · 首次检测小局 LIVE +${deltaMinutes} 分钟"\n                deltaMinutes <= -1L -> "赛事计划 $plannedLabel · 首次检测小局 LIVE ${deltaMinutes} 分钟"\n                else -> "赛事计划 $plannedLabel · 小局进行中"\n            }\n        }\n    }'''
)

# 3) Event center: show broadcast/event-live, actual game-live and between-games as
# distinct labels instead of flattening every ongoing series into LIVE.
replace_exact(
    "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt",
    '''import com.riftlab.app.data.MatchSessionStore\nimport com.riftlab.app.data.ScheduleMatchPhase''',
    '''import com.riftlab.app.data.MatchSessionStore\nimport com.riftlab.app.data.ScheduleActivityState\nimport com.riftlab.app.data.ScheduleMatchPhase'''
)

replace_exact(
    "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt",
    '''    val hasCurrent = bucket.matches.any { it.matchId == current?.matchId }\n    val hasNext = bucket.matches.any { it.matchId == next?.matchId }''',
    '''    val hasCurrent = bucket.matches.any { it.matchId == current?.matchId }\n    val hasNext = bucket.matches.any { it.matchId == next?.matchId }\n    val currentActivity = current?.takeIf { hasCurrent }?.let(MatchSessionStore::scheduleActivity)'''
)

replace_exact(
    "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt",
    '''                    hasCurrent -> "进行中"''',
    '''                    hasCurrent -> currentActivity?.let(::scheduleActivityText) ?: "进行中"'''
)

replace_exact(
    "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt",
    '''            Text("LIVE · ${matchLabel(current)}", color = RiftCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)''',
    '''            Text(\n                "${currentActivity?.let(::scheduleActivityText) ?: "进行中"} · ${matchLabel(current)} · ${MatchSessionStore.scheduleTimingNote(current)}",\n                color = RiftCyan,\n                fontSize = 11.sp,\n                fontWeight = FontWeight.SemiBold\n            )'''
)

replace_exact(
    "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt",
    '''            val hasCurrent = bucket.matches.any { it.matchId == currentMatchId }\n            val hasNext = bucket.matches.any { it.matchId == nextMatchId }''',
    '''            val currentMatch = bucket.matches.firstOrNull { it.matchId == currentMatchId }\n            val hasCurrent = currentMatch != null\n            val hasNext = bucket.matches.any { it.matchId == nextMatchId }'''
)

replace_exact(
    "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt",
    '''                            hasCurrent -> Text("LIVE", color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)''',
    '''                            hasCurrent -> Text(\n                                currentMatch?.let { scheduleActivityText(MatchSessionStore.scheduleActivity(it)) } ?: "进行中",\n                                color = RiftCyan,\n                                fontSize = 10.sp,\n                                fontWeight = FontWeight.Bold\n                            )'''
)

replace_exact(
    "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt",
    '''private fun ScheduleMatchCard(match: ScheduledEsportsMatch, selected: Boolean, onClick: () -> Unit) {\n    val phase = MatchSessionStore.schedulePhase(match)\n    val left = match.teams.getOrNull(0)\n    val right = match.teams.getOrNull(1)''',
    '''private fun ScheduleMatchCard(match: ScheduledEsportsMatch, selected: Boolean, onClick: () -> Unit) {\n    val phase = MatchSessionStore.scheduleActivity(match)\n    val active = phase == ScheduleActivityState.GAME_LIVE ||\n        phase == ScheduleActivityState.EVENT_LIVE ||\n        phase == ScheduleActivityState.BETWEEN_GAMES\n    val left = match.teams.getOrNull(0)\n    val right = match.teams.getOrNull(1)'''
)

replace_exact(
    "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt",
    '''            .border(1.dp, if (selected || phase == ScheduleMatchPhase.LIVE) RiftCyan.copy(alpha = 0.48f) else RiftLine, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))''',
    '''            .border(1.dp, if (selected || active) RiftCyan.copy(alpha = 0.48f) else RiftLine, CutCornerShape(topEnd = 14.dp, bottomStart = 8.dp))'''
)

replace_exact(
    "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt",
    '''                when (phase) {\n                    ScheduleMatchPhase.LIVE -> "LIVE"\n                    ScheduleMatchPhase.UPCOMING -> "待开"\n                    ScheduleMatchPhase.COMPLETED -> "已结束"\n                },\n                color = if (phase == ScheduleMatchPhase.LIVE) RiftCyan else RiftMuted,''',
    '''                scheduleActivityText(phase),\n                color = if (active) RiftCyan else RiftMuted,'''
)

replace_exact(
    "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt",
    '''            centerText = if (phase == ScheduleMatchPhase.COMPLETED || match.teams.any { it.gameWins > 0 }) MatchSessionStore.scheduleScore(match) else "VS",''',
    '''            centerText = if (phase == ScheduleActivityState.COMPLETED || match.teams.any { it.gameWins > 0 }) MatchSessionStore.scheduleScore(match) else "VS",'''
)

replace_exact(
    "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt",
    '''private fun bracketState(value: String): String = when {''',
    '''private fun scheduleActivityText(value: ScheduleActivityState): String = when (value) {\n    ScheduleActivityState.GAME_LIVE -> "小局直播"\n    ScheduleActivityState.EVENT_LIVE -> "赛事进行中"\n    ScheduleActivityState.BETWEEN_GAMES -> "局间"\n    ScheduleActivityState.UPCOMING -> "待开"\n    ScheduleActivityState.COMPLETED -> "已结束"\n}\n\nprivate fun bracketState(value: String): String = when {'''
)

# 4) Main live screen: keep provider internals out of the primary user-facing state line.
replace_exact(
    "app/src/main/java/com/riftlab/app/ui/RiftLabApp.kt",
    '''import com.riftlab.app.data.PlayerCard\nimport com.riftlab.app.data.ScheduledEsportsMatch''',
    '''import com.riftlab.app.data.PlayerCard\nimport com.riftlab.app.data.ScheduleActivityState\nimport com.riftlab.app.data.ScheduledEsportsMatch'''
)

replace_exact(
    "app/src/main/java/com/riftlab/app/ui/RiftLabApp.kt",
    '''    val target by MatchSessionStore.targetMatch.collectAsState()\n    val isLive = status.phase == LiveSourcePhase.LIVE\n    val displayBlue = if (isLive) snapshot.blue else scheduled.blue.takeUnless { it.isBlank() || it == "—" } ?: "—"''',
    '''    val target by MatchSessionStore.targetMatch.collectAsState()\n    val isLive = status.phase == LiveSourcePhase.LIVE\n    val activity = target?.let(MatchSessionStore::scheduleActivity)\n    val eventActive = isLive ||\n        activity == ScheduleActivityState.EVENT_LIVE ||\n        activity == ScheduleActivityState.BETWEEN_GAMES\n    val phaseLabel = when {\n        isLive -> "小局直播 · GAME ${snapshot.game}"\n        activity == ScheduleActivityState.BETWEEN_GAMES -> "局间 · 等待下一小局"\n        activity == ScheduleActivityState.EVENT_LIVE -> "赛事已开始 · 等待小局"\n        status.phase == LiveSourcePhase.WAITING_FOR_MATCH -> "等待赛事开始"\n        status.phase == LiveSourcePhase.ERROR -> "实时源异常"\n        else -> "实时源待机"\n    }\n    val feedLabel = when {\n        isLive -> "小局实时数据"\n        activity == ScheduleActivityState.BETWEEN_GAMES -> "局间待机"\n        activity == ScheduleActivityState.EVENT_LIVE -> "赛事进行中 · 等待小局数据"\n        status.phase == LiveSourcePhase.WAITING_FOR_MATCH -> "等待赛事"\n        status.phase == LiveSourcePhase.ERROR -> "实时源异常"\n        else -> "实时源待机"\n    }\n    val displayBlue = if (isLive) snapshot.blue else scheduled.blue.takeUnless { it.isBlank() || it == "—" } ?: "—"'''
)

replace_exact(
    "app/src/main/java/com/riftlab/app/ui/RiftLabApp.kt",
    '''            Panel(accent = isLive) {\n                Row(verticalAlignment = Alignment.CenterVertically) {\n                    Text(\n                        if (isLive) "LIVE · GAME ${snapshot.game}" else status.phase.name,\n                        color = when (status.phase) {\n                            LiveSourcePhase.LIVE -> RiftCyan\n                            LiveSourcePhase.ERROR -> RiftRed\n                            else -> RiftMuted\n                        },''',
    '''            Panel(accent = eventActive) {\n                Row(verticalAlignment = Alignment.CenterVertically) {\n                    Text(\n                        phaseLabel,\n                        color = when {\n                            status.phase == LiveSourcePhase.ERROR -> RiftRed\n                            eventActive -> RiftCyan\n                            else -> RiftMuted\n                        },'''
)

replace_exact(
    "app/src/main/java/com/riftlab/app/ui/RiftLabApp.kt",
    '''            Panel(accent = isLive) {\n                Row(verticalAlignment = Alignment.CenterVertically) {\n                    Text(\n                        status.phase.name,\n                        color = when (status.phase) {\n                            LiveSourcePhase.LIVE -> RiftCyan\n                            LiveSourcePhase.ERROR -> RiftRed\n                            else -> RiftMuted\n                        },''',
    '''            Panel(accent = eventActive) {\n                Row(verticalAlignment = Alignment.CenterVertically) {\n                    Text(\n                        feedLabel,\n                        color = when {\n                            status.phase == LiveSourcePhase.ERROR -> RiftRed\n                            eventActive -> RiftCyan\n                            else -> RiftMuted\n                        },'''
)

replace_exact(
    "app/src/main/java/com/riftlab/app/ui/RiftLabApp.kt",
    '''                "直播跳转只是快捷入口；赛事数据、RiftScreen 与直播平台完全解耦。计划开赛时间仅作参考，Live 状态以 Riot 实际数据为准。",''',
    '''                "赛事开始状态与小局 LIVE 分开判定：选手入场、评论席等阶段只标记“赛事进行中”，只有实时 Provider 拿到小局帧才进入“小局直播”。直播跳转只是快捷入口，赛事数据与直播平台完全解耦。",'''
)

# 5) Version bump. Changelog is updated in the follow-up connector commit so normal CI is
# triggered after this GITHUB_TOKEN-authored migration commit.
replace_exact(
    "app/build.gradle.kts",
    '''        versionCode = 21\n        versionName = "1.0.0-dev.21"''',
    '''        versionCode = 22\n        versionName = "1.0.0-dev.22"'''
)
replace_exact(
    "app/build.gradle.kts",
    '''// dev.21: offline management snapshot; remove runtime Fandom profile dependency.''',
    '''// dev.22: distinguish event/broadcast start from actual game-live and between-games states.'''
)

print("dev22 migration applied successfully")
