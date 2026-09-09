from pathlib import Path


def replace_exact(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'expected block not found in {path}: {old[:160]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')


ui = 'app/src/main/java/com/riftlab/app/ui/TeamDetailUi.kt'
dynamic = 'app/src/main/java/com/riftlab/app/data/DynamicTeamDataProvider.kt'
gradle = 'app/build.gradle.kts'
changelog = 'DEV_CHANGELOG.txt'

replace_exact(
    ui,
    '''        if (details?.socialLinks?.isNotEmpty() == true) {\n            item { TeamSectionTitle("OFFICIAL SOCIALS / 官方账号") }\n            item { SocialLinkRow(details.socialLinks) }\n            item { TeamSourceNote(state.profileStatus) }\n        }\n\n        if (state.loading && roster.isEmpty()) {''',
    '''        if (details?.socialLinks?.isNotEmpty() == true) {\n            item { TeamSectionTitle("OFFICIAL SOCIALS / 官方账号") }\n            item { SocialLinkRow(details.socialLinks) }\n            item { TeamSourceNote(state.profileStatus) }\n        }\n\n        if (state.organizationSummary.isNotBlank()) {\n            item { TeamSectionTitle("ORGANIZATION / 当前运营") }\n            item { TeamStatus("运营主体：${state.organizationSummary}") }\n        }\n\n        if (state.legalSummary.isNotBlank()) {\n            item { TeamSectionTitle("CORPORATE / 工商信息") }\n            item { TeamStatus(state.legalSummary) }\n        }\n\n        if (state.loading && roster.isEmpty()) {'''
)

replace_exact(
    ui,
    '''    "GENERALMANAGER" -> "总经理"\n    "ASSISTANTMANAGER" -> "助理经理"\n    "LEADER" -> "领队"\n    "SUPERVISOR" -> "监督"\n    "DIRECTOR" -> "主管"\n    "OWNER" -> "负责人"\n    "COOWNER" -> "联合负责人"\n    "CEO", "CHIEFEXECUTIVEOFFICER" -> "CEO"\n    "COO", "CHIEFOPERATINGOFFICER" -> "COO"\n    "HEADOFESPORTS" -> "电竞负责人"\n    "HEADOFLOL" -> "英雄联盟负责人"''',
    '''    "GENERALMANAGER" -> "总经理"\n    "ASSISTANTMANAGER" -> "助理经理"\n    "DEPUTYMANAGER" -> "副经理"\n    "LEADER" -> "领队"\n    "SUPERVISOR" -> "监督"\n    "DIRECTOR" -> "主管"\n    "ESPORTSDIRECTOR" -> "电竞总监"\n    "MANAGINGDIRECTOR" -> "执行董事"\n    "CHAIRMAN" -> "董事长"\n    "VICEPRESIDENT" -> "副总裁"\n    "OWNER" -> "负责人"\n    "COOWNER" -> "联合负责人"\n    "FOUNDER" -> "创始人"\n    "FOUNDERANDCEO" -> "创始人 / CEO"\n    "CEO", "CHIEFEXECUTIVEOFFICER" -> "CEO"\n    "COO", "CHIEFOPERATINGOFFICER" -> "COO"\n    "HEADOFESPORTS" -> "电竞负责人"\n    "HEADOFLOL" -> "英雄联盟负责人"'''
)

replace_exact(
    ui,
    '''    return key.contains("MANAGER") || key in setOf(\n        "LEADER", "SUPERVISOR", "DIRECTOR", "OWNER", "COOWNER", "CEO",\n        "CHIEFEXECUTIVEOFFICER", "COO", "CHIEFOPERATINGOFFICER", "HEADOFESPORTS", "HEADOFLOL"\n    )''',
    '''    return key.contains("MANAGER") || key in setOf(\n        "LEADER", "SUPERVISOR", "DIRECTOR", "ESPORTSDIRECTOR", "MANAGINGDIRECTOR",\n        "CHAIRMAN", "VICEPRESIDENT", "OWNER", "COOWNER", "FOUNDER", "FOUNDERANDCEO", "CEO",\n        "CHIEFEXECUTIVEOFFICER", "COO", "CHIEFOPERATINGOFFICER", "HEADOFESPORTS", "HEADOFLOL"\n    )'''
)

replace_exact(
    dynamic,
    '''        return TeamDynamicSupplement(\n            profile = profile,\n            staff = staff,\n            organizationSummary = "动态目录暂不可达 · 已回退 APK 离线快照",\n            sourceMode = "fallback"\n        )''',
    '''        return TeamDynamicSupplement(\n            profile = profile.copy(status = "RiftLab Dynamic Data 暂不可达 · ${profile.status}"),\n            staff = staff.copy(status = "RiftLab Dynamic Data 暂不可达 · ${staff.status}"),\n            sourceMode = "fallback"\n        )'''
)

replace_exact(
    gradle,
    '''        versionCode = 25\n        versionName = "1.0.0-dev.25"''',
    '''        versionCode = 26\n        versionName = "1.0.0-dev.26"'''
)
replace_exact(
    gradle,
    '''// dev.25: correct current IG governance after the OxHope Sports / Huya reorganization.''',
    '''// dev.26: remote-first dynamic team directory with scheduled source synchronization.'''
)

p = Path(changelog)
text = p.read_text(encoding='utf-8')
prefix = '''dev.26：战队资料从“写死在 APK 里的 Kotlin 快照”迁移为 RiftLab Dynamic Team Data。新增 data/lpl/team_profiles.json 远程目录，App 优先从 jsDelivr / GitHub Raw 拉取并以 30 分钟 TTL 缓存，管理层、教练组、运营主体和工商备注可以只更新数据文件而不重新发布 APK；远程不可达时自动回退现有离线快照。战队详情新增“当前运营 / 工商信息”区域，IG 可直接显示“氧望体育 × 虎牙直播”，法人信息要求绑定具体公司主体后再展示，不再从创始人或负责人推断。新增每 3 小时运行的 Team Data Sync：低频读取 Leaguepedia 当前 staff 作为教练组自动补充源，并通过官方微博定向搜索监听“人员变动公告 / 大名单 / 离队 / 加入 / 转会 / 重组”等事件；明确命中的离任、加入和角色变更可自动写回 team_profiles.json，不确定事件进入 watch state 而不污染现任数据。以后战队人员变化首先是“数据更新”，不再默认等于“发新版 APK”。\n\n'''
if not text.startswith('dev.26：'):
    p.write_text(prefix + text, encoding='utf-8')

print('dev26 migration applied')
