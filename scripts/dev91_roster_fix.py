from pathlib import Path
import re

feed = Path('app/src/main/java/com/riftlab/app/data/StartingRosterFeed.kt')
text = feed.read_text()
new_aliases = '''    private fun aliases(team: EsportsTeamRef): Set<String> {
        val raw = listOf(team.code, team.name, team.slug, team.id).filter(String::isNotBlank)
        val base = raw.map(::token).filter(String::isNotBlank).toMutableSet()
        raw.mapNotNull(::initialism).filter { it.length in 2..5 }.forEach(base::add)
        val expanded = base.toMutableSet()
        TEAM_ALIAS_GROUPS.forEach { group ->
            if (base.any(group::contains)) expanded.addAll(group)
        }
        return expanded
    }

    private fun initialism(value: String): String? {
        val cleaned = value
            .replace(Regex("['’]s\\b", RegexOption.IGNORE_CASE), "")
            .trim()
        val words = cleaned.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter(String::isNotBlank)
        if (words.size < 2) return null
        return token(words.joinToString("") { it.take(1) })
    }

    private val TEAM_ALIAS_GROUPS = listOf(
        setOf("IG", "INVICTUSGAMING"),
        setOf("AL", "ANYONESLEGEND", "ANYONELEGEND")
    )

    private fun token(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9\\p{L}\\p{N}]+"), "")
'''
pattern = re.compile(
    r'    private fun aliases\(team: EsportsTeamRef\): Set<String> =\n'
    r'        listOf\(team\.code, team\.name, team\.slug, team\.id\).*?\n\n'
    r'    private fun token\(value: String\): String =\n'
    r'        value\.uppercase\(\)\.replace\(Regex\("\[\^A-Z0-9\\\\p\{L\}\\\\p\{N\}\]\+"\), ""\)\n',
    re.S,
)
text, count = pattern.subn(lambda _: new_aliases, text, count=1)
if count != 1:
    raise SystemExit('StartingRosterFeed aliases block not found')
feed.write_text(text)

ui = Path('app/src/main/java/com/riftlab/app/ui/RiftLabApp.kt')
text = ui.read_text()
old = '''                Text(
                    if (socialCount > 0) officialRosterState.message else data.rosterNote,
                    color = RiftMuted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
'''
new = '''                Text(
                    officialRosterState.message,
                    color = RiftMuted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
                if (officialRosterState.diagnostics.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "ROSTER FEED · ${officialRosterState.diagnostics}",
                        color = RiftMuted,
                        fontSize = 10.sp,
                        lineHeight = 15.sp
                    )
                }
'''
if old not in text:
    raise SystemExit('RiftLabApp roster status block not found')
ui.write_text(text.replace(old, new, 1))

gradle = Path('app/build.gradle.kts')
text = gradle.read_text()
if 'versionCode = 90' in text:
    text = text.replace('versionCode = 90', 'versionCode = 91', 1)
if 'versionName = "1.0.0-dev.90"' in text:
    text = text.replace('versionName = "1.0.0-dev.90"', 'versionName = "1.0.0-dev.91"', 1)
text = text.replace('// dev.90: prefer LiteRT-LM GPU/OpenCL with CPU fallback and graded steady-state readiness.',
                    '// dev.91: robust official-roster team identity crosswalk and always-visible feed diagnostics.')
gradle.write_text(text)
