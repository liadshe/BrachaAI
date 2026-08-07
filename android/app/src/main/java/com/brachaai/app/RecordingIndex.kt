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
 *
 * The file is the single source of truth — every operation loads, applies the change, and
 * writes back inside a process-wide lock. This prevents concurrent instances from holding
 * stale in-memory snapshots that overwrite each other on disk. Multiple RecordingIndex
 * objects over the same file all serialize against every other through [indexLock], so
 * writes from instance A cannot be lost to instance B's stale snapshot.
 */
class RecordingIndex(private val file: File) {

    fun stateOf(name: String): RecordingState = synchronized(indexLock) {
        val states = load()
        states[name] ?: RecordingState()
    }

    fun allNames(): Set<String> = synchronized(indexLock) {
        val states = load()
        states.keys.toSet()
    }

    /**
     * Every recorded state in one read.
     *
     * Exists because [stateOf] parses the whole file on every call, so asking it about N
     * recordings costs N parses of a file that grows with N. A sweep does exactly that, and
     * on a device with thousands of recordings it turned into gigabytes of JSON parsing per
     * sweep — held under the queue's mutex, which blocked the file-observer path behind it
     * and delayed a freshly-recorded call by minutes.
     *
     * Callers filtering a directory listing must use this, not [stateOf] in a loop.
     *
     * A missing recording is absent from the map; treat that as [RecordingState] defaults,
     * exactly as [stateOf] does.
     */
    fun snapshot(): Map<String, RecordingState> = synchronized(indexLock) {
        load()
    }

    /**
     * Records [state] for [name] and reports whether it actually reached disk.
     *
     * There is no in-memory cache behind this: a failed write is simply lost, and the next
     * `stateOf` will report the recording as never attempted. That matters enough for the
     * caller to be able to react, which is why this returns a value instead of `Unit`:
     *
     * - a lost `done = true` with delete-after-processing OFF means the recording is still
     *   on disk, so the next sweep re-transcribes and **re-uploads** the same call;
     * - a lost `stuck = true` means the recording is retried and re-notified forever
     *   instead of staying given up on.
     *
     * Never throws — losing the index must not kill the pipeline.
     */
    fun put(name: String, state: RecordingState): Boolean = synchronized(indexLock) {
        val states = load()
        states[name] = state
        persist(states)
    }

    /**
     * Records every entry in [newStates] in a single load-and-write.
     *
     * Exists for the first-run baseline, which marks every pre-existing recording at once.
     * Doing that through [put] would reload and rewrite the whole file per recording — on a
     * phone with hundreds of old calls that is hundreds of parses of a file that is itself
     * growing with each write.
     *
     * Never throws. Returns whether the batch reached disk.
     */
    fun putAll(newStates: Map<String, RecordingState>): Boolean = synchronized(indexLock) {
        if (newStates.isEmpty()) return true
        val states = load()
        states.putAll(newStates)
        persist(states)
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
            val states = load()
            val gone = states.keys.filterNot { it in existingNames }
            if (gone.isEmpty()) return
            gone.forEach { states.remove(it) }
            Log.d(TAG, "Pruned ${gone.size} index entr(ies) whose recording is gone")
            persist(states)
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

    /**
     * Caller must hold [indexLock]. Never throws: losing the index must not kill the
     * pipeline. Returns true only when the snapshot is on disk.
     */
    private fun persist(states: Map<String, RecordingState>): Boolean {
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
        return try {
            file.parentFile?.mkdirs()
            temp.writeText(json.toString())
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: Exception) {
            // Nothing is cached in memory, so this state is not "kept for this run" — it is
            // gone. The previous snapshot on disk (if any) is left exactly as it was by the
            // temp-then-move, so the loss is limited to this one change.
            Log.e(TAG, "Could not persist recording index; this change is lost, not held in memory", e)
            try {
                if (temp.exists()) temp.delete()
            } catch (ignored: Exception) {
            }
            false
        }
    }

    companion object {
        private const val TAG = "RecordingIndex"

        // Shared across every instance in the process. Multiple RecordingIndex objects
        // pointing at the same file each read, apply changes, and write back inside this
        // lock, so the file is the single source of truth and no instance can lose another
        // instance's writes. Without a process-wide lock, instance B could hold a stale
        // in-memory snapshot and persist() it over instance A's newer write — last-writer
        // wins over the whole file, entries are lost. This happened before to TokenRefresher
        // (see android/CLAUDE.md) and was fixed by moving the lock into the companion object.
        // Contention is not a concern: writes are small, infrequent, and this app only ever
        // has one index file anyway.
        private val indexLock = Any()

        /** Standard location, under app-private storage alongside the pending upload queue. */
        fun default(filesDir: File) = RecordingIndex(File(filesDir, "recordings-index.json"))
    }
}
