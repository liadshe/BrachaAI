package com.brachaai.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * The fallback renderer, used when the overlay permission is not held.
 *
 * Posted directly from the receiver rather than via a service: starting a service from the
 * background is restricted on Android 12+, and the exemption we rely on for the overlay is
 * the overlay permission itself — precisely what is missing here.
 */
object BriefingNotifier {

    fun show(context: Context, briefing: Briefing) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        createChannel(manager)

        val lines = buildList {
            briefing.lastCallSummary?.takeIf { it.isNotBlank() }?.let(::add)
            val shown = briefing.openTasks.take(MAX_TASKS_SHOWN)
            shown.forEach { add("• ${it.title}") }
            // Counted from the untruncated total minus what was actually shown — matches
            // CallOverlayService.bind() exactly, so the two renderers can't disagree on the
            // hidden count even if the list ever has fewer than MAX_TASKS_SHOWN items.
            val hidden = briefing.openTaskCount - shown.size
            if (hidden > 0) add(context.getString(R.string.overlay_more_tasks, hidden))
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(briefing.name)
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            // Backstop against a missed IDLE, matching CallOverlayService's delayed teardown
            // and reading the same constant. Without it a dropped call-ended broadcast left
            // the caller's summaries sitting in the shade indefinitely — the overlay had a
            // way out of that state and this renderer did not.
            .setTimeoutAfter(MAX_BRIEFING_LIFETIME_MS)
            .setContentIntent(contactIntent(context, briefing.contactId))
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun contactIntent(context: Context, contactId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_CONTACT_ID, contactId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            contactId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * IMPORTANCE_HIGH so the briefing still surfaces over the incoming-call screen — that
     * visibility is the whole point of the fallback — but with sound and vibration stripped
     * off the channel. This notification only ever posts while the phone is ringing, so the
     * default alert fired a second tone and a buzz on top of the ringtone.
     *
     * Silenced on the channel rather than with `setSilent(true)` on the builder: on O+ (which
     * is every supported device, minSdk 26) the channel owns sound and vibration, and
     * `setSilent` suppresses the heads-up along with the noise, which would bury the card in
     * the shade exactly when it needs to be seen.
     */
    private fun createChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Drop the pre-v2 channel on devices that created it. It's never recreated (there
            // is no code path left that names it), so this only ever removes the orphan; it's
            // a no-op on installs that never had it. Without this, those devices keep a second,
            // identically-named "Caller Briefings" entry in App Info -> Notifications forever
            // — inert, since nothing posts to it, but confusing since toggling it does nothing.
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)

            val channel =
                NotificationChannel(CHANNEL_ID, "Caller Briefings", NotificationManager.IMPORTANCE_HIGH)
            channel.setSound(null, null)
            channel.enableVibration(false)
            manager.createNotificationChannel(channel)
        }
    }

    // Versioned id: a channel's sound and vibration are immutable once created, so devices
    // that already have the original noisy channel would ignore the silencing above. Bumping
    // the id creates a fresh, silent one instead. Bump again if these settings ever change.
    private const val CHANNEL_ID = "caller_briefing_v2"

    // The pre-v2 channel id. Kept only so createChannel() can delete it on devices that
    // created it before the bump; safe to drop this constant (and the delete call) once
    // enough time has passed that no installs still carry the old channel.
    private const val LEGACY_CHANNEL_ID = "caller_briefing"

    /** Fixed id: a second call replaces the first card rather than stacking beside it. */
    private const val NOTIFICATION_ID = 2
}
