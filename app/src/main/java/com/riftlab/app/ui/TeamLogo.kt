package com.riftlab.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.riftlab.app.data.EsportsAssetCache

/** Team logo loader that supports Riot/OP.GG CDN PNG/WebP/SVG assets and redirects. */
@Composable
internal fun TeamLogo(
    imageUrl: String,
    code: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }

    // Do not remember a blank URL: a provider may populate the shared asset cache later in the same
    // MatchDetail load and the subsequent state update should immediately pick it up.
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
                model = ImageRequest.Builder(context)
                    .data(resolvedUrl)
                    .crossfade(true)
                    .build(),
                imageLoader = imageLoader,
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
