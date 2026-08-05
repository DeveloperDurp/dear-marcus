package com.dearmarcus.reminder

import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime

interface InexactAlarmGateway {
    fun schedule(triggerAt: Instant)

    fun cancel()
}

class AndroidReminderScheduler(
    private val alarms: InexactAlarmGateway,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ReminderScheduler {
    override fun schedule(time: ReminderTime): ReminderSchedulerResult = runCatching {
        alarms.schedule(nextOccurrence(time))
        ReminderSchedulerResult.Scheduled
    }.getOrElse { ReminderSchedulerResult.Failed(it.message ?: "Alarm scheduling failed.") }

    override fun replace(time: ReminderTime): ReminderSchedulerResult = runCatching {
        alarms.cancel()
        alarms.schedule(nextOccurrence(time))
        ReminderSchedulerResult.Replaced
    }.getOrElse { ReminderSchedulerResult.Failed(it.message ?: "Alarm scheduling failed.") }

    override fun cancel(): ReminderSchedulerResult = runCatching {
        alarms.cancel()
        ReminderSchedulerResult.Canceled
    }.getOrElse { ReminderSchedulerResult.Failed(it.message ?: "Alarm cancellation failed.") }

    private fun nextOccurrence(time: ReminderTime): Instant {
        val now = ZonedDateTime.now(clock)
        val today = now.toLocalDate().atTime(time.hour, time.minute).atZone(now.zone)
        return (if (today.isAfter(now)) today else today.plusDays(1)).toInstant()
    }
}
