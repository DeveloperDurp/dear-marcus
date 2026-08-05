package com.dearmarcus

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dearmarcus.ai.MlKitJournalAiClient
import com.dearmarcus.ai.OnDeviceAiClient
import com.dearmarcus.ai.OnDeviceJournalAiClient
import com.dearmarcus.core.JournalClock
import com.dearmarcus.core.JournalIdGenerator
import com.dearmarcus.core.ReflectionGenerator
import com.dearmarcus.core.RefreshInsights
import com.dearmarcus.core.RoomJournalInsightStore
import com.dearmarcus.core.SubmitJournal
import com.dearmarcus.core.TokenCounter
import com.dearmarcus.core.TokenCounterUnavailableException
import com.dearmarcus.data.JournalDatabase
import com.dearmarcus.data.JournalRepository
import com.dearmarcus.export.JournalBackupCodec
import com.dearmarcus.export.RepositoryJournalBackupExport
import com.dearmarcus.reminder.ReminderRecovery
import com.dearmarcus.reminder.ReminderSettings
import com.dearmarcus.reminder.ReminderSettingsStore
import com.dearmarcus.reminder.ReminderSchedulerResult
import com.dearmarcus.ui.DearMarcusRoot
import com.dearmarcus.ui.DailyEntryViewModel
import com.dearmarcus.ui.HistoryViewModel
import com.dearmarcus.ui.RepositoryHistoryDataSource
import com.dearmarcus.ui.RepositoryReviewDataSource
import com.dearmarcus.ui.ReviewViewModel
import com.dearmarcus.ui.SettingsUiState
import com.dearmarcus.ui.SubmitJournalDailyJournalSubmitter
import com.dearmarcus.ui.theme.DearMarcusTheme
import java.time.Instant
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val reminderSettingsStore by lazy { ReminderSettingsStore.fromContext(applicationContext) }
    private var reminderSettings by mutableStateOf<ReminderSettings?>(null)
    private var isReminderPermissionDenied by mutableStateOf(false)
    private var reminderStatusMessage by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onNotificationPermissionResult(granted) }

    internal fun onNotificationPermissionResult(granted: Boolean) {
        isReminderPermissionDenied = !granted
        if (granted) updateReminderEnabled(true)
    }

    private val aiClient: MlKitJournalAiClient by lazy { MlKitJournalAiClient() }
    private val database by lazy { JournalDatabase.create(applicationContext) }
    private val repository by lazy { JournalRepository(database) }
    private val store by lazy { RoomJournalInsightStore(repository) }
    private val clock = JournalClock { Instant.now() }
    private val reflectionGenerator by lazy {
        ReflectionGenerator(
            OnDeviceJournalAiClient(aiClient),
            TokenCounter { prompt ->
                aiClient.countInputTokens(prompt) ?: throw TokenCounterUnavailableException()
            },
        )
    }
    private val refreshInsights by lazy { RefreshInsights(store, reflectionGenerator, clock)::refresh }

    private val submitter by lazy {
        SubmitJournalDailyJournalSubmitter(
            SubmitJournal(
                store = store,
                reflectionGenerator = reflectionGenerator,
                idGenerator = JournalIdGenerator { UUID.randomUUID().toString() },
                clock = clock,
            ),
        )
    }

    private val dailyEntryViewModel by lazy {
        ViewModelProvider(this, DailyEntryViewModelFactory(submitter, aiClient, this))[DailyEntryViewModel::class.java]
    }

    private val historyViewModel by lazy {
        ViewModelProvider(this, HistoryViewModelFactory(
            RepositoryHistoryDataSource(
                repository = repository,
                clock = clock::now,
                localDataCleaner = {
                    withContext(Dispatchers.IO) {
                        clearDirectoryContents(cacheDir)
                        clearDirectoryContents(File(applicationInfo.dataDir, "shared_prefs"))
                    }
                },
            ),
        ))[HistoryViewModel::class.java]
    }

    private val reviewViewModel by lazy {
        ViewModelProvider(this, ReviewViewModelFactory(
            RepositoryReviewDataSource(
                repository = repository,
                refresh = refreshInsights,
            ),
        ))[ReviewViewModel::class.java]
    }

    private val journalBackupCodec by lazy { JournalBackupCodec() }
    private val journalBackupExport by lazy {
        RepositoryJournalBackupExport(repository, journalBackupCodec, clock::now)
    }

    private fun clearDirectoryContents(directory: File) {
        directory.listFiles()?.forEach { child ->
            check(child.deleteRecursively()) { "Could not clear local data." }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderRecovery.recover(applicationContext)
        refreshReminderState()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        setContent {
            DearMarcusTheme {
                DearMarcusRoot(
                    dailyEntryViewModel,
                    historyViewModel,
                    reviewViewModel,
                    journalBackupExport::createDocument,
                    journalBackupCodec::decode,
                    repository::importBackup,
                    SettingsUiState(
                        reminderSettings = requireNotNull(reminderSettings),
                        isReminderPermissionDenied = isReminderPermissionDenied,
                        reminderStatusMessage = reminderStatusMessage,
                    ),
                    ::onReminderEnabledChanged,
                    ::onReminderTimeClick,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshReminderState()
    }

    private fun onReminderEnabledChanged(enabled: Boolean) {
        if (enabled && requiresNotificationPermission() && !hasNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        updateReminderEnabled(enabled)
    }

    private fun updateReminderEnabled(enabled: Boolean) {
        updateReminderStatus(reminderSettingsStore.setEnabled(enabled))
        reminderSettings = reminderSettingsStore.settings()
    }

    private fun onReminderTimeClick() {
        val time = reminderSettingsStore.settings().time
        TimePickerDialog(
            this,
            { _, hour, minute -> onReminderTimeSelected(hour, minute) },
            time.hour,
            time.minute,
            DateFormat.is24HourFormat(this),
        ).show()
    }

    internal fun onReminderTimeSelected(hour: Int, minute: Int) {
        updateReminderStatus(reminderSettingsStore.setReminderTime(hour, minute))
        refreshReminderState()
    }

    private fun updateReminderStatus(result: ReminderSchedulerResult) {
        reminderStatusMessage = when (result) {
            is ReminderSchedulerResult.Failed -> result.reason
            else -> null
        }
    }

    private fun refreshReminderState() {
        reminderSettings = reminderSettingsStore.settings()
        isReminderPermissionDenied = requiresNotificationPermission() && !hasNotificationPermission()
    }

    private fun requiresNotificationPermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private fun hasNotificationPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    private class DailyEntryViewModelFactory(
        private val submitter: SubmitJournalDailyJournalSubmitter,
        private val aiClient: OnDeviceAiClient,
        activity: ComponentActivity,
    ) : AbstractSavedStateViewModelFactory(activity, null) {
        override fun <T : ViewModel> create(
            key: String,
            modelClass: Class<T>,
            handle: SavedStateHandle,
        ): T {
            require(modelClass == DailyEntryViewModel::class.java)
            return modelClass.cast(DailyEntryViewModel(handle, submitter, aiClient))
        }
    }

    private class HistoryViewModelFactory(
        private val dataSource: RepositoryHistoryDataSource,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == HistoryViewModel::class.java)
            return modelClass.cast(HistoryViewModel(dataSource))
        }
    }

    private class ReviewViewModelFactory(
        private val dataSource: RepositoryReviewDataSource,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == ReviewViewModel::class.java)
            return modelClass.cast(ReviewViewModel(dataSource))
        }
    }
}
