#!/usr/bin/env python3
from pathlib import Path
import runpy

p = Path("tools/apply_dev72_audit_batch7.py")
text = p.read_text(encoding="utf-8")
old = '''    ''' + "'''" + '''        identity.contains("americas cup") || identity.contains("america cup") || identity.contains("美洲杯") -> "美洲杯"\\n        identity.contains("emea masters") || identity.contains("emea 大师赛") -> "EMEA 大师赛"\\n        Regex("(^|[^a-z])wsc[il]([^a-z]|$)").containsMatchIn(identity) -> "WSCL"\\n''' + "'''" + ''',
    ''' + "'''" + '''        identity.contains("americas cup") || identity.contains("america cup") || identity.contains("美洲杯") -> "美洲杯"\\n        identity.contains("emea masters") || identity.contains("emea 大师赛") -> "EMEA 大师赛"\\n        Regex("(^|[^a-z])wsci([^a-z]|$)").containsMatchIn(identity) -> "WSCI"\\n        Regex("(^|[^a-z])wscl([^a-z]|$)").containsMatchIn(identity) -> "WSCL"\\n''' + "'''" + '''
'''
new = '''    ''' + "'''" + '''        Regex("(^|[^a-z])wsc[il]([^a-z]|$)").containsMatchIn(identity) -> "WSCL"\\n        identity.contains("americas cup") || identity.contains("america cup") || identity.contains("美洲杯") -> "美洲杯"\\n        identity.contains("emea masters") || identity.contains("emea 大师赛") -> "EMEA 大师赛"\\n''' + "'''" + ''',
    ''' + "'''" + '''        Regex("(^|[^a-z])wsci([^a-z]|$)").containsMatchIn(identity) -> "WSCI"\\n        Regex("(^|[^a-z])wscl([^a-z]|$)").containsMatchIn(identity) -> "WSCL"\\n        identity.contains("americas cup") || identity.contains("america cup") || identity.contains("美洲杯") -> "美洲杯"\\n        identity.contains("emea masters") || identity.contains("emea 大师赛") -> "EMEA 大师赛"\\n''' + "'''" + '''
'''
if old not in text:
    raise SystemExit("failed to repair TournamentResearch patch block")
p.write_text(text.replace(old, new, 1), encoding="utf-8")
runpy.run_path(str(p), run_name="__main__")
