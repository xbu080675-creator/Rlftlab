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
 * Reusable team-skin registry.
 *
 * Pages consume generic Rift* tokens. A skin only supplies light/dark palettes and a lightweight
 * backdrop motif, so adding another league or game never requires forking business UI.
 */
internal enum class RiftSkinId {
    DEFAULT,
    AL,
    BLG,
    EDG,
    IG,
    JDG,
    LGD,
    LNG,
    NIP,
    TES,
    TT,
    WBG,
    WE
}

internal enum class RiftSkinMotif {
    SLASH,
    STREAM,
    RINGS,
    MINIMAL,
    CIRCUIT,
    SHARDS,
    WAVE,
    SIGNAL,
    FLAME,
    GRID,
    BROADCAST,
    LEGACY
}

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
    val lightPalette: RiftSkinPalette,
    val darkPalette: RiftSkinPalette,
    val motif: RiftSkinMotif,
    val glass: Boolean = true
) {
    fun palette(dark: Boolean): RiftSkinPalette = if (dark) darkPalette else lightPalette
}

internal object RiftTeamSkins {
    private fun light(
        background: Long,
        panelAlt: Long,
        line: Long,
        accent: Long,
        secondary: Long,
        text: Long = 0xFF15181D,
        muted: Long = 0xFF626B78,
        danger: Long = 0xFFC83D50
    ) = RiftSkinPalette(
        background = Color(background),
        panel = Color(0xEEFFFFFF),
        panelAlt = Color(panelAlt),
        line = Color(line),
        accent = Color(accent),
        secondary = Color(secondary),
        danger = Color(danger),
        text = Color(text),
        muted = Color(muted)
    )

    private fun dark(
        background: Long,
        panel: Long,
        panelAlt: Long,
        line: Long,
        accent: Long,
        secondary: Long,
        text: Long = 0xFFF3F5F8,
        muted: Long = 0xFFA0A8B5,
        danger: Long = 0xFFFF667A
    ) = RiftSkinPalette(
        background = Color(background),
        panel = Color(panel),
        panelAlt = Color(panelAlt),
        line = Color(line),
        accent = Color(accent),
        secondary = Color(secondary),
        danger = Color(danger),
        text = Color(text),
        muted = Color(muted)
    )

    val Default = RiftTeamSkin(
        id = RiftSkinId.DEFAULT,
        teamCode = "",
        displayName = "RiftLab",
        motif = RiftSkinMotif.GRID,
        glass = false,
        lightPalette = light(
            background = 0xFFF4F7FA,
            panelAlt = 0xEAF0F4F8,
            line = 0xFFCAD4DF,
            accent = 0xFF087A91,
            secondary = 0xFF147E69
        ),
        darkPalette = dark(
            background = 0xFF0C1118,
            panel = 0xEE121A24,
            panelAlt = 0xE8182330,
            line = 0xFF2A3A4E,
            accent = 0xFF6CEBFF,
            secondary = 0xFF8AF3C9
        )
    )

    val AL = RiftTeamSkin(
        id = RiftSkinId.AL,
        teamCode = "AL",
        displayName = "Anyone's Legend",
        motif = RiftSkinMotif.SLASH,
        lightPalette = light(0xF5F8F3F4, 0xEAF3E8EA, 0xFFDDB7BE, 0xFFC91F36, 0xFF8E1728),
        darkPalette = dark(0xEE0B0B0F, 0xD0161218, 0xC31E181F, 0xAA87303B, 0xFFFF314C, 0xFFFF6978)
    )

    val BLG = RiftTeamSkin(
        id = RiftSkinId.BLG,
        teamCode = "BLG",
        displayName = "Bilibili Gaming",
        motif = RiftSkinMotif.STREAM,
        lightPalette = light(0xF5F1F8FB, 0xEAE7F5FA, 0xFFB8DCE8, 0xFF008EB8, 0xFF19A7CF),
        darkPalette = dark(0xEE07131A, 0xD00B1B24, 0xC3112630, 0xAA245267, 0xFF30D8FF, 0xFF0E9CC5)
    )

    val EDG = RiftTeamSkin(
        id = RiftSkinId.EDG,
        teamCode = "EDG",
        displayName = "EDward Gaming",
        motif = RiftSkinMotif.RINGS,
        lightPalette = light(0xF5F5F6F7, 0xEAE9ECEF, 0xFFBEC4CB, 0xFF252A31, 0xFFC62232),
        darkPalette = dark(0xEE0D0F12, 0xD016191E, 0xC31D2229, 0xAA4B515A, 0xFFF0F2F5, 0xFFE33B4E)
    )

