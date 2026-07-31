package com.brachaai.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class BriefingTask(
    val id: String,
    val title: String,
    val priority: String,
)

/**
 * What the overlay shows for one contact: who they are, what was last discussed, what is
 * still open.
 *
 * [openTaskCount] is the untruncated total. [openTasks] is capped by the backend and capped
 * again by the card, so counting the list would understate "+N more".
 */
data class Briefing(
    val contactId: String,
    val name: String,
    val phone: String,
    val lastCallSummary: String?,
    val openTasks: List<BriefingTask>,
    val openTaskCount: Int,
) {

    fun toJson(): JSONObject {
        val tasks = JSONArray()
        openTasks.forEach { task ->
            tasks.put(
                JSONObject()
                    .put("id", task.id)
                    .put("title", task.title)
                    .put("priority", task.priority)
            )
        }
        return JSONObject()
            .put("contactId", contactId)
            .put("name", name)
            .put("phone", phone)
            .put("lastCallSummary", lastCallSummary ?: JSONObject.NULL)
            .put("openTasks", tasks)
            .put("openTaskCount", openTaskCount)
    }

    companion object {
        private const val TAG = "Briefing"

        /**
         * Parses one briefing, from either the backend response or the on-disk cache — the
         * two use the same shape deliberately, so a synced payload can be written straight
         * back out.
         *
         * Returns null for an entry missing the fields that identify a contact, so one bad
         * record cannot take the whole snapshot down with it.
         */
        fun fromJson(json: JSONObject): Briefing? {
            val contactId = json.optString("contactId")
            val phone = json.optString("phone")
            if (contactId.isBlank() || phone.isBlank()) {
                Log.w(TAG, "Skipping briefing entry with no contact id or phone")
                return null
            }

            val summary = if (json.isNull("lastCallSummary")) {
                null
            } else {
                json.optString("lastCallSummary").ifBlank { null }
            }

            val tasksJson = json.optJSONArray("openTasks") ?: JSONArray()
            val tasks = (0 until tasksJson.length()).mapNotNull { index ->
                val task = tasksJson.optJSONObject(index) ?: return@mapNotNull null
                val title = task.optString("title")
                if (title.isBlank()) null
                else BriefingTask(
                    id = task.optString("id"),
                    title = title,
                    priority = task.optString("priority").ifBlank { "LOW" },
                )
            }

            return Briefing(
                contactId = contactId,
                name = json.optString("name").ifBlank { "Unknown" },
                phone = phone,
                lastCallSummary = summary,
                openTasks = tasks,
                openTaskCount = json.optInt("openTaskCount", tasks.size),
            )
        }
    }
}

/**
 * The backend sends `lastCall: { summary, dateTime }`; the card only ever shows the summary.
 * Flattening here keeps the date out of the device model entirely rather than carrying an
 * unused timestamp through the cache.
 */
fun briefingFromBackendJson(json: JSONObject): Briefing? {
    val lastCall = json.optJSONObject("lastCall")
    val flattened = JSONObject(json.toString())
        .put("lastCallSummary", lastCall?.optString("summary")?.ifBlank { null } ?: JSONObject.NULL)
    return Briefing.fromJson(flattened)
}
