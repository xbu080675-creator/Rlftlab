package com.riftlab.app.overlay

/**
 * One local-only end-to-end fixture for phone testing.
 *
 * It intentionally never touches MatchSessionStore, provider buses, timeline stores or archives.
 * The fixture runs the same visual lifecycle a viewer would see:
 * BP -> Global -> Fight -> Global.
 */
object FullMatchSimulation {
    fun startAuto() {
        TacticalHudSimulation.stop()
        DraftHudSimulation.startAuto(handoffToTactical = true)
    }

    fun startManual() {
        TacticalHudSimulation.stop()
        DraftHudSimulation.startManual(handoffToTactical = true)
    }

    fun next() {
        when {
            DraftHudSimulation.state.value.active -> DraftHudSimulation.next()
            TacticalHudSimulation.state.value.active -> TacticalHudSimulation.next()
            DraftHudSimulation.state.value.finished -> TacticalHudSimulation.startManual()
            else -> startManual()
        }
    }

    fun stop() {
        DraftHudSimulation.stop(stopTactical = false)
        TacticalHudSimulation.stop()
    }
}
