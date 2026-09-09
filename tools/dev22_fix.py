from pathlib import Path

p = Path('app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt')
text = p.read_text(encoding='utf-8')
old = '''    fun scheduleTimingNote(match: ScheduledEsportsMatch): String = when (scheduleActivity(match)) {
        ScheduleActivityState.UPCOMING -> "计划 ${formatLocalStart(match.startTimeIso)}"
        ScheduleActivityState.EVENT_LIVE -> "赛事已开始 · 等待小局数据"
        ScheduleActivityState.BETWEEN_GAMES -> "局间 · 等待下一小局"
        ScheduleActivityState.COMPLETED -> "已结束"
        ScheduleActivityState.GAME_LIVE -> {
            val plannedLabel = formatLocalStart(match.startTimeIso)
            val detectedAt = _scheduleCenter.value.liveDetectedAtEpochMs[scheduleKey(match)]
                ?: return@when "赛事计划 $plannedLabel · 小局进行中"
            val planned = plannedStartEpochMs(match)
                ?: return@when "小局进行中"
            val deltaMinutes = (detectedAt - planned) / 60_000L
            when {
                deltaMinutes >= 1L -> "赛事计划 $plannedLabel · 首次检测小局 LIVE +${deltaMinutes} 分钟"
                deltaMinutes <= -1L -> "赛事计划 $plannedLabel · 首次检测小局 LIVE ${deltaMinutes} 分钟"
                else -> "赛事计划 $plannedLabel · 小局进行中"
            }
        }
    }
'''
new = '''    fun scheduleTimingNote(match: ScheduledEsportsMatch): String = when (scheduleActivity(match)) {
        ScheduleActivityState.UPCOMING -> "计划 ${formatLocalStart(match.startTimeIso)}"
        ScheduleActivityState.EVENT_LIVE -> "赛事已开始 · 等待小局数据"
        ScheduleActivityState.BETWEEN_GAMES -> "局间 · 等待下一小局"
        ScheduleActivityState.COMPLETED -> "已结束"
        ScheduleActivityState.GAME_LIVE -> {
            val plannedLabel = formatLocalStart(match.startTimeIso)
            val detectedAt = _scheduleCenter.value.liveDetectedAtEpochMs[scheduleKey(match)]
            val planned = plannedStartEpochMs(match)
            if (detectedAt == null || planned == null) {
                "赛事计划 $plannedLabel · 小局进行中"
            } else {
                val deltaMinutes = (detectedAt - planned) / 60_000L
                when {
                    deltaMinutes >= 1L -> "赛事计划 $plannedLabel · 首次检测小局 LIVE +${deltaMinutes} 分钟"
                    deltaMinutes <= -1L -> "赛事计划 $plannedLabel · 首次检测小局 LIVE ${deltaMinutes} 分钟"
                    else -> "赛事计划 $plannedLabel · 小局进行中"
                }
            }
        }
    }
'''
if old not in text:
    raise SystemExit('target scheduleTimingNote block not found')
p.write_text(text.replace(old, new, 1), encoding='utf-8')
print('dev22 compile fix applied')
