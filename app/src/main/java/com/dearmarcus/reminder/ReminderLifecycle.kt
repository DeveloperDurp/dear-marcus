package com.dearmarcus.reminder

interface ReminderNotificationGateway {
    fun isPermissionGranted(): Boolean

    fun post()
}

class ReminderLifecycle(
    private val settings: () -> ReminderSettings,
    private val scheduler: ReminderScheduler,
    private val notifications: ReminderNotificationGateway,
) {
    fun recover(): ReminderSchedulerResult {
        val current = settings()
        return if (current.enabled && notifications.isPermissionGranted()) {
            scheduler.schedule(current.time)
        } else {
            ReminderSchedulerResult.NoChange
        }
    }

    fun onAlarm(): ReminderSchedulerResult {
        val current = settings()
        if (!current.enabled || !notifications.isPermissionGranted()) return ReminderSchedulerResult.NoChange

        runCatching { notifications.post() }
        return scheduler.schedule(current.time)
    }
}
