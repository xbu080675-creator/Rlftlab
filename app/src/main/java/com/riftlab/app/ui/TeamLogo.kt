package com.riftlab.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.riftlab.app.data.EsportsAssetCache

/** Shared team-logo loader with subtle per-club identity framing. */
@Composable
internal fun TeamLogo(
    imageUrl: String,
    code: String,
    modifier: Modifier = Modifier
) {
    val resolvedUrl = EsportsAssetCache.normalize(imageUrl)
        .ifBlank { EsportsAssetCache.team(code) }
    val shape = CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp)
    val skin = RiftTeamSkins.resolveCode(code)
    val dark = LocalRiftDarkMode.current
    val palette = skin.palette(dark)
    val hasClubSkin = skin.id != RiftSkinId.DEFAULT

    val fallback: @Composable () -> Unit = {
        Text(
            text = code.take(4).ifBlank { "—" },
            color = if (hasClubSkin) palette.accent else RiftMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }

    val framed = modifier
        .background(RiftPanelAlt, shape)
        .then(
            if (hasClubSkin) {
                Modifier.border(1.dp, palette.accent.copy(alpha = if (dark) 0.72f else 0.48f), shape)
            } else {
                Modifier
            }
        )

    Box(framed, contentAlignment = Alignment.Center) {
        if (resolvedUrl.isBlank()) {
            fallback()
        } else {
            SubcomposeAsyncImage(
                model = resolvedUrl,
                contentDescription = "$code 战队队标",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                loading = { fallback() },
                error = { fallback() },
                success = { SubcomposeAsyncImageContent() }
            )
        }
    }
}
