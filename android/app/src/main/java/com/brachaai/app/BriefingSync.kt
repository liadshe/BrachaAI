package com.brachaai.app

import android.util.Log

/**
 * Refreshes the local snapshot from the backend.
 *
 * A failure is logged and leaves the previous snapshot in place — a stale card beats no card
 * when the phone is ringing. There is no retry here; the next trigger picks it up.
 */
class BriefingSync(
    private val client: BriefingClient,
    private val store: BriefingStore,
) {

    /**
     * @return true if the snapshot was refreshed.
     *
     * Synchronized because [CallMonitorService] can call this from three independent
     * triggers — the periodic tick, the post-upload sync, and the foreground-resume
     * request — all launched onto the same IO dispatcher with nothing else serializing
     * them. [BriefingStore.replaceAll] writes through a single fixed temp file path, so
     * two concurrent callers would race on that file (a lost move, or a staler payload
     * clobbering a fresher one). One lock around the whole read-then-write turns "three
     * concurrent syncs" into "three syncs, one at a time" — simplest fix that doesn't
     * touch the store or add a dependency.
     */
    @Synchronized
    fun syncNow(): Boolean {
        // Null means the fetch failed. An empty list is a real answer — the user deleted
        // their last contact — and must clear the snapshot rather than preserve it.
        val briefings = client.fetchAll()
        if (briefings == null) {
            Log.w(TAG, "Briefing sync failed; keeping the previous snapshot")
            return false
        }

        store.replaceAll(briefings)
        Log.d(TAG, "Briefing snapshot refreshed (${briefings.size} contacts)")
        return true
    }

    companion object {
        private const val TAG = "BriefingSync"
    }
}
