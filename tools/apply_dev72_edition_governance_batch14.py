#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


ARCHIVE = "app/src/main/java/com/riftlab/app/data/TournamentEditionArchive.kt"
UI = "app/src/main/java/com/riftlab/app/ui/ScheduleCenterUi.kt"

# Persist the actual per-edition rule/draw/qualification facts, not only a summary slot. This keeps
# a previously verified historical rulebook usable when today's API window is thinner.
replace_once(
    ARCHIVE,
    '''data class TournamentEditionArchiveRecord(\n''',
    '''data class TournamentQualificationArchive(\n    val detail: String,\n    val source: String,\n    val savedAtEpochMs: Long = System.currentTimeMillis()\n)\n\ndata class TournamentEditionArchiveRecord(\n'''
)
replace_once(
    ARCHIVE,
    '''    val patchVersions: List<String> = emptyList(),\n    val archivedSlots: List<TournamentEditionSlot> = emptyList(),\n''',
    '''    val patchVersions: List<String> = emptyList(),\n    val archivedRules: TournamentRulesSnapshot? = null,\n    val archivedDraw: TournamentDrawSnapshot? = null,\n    val archivedQualification: TournamentQualificationArchive? = null,\n    val archivedSlots: List<TournamentEditionSlot> = emptyList(),\n'''
)

# During directory rebuild, preserve the best known governance for every edition, even when that
# edition is not the current home target. This is local work: no extra network fetch is introduced.
replace_once(
    ARCHIVE,
    '''            val old = previous[ref.id]\n            previous[ref.id] = TournamentEditionArchiveRecord(\n''',
    '''            val old = previous[ref.id]\n            val displayName = StandingsCenterStore.displayTournamentName(ref)\n            val standingsForEdition = standingsState.standings?.takeIf { it.tournamentId == ref.id }\n            val liveGovernance = TournamentGovernanceProvider.resolve(\n                tournament = ref,\n                competitionTitle = displayName,\n                matches = matches,\n                standings = standingsForEdition\n            )\n            val retainedGovernance = mergeGovernanceForDisplay(old, liveGovernance)\n            val liveQualification = OfficialHandbookGovernance2026.qualificationSummaryFor(\n                tournament = ref,\n                competitionTitle = displayName,\n                identity = "${identity.family} ${identity.stage} ${ref.slug} ${ref.leagueSlug} ${ref.leagueName}"\n            )\n            val retainedQualification = preferQualification(old?.archivedQualification, liveQualification)\n            previous[ref.id] = TournamentEditionArchiveRecord(\n'''
)
replace_once(
    ARCHIVE,
    '''                displayName = StandingsCenterStore.displayTournamentName(ref),\n''',
    '''                displayName = displayName,\n'''
)
replace_once(
    ARCHIVE,
    '''                patchVersions = old?.patchVersions.orEmpty(),\n                archivedSlots = old?.archivedSlots.orEmpty(),\n''',
    '''                patchVersions = old?.patchVersions.orEmpty(),\n                archivedRules = retainedGovernance.rules,\n                archivedDraw = retainedGovernance.draw,\n                archivedQualification = retainedQualification,\n                archivedSlots = old?.archivedSlots.orEmpty(),\n'''
)

# Detail hydration can discover richer historical Standings/Bracket data. Merge it against the
# durable snapshot, preferring fresher/equally complete material but never downgrading verified data.
replace_once(
    ARCHIVE,
    '''        val governance = TournamentGovernanceProvider.resolve(\n            tournament = ref,\n            competitionTitle = record.displayName,\n            matches = matches,\n            standings = standings\n        )\n''',
    '''        val liveGovernance = TournamentGovernanceProvider.resolve(\n            tournament = ref,\n            competitionTitle = record.displayName,\n            matches = matches,\n            standings = standings\n        )\n        val governance = mergeGovernanceForDisplay(record, liveGovernance)\n'''
)
replace_once(
    ARCHIVE,
    '''        val qualificationSummary = OfficialHandbookGovernance2026.qualificationSummaryFor(\n            tournament = ref,\n            competitionTitle = record.displayName,\n            identity = "${record.family} ${record.stage} ${record.slug} ${record.leagueSlug} ${record.leagueName}"\n        )\n''',
    '''        val liveQualificationSummary = OfficialHandbookGovernance2026.qualificationSummaryFor(\n            tournament = ref,\n            competitionTitle = record.displayName,\n            identity = "${record.family} ${record.stage} ${record.slug} ${record.leagueSlug} ${record.leagueName}"\n        )\n        val archivedQualification = preferQualification(record.archivedQualification, liveQualificationSummary)\n        val qualificationSummary = archivedQualification?.let { it.detail to it.source }\n'''
)
replace_once(
    ARCHIVE,
    '''            patchVersions = (record.patchVersions + historyOverride?.patchVersions.orEmpty()).distinct()\n        )\n''',
    '''            patchVersions = (record.patchVersions + historyOverride?.patchVersions.orEmpty()).distinct(),\n            archivedRules = governance.rules,\n            archivedDraw = governance.draw,\n            archivedQualification = archivedQualification\n        )\n'''
)

