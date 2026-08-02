package com.brachaai.app

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class PendingUpload(
    val contactName: String,
    val date: String,
    val callerNumber: String?,
    val transcript: String,
    /** Whole seconds, or null when neither the call log nor the recording could tell us. */
    val callLengthSeconds: Int? = null,
    val callType: String? = null
)

/**
 * Durable queue of uploads that could not be delivered (no token, 401, or network failure).
 * One JSON file per entry, named so lexical order == chronological order.
 * Thread-safe via lock: all public operations are synchronized to prevent concurrent
 * access races, especially between enqueue (atomic write) and peekAll (read).
 */
class PendingUploadStore(private val dir: File) {

    private val counter = AtomicInteger(0)
    private val lock = ReentrantLock()

    init {
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create pending upload directory: ${dir.absolutePath}")
        }
        cleanupStaleTemps()
    }

    /**
     * Persists [upload] to durable storage.
     *
     * @return `true` only when the entry is genuinely on disk under its final name;
     * `false` at any bail-out point (temp-file write failure, rename failure, or any
     * other exception). Callers that treat a queued upload as "the transcript is safe"
     * must check this — a `false` here means it is not.
     */
    fun enqueue(upload: PendingUpload): Boolean {
        val json = JSONObject().apply {
            put("contactName", upload.contactName)
            put("date", upload.date)
            put("callerNumber", upload.callerNumber ?: JSONObject.NULL)
            put("transcript", upload.transcript)
            put("callLengthSeconds", upload.callLengthSeconds ?: JSONObject.NULL)
            put("callType", upload.callType ?: JSONObject.NULL)
        }

        val name = String.format("%013d-%03d.json", System.currentTimeMillis(), counter.getAndIncrement() % 1000)
        val finalFile = File(dir, name)
        val tempFile = File(dir, "$name.tmp")

        // Write to temp file first (outside lock for I/O efficiency).
        try {
            tempFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write temp file for upload", e)
            return false
        }

        // Atomic rename and eviction, protected by lock.
        lock.withLock {
            try {
                if (!tempFile.renameTo(finalFile)) {
                    Log.e(TAG, "Failed to rename temp file to $name")
                    tempFile.delete()
                    return false
                }
                Log.d(TAG, "Queued upload $name; queue size = ${sizeUnlocked()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to finalize upload write", e)
                tempFile.delete()
                return false
            }
            evictOverflow()
            cleanupStaleTemps()
        }
        return true
    }

    fun peekAll(): List<Pair<File, PendingUpload>> = lock.withLock {
        listFiles().mapNotNull { file ->
            try {
                val json = JSONObject(file.readText())
                val number = if (json.isNull("callerNumber")) null else json.getString("callerNumber")
                // isNull() is true for a missing key as well as an explicit null, which is
                // exactly what an entry queued before this field existed needs — anything
                // stricter would quarantine it and strand its transcript.
                val callLength = if (json.isNull("callLengthSeconds")) null else json.getInt("callLengthSeconds")
                val type = if (json.has("callType") && !json.isNull("callType")) json.getString("callType") else null
                file to PendingUpload(
                    contactName = json.getString("contactName"),
                    date = json.getString("date"),
                    callerNumber = number,
                    transcript = json.getString("transcript"),
                    callLengthSeconds = callLength,
                    callType = type
                )
            } catch (e: Exception) {
                quarantineUnlocked(file, e)
                null
            }
        }
    }

    /**
     * Moves an unparseable entry aside instead of deleting it.
     *
     * A truncated or corrupt entry is exactly what a full or failing disk produces — the
     * same scenario the rest of this queue is hardened against — and by the time it is
     * noticed the original recording has usually already been deleted. That makes this
     * file the last surviving copy of the call, so it must not be destroyed just because
     * this process cannot parse it: the transcript text is still plainly readable by hand.
     *
     * The new name deliberately does not end in `.json`, so [listFiles] never picks it up
     * again and it can never re-enter the queue, be counted towards capacity, or be
     * evicted by [evictOverflow].
     *
     * Caller must hold [lock].
     */
    private fun quarantineUnlocked(file: File, cause: Exception) {
        var target = File(dir, file.name + CORRUPT_SUFFIX)
        if (target.exists()) {
            // Don't clobber an earlier quarantined entry; that would be the deletion we
            // are trying to avoid, just spelled differently.
            target = File(dir, "${file.name}.${System.currentTimeMillis()}$CORRUPT_SUFFIX")
        }
        val moved = try {
            file.renameTo(target)
        } catch (e: Exception) {
            Log.e(TAG, "Could not move corrupt queue entry ${file.name} aside", e)
            false
        }
        if (moved) {
            Log.e(
                TAG,
                "Unreadable queue entry ${file.name}: kept as ${target.name} instead of deleted. " +
                    "Its recording may already be gone, so this file may be the only surviving " +
                    "copy of the transcript — recover it by hand.",
                cause
            )
        } else {
            // Leaving it in place is still safe: peekAll skips it every time, so it never
            // blocks the queue, and nothing is lost.
            Log.e(
                TAG,
                "Unreadable queue entry ${file.name}: could not be moved aside, leaving it in place " +
                    "rather than deleting it. It will be skipped on every flush.",
                cause
            )
        }
    }

    fun remove(file: File) {
        lock.withLock {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Could not delete queue entry ${file.name}")
            }
        }
    }

    fun size(): Int = lock.withLock {
        sizeUnlocked()
    }

    private fun sizeUnlocked(): Int = listFiles().size

    private fun listFiles(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()

    private fun cleanupStaleTemps() {
        val threshold = System.currentTimeMillis() - TEMP_FILE_AGE_THRESHOLD_MS
        dir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") }?.forEach { tempFile ->
            if (tempFile.lastModified() < threshold) {
                Log.w(TAG, "Cleaning up orphaned temp file ${tempFile.name}: older than ${TEMP_FILE_AGE_THRESHOLD_MS}ms")
                tempFile.delete()
            }
        }
    }

    private fun evictOverflow() {
        val files = listFiles()
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS

        // Both eviction paths below are permanent user data loss, not a dropped retry: the
        // original recording is deleted once its transcript reaches this queue, so the
        // evicted entry is the last copy of that call. Logged at Log.e accordingly.
        files.filter { it.lastModified() < cutoff }.forEach {
            Log.e(TAG, "DESTROYING transcript in queue entry ${it.name}: older than 30 days. Its recording is likely already deleted; this call is unrecoverable.")
            it.delete()
        }

        val remaining = listFiles()
        if (remaining.size > MAX_ENTRIES) {
            remaining.take(remaining.size - MAX_ENTRIES).forEach {
                Log.e(TAG, "DESTROYING transcript in queue entry ${it.name}: queue over capacity ($MAX_ENTRIES). Its recording is likely already deleted; this call is unrecoverable.")
                it.delete()
            }
        }
    }

    companion object {
        private const val TAG = "PendingUploadStore"

        /**
         * Hard cap on queued entries. Public because callers that delete the recording once
         * its transcript is queued need to know when the queue is full enough that the next
         * [evictOverflow] will start destroying transcripts — see
         * `AudioProcessor.queuedTranscriptIsDurable`. Read it from here rather than
         * duplicating the number.
         */
        const val MAX_ENTRIES = 200
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        private const val TEMP_FILE_AGE_THRESHOLD_MS = 60_000L  // 1 minute

        /** Suffix for entries moved aside by [quarantineUnlocked]; deliberately not `.json`. */
        const val CORRUPT_SUFFIX = ".corrupt"
    }
}
