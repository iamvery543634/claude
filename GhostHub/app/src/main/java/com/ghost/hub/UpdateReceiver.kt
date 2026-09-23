package com.ghost.hub

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.concurrent.thread

/** Fires periodically: if GitHub (or the PC) is reachable and has newer apps, posts one notification. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val repo = Repo(app)
        if (!repo.autoCheck) return
        val pending = goAsync()
        thread {
            try {
                // Short timeouts: a receiver only gets a few seconds before Android may stop it.
                val updates = runCatching { repo.load(quick = true) }.getOrNull()
                    ?.filter { it.state == InstallState.UPDATE }
                    ?: emptyList()
                if (updates.isNotEmpty()) notify(app, updates)
            } finally {
                pending.finish()
            }
        }
    }

    private fun notify(ctx: Context, updates: List<AppStatus>) {
        if (NotificationManagerCompat.from(ctx).areNotificationsEnabled().not()) return
        val names = updates.joinToString(", ") { it.entry.name }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (updates.size == 1) "${updates[0].entry.name} has an update" else "Updates for $names"
        val n = NotificationCompat.Builder(ctx, HubApp.CHANNEL)
            .setSmallIcon(R.drawable.ic_hub)
            .setContentTitle("Ghost Hub")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(42, n) }
    }

    companion object {
        /** Schedule a rough periodic check (every ~6 hours). Inexact, so no special permission is needed. */
        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, 1, Intent(ctx, UpdateReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val period = AlarmManager.INTERVAL_HALF_DAY / 2
            am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + period, period, pi)
        }
    }
}
