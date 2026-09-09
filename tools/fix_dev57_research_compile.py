from pathlib import Path

# Keep the original dev.57 research-model fixes idempotent.
p = Path('app/src/main/java/com/riftlab/app/data/TournamentResearch.kt')
s = p.read_text(encoding='utf-8')
s = s.replace('it.all(Char::isDigit)', 'it.all { ch -> ch.isDigit() }')
s = s.replace('.map { it.code.ifBlank { team -> it.name } }', '.map { team -> team.code.ifBlank { team.name } }')
s = s.replace('.map { it.name.ifBlank { stage -> it.slug } }', '.map { stage -> stage.name.ifBlank { stage.slug } }')
p.write_text(s, encoding='utf-8')

# Fix the two compile errors surfaced by Android Build after dev.57 landed.
p = Path('app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt')
s = p.read_text(encoding='utf-8')
needle = 'private fun researchEvidenceColor(value: ResearchEvidence) = when (value) {'
if '@Composable\n' + needle not in s:
    if needle not in s:
        raise SystemExit('researchEvidenceColor anchor not found')
    s = s.replace(needle, '@Composable\n' + needle, 1)

# Kotlin quoted strings reject \d. Use a raw triple-quoted regex instead.
old = 'return Regex("(?:19|20)\\d{2}").find(bucket.title)?.value?.toIntOrNull()'
new = 'return Regex("""(?:19|20)\\d{2}""").find(bucket.title)?.value?.toIntOrNull()'
if old in s:
    s = s.replace(old, new, 1)
elif new not in s:
    raise SystemExit('researchEditionYear regex anchor not found')
p.write_text(s, encoding='utf-8')
