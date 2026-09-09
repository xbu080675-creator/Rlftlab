from pathlib import Path
p = Path('app/src/main/java/com/riftlab/app/data/TournamentResearch.kt')
s = p.read_text()
s = s.replace('it.all(Char::isDigit)', 'it.all { ch -> ch.isDigit() }')
s = s.replace('.map { it.code.ifBlank { team -> it.name } }', '.map { team -> team.code.ifBlank { team.name } }')
s = s.replace('.map { it.name.ifBlank { stage -> it.slug } }', '.map { stage -> stage.name.ifBlank { stage.slug } }')
p.write_text(s)
