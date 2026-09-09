package com.riftlab.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.riftlab.app.data.MatchDetailRepository
import com.riftlab.app.data.TeamDetailRepository

/**
 * Legacy color names are now skin-aware Compose tokens.
 * Existing screens keep the same API while team skins can swap the whole palette contextually.
 */
val RiftBg: Color
    @Composable get() = LocalRiftTeamSkin.current.palette.background
val RiftPanel: Color
    @Composable get() = LocalRiftTeamSkin.current.palette.panel
val RiftPanelAlt: Color
    @Composable get() = LocalRiftTeamSkin.current.palette.panelAlt
val RiftLine: Color
    @Composable get() = LocalRiftTeamSkin.current.palette.line
val RiftCyan: Color
    @Composable get() = LocalRiftTeamSkin.current.palette.accent
val RiftRed: Color
    @Composable get() = LocalRiftTeamSkin.current.palette.danger
val RiftText: Color
    @Composable get() = LocalRiftTeamSkin.current.palette.text
val RiftMuted: Color
    @Composable get() = LocalRiftTeamSkin.current.palette.muted

@Composable
fun RiftTheme(content: @Composable () -> Unit) {
    val teamState by TeamDetailRepository.state.collectAsState()
    val matchState by MatchDetailRepository.state.collectAsState()

    val teamSkin = RiftTeamSkins.resolve(teamState.team)
    val matchSkin = RiftTeamSkins.resolve(matchState.match)
    // Team-detail context wins over a stale match-detail selection. This keeps the active club skin
    // stable while drilling from a team page into one of its matches.
    val skin = if (teamSkin.id != RiftSkinId.DEFAULT) teamSkin else matchSkin
    val palette = skin.palette

    val scheme = darkColorScheme(
        primary = palette.accent,
        secondary = palette.secondary,
        background = palette.background,
        surface = palette.panel,
        onPrimary = palette.text,
        onSecondary = palette.background,
        onBackground = palette.text,
        onSurface = palette.text,
        onSurfaceVariant = palette.muted
    )

    CompositionLocalProvider(LocalRiftTeamSkin provides skin) {
        MaterialTheme(colorScheme = scheme) {
            Box(Modifier.fillMaxSize()) {
                RiftTeamSkinBackdrop(skin, Modifier.fillMaxSize())
                content()
            }
        }
    }
}
