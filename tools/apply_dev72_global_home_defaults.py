from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"missing anchor in {path}: {old[:120]!r}")
    if text.count(old) != 1:
        raise SystemExit(f"non-unique anchor in {path}: {text.count(old)}")
    p.write_text(text.replace(old, new, 1))


# New installs are global by default. Regional subscriptions remain an explicit user filter.
replace_once(
    "app/src/main/java/com/riftlab/app/ui/LeagueSubscriptionUi.kt",
    '''internal val LeagueSubscriptionOptions = listOf(
    LeagueSubscriptionOption("LPL", "LPL"),''',
    '''internal val LeagueSubscriptionOptions = listOf(
    LeagueSubscriptionOption("GLOBAL", "全球赛事"),
    LeagueSubscriptionOption("LPL", "LPL"),'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/ui/LeagueSubscriptionUi.kt",
    '''    private val _subscribed = MutableStateFlow(setOf("LPL"))''',
    '''    private val _subscribed = MutableStateFlow(setOf("GLOBAL"))'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/ui/LeagueSubscriptionUi.kt",
    '''        _subscribed.value = stored.ifEmpty { setOf("LPL") }''',
    '''        _subscribed.value = stored.ifEmpty { setOf("GLOBAL") }'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/ui/LeagueSubscriptionUi.kt",
    '''            Text("赛区订阅", color = RiftText, fontSize = 11.sp, fontWeight = FontWeight.Bold)''',
    '''            Text("赛事订阅", color = RiftText, fontSize = 11.sp, fontWeight = FontWeight.Bold)'''
)

replace_once(
    "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt",
    '''    @Volatile private var subscribedLeagueKeys: Set<String> = setOf("LPL")''',
    '''    @Volatile private var subscribedLeagueKeys: Set<String> = setOf("GLOBAL")'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt",
    '''        val normalized = keys.map(::subscriptionLeagueToken).filter { it.isNotBlank() }.toSet().ifEmpty { setOf("LPL") }''',
    '''        val normalized = keys.map(::subscriptionLeagueToken).filter { it.isNotBlank() }.toSet().ifEmpty { setOf("GLOBAL") }'''
)
replace_once(
    "app/src/main/java/com/riftlab/app/data/MatchSessionStore.kt",
    '''    private fun matchesHomepageSubscription(match: ScheduledEsportsMatch): Boolean {
        val key = canonicalLeagueKey(match)
        return key.isNotBlank() && key in subscribedLeagueKeys
    }''',
    '''    private fun matchesHomepageSubscription(match: ScheduledEsportsMatch): Boolean {
        if ("GLOBAL" in subscribedLeagueKeys) return true
        val key = canonicalLeagueKey(match)
        return key.isNotBlank() && key in subscribedLeagueKeys
    }'''
)

print("dev72 global homepage defaults applied")
