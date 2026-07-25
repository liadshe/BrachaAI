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
    val transcript: String
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
    }

    fun enqueue(upload: PendingUpload) {
        val json = JSONObject().apply {
            put("contactName", upload.contactName)
            put("date", upload.date)
            put("callerNumber", upload.callerNumber ?: JSONObject.NULL)
            put("transcript", upload.transcript)
        }

        val name = String.format("%013d-%03d.json", System.currentTimeMillis(), counter.getAndIncrement() % 1000)
        val finalFile = File(dir, name)
        val tempFile = File(dir, "$name.tmp")

        // Write to temp file first (outside lock for I/O efficiency).
        try {
            tempFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write temp file for upload", e)
            return
        }

        // Atomic rename and eviction, protected by lock.
        lock.withLock {
            try {
                if (!tempFile.renameTo(finalFile)) {
                    Log.e(TAG, "Failed to rename temp file to $name")
                    tempFile.delete()
                    return
                }
                Log.d(TAG, "Queued upload $name; queue size = ${sizeUnlocked()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to finalize upload write", e)
                tempFile.delete()
                return
            }
            evictOverflow()
        }
    }

    fun peekAll(): List<Pair<File, PendingUpload>> = lock.withLock {
        listFiles().mapNotNull { file ->
            try {
                val json = JSONObject(file.readText())
                val number = if (json.isNull("callerNumber")) null else json.getString("callerNumber")
                file to PendingUpload(
                    contactName = json.getString("contactName"),
                    date = json.getString("date"),
                    callerNumber = number,
                    transcript = json.getString("transcript")
                )
            } catch (e: Exception) {
                Log.w(TAG, "Discarding unreadable queue entry ${file.name}", e)
                file.delete()
                null
            }
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

    private fun evictOverflow() {
        val files = listFiles()
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS

        files.filter { it.lastModified() < cutoff }.forEach {
            Log.w(TAG, "Evicting queue entry ${it.name}: older than 30 days")
            it.delete()
        }

        val remaining = listFiles()
        if (remaining.size > MAX_ENTRIES) {
            remaining.take(remaining.size - MAX_ENTRIES).forEach {
                Log.w(TAG, "Evicting queue entry ${it.name}: queue over capacity")
                it.delete()
            }
        }
    }

    companion object {
        private const val TAG = "PendingUploadStore"
        const val MAX_ENTRIES = 200
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
