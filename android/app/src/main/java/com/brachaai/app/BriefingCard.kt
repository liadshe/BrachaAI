package com.brachaai.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Paints a briefing into an inflated `overlay_call_briefing.xml`.
 *
 * Shared by the two things that can put the card on screen: [CallOverlayService]'s
 * `WindowManager` window when the device is unlocked, and [CallOverlayActivity] when it is
 * locked. A `TYPE_APPLICATION_OVERLAY` window cannot be shown above the keyguard at all —
 * that capability belongs to `Activity.setShowWhenLocked`, so the locked case has to be an
 * Activity — but the card is meant to look identical either side of the lock. Keeping the
 * binding in one place is what stops the two renderers drifting apart.
 *
 * Sets `root.tag` to the contact id: [CallOverlayService] reads it back to discard a live
 * refresh that arrives after the card has moved on to a different caller.
 */
fun bindBriefingCard(context: Context, root: View, briefing: Briefing) {
    root.tag = briefing.contactId
    root.findViewById<TextView>(R.id.overlay_name).text = briefing.name

    val summary = briefing.lastCallSummary?.takeIf { it.isNotBlank() }
    root.findViewById<View>(R.id.overlay_summary_section).visibility =
        if (summary == null) View.GONE else View.VISIBLE
    summary?.let { root.findViewById<TextView>(R.id.overlay_summary).text = it }

    val shown = briefing.openTasks.take(MAX_TASKS_SHOWN)
    root.findViewById<View>(R.id.overlay_tasks_section).visibility =
        if (shown.isEmpty()) View.GONE else View.VISIBLE

    val container = root.findViewById<LinearLayout>(R.id.overlay_tasks)
    container.removeAllViews()
    val inflater = LayoutInflater.from(context)
    shown.forEach { task ->
        val row = inflater.inflate(R.layout.overlay_task_item, container, false)
        row.findViewById<TextView>(R.id.overlay_task_title).text = task.title
        container.addView(row)
    }

    // Counted from the untruncated total, not from the list, which is capped twice over —
    // once by the backend and again by MAX_TASKS_SHOWN.
    val hidden = briefing.openTaskCount - shown.size
    val more = root.findViewById<TextView>(R.id.overlay_more_tasks)
    if (hidden > 0) {
        more.text = context.getString(R.string.overlay_more_tasks, hidden)
        more.visibility = View.VISIBLE
    } else {
        more.visibility = View.GONE
    }
}
