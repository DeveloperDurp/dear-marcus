package com.dearmarcus.reminder

data class ReminderTime(val hour: Int, val minute: Int) {
    init {
        require(hour in 0..23) { "Reminder hour must be between 0 and 23." }
        require(minute in 0..59) { "Reminder minute must be between 0 and 59." }
    }
}

data class ReminderSettings(
    val enabled: Boolean,
    val time: ReminderTime,
)

sealed interface ReminderSchedulerResult {
    data object Scheduled : ReminderSchedulerResult

    data object Replaced : ReminderSchedulerResult

    data object Canceled : ReminderSchedulerResult

    data object NoChange : ReminderSchedulerResult

    data class Failed(val reason: String) : ReminderSchedulerResult
}

interface ReminderScheduler {
    fun schedule(time: ReminderTime): ReminderSchedulerResult

    fun replace(time: ReminderTime): ReminderSchedulerResult

    fun cancel(): ReminderSchedulerResult
}

object ReminderDefaults {
    val TIME: ReminderTime = ReminderTime(hour = 20, minute = 0)
    const val ENABLED: Boolean = false
}
