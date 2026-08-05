package com.dearmarcus.reminder

import com.dearmarcus.reminder.ReminderSchedulerResult.Canceled
import com.dearmarcus.reminder.ReminderSchedulerResult.NoChange
import com.dearmarcus.reminder.ReminderSchedulerResult.Replaced
import com.dearmarcus.reminder.ReminderSchedulerResult.Scheduled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderSettingsStoreTest {
    @Test
    fun default_settings_are_disabled_with_default_time_and_no_initial_scheduler_calls() {
        val persistence = InMemoryReminderSettingsPersistence()
        val scheduler = RecordingReminderScheduler()
        val store = ReminderSettingsStore(persistence, scheduler)

        assertEquals(ReminderSettings(enabled = false, time = ReminderDefaults.TIME), store.settings())
        assertEquals(emptyList<String>(), scheduler.calls)
    }

    @Test
    fun settings_persist_across_store_instances() {
        val persistence = InMemoryReminderSettingsPersistence()
        val firstStore = ReminderSettingsStore(persistence, RecordingReminderScheduler())

        firstStore.setEnabled(true)
        firstStore.setReminderTime(21, 30)

        val secondStore = ReminderSettingsStore(persistence, RecordingReminderScheduler())

        assertEquals(ReminderSettings(enabled = true, time = ReminderTime(21, 30)), secondStore.settings())
    }

    @Test
    fun invalid_hour_is_rejected_when_setting_time() {
        val store = ReminderSettingsStore(InMemoryReminderSettingsPersistence(), RecordingReminderScheduler())

        val invalidHourFailure = runCatching { store.setReminderTime(-1, 0) }.exceptionOrNull()

        assertTrue(invalidHourFailure is IllegalArgumentException)
        assertTrue(invalidHourFailure?.message?.contains("hour") == true)
    }

    @Test
    fun invalid_minute_is_rejected_when_setting_time() {
        val store = ReminderSettingsStore(InMemoryReminderSettingsPersistence(), RecordingReminderScheduler())

        val invalidMinuteFailure = runCatching { store.setReminderTime(1, 60) }.exceptionOrNull()

        assertTrue(invalidMinuteFailure is IllegalArgumentException)
        assertTrue(invalidMinuteFailure?.message?.contains("minute") == true)
    }

    @Test
    fun enabling_schedules_with_default_time_when_offline() {
        val scheduler = RecordingReminderScheduler()
        val store = ReminderSettingsStore(InMemoryReminderSettingsPersistence(), scheduler)

        val result = store.setEnabled(true)

        assertEquals(ReminderSettings(enabled = true, time = ReminderDefaults.TIME), store.settings())
        assertEquals(Scheduled, result)
        assertEquals(listOf("schedule:20:0"), scheduler.calls)
    }

    @Test
    fun changing_time_replaces_previous_schedule_when_enabled() {
        val scheduler = RecordingReminderScheduler()
        val persistence = InMemoryReminderSettingsPersistence()
        val store = ReminderSettingsStore(persistence, scheduler)

        store.setEnabled(true)
        val result = store.setReminderTime(21, 15)

        assertEquals(ReminderSettings(enabled = true, time = ReminderTime(21, 15)), store.settings())
        assertEquals(Replaced, result)
        assertEquals(listOf("schedule:20:0", "replace:21:15"), scheduler.calls)
    }

    @Test
    fun disabling_cancels_previous_schedule() {
        val scheduler = RecordingReminderScheduler()
        val store = ReminderSettingsStore(InMemoryReminderSettingsPersistence(), scheduler)

        store.setEnabled(true)
        val result = store.setEnabled(false)

        assertEquals(ReminderSettings(enabled = false, time = ReminderDefaults.TIME), store.settings())
        assertEquals(Canceled, result)
        assertEquals(listOf("schedule:20:0", "cancel"), scheduler.calls)
    }

    @Test
    fun explicit_scheduler_results_propagate_to_callers() {
        val scheduler = RecordingReminderScheduler()
        scheduler.scheduleResult = ReminderSchedulerResult.Failed("scheduler unavailable")
        val store = ReminderSettingsStore(InMemoryReminderSettingsPersistence(), scheduler)

        val result = store.setEnabled(true)

        assertEquals(ReminderSchedulerResult.Failed("scheduler unavailable"), result)
    }

    @Test
    fun setting_time_without_enable_only_updates_state_without_scheduling() {
        val scheduler = RecordingReminderScheduler()
        val store = ReminderSettingsStore(InMemoryReminderSettingsPersistence(), scheduler)

        val result = store.setReminderTime(6, 45)

        assertEquals(ReminderSettings(enabled = false, time = ReminderTime(6, 45)), store.settings())
        assertEquals(NoChange, result)
        assertEquals(emptyList<String>(), scheduler.calls)
    }
}

private class InMemoryReminderSettingsPersistence(
    initialEnabled: Boolean = ReminderDefaults.ENABLED,
    initialTime: ReminderTime = ReminderDefaults.TIME,
) : ReminderSettingsPersistence {
    private var enabled = initialEnabled
    private var hour = initialTime.hour
    private var minute = initialTime.minute

    override fun readEnabled(): Boolean = enabled

    override fun readTime(): ReminderTime = ReminderTime(hour = hour, minute = minute)

    override fun saveState(enabled: Boolean, time: ReminderTime) {
        this.enabled = enabled
        this.hour = time.hour
        this.minute = time.minute
    }
}

private class RecordingReminderScheduler : ReminderScheduler {
    val calls = mutableListOf<String>()
    var scheduleResult: ReminderSchedulerResult = Scheduled
    var replaceResult: ReminderSchedulerResult = Replaced
    var cancelResult: ReminderSchedulerResult = Canceled

    override fun schedule(time: ReminderTime): ReminderSchedulerResult {
        calls.add("schedule:${time.hour}:${time.minute}")
        return scheduleResult
    }

    override fun replace(time: ReminderTime): ReminderSchedulerResult {
        calls.add("replace:${time.hour}:${time.minute}")
        return replaceResult
    }

    override fun cancel(): ReminderSchedulerResult {
        calls.add("cancel")
        return cancelResult
    }
}
