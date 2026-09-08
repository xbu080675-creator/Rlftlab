package com.riftlab.app.ui

import androidx.compose.foundation.background
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

/**
 * Team logo loader backed by the application-wide Coil ImageLoader.
 *
 * Artwork stays remote and is decoded to the actual composable size. The shared loader provides
 * memory + disk cache and SVG support, so dozens of score cards do not each allocate a private
 * loader/cache.
 */
@Composable
internal fun TeamLogo(
    imageUrl: String,
    code: String,
    modifier: Modifier = Modifier
) {
    val resolvedUrl = EsportsAssetCache.normalize(imageUrl)
        .ifBlank { EsportsAssetCache.team(code) }

    val fallback: @Composable () -> Unit = {
        Text(
            text = code.take(4).ifBlank { "—" },
            color = RiftMuted,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
    }

    Box(
        modifier.background(RiftPanelAlt, CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp)),
        contentAlignment = Alignment.Center
    ) {
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