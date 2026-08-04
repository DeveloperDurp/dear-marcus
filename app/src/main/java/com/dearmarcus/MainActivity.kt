package com.dearmarcus

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.dearmarcus.export.JournalMarkdownExporter
import com.dearmarcus.export.RepositoryJournalMarkdownExport
import com.dearmarcus.ui.DearMarcusRoot
import com.dearmarcus.ui.DailyEntryViewModel
import com.dearmarcus.ui.HistoryViewModel
import com.dearmarcus.ui.RepositoryHistoryDataSource
import com.dearmarcus.ui.RepositoryReviewDataSource
import com.dearmarcus.ui.ReviewViewModel
import com.dearmarcus.ui.SubmitJournalDailyJournalSubmitter
import com.dearmarcus.ui.theme.DearMarcusTheme
import java.time.Instant
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
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
                refresh = RefreshInsights(store, reflectionGenerator, clock)::refresh,
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
                refresh = RefreshInsights(store, reflectionGenerator, clock)::refresh,
            ),
        ))[ReviewViewModel::class.java]
    }

    private val journalMarkdownExport by lazy {
        RepositoryJournalMarkdownExport(repository, JournalMarkdownExporter(), clock::now)
    }

    private fun clearDirectoryContents(directory: File) {
        directory.listFiles()?.forEach { child ->
            check(child.deleteRecursively()) { "Could not clear local data." }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        setContent {
            DearMarcusTheme {
                DearMarcusRoot(
                    dailyEntryViewModel,
                    historyViewModel,
                    reviewViewModel,
                    journalMarkdownExport::createDocument,
                )
            }
        }
    }

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
