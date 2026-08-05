package com.dearmarcus.reminder

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderAndroidAdapterTest {
    @Test
    fun scheduler_schedules_the_next_local_daily_occurrence() {
        // Given
        val alarms = RecordingAlarmGateway()
        val scheduler = AndroidReminderScheduler(
            alarms,
            Clock.fixed(Instant.parse("2026-08-04T20:00:00Z"), ZoneId.of("UTC")),
        )

        // When
        val result = scheduler.schedule(ReminderTime(20, 0))

        // Then
        assertEquals(ReminderSchedulerResult.Scheduled, result)
        assertEquals(listOf(Instant.parse("2026-08-05T20:00:00Z")), alarms.scheduledAt)
    }

    @Test
    fun scheduler_reports_failure_when_the_alarm_service_denies_scheduling() {
        // Given
        val scheduler = AndroidReminderScheduler(
            RecordingAlarmGateway(scheduleFailure = SecurityException("denied")),
            Clock.systemUTC(),
        )

        // When
        val result = scheduler.schedule(ReminderTime(20, 0))

        // Then
        assertTrue(result is ReminderSchedulerResult.Failed)
    }

    @Test
    fun recovery_does_not_schedule_when_boot_finds_reminders_disabled() {
        // Given
        val scheduler = RecordingScheduler()
        val lifecycle = ReminderLifecycle(
            settings = { ReminderSettings(enabled = false, time = ReminderDefaults.TIME) },
            scheduler = scheduler,
            notifications = RecordingNotifications(permissionGranted = true),
        )

        // When
        val result = lifecycle.recover()

        // Then
        assertEquals(ReminderSchedulerResult.NoChange, result)
        assertEquals(emptyList<ReminderTime>(), scheduler.scheduled)
    }

    @Test
    fun recovery_schedules_enabled_reminder_at_app_start() {
        // Given
        val scheduler = RecordingScheduler()
        val expected = ReminderTime(7, 30)
        val lifecycle = ReminderLifecycle(
            settings = { ReminderSettings(enabled = true, time = expected) },
            scheduler = scheduler,
            notifications = RecordingNotifications(permissionGranted = true),
        )

        // When
        val result = lifecycle.recover()

        // Then
        assertEquals(ReminderSchedulerResult.Scheduled, result)
        assertEquals(listOf(expected), scheduler.scheduled)
    }

    @Test
    fun recovery_doesNotScheduleEnabledReminderWhenPermissionIsDenied() {
        // Given
        val scheduler = RecordingScheduler()
        val lifecycle = ReminderLifecycle(
            settings = { ReminderSettings(enabled = true, time = ReminderTime(7, 30)) },
            scheduler = scheduler,
            notifications = RecordingNotifications(permissionGranted = false),
        )

        // When
        val result = lifecycle.recover()

        // Then
        assertEquals(ReminderSchedulerResult.NoChange, result)
        assertEquals(emptyList<ReminderTime>(), scheduler.scheduled)
    }

    @Test
    fun receiver_posts_and_rearms_after_delivery_when_permission_is_granted() {
        // Given
        val scheduler = RecordingScheduler()
        val notifications = RecordingNotifications(permissionGranted = true)
        val expected = ReminderTime(21, 15)
        val lifecycle = ReminderLifecycle(
            settings = { ReminderSettings(enabled = true, time = expected) },
            scheduler = scheduler,
            notifications = notifications,
        )

        // When
        val result = lifecycle.onAlarm()

        // Then
        assertEquals(ReminderSchedulerResult.Scheduled, result)
        assertEquals(1, notifications.postCount)
        assertEquals(listOf(expected), scheduler.scheduled)
    }

    @Test
    fun receiver_doesNotPostOrRearmAfterDeliveryWhenPermissionIsDenied() {
        // Given
        val scheduler = RecordingScheduler()
        val notifications = RecordingNotifications(permissionGranted = false)
        val expected = ReminderTime(21, 15)
        val lifecycle = ReminderLifecycle(
            settings = { ReminderSettings(enabled = true, time = expected) },
            scheduler = scheduler,
            notifications = notifications,
        )

        // When
        val result = lifecycle.onAlarm()

        // Then
        assertEquals(ReminderSchedulerResult.NoChange, result)
        assertEquals(0, notifications.postCount)
        assertEquals(emptyList<ReminderTime>(), scheduler.scheduled)
    }
}

private class RecordingAlarmGateway(
    private val scheduleFailure: RuntimeException? = null,
) : InexactAlarmGateway {
    val scheduledAt = mutableListOf<Instant>()

    override fun schedule(triggerAt: Instant) {
        scheduleFailure?.let { throw it }
        scheduledAt += triggerAt
    }

    override fun cancel() = Unit
}

private class RecordingScheduler : ReminderScheduler {
    val scheduled = mutableListOf<ReminderTime>()

    override fun schedule(time: ReminderTime): ReminderSchedulerResult {
        scheduled += time
        return ReminderSchedulerResult.Scheduled
    }

    override fun replace(time: ReminderTime): ReminderSchedulerResult = schedule(time)

    override fun cancel(): ReminderSchedulerResult = ReminderSchedulerResult.Canceled
}

private class RecordingNotifications(
    private val permissionGranted: Boolean,
) : ReminderNotificationGateway {
    var postCount = 0

    override fun isPermissionGranted(): Boolean = permissionGranted

    override fun post() { postCount += 1 }
}
