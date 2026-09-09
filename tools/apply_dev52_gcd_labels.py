from pathlib import Path

p = Path('app/src/main/java/com/riftlab/app/ui/TeamDetailUi.kt')
s = p.read_text()
old = '''    "POSITIONALCOACH" -> "位置教练"
    "COACH" -> "教练"
    "ANALYST" -> "分析师"'''
new = '''    "POSITIONALCOACH" -> "位置教练"
    "COACHINGSTAFF" -> "官方注册教练组"
    "COACH" -> "教练"
    "ANALYST" -> "分析师"
    "TEAMCONTACT" -> "官方战队联系人"'''
if old not in s:
    raise SystemExit('staff role label block not found')
s = s.replace(old, new, 1)
p.write_text(s)

p = Path('app/src/main/java/com/riftlab/app/data/DynamicTeamDataProvider.kt')
s = p.read_text()
s = s.replace(
    'Use the global current-roster mirror / Leaguepedia resolver',
    'Use the Riot GCD global staff mirror / conservative secondary resolver',
    1
)
p.write_text(s)
