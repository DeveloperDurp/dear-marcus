package com.dearmarcus.reminder

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderManifestTest {
    @Test
    fun mergedManifest_declaresReminderSupportWithoutNetworkOrExactAlarmPermissions() {
        // Given
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()

        // When
        val alarmReceiver = context.packageManager.getReceiverInfo(
            ComponentName(context, ReminderAlarmReceiver::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        val bootReceiver = context.packageManager.getReceiverInfo(
            ComponentName(context, ReminderBootReceiver::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )

        // Then
        assertTrue(permissions.contains(Manifest.permission.POST_NOTIFICATIONS))
        assertTrue(permissions.contains(Manifest.permission.RECEIVE_BOOT_COMPLETED))
        assertFalse(permissions.contains(Manifest.permission.INTERNET))
        assertFalse(permissions.contains(Manifest.permission.SCHEDULE_EXACT_ALARM))
        assertFalse(alarmReceiver.exported)
        assertFalse(bootReceiver.exported)
    }

    @Test
    fun contextStoreUsesTheProvidedSchedulerFactoryForEnabledTimeChanges() {
        // Given
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("reminder-settings", 0).edit().clear().commit()
        val scheduler = RecordingScheduler()
        val store = ReminderSettingsStore.fromContext(context) { scheduler }

        // When
        store.setEnabled(true)
        store.setReminderTime(7, 15)

        // Then
        assertEquals(listOf("schedule:20:0", "replace:7:15"), scheduler.calls)
    }

    private class RecordingScheduler : ReminderScheduler {
        val calls = mutableListOf<String>()

        override fun schedule(time: ReminderTime): ReminderSchedulerResult {
            calls += "schedule:${time.hour}:${time.minute}"
            return ReminderSchedulerResult.Scheduled
        }

        override fun replace(time: ReminderTime): ReminderSchedulerResult {
            calls += "replace:${time.hour}:${time.minute}"
            return ReminderSchedulerResult.Replaced
        }

        override fun cancel(): ReminderSchedulerResult = ReminderSchedulerResult.Canceled
    }
}