# Public display merge lets ScheduleCenter consume the durable snapshot without changing the archive
# browsing target. Equal-quality live snapshots win, so official revisions can replace old copies.
marker = '''    /** Never downgrade a known historical slot because a later network window is thinner. */\n'''
helper = '''    fun mergeGovernanceForDisplay(\n        record: TournamentEditionArchiveRecord?,\n        live: TournamentGovernanceSnapshot\n    ): TournamentGovernanceSnapshot = TournamentGovernanceSnapshot(\n        rules = preferRules(record?.archivedRules, live.rules),\n        draw = preferDraw(record?.archivedDraw, live.draw)\n    )\n\n    private fun preferRules(\n        archived: TournamentRulesSnapshot?,\n        live: TournamentRulesSnapshot\n    ): TournamentRulesSnapshot {\n        if (archived == null) return live\n        fun quality(snapshot: TournamentRulesSnapshot): Int =\n            snapshot.items.count { it.verified } * 1000 +\n                snapshot.items.count { !it.title.contains("待同步") } * 10 +\n                snapshot.items.size\n        return if (quality(live) >= quality(archived)) live else archived\n    }\n\n    private fun preferDraw(\n        archived: TournamentDrawSnapshot?,\n        live: TournamentDrawSnapshot\n    ): TournamentDrawSnapshot {\n        if (archived == null) return live\n        fun quality(snapshot: TournamentDrawSnapshot): Int =\n            snapshot.slots.count { it.verified } * 1000 +\n                snapshot.slots.sumOf { slot ->\n                    listOf(slot.left, slot.right).count { it.isNotBlank() && !it.equals("TBD", true) } * 10\n                } + snapshot.slots.size\n        return if (quality(live) >= quality(archived)) live else archived\n    }\n\n    private fun preferQualification(\n        archived: TournamentQualificationArchive?,\n        live: Pair<String, String>?\n    ): TournamentQualificationArchive? {\n        if (live == null) return archived\n        val detail = live.first.trim()\n        val source = live.second.trim()\n        if (detail.isBlank() || source.isBlank()) return archived\n        return TournamentQualificationArchive(detail = detail, source = source)\n    }\n\n'''
p = Path(ARCHIVE)
text = p.read_text(encoding="utf-8")
if text.count(marker) != 1:
    raise SystemExit("TournamentEditionArchive.kt: merge helper marker not found")
p.write_text(text.replace(marker, helper + marker, 1), encoding="utf-8")

# Backward-compatible schema-1 load: older archives simply have these objects absent.
replace_once(
    ARCHIVE,
    '''                            patchVersions = jsonStrings(row.optJSONArray("patchVersions")),\n                            archivedSlots = parseSlots(row.optJSONArray("slots")),\n''',
    '''                            patchVersions = jsonStrings(row.optJSONArray("patchVersions")),\n                            archivedRules = parseRulesSnapshot(row.optJSONObject("rulesSnapshot")),\n                            archivedDraw = parseDrawSnapshot(row.optJSONObject("drawSnapshot")),\n                            archivedQualification = parseQualificationSnapshot(row.optJSONObject("qualificationSnapshot")),\n                            archivedSlots = parseSlots(row.optJSONArray("slots")),\n'''
)

# Write the durable full snapshots alongside existing compact coverage slots.
replace_once(
    ARCHIVE,
    '''                                    put("patchVersions", JSONArray(edition.patchVersions))\n                                    put("slots", JSONArray().apply {\n''',
    '''                                    put("patchVersions", JSONArray(edition.patchVersions))\n                                    edition.archivedRules?.let { put("rulesSnapshot", rulesSnapshotJson(it)) }\n                                    edition.archivedDraw?.let { put("drawSnapshot", drawSnapshotJson(it)) }\n                                    edition.archivedQualification?.let { qualification ->\n                                        put("qualificationSnapshot", JSONObject().apply {\n                                            put("detail", qualification.detail)\n                                            put("source", qualification.source)\n                                            put("savedAtEpochMs", qualification.savedAtEpochMs)\n                                        })\n                                    }\n                                    put("slots", JSONArray().apply {\n'''
)

