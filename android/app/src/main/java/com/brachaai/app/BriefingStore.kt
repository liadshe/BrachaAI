package com.brachaai.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The local snapshot the overlay reads when the phone rings.
 *
 * Deliberately simpler than [PendingUploadStore], which uses one durable file per entry
 * because losing an entry loses a transcript forever. This is disposable derived data: a
 * corrupt or missing snapshot means "no card until the next sync", never data loss. So every
 * read failure degrades to an empty cache instead of throwing, and there is no quarantine.
 *
 * Writes go through a temp file and a rename so a call arriving mid-sync can never read a
 * half-written snapshot.
 */
class BriefingStore(private val file: File) {

    fun replaceAll(briefings: List<Briefing>) {
        val array = JSONArray()
        briefings.forEach { array.put(it.toJson()) }
        val payload = JSONObject().put(KEY_CONTACTS, array).toString()

        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            temp.writeText(payload)
            // Files.move with REPLACE_EXISTING swaps the old snapshot for the new one without
            // ever deleting the destination first. If the replace can't complete — e.g. the
            // destination is locked by another handle — this throws and the previous snapshot
            // is left exactly as it was, which is strictly better than losing it.
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Log.e(TAG, "Could not replace the briefing snapshot; keeping the previous one", e)
            temp.delete()
        }
    }

    /** The cached briefing for a normalized phone key, or null if this caller is unknown. */
    fun lookup(phoneKey: String): Briefing? =
        readAll().firstOrNull { PhoneNormalizer.key(it.phone) == phoneKey }

    fun readAll(): List<Briefing> {
        if (!file.exists()) return emptyList()

        return try {
            val contacts = JSONObject(file.readText()).optJSONArray(KEY_CONTACTS) ?: JSONArray()
            (0 until contacts.length()).mapNotNull { index ->
                contacts.optJSONObject(index)?.let(Briefing::fromJson)
            }
        } catch (e: Exception) {
            // Nothing here is irreplaceable — the next sync rewrites it.
            Log.w(TAG, "Briefing snapshot unreadable; treating as empty", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "BriefingStore"
        private const val KEY_CONTACTS = "contacts"

        /** The snapshot lives in app-private storage, beside the pending-upload queue. */
        fun default(filesDir: File): BriefingStore = BriefingStore(File(filesDir, "briefings.json"))
    }
}
