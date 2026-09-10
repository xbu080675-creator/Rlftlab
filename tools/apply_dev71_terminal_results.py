#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


archive = "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt"
audit = "docs/RIFTLAB_DEV71_DATA_COVERAGE_AUDIT.md"

patch(
    archive,
    '''        val terminalOutcome = resolveTerminalOutcome(standings)
''',
    '''        val terminalOutcome = resolveTerminalOutcome(matches, standings)
'''
)

patch(
    archive,
    '''            TournamentEditionSlot(
                key = "placement",
                label = "最终名次",
                state = if (terminalOutcome != null && ended) TournamentEditionSlotState.PARTIAL else TournamentEditionSlotState.PENDING,
                detail = terminalOutcome ?: if (ended) "赛事已结束；等待可信最终名次来源" else "赛事未结束；最终名次尚未产生",
                source = if (terminalOutcome != null) "Riot Standings · terminal bracket outcome" else ""
            ),
''',
    '''            TournamentEditionSlot(
                key = "placement",
                label = "最终名次",
                state = if (terminalOutcome != null && ended) TournamentEditionSlotState.PARTIAL else TournamentEditionSlotState.PENDING,
                detail = terminalOutcome?.first ?: if (ended) "赛事已结束；等待可信最终名次来源" else "赛事未结束；最终名次尚未产生",
                source = terminalOutcome?.second.orEmpty()
            ),
'''
)

old_fn = '''    private fun resolveTerminalOutcome(standings: TournamentStandings?): String? {
        val bracket = standings?.stages.orEmpty().flatMap { stage -> stage.sections.flatMap { it.matches } }
        if (bracket.isEmpty()) return null
        val referenced = bracket.flatMap { it.previousMatchIds }.toSet()
        val terminal = bracket.lastOrNull {
            it.id.isNotBlank() && it.id !in referenced && isCompleted(it.state)
        } ?: bracket.lastOrNull { isCompleted(it.state) } ?: return null
        val winner = terminal.teams.firstOrNull { it.outcome.contains("win", ignoreCase = true) }
        val loser = terminal.teams.firstOrNull {
            it.outcome.contains("loss", ignoreCase = true) || it.outcome.contains("lose", ignoreCase = true)
        }
        if (winner == null) return null
        val winnerCode = winner.code.ifBlank { winner.name }
        val loserCode = loser?.let { it.code.ifBlank { it.name } }.orEmpty()
        return if (loserCode.isNotBlank()) {
            "冠军候选：$winnerCode · 亚军候选：$loserCode（Bracket 终局结构推导，仍待官方最终名次源确认）"
        } else {
            "冠军候选：$winnerCode（Bracket 终局结构推导，仍待官方最终名次源确认）"
        }
    }
'''

new_fn = '''    private fun resolveTerminalOutcome(
        matches: List<ScheduledEsportsMatch>,
        standings: TournamentStandings?
    ): Pair<String, String>? {
        // Prefer an explicitly labelled completed Final/Grand Final Series.  This is a provider
        // result fact, not a bracket-shape guess.  It establishes champion/runner-up only; it does
        // not pretend to know a complete placement table.
        val explicitFinal = matches
            .filter(::isCompletedSeries)
            .filter { match -> isExplicitFinalBlock(match.blockName) }
            .maxByOrNull { it.startTimeIso }
        if (explicitFinal != null) {
            val winner = explicitFinal.teams.firstOrNull { team ->
                team.outcome.contains("win", ignoreCase = true) ||
                    team.outcome.contains("winner", ignoreCase = true)
            } ?: explicitFinal.teams.maxByOrNull { it.gameWins }
            val loser = explicitFinal.teams.firstOrNull { team ->
                team !== winner && (
                    team.outcome.contains("loss", ignoreCase = true) ||
                        team.outcome.contains("lose", ignoreCase = true)
                    )
            } ?: explicitFinal.teams.firstOrNull { it !== winner }
            if (winner != null && explicitFinal.teams.size >= 2 && winner.gameWins >= loser?.gameWins ?: 0) {
                val winnerCode = winner.code.ifBlank { winner.name }
                val loserCode = loser?.let { it.code.ifBlank { it.name } }.orEmpty()
                val detail = if (loserCode.isNotBlank()) {
                    "冠军：$winnerCode · 亚军：$loserCode（已结束 Final Series 赛果）"
                } else {
                    "冠军：$winnerCode（已结束 Final Series 赛果）"
                }
                return detail to "Riot Completed Events / Unified Schedule · explicit Final Series"
            }
        }

        // Fallback remains clearly labelled as a bracket-derived candidate.
        val bracket = standings?.stages.orEmpty().flatMap { stage -> stage.sections.flatMap { it.matches } }
        if (bracket.isEmpty()) return null
        val referenced = bracket.flatMap { it.previousMatchIds }.toSet()
        val terminal = bracket.lastOrNull {
            it.id.isNotBlank() && it.id !in referenced && isCompleted(it.state)
        } ?: bracket.lastOrNull { isCompleted(it.state) } ?: return null
        val winner = terminal.teams.firstOrNull { it.outcome.contains("win", ignoreCase = true) }
        val loser = terminal.teams.firstOrNull {
            it.outcome.contains("loss", ignoreCase = true) || it.outcome.contains("lose", ignoreCase = true)
        }
        if (winner == null) return null
        val winnerCode = winner.code.ifBlank { winner.name }
        val loserCode = loser?.let { it.code.ifBlank { it.name } }.orEmpty()
        val detail = if (loserCode.isNotBlank()) {
            "冠军候选：$winnerCode · 亚军候选：$loserCode（Bracket 终局结构推导，仍待明确 Final/官方最终名次源确认）"
        } else {
            "冠军候选：$winnerCode（Bracket 终局结构推导，仍待明确 Final/官方最终名次源确认）"
        }
        return detail to "Riot Standings · terminal bracket structure (DERIVED)"
    }

    private fun isCompletedSeries(match: ScheduledEsportsMatch): Boolean {
        val state = match.state.lowercase().replace("_", "").replace("-", "").replace(" ", "")
        if (!(state.contains("complete") || state.contains("finished"))) return false
        val requiredWins = if (match.bestOf > 0) match.bestOf / 2 + 1 else 1
        return (match.teams.maxOfOrNull { it.gameWins } ?: 0) >= requiredWins
    }

    private fun isExplicitFinalBlock(blockName: String): Boolean {
        val token = blockName.trim().lowercase()
        if (token.isBlank()) return false
        if (token.contains("semi") || token.contains("quarter") || token.contains("1/2") || token.contains("1/4")) return false
        return token == "final" || token == "finals" || token.contains("grand final") ||
            token.contains("总决赛") || token.contains("决赛")
    }
'''
patch(archive, old_fn, new_fn)

p = ROOT / audit
text = p.read_text(encoding="utf-8")
addition = '''
## 2026-09-10 · terminal result tranche

- Final placement now prefers an explicitly labelled completed Final / Grand Final Series from Riot Completed Events / Unified Schedule.
- A verified final Series upgrades champion and runner-up from a bracket-shape guess to provider-backed result facts.
- If only a terminal bracket node exists, the UI continues to say `候选` and the source is explicitly marked `DERIVED`; lower placements stay unknown until a trustworthy placement table exists.
'''
if addition.strip() not in text:
    p.write_text(text.rstrip() + "\n" + addition, encoding="utf-8")

print("dev71 terminal result patch prepared")