    val IG = RiftTeamSkin(
        id = RiftSkinId.IG,
        teamCode = "IG",
        displayName = "Invictus Gaming",
        motif = RiftSkinMotif.MINIMAL,
        lightPalette = light(0xF5F7F7F7, 0xEAEDEEEF, 0xFFC9CCD0, 0xFF15171A, 0xFF737982),
        darkPalette = dark(0xEE0A0B0D, 0xD0121417, 0xC31A1D21, 0xAA484C52, 0xFFF5F5F5, 0xFF92979D)
    )

    val JDG = RiftTeamSkin(
        id = RiftSkinId.JDG,
        teamCode = "JDG",
        displayName = "JDG Esports",
        motif = RiftSkinMotif.CIRCUIT,
        lightPalette = light(0xF5FBF4F6, 0xEAF7E9ED, 0xFFE3B7C2, 0xFFC9163A, 0xFF2F3238),
        darkPalette = dark(0xEE15080D, 0xD0210D14, 0xC32C111A, 0xAA753045, 0xFFFF3558, 0xFFB9BDC3)
    )

    val LGD = RiftTeamSkin(
        id = RiftSkinId.LGD,
        teamCode = "LGD",
        displayName = "LGD Gaming",
        motif = RiftSkinMotif.SHARDS,
        lightPalette = light(0xF5F3F7FB, 0xEAE8F1F8, 0xFFBED2E5, 0xFF176CB3, 0xFFD83C47),
        darkPalette = dark(0xEE08111A, 0xD00D1A27, 0xC3132231, 0xAA315474, 0xFF4DA9F4, 0xFFFF5A65)
    )

    val LNG = RiftTeamSkin(
        id = RiftSkinId.LNG,
        teamCode = "LNG",
        displayName = "LNG Esports",
        motif = RiftSkinMotif.WAVE,
        lightPalette = light(0xF5F1F8FA, 0xEAE7F4F7, 0xFFB8DADF, 0xFF007F9D, 0xFFC62C72),
        darkPalette = dark(0xEE061419, 0xD00A2027, 0xC3102A31, 0xAA2C5962, 0xFF28C6E5, 0xFFFF4B9A)
    )

    val NIP = RiftTeamSkin(
        id = RiftSkinId.NIP,
        teamCode = "NIP",
        displayName = "Ninjas in Pyjamas",
        motif = RiftSkinMotif.SIGNAL,
        lightPalette = light(0xF5F6F9EF, 0xEAF0F5E4, 0xFFD1DCB1, 0xFF698800, 0xFF9BCC12),
        darkPalette = dark(0xEE0E1309, 0xD0171E0D, 0xC3202812, 0xAA526329, 0xFFB8FF2C, 0xFF7FC500)
    )

    val TES = RiftTeamSkin(
        id = RiftSkinId.TES,
        teamCode = "TES",
        displayName = "Top Esports",
        motif = RiftSkinMotif.FLAME,
        lightPalette = light(0xF5FBF5F1, 0xEAF7ECE5, 0xFFE4C2B1, 0xFFD83D1F, 0xFFFF713F),
        darkPalette = dark(0xEE170C08, 0xD021120C, 0xC32A1710, 0xAA73402D, 0xFFFF5B35, 0xFFFF9B6D)
    )

    val TT = RiftTeamSkin(
        id = RiftSkinId.TT,
        teamCode = "TT",
        displayName = "ThunderTalk Gaming",
        motif = RiftSkinMotif.GRID,
        lightPalette = light(0xF5F1F8FA, 0xEAE8F4F7, 0xFFB9D8E0, 0xFF0087A9, 0xFF30B8D5),
        darkPalette = dark(0xEE071319, 0xD00B1D25, 0xC3112730, 0xAA2A5361, 0xFF38D5F2, 0xFF1597B8)
    )

    val WBG = RiftTeamSkin(
        id = RiftSkinId.WBG,
        teamCode = "WBG",
        displayName = "Weibo Gaming",
        motif = RiftSkinMotif.BROADCAST,
        lightPalette = light(0xF5FBF4F3, 0xEAF7EAE8, 0xFFE1BCB8, 0xFFD91E2B, 0xFFE99A18),
        darkPalette = dark(0xEE16090A, 0xD0210D10, 0xC32A1215, 0xAA713036, 0xFFFF3442, 0xFFFFB52E)
    )