# JSON helpers are deliberately explicit; no reflection/serialization dependency is added.
marker = '''    private fun parseSlots(array: JSONArray?): List<TournamentEditionSlot> {\n'''
helpers = '''    private fun rulesSnapshotJson(snapshot: TournamentRulesSnapshot): JSONObject = JSONObject().apply {\n        put("title", snapshot.title)\n        put("sourceSummary", snapshot.sourceSummary)\n        put("updatedAtEpochMs", snapshot.updatedAtEpochMs)\n        put("items", JSONArray().apply {\n            snapshot.items.forEach { item ->\n                put(JSONObject().apply {\n                    put("title", item.title)\n                    put("detail", item.detail)\n                    put("source", item.source)\n                    put("verified", item.verified)\n                })\n            }\n        })\n    }\n\n    private fun drawSnapshotJson(snapshot: TournamentDrawSnapshot): JSONObject = JSONObject().apply {\n        put("title", snapshot.title)\n        put("note", snapshot.note)\n        put("sourceSummary", snapshot.sourceSummary)\n        put("updatedAtEpochMs", snapshot.updatedAtEpochMs)\n        put("slots", JSONArray().apply {\n            snapshot.slots.forEach { slot ->\n                put(JSONObject().apply {\n                    put("label", slot.label)\n                    put("left", slot.left)\n                    put("right", slot.right)\n                    put("scheduledAt", slot.scheduledAt)\n                    put("status", slot.status)\n                    put("source", slot.source)\n                    put("verified", slot.verified)\n                    put("bracketMatchId", slot.bracketMatchId)\n                })\n            }\n        })\n    }\n\n    private fun parseRulesSnapshot(obj: JSONObject?): TournamentRulesSnapshot? {\n        if (obj == null) return null\n        val items = buildList {\n            val rows = obj.optJSONArray("items") ?: JSONArray()\n            for (i in 0 until rows.length()) {\n                val row = rows.optJSONObject(i) ?: continue\n                val title = row.optString("title")\n                if (title.isBlank()) continue\n                add(TournamentRuleItem(\n                    title = title,\n                    detail = row.optString("detail"),\n                    source = row.optString("source"),\n                    verified = row.optBoolean("verified", false)\n                ))\n            }\n        }\n        if (items.isEmpty()) return null\n        return TournamentRulesSnapshot(\n            title = obj.optString("title").ifBlank { "赛事规则" },\n            items = items,\n            sourceSummary = obj.optString("sourceSummary"),\n            updatedAtEpochMs = obj.optLong("updatedAtEpochMs", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()\n        )\n    }\n\n    private fun parseDrawSnapshot(obj: JSONObject?): TournamentDrawSnapshot? {\n        if (obj == null) return null\n        val slots = buildList {\n            val rows = obj.optJSONArray("slots") ?: JSONArray()\n            for (i in 0 until rows.length()) {\n                val row = rows.optJSONObject(i) ?: continue\n                val label = row.optString("label")\n                if (label.isBlank()) continue\n                add(TournamentDrawSlot(\n                    label = label,\n                    left = row.optString("left"),\n                    right = row.optString("right"),\n                    scheduledAt = row.optString("scheduledAt"),\n                    status = row.optString("status"),\n                    source = row.optString("source"),\n                    verified = row.optBoolean("verified", false),\n                    bracketMatchId = row.optString("bracketMatchId")\n                ))\n            }\n        }\n        if (slots.isEmpty()) return null\n        return TournamentDrawSnapshot(\n            title = obj.optString("title").ifBlank { "抽签 / 签位" },\n            slots = slots,\n            note = obj.optString("note"),\n            sourceSummary = obj.optString("sourceSummary"),\n            updatedAtEpochMs = obj.optLong("updatedAtEpochMs", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()\n        )\n    }\n\n    private fun parseQualificationSnapshot(obj: JSONObject?): TournamentQualificationArchive? {\n        if (obj == null) return null\n        val detail = obj.optString("detail")\n        val source = obj.optString("source")\n        if (detail.isBlank() || source.isBlank()) return null\n        return TournamentQualificationArchive(\n            detail = detail,\n            source = source,\n            savedAtEpochMs = obj.optLong("savedAtEpochMs", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()\n        )\n    }\n\n'''
p = Path(ARCHIVE)
text = p.read_text(encoding="utf-8")
if text.count(marker) != 1:
    raise SystemExit("TournamentEditionArchive.kt: JSON helper marker not found")
p.write_text(text.replace(marker, helpers + marker, 1), encoding="utf-8")

# ScheduleCenter now consumes archived Patch + governance snapshots. This closes the old split where
# archive cards knew the patch/rules but the Rules/Research page recomputed a thinner current view.
replace_once(
    UI,
    '''                            EventCenterTab.RESEARCH -> ResearchView(\n                                bucket = selectedBucket,\n                                standings = selectedStandings,\n                                onOpenSchedule = { tabIndex = EventCenterTab.SCHEDULE.ordinal }\n                            )\n''',
    '''                            EventCenterTab.RESEARCH -> ResearchView(\n                                bucket = selectedBucket,\n                                standings = selectedStandings,\n                                archivedEdition = selectedArchive,\n                                onOpenSchedule = { tabIndex = EventCenterTab.SCHEDULE.ordinal }\n                            )\n'''
)
replace_once(
    UI,
    '''                            EventCenterTab.RULES -> RulesView(selectedBucket, selectedStandings)\n                            EventCenterTab.DRAW -> DrawView(selectedBucket, selectedStandings)\n''',
    '''                            EventCenterTab.RULES -> RulesView(selectedBucket, selectedStandings, selectedArchive)\n                            EventCenterTab.DRAW -> DrawView(selectedBucket, selectedStandings, selectedArchive)\n'''
)

