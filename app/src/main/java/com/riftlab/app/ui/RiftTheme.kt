package com.riftlab.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val RiftBg = Color(0xFF090D14)
val RiftPanel = Color(0xFF101722)
val RiftPanelAlt = Color(0xFF151F2C)
val RiftLine = Color(0xFF243449)
val RiftCyan = Color(0xFF6CEBFF)
val RiftRed = Color(0xFFFF667A)
val RiftText = Color(0xFFF1F5FA)
val RiftMuted = Color(0xFF8C98AA)

private val RiftScheme = darkColorScheme(
    primary = RiftCyan,
    secondary = Color(0xFF8AF3C9),
    background = RiftBg,
    surface = RiftPanel,
    onPrimary = RiftBg,
    onBackground = RiftText,
    onSurface = RiftText
)

@Composable
fun RiftTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RiftScheme, content = content)
}
