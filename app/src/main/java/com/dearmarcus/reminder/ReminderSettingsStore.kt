package com.dearmarcus.reminder

import android.content.Context
import android.content.SharedPreferences

private const val PREFERENCES_NAME = "reminder-settings"
private const val KEY_ENABLED = "reminder-enabled"
private const val KEY_HOUR = "reminder-hour"
private const val KEY_MINUTE = "reminder-minute"

class ReminderSettingsStore(
    private val persistence: ReminderSettingsPersistence,
    private val scheduler: ReminderScheduler,
) {
    fun settings(): ReminderSettings = ReminderSettings(
        enabled = persistence.readEnabled(),
        time = persistence.readTime(),
    )

    fun setEnabled(enabled: Boolean): ReminderSchedulerResult {
        val current = settings()
        if (current.enabled == enabled) return ReminderSchedulerResult.NoChange

        persistence.saveState(enabled = enabled, time = current.time)

        return if (enabled) {
            scheduler.schedule(current.time)
        } else {
            scheduler.cancel()
        }
    }

    fun setReminderTime(hour: Int, minute: Int): ReminderSchedulerResult {
        val current = settings()
        val nextTime = ReminderTime(hour, minute)
        if (current.time == nextTime) return ReminderSchedulerResult.NoChange

        persistence.saveState(enabled = current.enabled, time = nextTime)

        return if (current.enabled) {
            scheduler.replace(nextTime)
        } else {
            ReminderSchedulerResult.NoChange
        }
    }

    companion object {
        fun fromContext(
            context: Context,
            schedulerFactory: (Context) -> ReminderScheduler = ::androidReminderScheduler,
        ): ReminderSettingsStore = ReminderSettingsStore(
            SharedPreferencesReminderSettingsPersistence(
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            ),
            schedulerFactory(context),
        )
    }
}

interface ReminderSettingsPersistence {
    fun readEnabled(): Boolean

    fun readTime(): ReminderTime

    fun saveState(enabled: Boolean, time: ReminderTime)
}

class SharedPreferencesReminderSettingsPersistence(
    private val preferences: SharedPreferences,
) : ReminderSettingsPersistence {
    override fun readEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, ReminderDefaults.ENABLED)

    override fun readTime(): ReminderTime = ReminderTime(
        hour = preferences.getInt(KEY_HOUR, ReminderDefaults.TIME.hour),
        minute = preferences.getInt(KEY_MINUTE, ReminderDefaults.TIME.minute),
    )

    override fun saveState(enabled: Boolean, time: ReminderTime) {
        preferences
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_HOUR, time.hour)
            .putInt(KEY_MINUTE, time.minute)
            .apply()
    }
}
