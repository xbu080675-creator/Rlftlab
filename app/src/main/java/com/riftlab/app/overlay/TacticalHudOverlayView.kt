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
 * 全局赛事 X 光层。
 *
 * 这不是第二块直播底板：不重复总击杀、总塔数、总经济等导播已常驻展示的信息。
 * 只放导播未第一时间给、底板没呈现、但会改变观众判断的状态。
 */
class TacticalHudOverlayView(context: Context) : FrameLayout(context) {
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(12))
    }
    private val backgroundShape = GradientDrawable()
    private val accent = View(context)
    private val phase = label("全局态势", 10f, Color.WHITE, true)
    private val sim = label("模拟", 9f, 0xFF9BA8BA.toInt(), true)
    private val clock = label("--:--", 10f, 0xFF9BA8BA.toInt(), true)
    private val match = label("", 9f, 0xFF77869A.toInt(), false)
    private val headline = label("", 15f, Color.WHITE, true)
    private val explanation = label("", 11f, 0xFFDDE5EF.toInt(), false).apply { maxLines = 3 }
    private val evidenceWrap = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val alive = label("", 24f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
    private val objectiveLine = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val objectiveName = label("", 10f, 0xFF9BA8BA.toInt(), true)
    private val objectiveHp = label("", 12f, Color.WHITE, true)
    private val objectiveTrack = FrameLayout(context)
    private val objectiveFill = View(context)
    private val players = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val progress = label("", 9f, 0xFF687589.toInt(), true)

    init {
        elevation = dp(12).toFloat()
        val width = (resources.displayMetrics.widthPixels * 0.92f).roundToInt().coerceAtLeast(dp(320))
        addView(panel, LayoutParams(width, LayoutParams.WRAP_CONTENT))

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
        panel.addView(match)

        panel.addView(accent, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(2)).apply {
            topMargin = dp(6)
            bottomMargin = dp(8)
        })
        panel.addView(headline)
        explanation.setPadding(0, dp(5), 0, 0)
        panel.addView(explanation)

        evidenceWrap.setPadding(0, dp(8), 0, dp(2))
        panel.addView(evidenceWrap)

        alive.setPadding(0, dp(5), 0, dp(4))
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
        phase.text = if (fight) "团战态势" else "全局态势"
        phase.setTextColor(phaseColor)
        clock.text = state.clock
        match.text = state.matchLabel
        headline.text = state.headline
        explanation.text = state.explanation
        accent.setBackgroundColor(phaseColor)
        objectiveFill.setBackgroundColor(phaseColor)
        progress.text = "模拟 ${state.step}/${state.totalSteps}"

        backgroundShape.setColor(if (fight) 0xE817151B.toInt() else 0xE8121821.toInt())
        backgroundShape.cornerRadius = dp(8).toFloat()
        backgroundShape.setStroke(dp(1), if (fight) 0x88FF5E67.toInt() else 0x806CEBFF.toInt())
        background = backgroundShape

        renderEvidence(state.evidence, phaseColor)
        renderPlayers(state.players, phaseColor)

        if (fight) {
            val blue = state.blueAlive
            val red = state.redAlive
            alive.visibility = if (blue != null && red != null) View.VISIBLE else View.GONE
            alive.text = if (blue != null && red != null) "${state.blueTeam}  $blue  :  $red  ${state.redTeam}" else ""
            renderObjective(state)
        } else {
            alive.visibility = View.GONE
            objectiveLine.visibility = View.GONE
            objectiveTrack.visibility = View.GONE
        }

        alpha = 0.72f
        animate().cancel()
        animate().alpha(1f).setDuration(160L).start()
    }

    private fun renderEvidence(rows: List<TacticalHudEvidence>, phaseColor: Int) {
        evidenceWrap.removeAllViews()
        evidenceWrap.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        rows.take(4).forEachIndexed { index, row ->
            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = GradientDrawable().apply {
                    setColor(if (row.emphasis) 0x3329D3FF else 0x221B2430)
                    setStroke(dp(1), if (row.emphasis) phaseColor else 0x445A6878)
                    cornerRadius = dp(6).toFloat()
                }
            }
            chip.addView(label(row.label, 8f, 0xFF8F9CAF.toInt(), true))
            chip.addView(label(row.value, 10f, if (row.emphasis) Color.WHITE else 0xFFDDE4EE.toInt(), true))
            evidenceWrap.addView(chip, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = dp(5)
            })
        }
    }

    private fun renderObjective(state: TacticalHudState) {
        val hp = state.objectiveHp
        val max = state.objectiveMaxHp
        val show = state.objective.isNotBlank() && hp != null
        objectiveLine.visibility = if (show) View.VISIBLE else View.GONE
        objectiveTrack.visibility = if (show && max != null && max > 0) View.VISIBLE else View.GONE
        if (!show) return
        objectiveName.text = state.objective
        objectiveHp.text = if (max != null) "$hp / $max" else hp.toString()
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
        rows.take(3).forEach { row ->
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val identityText = buildString {
                append(row.team)
                append(" · ")
                append(row.player.ifBlank { row.role })
                if (row.player.isNotBlank()) append(" · ${row.role}")
            }
            val identity = label(identityText, 10f, Color.WHITE, true)
            line.addView(identity, LinearLayout.LayoutParams(dp(128), LayoutParams.WRAP_CONTENT))

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
                row.alive == false -> "阵亡"
                row.note.isNotBlank() -> row.note
                row.smiteReady != null -> "惩戒${if (row.smiteReady) "可用" else "不可用"}"
                else -> buildString {
                    row.flashReady?.let { append("闪现${if (it) "可用" else "未转好"}") }
                    row.ultimateReady?.let {
                        if (isNotEmpty()) append(" · ")
                        append("R${if (it) "可用" else "不可用"}")
                    }
                }.ifBlank { "${row.hpPercent ?: 0}%" }
            }
            val stateText = label(status, 9f, if (row.alive == false) 0xFFFF777E.toInt() else 0xFFDDE4EE.toInt(), true)
            stateText.gravity = Gravity.END
            line.addView(stateText, LinearLayout.LayoutParams(dp(96), LayoutParams.WRAP_CONTENT))
            players.addView(line, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun label(value: String, sp: Float, color: Int, bold: Boolean): TextView = TextView(context).apply {
        text = value
        textSize = sp
        setTextColor(color)
        includeFontPadding = false
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        maxLines = 3
    }

    private fun space(): View = View(context)

    private fun solid(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