    val WE = RiftTeamSkin(
        id = RiftSkinId.WE,
        teamCode = "WE",
        displayName = "Team WE",
        motif = RiftSkinMotif.LEGACY,
        lightPalette = light(0xF5FAF4F4, 0xEAF6E9E9, 0xFFE0BABA, 0xFFC9141C, 0xFF3D4249),
        darkPalette = dark(0xEE15090A, 0xD01F0D0F, 0xC3291215, 0xAA703034, 0xFFFF303A, 0xFFC6CBD1)
    )

    val all: List<RiftTeamSkin> = listOf(AL, BLG, EDG, IG, JDG, LGD, LNG, NIP, TES, TT, WBG, WE)

    fun resolve(team: EsportsTeamRef?): RiftTeamSkin = team?.let {
        resolveToken(it.code).takeUnless { skin -> skin.id == RiftSkinId.DEFAULT }
            ?: resolveToken(it.name).takeUnless { skin -> skin.id == RiftSkinId.DEFAULT }
            ?: resolveToken(it.slug)
    } ?: Default

    /** Direct match entry uses the first listed team; team-detail context overrides this in RiftTheme. */
    fun resolve(match: ScheduledEsportsMatch?): RiftTeamSkin =
        resolve(match?.teams?.firstOrNull())

    fun resolveCode(value: String): RiftTeamSkin = resolveToken(value)

    fun accentFor(value: String, dark: Boolean): Color = resolveToken(value).palette(dark).accent

    fun isAL(value: String): Boolean = resolveToken(value).id == RiftSkinId.AL

    private fun resolveToken(value: String): RiftTeamSkin {
        val token = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")
        if (token.isBlank()) return Default
        return when (token) {
            "AL", "ANYONESLEGEND", "AGAL" -> AL
            "BLG", "BILIBILIGAMING" -> BLG
            "EDG", "EDWARDGAMING" -> EDG
            "IG", "INVICTUSGAMING" -> IG
            "JDG", "JDGESPORTS", "BEIJINGJDGESPORTS" -> JDG
            "LGD", "LGDGAMING" -> LGD
            "LNG", "LNGESPORTS", "SUZHOULNGESPORTS" -> LNG
            "NIP", "NINJASINPYJAMAS", "SHENZHENNINJASINPYJAMAS" -> NIP
            "TES", "TOPESPORTS" -> TES
            "TT", "THUNDERTALKGAMING" -> TT
            "WBG", "WEIBOGAMING" -> WBG
            "WE", "TEAMWE", "XIANTEAMWE" -> WE
            else -> Default
        }
    }
}

internal val LocalRiftTeamSkin = staticCompositionLocalOf { RiftTeamSkins.Default }
internal val LocalRiftDarkMode = staticCompositionLocalOf { true }

/**
 * Shared background chrome. Light mode uses white/tinted surfaces and keeps black out of the base;
 * dark mode may use near-black. The club identity survives through accent glows and motif geometry.
 */
