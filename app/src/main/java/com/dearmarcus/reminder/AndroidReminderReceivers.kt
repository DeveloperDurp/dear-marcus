package com.dearmarcus.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.time.Instant

private const val ALARM_REQUEST_CODE = 401
private const val NOTIFICATION_ID = 402
private const val NOTIFICATION_CHANNEL_ID = "daily-reminder"
private const val REMINDER_ALARM_ACTION = "com.dearmarcus.reminder.ACTION_REMINDER"

internal class AlarmManagerGateway(
    private val context: Context,
    private val alarmManager: AlarmManager,
) : InexactAlarmGateway {
    override fun schedule(triggerAt: Instant) {
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt.toEpochMilli(),
            pendingIntent(),
        )
    }

    override fun cancel() {
        alarmManager.cancel(pendingIntent())
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, ReminderAlarmReceiver::class.java).setAction(REMINDER_ALARM_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private class AndroidReminderNotifications(
    private val context: Context,
) : ReminderNotificationGateway {
    override fun isPermissionGranted(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    override fun post() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Daily reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Dear Marcus")
                .setContentText("Take a moment to reflect on your day.")
                .setAutoCancel(true)
                .build(),
        )
    }
}

internal fun androidReminderScheduler(context: Context): ReminderScheduler {
    val alarmManager = context.getSystemService(AlarmManager::class.java)
        ?: return UnavailableReminderScheduler
    return AndroidReminderScheduler(AlarmManagerGateway(context, alarmManager))
}

object ReminderRecovery {
    fun recover(context: Context): ReminderSchedulerResult = lifecycle(context).recover()

    fun deliver(context: Context): ReminderSchedulerResult = lifecycle(context).onAlarm()

    private fun lifecycle(context: Context): ReminderLifecycle {
        val scheduler = androidReminderScheduler(context)
        val store = ReminderSettingsStore.fromContext(context) { scheduler }
        return ReminderLifecycle(store::settings, scheduler, AndroidReminderNotifications(context))
    }
}

internal object UnavailableReminderScheduler : ReminderScheduler {
    override fun schedule(time: ReminderTime): ReminderSchedulerResult =
        ReminderSchedulerResult.Failed("Alarm service unavailable.")

    override fun replace(time: ReminderTime): ReminderSchedulerResult = schedule(time)

    override fun cancel(): ReminderSchedulerResult = ReminderSchedulerResult.Failed("Alarm service unavailable.")
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == REMINDER_ALARM_ACTION) ReminderRecovery.deliver(context)
    }
}

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ReminderRecovery.recover(context)
    }
}
