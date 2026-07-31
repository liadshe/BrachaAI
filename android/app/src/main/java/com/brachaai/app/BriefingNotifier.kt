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
            briefing.openTasks.take(CallOverlayService.MAX_TASKS_SHOWN).forEach { add("• ${it.title}") }
            val hidden = briefing.openTaskCount - CallOverlayService.MAX_TASKS_SHOWN
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

    private fun createChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Caller Briefings", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private const val CHANNEL_ID = "caller_briefing"

    /** Fixed id: a second call replaces the first card rather than stacking beside it. */
    private const val NOTIFICATION_ID = 2
}
