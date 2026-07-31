package com.brachaai.app

sealed interface OverlayAction {

    /** Unknown caller, withheld number, or a contact with nothing worth saying. */
    object DoNothing : OverlayAction

    /** @param asNotification true when the overlay permission is not held. */
    data class Show(val briefing: Briefing, val asNotification: Boolean) : OverlayAction
}

/**
 * Decides what a ringing number should put on screen.
 *
 * Pure by design — no Android imports — so every branch of the ring path is unit-testable.
 * The service around it only performs the decision.
 */
class OverlayDecider(private val store: BriefingStore) {

    fun decide(rawNumber: String?, canDrawOverlays: Boolean): OverlayAction {
        val phoneKey = PhoneNormalizer.key(rawNumber) ?: return OverlayAction.DoNothing

        // A miss is a complete answer: unknown callers show nothing, so no network is needed.
        val briefing = store.lookup(phoneKey) ?: return OverlayAction.DoNothing

        // A known contact with no summary and no open tasks would render an empty card.
        if (briefing.lastCallSummary.isNullOrBlank() && briefing.openTasks.isEmpty()) {
            return OverlayAction.DoNothing
        }

        return OverlayAction.Show(briefing, asNotification = !canDrawOverlays)
    }
}
