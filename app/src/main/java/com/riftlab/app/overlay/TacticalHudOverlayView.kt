package com.riftlab.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Broadcast-safe tactical overlay: it intentionally avoids score, total kills, towers and full item
 * rows. The demo renders only information that would justify covering part of the match picture.
 */
class TacticalHudOverlayView(context: Context) : FrameLayout(context) {
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(9), dp(12), dp(10))
    }
    private val backgroundShape = GradientDrawable()
    private val accent = View(context)
    private val phase = label("GLOBAL", 10f, Color.WHITE, true)
    private val sim = label("SIM", 9f, 0xFF9BA8BA.toInt(), true)
    private val clock = label("--:--", 10f, 0xFF9BA8BA.toInt(), true)
    private val headline = label("", 14f, Color.WHITE, true)
    private val alive = label("", 27f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
    private val primary = label("", 11f, 0xFFE9EDF4.toInt(), true)
    private val secondary = label("", 10f, 0xFF9BA8BA.toInt(), true)
    private val objectiveLine = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val objectiveName = label("", 10f, 0xFF9BA8BA.toInt(), true)
    private val objectiveHp = label("", 13f, Color.WHITE, true)
    private val objectiveTrack = FrameLayout(context)
    private val objectiveFill = View(context)
    private val players = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val progress = label("", 9f, 0xFF687589.toInt(), true)

    init {
        elevation = dp(12).toFloat()
        addView(panel, LayoutParams(dp(286), LayoutParams.WRAP_CONTENT))

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(phase)
        sim.setPadding(dp(7), 0, 0, 0)
        top.addView(sim)
        top.addView(space(), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(clock)
        panel.addView(top)

        panel.addView(accent, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(2)).apply {
            topMargin = dp(6)
            bottomMargin = dp(8)
        })
        panel.addView(headline)
        alive.setPadding(0, dp(4), 0, dp(3))
        panel.addView(alive)

        objectiveLine.addView(objectiveName, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        objectiveLine.addView(objectiveHp)
        panel.addView(objectiveLine)

        objectiveTrack.background = solid(0xFF293241.toInt(), 2)
        objectiveFill.background = solid(0xFF6CEBFF.toInt(), 2)
        objectiveTrack.addView(objectiveFill, LayoutParams(0, LayoutParams.MATCH_PARENT))
        panel.addView(objectiveTrack, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(5)).apply {
            topMargin = dp(5)
            bottomMargin = dp(7)
        })

        panel.addView(players)
        primary.setPadding(0, dp(6), 0, 0)
        secondary.setPadding(0, dp(4), 0, 0)
        panel.addView(primary)
        panel.addView(secondary)
        progress.gravity = Gravity.END
        progress.setPadding(0, dp(5), 0, 0)
        panel.addView(progress)

        render(TacticalHudState())
    }

    fun render(state: TacticalHudState) {
        visibility = if (state.active) View.VISIBLE else View.GONE
        if (!state.active) return

        val fight = state.phase == TacticalHudPhase.FIGHT
        val phaseColor = if (fight) 0xFFFF5E67.toInt() else 0xFF6CEBFF.toInt()
        phase.text = if (fight) "RIFT // FIGHT" else "RIFT // GLOBAL"
        phase.setTextColor(phaseColor)
        clock.text = state.clock
        headline.text = state.headline
        headline.setTextColor(if (fight) Color.WHITE else 0xFFF0F4FA.toInt())
        accent.setBackgroundColor(phaseColor)
        objectiveFill.setBackgroundColor(phaseColor)
        progress.text = "${state.step}/${state.totalSteps}"

        backgroundShape.setColor(if (fight) 0xE817151B.toInt() else 0xE8121821.toInt())
        backgroundShape.cornerRadius = dp(5).toFloat()
        backgroundShape.setStroke(dp(1), if (fight) 0x88FF5E67.toInt() else 0x806CEBFF.toInt())
        background = backgroundShape

        if (fight) {
            val blue = state.blueAlive
            val red = state.redAlive
            alive.visibility = if (blue != null && red != null) View.VISIBLE else View.GONE
            alive.text = if (blue != null && red != null) "$blue  v  $red" else ""
            primary.visibility = View.GONE
            secondary.visibility = View.GONE
            renderObjective(state)
            renderPlayers(state.players, phaseColor)
        } else {
            alive.visibility = View.GONE
            objectiveLine.visibility = View.GONE
            objectiveTrack.visibility = View.GONE
            players.visibility = View.GONE
            primary.visibility = if (state.primary.isBlank()) View.GONE else View.VISIBLE
            secondary.visibility = if (state.secondary.isBlank()) View.GONE else View.VISIBLE
            primary.text = state.primary
            secondary.text = state.secondary
        }

        alpha = 0.64f
        animate().cancel()
        animate().alpha(1f).setDuration(150L).start()
    }

    private fun renderObjective(state: TacticalHudState) {
        val hp = state.objectiveHp
        val max = state.objectiveMaxHp
        val show = state.objective.isNotBlank() && hp != null
        objectiveLine.visibility = if (show) View.VISIBLE else View.GONE
        objectiveTrack.visibility = if (show && max != null && max > 0) View.VISIBLE else View.GONE
        if (!show) return
        objectiveName.text = state.objective
        objectiveHp.text = hp.toString()
        if (max != null && max > 0) {
            objectiveTrack.post {
                val ratio = (hp.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                objectiveFill.layoutParams = LayoutParams(
                    (objectiveTrack.width * ratio).roundToInt().coerceAtLeast(dp(2)),
                    LayoutParams.MATCH_PARENT
                )
            }
        }
    }

    private fun renderPlayers(rows: List<TacticalHudPlayer>, phaseColor: Int) {
        players.removeAllViews()
        players.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        rows.take(2).forEach { row ->
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            val identity = label("${row.team} ${row.role}", 10f, Color.WHITE, true)
            line.addView(identity, LinearLayout.LayoutParams(dp(78), LayoutParams.WRAP_CONTENT))

            val hpTrack = FrameLayout(context).apply { background = solid(0xFF2A303B.toInt(), 2) }
            val hpFill = View(context).apply {
                setBackgroundColor(
                    when {
                        row.alive == false -> 0xFF5B6069.toInt()
                        (row.hpPercent ?: 100) <= 30 -> 0xFFFF5E67.toInt()
                        else -> phaseColor
                    }
                )
            }
            hpTrack.addView(hpFill, LayoutParams(0, LayoutParams.MATCH_PARENT))
            line.addView(hpTrack, LinearLayout.LayoutParams(0, dp(5), 1f).apply {
                marginStart = dp(5)
                marginEnd = dp(8)
            })
            hpTrack.post {
                val pct = if (row.alive == false) 0 else row.hpPercent ?: 100
                hpFill.layoutParams = LayoutParams(
                    (hpTrack.width * (pct.coerceIn(0, 100) / 100f)).roundToInt(),
                    LayoutParams.MATCH_PARENT
                )
            }

            val status = when {
                row.alive == false -> "DEAD"
                row.smiteReady != null -> "S ${if (row.smiteReady) "●" else "×"}"
                else -> buildString {
                    row.flashReady?.let { append("F ${if (it) "●" else "×"}") }
                    row.ultimateReady?.let {
                        if (isNotEmpty()) append("  ")
                        append("R ${if (it) "●" else "×"}")
                    }
                }.ifBlank { "${row.hpPercent ?: 0}%" }
            }
            val stateText = label(status, 9f, if (row.alive == false) 0xFFFF777E.toInt() else 0xFFDDE4EE.toInt(), true)
            stateText.gravity = Gravity.END
            line.addView(stateText, LinearLayout.LayoutParams(dp(70), LayoutParams.WRAP_CONTENT))
            players.addView(line, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun label(value: String, sp: Float, color: Int, bold: Boolean): TextView = TextView(context).apply {
        text = value
        textSize = sp
        setTextColor(color)
        includeFontPadding = false
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        maxLines = 1
    }

    private fun space(): View = View(context)

    private fun solid(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
