package com.brachaai.app

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class PendingUpload(
    val contactName: String,
    val date: String,
    val callerNumber: String?,
    val transcript: String
)

/**
 * Durable queue of uploads that could not be delivered (no token, 401, or network failure).
 * One JSON file per entry, named so lexical order == chronological order.
 */
class PendingUploadStore(private val dir: File) {

    private val counter = AtomicInteger(0)

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
        try {
            File(dir, name).writeText(json.toString())
            Log.d(TAG, "Queued upload $name; queue size = ${size()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue upload", e)
            return
        }
        evictOverflow()
    }

    fun peekAll(): List<Pair<File, PendingUpload>> =
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

    fun remove(file: File) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete queue entry ${file.name}")
        }
    }

    fun size(): Int = listFiles().size

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
