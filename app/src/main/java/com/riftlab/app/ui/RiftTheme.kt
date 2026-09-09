package com.riftlab.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.riftlab.app.data.MatchDetailRepository
import com.riftlab.app.data.TeamDetailRepository

/** Skin-aware tokens shared by the existing Compose screens. */
val RiftBg: Color
    @Composable get() = LocalRiftTeamSkin.current.palette(LocalRiftDarkMode.current).background
val RiftPanel: Color
    @Composable get() = LocalRiftTeamSkin.current.palette(LocalRiftDarkMode.current).panel
val RiftPanelAlt: Color
    @Composable get() = LocalRiftTeamSkin.current.palette(LocalRiftDarkMode.current).panelAlt
val RiftLine: Color
    @Composable get() = LocalRiftTeamSkin.current.palette(LocalRiftDarkMode.current).line
val RiftCyan: Color
    @Composable get() = LocalRiftTeamSkin.current.palette(LocalRiftDarkMode.current).accent
val RiftRed: Color
    @Composable get() = LocalRiftTeamSkin.current.palette(LocalRiftDarkMode.current).danger
val RiftText: Color
    @Composable get() = LocalRiftTeamSkin.current.palette(LocalRiftDarkMode.current).text
val RiftMuted: Color
    @Composable get() = LocalRiftTeamSkin.current.palette(LocalRiftDarkMode.current).muted

@Composable
fun RiftTheme(content: @Composable () -> Unit) {
    val teamState by TeamDetailRepository.state.collectAsState()
    val matchState by MatchDetailRepository.state.collectAsState()
    val dark = isSystemInDarkTheme()

    val teamSkin = RiftTeamSkins.resolve(teamState.team)
    val matchSkin = RiftTeamSkins.resolve(matchState.match)
    // Team-detail context wins over a match selection. Direct match entry uses the first listed team.
    val skin = if (teamSkin.id != RiftSkinId.DEFAULT) teamSkin else matchSkin
    val palette = skin.palette(dark)

    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.accent,
            secondary = palette.secondary,
            background = palette.background,
            surface = palette.panel,
            surfaceVariant = palette.panelAlt,
            outline = palette.line,
            error = palette.danger,
            onPrimary = palette.background,
            onSecondary = palette.background,
            onBackground = palette.text,
            onSurface = palette.text,
            onSurfaceVariant = palette.muted
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            secondary = palette.secondary,
            background = palette.background,
            surface = palette.panel,
            surfaceVariant = palette.panelAlt,
            outline = palette.line,
            error = palette.danger,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = palette.text,
            onSurface = palette.text,
            onSurfaceVariant = palette.muted
        )
    }

    CompositionLocalProvider(
        LocalRiftTeamSkin provides skin,
        LocalRiftDarkMode provides dark
    ) {
        MaterialTheme(colorScheme = scheme) {
            Box(Modifier.fillMaxSize()) {
                RiftTeamSkinBackdrop(skin, dark, Modifier.fillMaxSize())
                content()
            }
        }
    }
}
