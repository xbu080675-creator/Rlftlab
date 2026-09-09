package com.riftlab.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riftlab.app.data.EsportsTeamRef
import com.riftlab.app.data.ScheduledEsportsMatch

/**
 * Team skin registry.
 *
 * UI code consumes a generic palette; each club skin only supplies tokens and background chrome.
 * Future LCK/LCP/KPL/CS2 skins can therefore be added without forking the page structure.
 */
internal enum class RiftSkinId { DEFAULT, AL }

@Immutable
internal data class RiftSkinPalette(
    val background: Color,
    val panel: Color,
    val panelAlt: Color,
    val line: Color,
    val accent: Color,
    val secondary: Color,
    val danger: Color,
    val text: Color,
    val muted: Color
)

@Immutable
internal data class RiftTeamSkin(
    val id: RiftSkinId,
    val teamCode: String,
    val displayName: String,
    val palette: RiftSkinPalette,
    val glass: Boolean = false
)

internal object RiftTeamSkins {
    val Default = RiftTeamSkin(
        id = RiftSkinId.DEFAULT,
        teamCode = "",
        displayName = "RiftLab",
        palette = RiftSkinPalette(
            background = Color(0xFF090D14),
            panel = Color(0xFF101722),
            panelAlt = Color(0xFF151F2C),
            line = Color(0xFF243449),
            accent = Color(0xFF6CEBFF),
            secondary = Color(0xFF8AF3C9),
            danger = Color(0xFFFF667A),
            text = Color(0xFFF1F5FA),
            muted = Color(0xFF8C98AA)
        )
    )

    /**
     * Anyone's Legend — black / graphite / crimson, with translucent smoked panels.
     * The palette intentionally avoids a slogan/footer badge; the identity comes from atmosphere,
     * logo treatment and motion-like red slashes instead of decorative copy.
     */
    val AL = RiftTeamSkin(
        id = RiftSkinId.AL,
        teamCode = "AL",
        displayName = "Anyone's Legend",
        glass = true,
        palette = RiftSkinPalette(
            background = Color(0xE907090D),
            panel = Color(0xC7131117),
            panelAlt = Color(0xB91B171D),
            line = Color(0x8A7E2A34),
            accent = Color(0xFFFF314C),
            secondary = Color(0xFFFF6A75),
            danger = Color(0xFFFF5268),
            text = Color(0xFFF8F3F4),
            muted = Color(0xFFAA9CA1)
        )
    )

    fun resolve(team: EsportsTeamRef?): RiftTeamSkin = when {
        team == null -> Default
        isAL(team.code) || isAL(team.name) || isAL(team.slug) -> AL
        else -> Default
    }

    fun resolve(match: ScheduledEsportsMatch?): RiftTeamSkin =
        match?.teams?.firstOrNull { team ->
            isAL(team.code) || isAL(team.name) || isAL(team.slug)
        }?.let { AL } ?: Default

    fun isAL(value: String): Boolean {
        val token = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
        return token == "AL" || token == "ANYONESLEGEND" || token == "AGAL"
    }
}

internal val LocalRiftTeamSkin = staticCompositionLocalOf { RiftTeamSkins.Default }

/** Background chrome for the active skin. Panels remain real Compose content above this layer. */
@Composable
internal fun RiftTeamSkinBackdrop(skin: RiftTeamSkin, modifier: Modifier = Modifier) {
    if (skin.id == RiftSkinId.DEFAULT) {
        Box(modifier.fillMaxSize().background(skin.palette.background.copy(alpha = 1f)))
        return
    }

    val red = skin.palette.accent
    Box(
        modifier.fillMaxSize().background(
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFF06070A),
                    Color(0xFF10080C),
                    Color(0xFF07090D),
                    Color(0xFF16070B)
                ),
                start = Offset.Zero,
                end = Offset(1400f, 2100f)
            )
        )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(red.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(size.width * 0.88f, size.height * 0.12f),
                    radius = size.minDimension * 0.82f
                )
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(red.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(size.width * 0.06f, size.height * 0.72f),
                    radius = size.minDimension * 0.74f
                )
            )

            fun slash(y: Float, thickness: Float, alpha: Float, drift: Float) {
                val path = Path().apply {
                    moveTo(-size.width * 0.18f, y)
                    lineTo(size.width * 0.88f, y - size.height * drift)
                    lineTo(size.width * 1.16f, y - size.height * drift + thickness)
                    lineTo(-size.width * 0.08f, y + thickness)
                    close()
                }
                drawPath(path, color = red.copy(alpha = alpha))
            }

            slash(size.height * 0.15f, size.height * 0.020f, 0.12f, 0.11f)
            slash(size.height * 0.31f, size.height * 0.010f, 0.07f, 0.07f)
            slash(size.height * 0.61f, size.height * 0.028f, 0.08f, 0.13f)
            slash(size.height * 0.84f, size.height * 0.012f, 0.10f, 0.08f)

            val hairline = red.copy(alpha = 0.07f)
            repeat(7) { index ->
                val x = size.width * (0.12f + index * 0.15f)
                drawLine(
                    color = hairline,
                    start = Offset(x, 0f),
                    end = Offset(x - size.width * 0.34f, size.height),
                    strokeWidth = 1f
                )
            }
        }

        Text(
            text = "AL",
            color = red.copy(alpha = 0.045f),
            fontSize = 118.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 76.dp, end = 6.dp)
        )
        Text(
            text = "ANYONE'S LEGEND",
            color = skin.palette.text.copy(alpha = 0.055f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.2.sp,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp)
        )
    }
}
