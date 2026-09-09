from pathlib import Path
p=Path('app/src/main/java/com/riftlab/app/data/MatchDetailRepository.kt')
s=p.read_text()
s=s.replace(
'    private val awardsProvider = LplOfficialAwardsProvider()\n    private val draftProvider = LplOfficialDraftProvider()\n',
'    private val awardsProvider = LplOfficialAwardsProvider()\n    private val globalAwardsProvider = GlobalVerifiedAwardsProvider()\n    private val draftProvider = LplOfficialDraftProvider()\n',1)
s=s.replace(
'                !lplMatch -> OfficialAwardsResult(status = "MVP / POG · 非 LPL 不调用 LPL/TJStats 接口")\n',
'                !lplMatch -> runCatching { globalAwardsProvider.fetch(matchWithRiotAssets) }.getOrElse {\n                    OfficialAwardsResult(status = "全球 MVP / POG 同步失败 · ${it.message?.take(100) ?: it::class.java.simpleName}")\n                }\n',1)
p.write_text(s)
