package com.riftlab.app.overlay

import android.content.Context

/**
 * User-owned layout profile for RiftScreen Draft HUD.
 * Positions are normalized screen-center coordinates so a layout survives resolution changes.
 * Landscape and portrait profiles are stored independently.
 */
enum class DraftHudModule(val key: String, val label: String) {
    STATUS("status", "状态条"),
    PROGRESS("progress", "BP 进度"),
    BLUE_PICK("blue_pick", "蓝方 Pick"),
    RED_PICK("red_pick", "红方 Pick"),
    MATCHUP("matchup", "对位关系"),
    WATERMARK("watermark", "模拟标识")
}

data class DraftHudPlacement(
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
    val alpha: Float = 1f,
    val visible: Boolean = true
)

object DraftHudLayoutStore {
    private const val PREFS = "riftlab_draft_hud_layout_v1"

    fun load(context: Context, module: DraftHudModule, landscape: Boolean): DraftHudPlacement {
        val defaults = defaultPlacement(module, landscape)
        val prefix = prefix(module, landscape)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return DraftHudPlacement(
            x = prefs.getFloat("${prefix}_x", defaults.x).coerceIn(0f, 1f),
            y = prefs.getFloat("${prefix}_y", defaults.y).coerceIn(0f, 1f),
            scale = prefs.getFloat("${prefix}_scale", defaults.scale).coerceIn(0.55f, 1.45f),
            alpha = prefs.getFloat("${prefix}_alpha", defaults.alpha).coerceIn(0.30f, 1f),
            visible = prefs.getBoolean("${prefix}_visible", defaults.visible)
        )
    }

    fun save(context: Context, module: DraftHudModule, landscape: Boolean, value: DraftHudPlacement) {
        val prefix = prefix(module, landscape)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("${prefix}_x", value.x.coerceIn(0f, 1f))
            .putFloat("${prefix}_y", value.y.coerceIn(0f, 1f))
            .putFloat("${prefix}_scale", value.scale.coerceIn(0.55f, 1.45f))
            .putFloat("${prefix}_alpha", value.alpha.coerceIn(0.30f, 1f))
            .putBoolean("${prefix}_visible", value.visible)
            .apply()
    }

    fun reset(context: Context, landscape: Boolean) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        DraftHudModule.entries.forEach { module ->
            val prefix = prefix(module, landscape)
            editor.remove("${prefix}_x")
            editor.remove("${prefix}_y")
            editor.remove("${prefix}_scale")
            editor.remove("${prefix}_alpha")
            editor.remove("${prefix}_visible")
        }
        editor.apply()
    }

    private fun prefix(module: DraftHudModule, landscape: Boolean): String =
        "${if (landscape) "land" else "port"}_${module.key}"

    private fun defaultPlacement(module: DraftHudModule, landscape: Boolean): DraftHudPlacement {
        return if (landscape) {
            when (module) {
                DraftHudModule.STATUS -> DraftHudPlacement(0.50f, 0.055f, 0.92f, 0.90f)
                DraftHudModule.PROGRESS -> DraftHudPlacement(0.935f, 0.055f, 0.90f, 0.92f)
                DraftHudModule.BLUE_PICK -> DraftHudPlacement(0.17f, 0.19f, 0.78f, 0.92f)
                DraftHudModule.RED_PICK -> DraftHudPlacement(0.83f, 0.19f, 0.78f, 0.92f)
                DraftHudModule.MATCHUP -> DraftHudPlacement(0.50f, 0.31f, 0.82f, 0.94f)
                DraftHudModule.WATERMARK -> DraftHudPlacement(0.50f, 0.405f, 0.90f, 0.62f)
            }
        } else {
            when (module) {
                DraftHudModule.STATUS -> DraftHudPlacement(0.50f, 0.028f, 0.78f, 0.82f)
                DraftHudModule.PROGRESS -> DraftHudPlacement(0.91f, 0.028f, 0.78f, 0.88f)
                DraftHudModule.BLUE_PICK -> DraftHudPlacement(0.25f, 0.115f, 0.72f, 0.88f)
                DraftHudModule.RED_PICK -> DraftHudPlacement(0.75f, 0.115f, 0.72f, 0.88f)
                DraftHudModule.MATCHUP -> DraftHudPlacement(0.50f, 0.255f, 0.72f, 0.90f)
                DraftHudModule.WATERMARK -> DraftHudPlacement(0.50f, 0.325f, 0.75f, 0.55f, visible = false)
            }
        }
    }
}
