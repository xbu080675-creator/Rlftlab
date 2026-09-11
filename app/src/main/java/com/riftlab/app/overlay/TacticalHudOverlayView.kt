package com.riftlab.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 全局赛事 X 光层。
 *
 * “全局”指整块画面都是信息坐标系，不是铺一张覆盖比赛的大面板。
 * HUD 只在边缘放置镜头外的重要状态，主动避让直播中心与底部赛事板。
 */
class TacticalHudOverlayView(context: Context) : FrameLayout(context) {
    private val stageWidth = (resources.displayMetrics.widthPixels * 0.92f).roundToInt()
    private val stageHeight = (resources.displayMetrics.heightPixels * 0.52f).roundToInt()

    private val stage = FrameLayout(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
    }

    private val eventCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(9))
    }
    private val phase = label("全局态势", 9f, 0xFF6CEBFF.toInt(), true)
    private val clock = label("--:--", 9f, 0xFFB2BECD.toInt(), true)
    private val match = label("", 8f, 0xFF8290A2.toInt(), false).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val headline = label("", 13f, Color.WHITE, true).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val explanation = label("", 9.5f, 0xFFD8E0EA.toInt(), false).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val eventAccent = View(context)

    private val evidenceColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
    }

    private val playersColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private val objectiveCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(9), dp(7), dp(9), dp(8))
        visibility = View.GONE
    }
    private val objectiveTop = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val objectiveName = label("", 9f, 0xFF9BA8BA.toInt(), true)
    private val objectiveHp = label("", 10f, Color.WHITE, true)
    private val objectiveTrack = FrameLayout(context)
    private val objectiveFill = View(context)

    private val simBadge = label("赛事模拟", 8f, 0xFF95A3B5.toInt(), true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(7), dp(3), dp(7), dp(3))
        background = rounded(0x9911171F.toInt(), 0x334A5A6B, 5)
    }
    private val progress = label("", 8f, 0xFF718095.toInt(), true)

    init {
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        addView(stage, LayoutParams(stageWidth, stageHeight))

        val topLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(phase)
            addView(space(), LinearLayout.LayoutParams(0, 1, 1f))
            addView(clock)
        }
        eventCard.addView(topLine)
        eventCard.addView(match)
        eventCard.addView(eventAccent, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(2)).apply {
            topMargin = dp(5)
            bottomMargin = dp(6)
        })
        eventCard.addView(headline)
        explanation.setPadding(0, dp(4), 0, 0)
        eventCard.addView(explanation)

        stage.addView(eventCard)
        stage.addView(evidenceColumn)
        stage.addView(playersColumn)

        objectiveTop.addView(objectiveName, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        objectiveTop.addView(objectiveHp)
        objectiveCard.addView(objectiveTop)
        objectiveTrack.background = rounded(0xAA27303B.toInt(), 0x0027303B, 2)
        objectiveTrack.addView(objectiveFill, LayoutParams(0, LayoutParams.MATCH_PARENT))
        objectiveCard.addView(objectiveTrack, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(4)).apply {
            topMargin = dp(5)
        })
        stage.addView(objectiveCard)

        stage.addView(simBadge)
        stage.addView(progress)

        post { placeModules() }
        render(TacticalHudState())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { placeModules() }
    }

    fun render(state: TacticalHudState) {
        visibility = if (state.active) View.VISIBLE else View.GONE
        if (!state.active) return

        val fight = state.phase == TacticalHudPhase.FIGHT
        val accent = if (fight) 0xFFFF6470.toInt() else 0xFF65E6FF.toInt()
        phase.text = if (fight) "团战态势" else "全局态势"
        phase.setTextColor(accent)
        clock.text = state.clock
        match.text = state.matchLabel
        headline.text = state.headline
        explanation.text = state.explanation
        eventAccent.setBackgroundColor(accent)
        eventCard.background = rounded(
            if (fight) 0xB81A1217.toInt() else 0xB5121821.toInt(),
            if (fight) 0x66FF6470 else 0x5A65E6FF,
            7
        )
        progress.text = "${state.step}/${state.totalSteps}"

        renderEvidence(state.evidence, accent)
        renderPlayers(state.players, accent)
        renderObjective(state, accent)

        alpha = 0.76f
        animate().cancel()
        animate().alpha(1f).setDuration(130L).start()
    }

    private fun placeModules() {
        val w = stageWidth
        val h = stageHeight

        eventCard.layoutParams = LayoutParams((w * 0.35f).roundToInt(), LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(8)
            topMargin = (h * 0.13f).roundToInt()
        }

        evidenceColumn.layoutParams = LayoutParams((w * 0.24f).roundToInt(), LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = dp(8)
            topMargin = (h * 0.15f).roundToInt()
        }

        playersColumn.layoutParams = LayoutParams((w * 0.31f).roundToInt(), LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(8)
            topMargin = (h * 0.55f).roundToInt()
        }

        objectiveCard.layoutParams = LayoutParams((w * 0.22f).roundToInt(), LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = dp(8)
            topMargin = (h * 0.58f).roundToInt()
        }

        simBadge.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(8)
            topMargin = dp(5)
        }

        progress.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = dp(8)
            topMargin = dp(8)
        }
    }

    private fun renderEvidence(rows: List<TacticalHudEvidence>, accent: Int) {
        evidenceColumn.removeAllViews()
        evidenceColumn.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        rows.take(4).forEachIndexed { index, row ->
            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = rounded(
                    if (row.emphasis) 0xB51B2530.toInt() else 0xA5121820.toInt(),
                    if (row.emphasis) accent else 0x3E667486,
                    6
                )
            }
            val name = label(row.label, 8f, 0xFFA8B4C3.toInt(), true)
            val value = label(row.value, 9f, if (row.emphasis) Color.WHITE else 0xFFE0E6ED.toInt(), true).apply {
                gravity = Gravity.END
            }
            chip.addView(name, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            chip.addView(value)
            evidenceColumn.addView(chip, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = dp(4)
            })
        }
    }

    private fun renderPlayers(rows: List<TacticalHudPlayer>, accent: Int) {
        playersColumn.removeAllViews()
        playersColumn.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE

        rows.take(3).forEachIndexed { index, row ->
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(5), dp(8), dp(6))
                background = rounded(0xA9121820.toInt(), 0x3E667486, 6)
            }

            val head = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val identity = buildString {
                append(row.team)
                append(" · ")
                append(row.player.ifBlank { row.role })
                if (row.player.isNotBlank()) append(" · ${row.role}")
            }
            head.addView(label(identity, 8.5f, Color.WHITE, true), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            head.addView(label(playerStatus(row), 8f, if (row.alive == false) 0xFFFF777E.toInt() else 0xFFDDE5EE.toInt(), true))
            line.addView(head)

            row.hpPercent?.let { hp ->
                val hpTrack = FrameLayout(context).apply { background = rounded(0xAA29313C.toInt(), 0x0029313C, 2) }
                val hpFill = View(context).apply {
                    setBackgroundColor(
                        when {
                            row.alive == false -> 0xFF626873.toInt()
                            hp <= 30 -> 0xFFFF6470.toInt()
                            else -> accent
                        }
                    )
                }
                hpTrack.addView(hpFill, LayoutParams(0, LayoutParams.MATCH_PARENT))
                line.addView(hpTrack, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(4) })
                hpTrack.post {
                    val pct = if (row.alive == false) 0 else hp.coerceIn(0, 100)
                    hpFill.layoutParams = LayoutParams((hpTrack.width * pct / 100f).roundToInt(), LayoutParams.MATCH_PARENT)
                }
            }

            playersColumn.addView(line, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = dp(4)
            })
        }
    }

    private fun renderObjective(state: TacticalHudState, accent: Int) {
        val hp = state.objectiveHp
        val max = state.objectiveMaxHp
        val show = state.objective.isNotBlank() && hp != null
        objectiveCard.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        objectiveCard.background = rounded(0xB5121820.toInt(), 0x48667686, 6)
        objectiveName.text = state.objective
        objectiveHp.text = if (max != null) "$hp / $max" else hp.toString()
        objectiveFill.setBackgroundColor(accent)

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

    private fun playerStatus(row: TacticalHudPlayer): String = when {
        row.alive == false -> "阵亡"
        row.note.isNotBlank() -> row.note
        row.smiteReady != null -> "惩戒${if (row.smiteReady) "可用" else "不可用"}"
        else -> buildString {
            row.flashReady?.let { append("闪现${if (it) "可用" else "未转好"}") }
            row.ultimateReady?.let {
                if (isNotEmpty()) append(" · ")
                append("R${if (it) "可用" else "不可用"}")
            }
        }.ifBlank { row.hpPercent?.let { "$it%" } ?: "状态未知" }
    }

    private fun label(value: String, sp: Float, color: Int, bold: Boolean): TextView = TextView(context).apply {
        text = value
        textSize = sp
        setTextColor(color)
        includeFontPadding = false
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        if ((stroke ushr 24) != 0) setStroke(dp(1), stroke)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun space(): View = View(context)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