@Composable
internal fun RiftTeamSkinBackdrop(
    skin: RiftTeamSkin,
    dark: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = skin.palette(dark)
    val accent = palette.accent
    val secondary = palette.secondary

    if (skin.id == RiftSkinId.DEFAULT) {
        Box(modifier.fillMaxSize().background(palette.background.copy(alpha = 1f)))
        return
    }

    val base = if (dark) {
        listOf(
            palette.background.copy(alpha = 1f),
            palette.panelAlt.copy(alpha = 1f),
            palette.background.copy(alpha = 1f)
        )
    } else {
        listOf(
            palette.background.copy(alpha = 1f),
            Color.White,
            palette.panelAlt.copy(alpha = 1f)
        )
    }

    Box(
        modifier.fillMaxSize().background(
            Brush.linearGradient(
                colors = base,
                start = Offset.Zero,
                end = Offset(1200f, 2100f)
            )
        )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val glowAlpha = if (dark) 0.17f else 0.11f
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = glowAlpha), Color.Transparent),
                    center = Offset(size.width * 0.86f, size.height * 0.14f),
                    radius = size.minDimension * 0.88f
                )
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(secondary.copy(alpha = glowAlpha * 0.72f), Color.Transparent),
                    center = Offset(size.width * 0.08f, size.height * 0.76f),
                    radius = size.minDimension * 0.78f
                )
            )

            val weak = if (dark) 0.09f else 0.065f
            val medium = if (dark) 0.14f else 0.09f

            fun slash(y: Float, thickness: Float, alpha: Float, drift: Float) {
                val path = Path().apply {
                    moveTo(-size.width * 0.18f, y)
                    lineTo(size.width * 0.88f, y - size.height * drift)
                    lineTo(size.width * 1.16f, y - size.height * drift + thickness)
                    lineTo(-size.width * 0.08f, y + thickness)
                    close()
                }
                drawPath(path, color = accent.copy(alpha = alpha))
            }

            when (skin.motif) {
                RiftSkinMotif.SLASH -> {
                    slash(size.height * 0.17f, size.height * 0.022f, medium, 0.11f)
                    slash(size.height * 0.59f, size.height * 0.030f, weak, 0.13f)
                    slash(size.height * 0.84f, size.height * 0.012f, weak, 0.08f)
                }
                RiftSkinMotif.STREAM, RiftSkinMotif.WAVE -> {
                    repeat(7) { index ->
                        val y = size.height * (0.14f + index * 0.11f)
                        drawLine(
                            color = if (index % 2 == 0) accent.copy(alpha = weak) else secondary.copy(alpha = weak),
                            start = Offset(-40f, y),
                            end = Offset(size.width + 40f, y - size.height * (0.05f + index * 0.004f)),
                            strokeWidth = 3f + index
                        )
                    }
                }
                RiftSkinMotif.RINGS -> {
                    repeat(5) { index ->
                        drawCircle(
                            color = accent.copy(alpha = weak / (1f + index * 0.18f)),
                            radius = size.minDimension * (0.18f + index * 0.11f),
                            center = Offset(size.width * 0.78f, size.height * 0.28f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                        )
                    }
                }
                RiftSkinMotif.MINIMAL -> {
                    repeat(5) { index ->
                        val x = size.width * (0.08f + index * 0.23f)
                        drawLine(
                            color = accent.copy(alpha = weak),
                            start = Offset(x, 0f),
                            end = Offset(x - size.width * 0.42f, size.height),
                            strokeWidth = 1.5f
                        )
                    }
                }
                RiftSkinMotif.CIRCUIT, RiftSkinMotif.GRID -> {
                    repeat(7) { index ->
                        val x = size.width * (index / 6f)
                        drawLine(accent.copy(alpha = weak * 0.7f), Offset(x, 0f), Offset(x, size.height), 1f)
                    }
                    repeat(10) { index ->
                        val y = size.height * (index / 9f)
                        drawLine(secondary.copy(alpha = weak * 0.55f), Offset(0f, y), Offset(size.width, y), 1f)
                    }
                }
                RiftSkinMotif.SHARDS, RiftSkinMotif.FLAME -> {
                    slash(size.height * 0.24f, size.height * 0.055f, medium, 0.22f)
                    slash(size.height * 0.52f, size.height * 0.035f, weak, -0.12f)
                    slash(size.height * 0.77f, size.height * 0.060f, weak, 0.18f)
                }
                RiftSkinMotif.SIGNAL, RiftSkinMotif.BROADCAST -> {
                    repeat(6) { index ->
                        val y = size.height * (0.18f + index * 0.12f)
                        drawLine(
                            color = if (index % 2 == 0) accent.copy(alpha = medium) else secondary.copy(alpha = weak),
                            start = Offset(size.width * 0.04f, y),
                            end = Offset(size.width * (0.32f + index * 0.11f).coerceAtMost(0.96f), y),
                            strokeWidth = if (index % 2 == 0) 4f else 2f
                        )
                    }
                }
                RiftSkinMotif.LEGACY -> {
                    repeat(8) { index ->
                        val x = size.width * (0.08f + index * 0.12f)
                        drawRect(
                            color = if (index % 3 == 0) accent.copy(alpha = weak) else secondary.copy(alpha = weak * 0.45f),
                            topLeft = Offset(x, size.height * 0.12f),
                            size = androidx.compose.ui.geometry.Size(size.width * 0.012f, size.height * 0.76f)
                        )
                    }
                }
            }
        }

        Text(
            text = skin.teamCode,
            color = accent.copy(alpha = if (dark) 0.055f else 0.075f),
            fontSize = 112.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 8.dp)
        )
        Text(
            text = skin.displayName.uppercase(),
            color = palette.text.copy(alpha = if (dark) 0.055f else 0.075f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp)
        )
    }
}