replace_once(
    UI,
    '''private fun ResearchView(\n    bucket: ScheduleCompetitionBucket,\n    standings: TournamentStandings?,\n    onOpenSchedule: () -> Unit\n) {\n    val governance = remember(bucket.key, standings?.tournamentId, standings?.stages, bucket.matches) {\n        TournamentGovernanceProvider.resolve(bucket.tournament, bucket.title, bucket.matches, standings)\n    }\n    val research = remember(bucket.key, standings?.tournamentId, standings?.stages, bucket.matches, governance) {\n        TournamentResearchProvider.resolve(\n            tournament = bucket.tournament,\n            competitionTitle = bucket.title,\n            matches = bucket.matches,\n            standings = standings,\n            governance = governance\n        )\n    }\n''',
    '''private fun ResearchView(\n    bucket: ScheduleCompetitionBucket,\n    standings: TournamentStandings?,\n    archivedEdition: TournamentEditionArchiveRecord?,\n    onOpenSchedule: () -> Unit\n) {\n    val liveGovernance = remember(bucket.key, standings?.tournamentId, standings?.stages, bucket.matches) {\n        TournamentGovernanceProvider.resolve(bucket.tournament, bucket.title, bucket.matches, standings)\n    }\n    val governance = remember(liveGovernance, archivedEdition?.archivedRules, archivedEdition?.archivedDraw) {\n        TournamentEditionArchiveStore.mergeGovernanceForDisplay(archivedEdition, liveGovernance)\n    }\n    val research = remember(bucket.key, standings?.tournamentId, standings?.stages, bucket.matches, governance, archivedEdition?.patchVersions) {\n        TournamentResearchProvider.resolve(\n            tournament = bucket.tournament,\n            competitionTitle = bucket.title,\n            matches = bucket.matches,\n            standings = standings,\n            governance = governance,\n            verifiedPatchVersions = archivedEdition?.patchVersions.orEmpty()\n        )\n    }\n'''
)

replace_once(
    UI,
    '''private fun RulesView(bucket: ScheduleCompetitionBucket, standings: TournamentStandings?) {\n    val governance = remember(bucket.key, standings?.tournamentId, standings?.stages, bucket.matches) {\n        TournamentGovernanceProvider.resolve(bucket.tournament, bucket.title, bucket.matches, standings)\n    }\n    val snapshot = governance.rules\n''',
    '''private fun RulesView(\n    bucket: ScheduleCompetitionBucket,\n    standings: TournamentStandings?,\n    archivedEdition: TournamentEditionArchiveRecord?\n) {\n    val liveGovernance = remember(bucket.key, standings?.tournamentId, standings?.stages, bucket.matches) {\n        TournamentGovernanceProvider.resolve(bucket.tournament, bucket.title, bucket.matches, standings)\n    }\n    val governance = remember(liveGovernance, archivedEdition?.archivedRules, archivedEdition?.archivedDraw) {\n        TournamentEditionArchiveStore.mergeGovernanceForDisplay(archivedEdition, liveGovernance)\n    }\n    val snapshot = governance.rules\n'''
)

replace_once(
    UI,
    '''private fun DrawView(bucket: ScheduleCompetitionBucket, standings: TournamentStandings?) {\n    val governance = remember(bucket.key, standings?.tournamentId, standings?.stages, bucket.matches) {\n        TournamentGovernanceProvider.resolve(bucket.tournament, bucket.title, bucket.matches, standings)\n    }\n    val snapshot = governance.draw\n''',
    '''private fun DrawView(\n    bucket: ScheduleCompetitionBucket,\n    standings: TournamentStandings?,\n    archivedEdition: TournamentEditionArchiveRecord?\n) {\n    val liveGovernance = remember(bucket.key, standings?.tournamentId, standings?.stages, bucket.matches) {\n        TournamentGovernanceProvider.resolve(bucket.tournament, bucket.title, bucket.matches, standings)\n    }\n    val governance = remember(liveGovernance, archivedEdition?.archivedRules, archivedEdition?.archivedDraw) {\n        TournamentEditionArchiveStore.mergeGovernanceForDisplay(archivedEdition, liveGovernance)\n    }\n    val snapshot = governance.draw\n'''
)

print("dev72 durable edition governance batch14 applied")
