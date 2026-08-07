package com.brachaai.app

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What the pipeline knows about one recording.
 *
 * The absence of an entry means "never attempted", so a recording that processes cleanly
 * on the first try under delete-after-processing costs no index write at all.
 */
data class RecordingState(
    val attempts: Int = 0,
    /** The call reached the backend, or was deliberately skipped. Never processed again. */
    val done: Boolean = false,
    /** Gave up. The recording is kept indefinitely but must never be retried. */
    val stuck: Boolean = false,
    val lastError: String? = null
)

/**
 * Durable record of which recordings are finished, so a sweep can tell a call that still
 * needs transcribing from one that is already handled.
 *
 * Deliberately dumb: it stores states and nothing else. The retry policy — how many
 * attempts are allowed, what counts as giving up — lives in [PendingAudioQueue], so that
 * policy can be tested without touching the disk and this can be tested without a policy.
 *
 * A single JSON snapshot written temp-then-move, matching `BriefingStore.replaceAll`: a
 * failed or half-finished write leaves the previous snapshot exactly as it was.
 *
 * Every failure mode here degrades to "nothing is known", which is the safe direction. The
 * cost of forgetting is re-transcribing a call; the cost of wrongly remembering would be
 * deleting a recording that never landed.
 */
class RecordingIndex(private val file: File) {

    private val states: MutableMap<String, RecordingState> = load()

    fun stateOf(name: String): RecordingState = synchronized(indexLock) {
        states[name] ?: RecordingState()
    }

    fun allNames(): Set<String> = synchronized(indexLock) { states.keys.toSet() }

    fun put(name: String, state: RecordingState) {
        synchronized(indexLock) {
            states[name] = state
            persist()
        }
    }

    /**
     * Drops every entry whose recording is no longer on disk, so the index cannot outgrow
     * the watch directory. Covers both the ordinary case (delete-after-processing removed
     * the file) and the user clearing the folder by hand.
     *
     * Writes nothing when there is nothing to drop — a sweep over an unchanged folder is
     * the common case and should not touch storage.
     */
    fun pruneTo(existingNames: Set<String>) {
        synchronized(indexLock) {
            val gone = states.keys.filterNot { it in existingNames }
            if (gone.isEmpty()) return
            gone.forEach { states.remove(it) }
            Log.d(TAG, "Pruned ${gone.size} index entr(ies) whose recording is gone")
            persist()
        }
    }

    private fun load(): MutableMap<String, RecordingState> {
        if (!file.exists()) return mutableMapOf()
        return try {
            val json = JSONObject(file.readText())
            val parsed = mutableMapOf<String, RecordingState>()
            json.keys().forEach { name ->
                val entry = json.getJSONObject(name)
                parsed[name] = RecordingState(
                    attempts = entry.optInt("attempts", 0),
                    done = entry.optBoolean("done", false),
                    stuck = entry.optBoolean("stuck", false),
                    lastError = if (entry.isNull("lastError")) null else entry.optString("lastError", null)
                )
            }
            parsed
        } catch (e: Exception) {
            // Forgetting everything means recordings get re-processed, which is wasteful but
            // safe. Reporting stale or half-parsed state as "done" would strand a call.
            Log.e(TAG, "Unreadable recording index; treating every recording as unprocessed", e)
            mutableMapOf()
        }
    }

    /** Caller must hold [indexLock]. Never throws: losing the index must not kill the pipeline. */
    private fun persist() {
        val json = JSONObject()
        states.forEach { (name, state) ->
            json.put(
                name,
                JSONObject().apply {
                    put("attempts", state.attempts)
                    put("done", state.done)
                    put("stuck", state.stuck)
                    put("lastError", state.lastError ?: JSONObject.NULL)
                }
            )
        }

        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            file.parentFile?.mkdirs()
            temp.writeText(json.toString())
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist recording index; state is in memory only this run", e)
            try {
                if (temp.exists()) temp.delete()
            } catch (ignored: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "RecordingIndex"

        // Shared across every instance in the process — see the comment on put(). Multiple
        // RecordingIndex objects (e.g. one built by a retry coordinator, another by a cleanup
        // task, or constructed in parallel during tests) all point at the same disk file and
        // hold independent in-memory snapshots. Without a process-wide lock, instance B's
        // persist() can overwrite instance A's newer write with B's stale snapshot — last
        // writer wins over the whole file, entries are lost, and done flags + attempt counts
        // reset. This has happened before to TokenRefresher (see android/CLAUDE.md) and was
        // fixed by moving the lock into the companion object. Contention is not a concern:
        // writes are small, infrequent, and this app only ever has one index file anyway.
        private val indexLock = Any()

        /** Standard location, under app-private storage alongside the pending upload queue. */
        fun default(filesDir: File) = RecordingIndex(File(filesDir, "recordings-index.json"))
    }
}
