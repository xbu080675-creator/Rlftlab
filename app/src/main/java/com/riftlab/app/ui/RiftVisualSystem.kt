package com.riftlab.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * RiftLab Visual System 2.0 primitives.
 *
 * The old UI put almost every block inside the same full red/gray outline. That made scores,
 * diagnostics and secondary metadata compete at the same visual weight. V2 keeps the angular
 * language but uses broadcast-style surfaces: soft layered fills, a short identity rail and only
 * minimal structural lines. Important data gets size/position/color; secondary data gets quieter.
 */
@Composable
internal fun RiftHudPanel(
    accent: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = CutCornerShape(topEnd = 22.dp, bottomStart = 11.dp)
    val accentColor = RiftCyan
    val panel = RiftPanel
    val panelAlt = RiftPanelAlt
    val line = RiftLine
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    panel,
                    panelAlt.copy(alpha = 0.78f),
                    panel.copy(alpha = 0.96f)
                )
            )
        )
        .drawBehind {
            val topRail = minOf(size.width * 0.30f, 118.dp.toPx())
            val sideRail = minOf(size.height * 0.34f, 48.dp.toPx())
            drawLine(
                color = if (accent) accentColor else line.copy(alpha = 0.54f),
                start = Offset.Zero,
                end = Offset(topRail, 0f),
                strokeWidth = if (accent) 3.dp.toPx() else 1.4.dp.toPx()
            )
            drawLine(
                color = accentColor.copy(alpha = if (accent) 0.82f else 0.28f),
                start = Offset.Zero,
                end = Offset(0f, sideRail),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                color = line.copy(alpha = 0.22f),
                start = Offset(size.width * 0.36f, size.height - 1.dp.toPx()),
                end = Offset(size.width, size.height - 1.dp.toPx()),
                strokeWidth = 1.dp.toPx()
            )
        }
    val interactive = if (onClick != null) base.clickable(onClick = onClick) else base
    Column(
        interactive.padding(horizontal = 16.dp, vertical = 15.dp),
        content = content
    )
}

@Composable
internal fun RiftSectionLabel(value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(top = 4.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(3.dp).height(16.dp)
                .background(RiftCyan, CutCornerShape(topEnd = 2.dp, bottomStart = 2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            color = RiftText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.35.sp
        )
    }
}

@Composable
internal fun RiftStatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color? = null
) {
    val resolved = tone ?: RiftCyan
    Text(
        text,
        color = resolved,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.45.sp,
        modifier = modifier
            .background(resolved.copy(alpha = 0.10f), CutCornerShape(topEnd = 7.dp, bottomStart = 5.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    )
}
