package com.riftlab.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.riftlab.app.data.MatchSessionStore

internal data class LeagueSubscriptionOption(
    val key: String,
    val label: String
)

internal val LeagueSubscriptionOptions = listOf(
    LeagueSubscriptionOption("LPL", "LPL"),
    LeagueSubscriptionOption("LCK", "LCK"),
    LeagueSubscriptionOption("LEC", "LEC"),
    LeagueSubscriptionOption("LCS", "LCS"),
    LeagueSubscriptionOption("LCP", "LCP"),
    LeagueSubscriptionOption("PCS", "PCS"),
    LeagueSubscriptionOption("VCS", "VCS"),
    LeagueSubscriptionOption("LJL", "LJL"),
    LeagueSubscriptionOption("CBLOL", "CBLOL"),
    LeagueSubscriptionOption("FLS", "FLS"),
    LeagueSubscriptionOption("LCKCL", "LCK CL"),
    LeagueSubscriptionOption("LCPWILDCARD", "LCP Wild Card"),
    LeagueSubscriptionOption("LDL", "LDL"),
    LeagueSubscriptionOption("NLC", "NLC"),
    LeagueSubscriptionOption("LIT", "LIT"),
    LeagueSubscriptionOption("TCL", "TCL")
)

internal fun leagueSubscriptionKey(value: String): String =
    value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

internal object LeagueSubscriptionStore {
    private const val PREFS = "riftlab_league_subscriptions"
    private const val KEY = "subscribed"
    private val _subscribed = MutableStateFlow(setOf("LPL"))
    val subscribed: StateFlow<Set<String>> = _subscribed.asStateFlow()
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(KEY, null)
            ?.map(::leagueSubscriptionKey)
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
        _subscribed.value = stored.ifEmpty { setOf("LPL") }
        loaded = true
    }

    fun toggle(context: Context, key: String) {
        ensureLoaded(context)
        val normalized = leagueSubscriptionKey(key)
        val next = _subscribed.value.toMutableSet()
        if (normalized in next) {
            if (next.size == 1) return
            next.remove(normalized)
        } else {
            next.add(normalized)
        }
        _subscribed.value = next
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY, next)
            .apply()
    }
}

@Composable
internal fun LeagueSubscriptionBar() {
    val context = LocalContext.current
    LaunchedEffect(context) { LeagueSubscriptionStore.ensureLoaded(context) }
    val subscribed by LeagueSubscriptionStore.subscribed.collectAsState()
    LaunchedEffect(subscribed) { MatchSessionStore.updateLeagueSubscriptions(subscribed) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("赛区订阅", color = RiftText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("常驻首页 · 至少保留 1 个", color = RiftMuted, fontSize = 8.sp)
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(LeagueSubscriptionOptions, key = { it.key }) { option ->
                val selected = option.key in subscribed
                val shape = CutCornerShape(topEnd = 8.dp, bottomStart = 6.dp)
                Text(
                    if (selected) "★ ${option.label}" else option.label,
                    color = if (selected) RiftCyan else RiftMuted,
                    fontSize = 9.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier
                        .clickable { LeagueSubscriptionStore.toggle(context, option.key) }
                        .background(if (selected) RiftPanel else RiftPanelAlt, shape)
                        .border(1.dp, if (selected) RiftCyan.copy(alpha = 0.45f) else RiftLine, shape)
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
        }
    }
}
