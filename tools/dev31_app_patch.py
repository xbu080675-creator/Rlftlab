#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ui_path = ROOT / 'app/src/main/java/com/riftlab/app/ui/TeamDetailUi.kt'
build_path = ROOT / 'app/build.gradle.kts'
changelog_path = ROOT / 'DEV_CHANGELOG.txt'

ui = ui_path.read_text(encoding='utf-8')
ui = ui.replace(
    'import com.riftlab.app.data.TeamHonorRef\nimport com.riftlab.app.data.TeamAlumniRef',
    'import com.riftlab.app.data.TeamHonorRef\nimport com.riftlab.app.data.TeamResultRef\nimport com.riftlab.app.data.TeamAlumniRef'
)
old = '''        if (archive.honors.isNotEmpty()) {
            item { TeamSectionTitle("HONORS / 战队荣誉") }
            items(archive.honors, key = { "honor-${it.year}-${it.event}-${it.placement}" }) { honor -> TeamHonorRow(honor) }
        }

        if (archive.lineage.isNotEmpty()) {'''
new = '''        if (archive.honors.isNotEmpty()) {
            item { TeamSectionTitle("HONORS / 冠军荣誉") }
            items(archive.honors, key = { "honor-${it.year}-${it.event}-${it.placement}" }) { honor -> TeamHonorRow(honor) }
        }

        if (archive.results.isNotEmpty()) {
            item { TeamSectionTitle("RESULTS / 赛事成绩") }
            items(archive.results, key = { "result-${it.id}" }) { result -> TeamResultRow(result) }
            item { TeamSourceNote("冠军荣誉与完整赛事成绩分离；前身战队成绩不自动并入当前品牌。") }
        }

        if (archive.lineage.isNotEmpty()) {'''
if old not in ui:
    raise SystemExit('honors insertion anchor missing')
ui = ui.replace(old, new, 1)
anchor = '''@Composable
private fun TeamLineageRow(lineage: TeamLineageRef) {'''
row = '''@Composable
private fun TeamResultRow(result: TeamResultRef) {
    Row(
        Modifier.fillMaxWidth().background(RiftPanel, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp))
            .border(1.dp, RiftLine, CutCornerShape(topEnd = 10.dp, bottomStart = 6.dp)).padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(result.year, color = RiftCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(46.dp))
        Column(Modifier.weight(1f)) {
            Text(result.event, color = RiftText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            val meta = listOf(result.stage, result.tier).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, color = RiftMuted, fontSize = 7.sp)
        }
        Text(
            result.placement,
            color = if (result.isTitle) RiftCyan else RiftText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TeamLineageRow(lineage: TeamLineageRef) {'''
if anchor not in ui:
    raise SystemExit('lineage anchor missing')
ui = ui.replace(anchor, row, 1)
ui_path.write_text(ui, encoding='utf-8')

build = build_path.read_text(encoding='utf-8')
build = build.replace('versionCode = 30', 'versionCode = 31', 1)
build = build.replace('versionName = "1.0.0-dev.30"', 'versionName = "1.0.0-dev.31"', 1)
build = build.replace('// dev.30: full Team Archive schema, audited organization lineage and bundled dynamic team seeds.', '// dev.31: organization-centric esports graph and complete tournament result records.')
build_path.write_text(build, encoding='utf-8')

changelog = changelog_path.read_text(encoding='utf-8')
entry = ('dev.31：数据核心升级为 Organization → Game → Team → Roster/Personnel 的通用电竞图谱。新增 organization_id / game_id / team_id 外键式标识与 data/esports/esports_graph.json，当前 LPL 12 队作为首批节点，运营主体、上层组织、负责人、谱系和赛事成绩通过关系记录挂接；当前 Riot roster 与 RiftLab people 人物目录继续作为适配器接入，后续 LCK、LCP、KPL、VALORANT、CS2 可新增节点而无需重写战队 UI。战队“冠军荣誉”与“赛事成绩”正式分离：冠军继续进入 HONORS，亚军、季军、四强、八强及其他已核验 Top-8 结果进入 RESULTS；首轮补齐 135 条当前品牌重要成绩，前身战队成绩不自动继承。客户端优先读取通用图谱，旧 team_archive.json 仅作为兼容兜底。')
if not changelog.startswith('dev.31：'):
    changelog_path.write_text(entry + '\n\n' + changelog, encoding='utf-8')

print('dev31 app patch applied')
