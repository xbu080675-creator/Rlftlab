package com.riftlab.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private val teamLogoCache = ConcurrentHashMap<String, ImageBitmap>()

/** Lightweight image loader for the small set of Riot-hosted team logos used in the event center. */
@Composable
internal fun TeamLogo(
    imageUrl: String,
    code: String,
    modifier: Modifier = Modifier
) {
    val logo = produceState<ImageBitmap?>(initialValue = teamLogoCache[imageUrl], imageUrl) {
        if (imageUrl.isBlank()) {
            value = null
            return@produceState
        }
        teamLogoCache[imageUrl]?.let {
            value = it
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(imageUrl).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 8_000
                    connection.setRequestProperty("User-Agent", "RiftLab-Android/1.0")
                    connection.inputStream.use { input ->
                        BitmapFactory.decodeStream(input)?.asImageBitmap()?.also {
                            teamLogoCache[imageUrl] = it
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }
    }.value

    Box(
        modifier.background(RiftPanelAlt, CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (logo != null) {
            Image(
                bitmap = logo,
                contentDescription = "$code 战队队标",
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = code.take(4).ifBlank { "—" },
                color = RiftMuted,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
