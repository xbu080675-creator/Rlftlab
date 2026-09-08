package com.riftlab.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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

/** Team logo loader that supports Riot CDN PNG/WebP/SVG assets and redirects. */
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
        if (imageUrl.isBlank()) {
            fallback()
        } else {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                imageLoader = imageLoader,
                contentDescription = "$code 战队队标",
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit,
                loading = { fallback() },
                error = { fallback() },
                success = { SubcomposeAsyncImageContent() }
            )
        }
    }
}
