from pathlib import Path

p = Path('app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt')
text = p.read_text(encoding='utf-8')
replacements = [
    (
        'EventCenterTab.POINTS -> ChampionshipPointsView()',
        'EventCenterTab.POINTS -> ChampionshipPointsView(selectedBucket)'
    ),
    (
        '''@Composable
private fun ChampionshipPointsView() {
    val rows = LplChampionshipPoints2026.rows
    Column(Modifier.fillMaxSize()) {''',
        '''@Composable
private fun ChampionshipPointsView(bucket: ScheduleCompetitionBucket) {
    val seasonYear = bucket.matches.mapNotNull(::matchStartDate).firstOrNull()?.year
    if (seasonYear != LplChampionshipPoints2026.season) {
        EmptyData("${seasonYear ?: "该"} 赛季年度积分尚未接入；不会显示 2026 数据作为替代。")
        return
    }
    val rows = LplChampionshipPoints2026.rows
    Column(Modifier.fillMaxSize()) {'''
    )
]
for old, new in replacements:
    if old not in text:
        raise SystemExit(f'target block not found: {old[:100]!r}')
    text = text.replace(old, new, 1)
p.write_text(text, encoding='utf-8')
print('dev23 season scope fix applied')
