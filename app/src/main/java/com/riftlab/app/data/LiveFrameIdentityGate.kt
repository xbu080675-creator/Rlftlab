package com.riftlab.app.data

/**
 * Global fail-closed identity gate for every realtime provider.
 *
 * A provider frame is not trusted merely because it is fresh or numerically plausible. Before it
 * can reach the live UI or lifecycle archive it must still belong to the currently selected
 * schedule series. When identity is ambiguous we prefer a short data gap over cross-event data.
 */
internal object LiveFrameIdentityGate {
    data class Verdict(
        val allowed: Boolean,
        val reason: String
    )

    fun validate(
        snapshot: LiveSnapshot,
        target: ScheduledEsportsMatch?,
        status: LiveSourceStatus? = null
    ): Verdict {
        target ?: return Verdict(false, "target_missing")

        val expectedKey = LiveMatchTargetRegistry.key(target)
        if (expectedKey.isBlank()) return Verdict(false, "target_key_missing")

        val frameKey = snapshot.targetKey.trim()
        if (frameKey.isNotBlank() && frameKey != expectedKey) {
            return Verdict(false, "target_key_mismatch")
        }

        if (!LiveMatchTargetRegistry.snapshotBelongsTo(snapshot, target)) {
            return Verdict(false, "team_identity_mismatch")
        }

        if (snapshot.game <= 0) return Verdict(false, "game_number_invalid")
        if (target.bestOf > 0 && snapshot.game > target.bestOf) {
            return Verdict(false, "game_number_out_of_series")
        }

        val providerEvent = status?.eventId?.trim().orEmpty()
        if (providerEvent.isNotBlank()) {
            val acceptedEventIds = setOf(target.eventId.trim(), target.matchId.trim())
                .filter { it.isNotBlank() }
                .toSet()
            if (acceptedEventIds.isNotEmpty() && providerEvent !in acceptedEventIds) {
                return Verdict(false, "event_identity_mismatch")
            }
        }

        return Verdict(true, "identity_verified")
    }

    fun diagnostic(verdict: Verdict): String =
        if (verdict.allowed) "IDENTITY VERIFIED" else "FRAME REJECTED · EVENT IDENTITY MISMATCH · ${verdict.reason}"
}
