from pathlib import Path

p = Path('app/src/main/java/com/riftlab/app/data/LolEsportsDataSources.kt')
s = p.read_text(encoding='utf-8')
s = s.replace(
    'LiveMatchTargetRegistry.target = chooseLiveWatchTarget(matches)',
    'LiveMatchTargetRegistry.update(chooseLiveWatchTarget(matches))'
)
s = s.replace(
    'val scheduled = LiveMatchTargetRegistry.target',
    'val scheduled = LiveMatchTargetRegistry.snapshot()'
)
p.write_text(s, encoding='utf-8')

Path('.github/workflows/fix-live-registry.yml').unlink(missing_ok=True)
Path('tools/fix_live_registry_calls.py').unlink(missing_ok=True)
