package com.dearmarcus.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dearmarcus.export.JournalMarkdownDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class DearMarcusDestination(val label: String) {
    DAILY("Daily"),
    HISTORY("History"),
    REVIEW("Review"),
}

@Composable
fun DearMarcusRoot(
    viewModel: DailyEntryViewModel,
    historyViewModel: HistoryViewModel,
    reviewViewModel: ReviewViewModel,
    createJournalExport: suspend () -> JournalMarkdownDocument,
) {
    var destination by rememberSaveable { mutableStateOf(DearMarcusDestination.DAILY) }
    var pendingJournalExportFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingJournalExportMarkdown by rememberSaveable { mutableStateOf<String?>(null) }
    var isExportingJournal by rememberSaveable { mutableStateOf(false) }
    var journalExportStatus by remember { mutableStateOf<String?>(null) }
    val pendingJournalExport = PendingJournalMarkdownExport(
        pendingJournalExportFileName,
        pendingJournalExportMarkdown,
    ).document
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val dailyState by viewModel.uiState.collectAsStateWithLifecycle()
    val historyState by historyViewModel.uiState.collectAsStateWithLifecycle()
    val reviewState by reviewViewModel.uiState.collectAsStateWithLifecycle()
    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val document = pendingJournalExport
        coroutineScope.launch {
            try {
                val saveResult = withContext(Dispatchers.IO) {
                    if (result.resultCode != Activity.RESULT_OK) {
                        JournalMarkdownSaveResult.Cancelled
                    } else {
                        saveJournalMarkdownDocument(result.data?.data, document) { uri ->
                            context.contentResolver.openOutputStream(uri)
                        }
                    }
                }
                journalExportStatus = when (saveResult) {
                    JournalMarkdownSaveResult.Saved -> "Markdown export saved."
                    JournalMarkdownSaveResult.Failed -> "Markdown export could not be saved."
                    JournalMarkdownSaveResult.Cancelled -> null
                }
            } finally {
                pendingJournalExportFileName = null
                pendingJournalExportMarkdown = null
                isExportingJournal = false
            }
        }
    }

    Scaffold(
        bottomBar = {
            TabRow(
                selectedTabIndex = destination.ordinal,
                modifier = Modifier
                    .navigationBarsPadding()
                    .testTag("bottom-tab-row"),
            ) {
                DearMarcusDestination.entries.forEach { item ->
                    Tab(
                        selected = destination == item,
                        onClick = {
                            destination = item
                            if (item == DearMarcusDestination.HISTORY) historyViewModel.reload()
                            if (item == DearMarcusDestination.REVIEW) reviewViewModel.reload()
                        },
                        text = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (destination) {
                DearMarcusDestination.DAILY -> DailyEntryScreen(
                    state = dailyState,
                    onAnswerChanged = viewModel::updateAnswer,
                    onSave = viewModel::submit,
                    onDownloadModel = viewModel::startModelDownload,
                    onRetryAi = viewModel::refreshAiReadiness,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp),
                )
                DearMarcusDestination.HISTORY -> HistoryScreen(
                    state = historyState,
                    onSelect = historyViewModel::select,
                    onCloseDetail = historyViewModel::closeDetail,
                    onBeginEdit = historyViewModel::beginEdit,
                    onEditChanged = historyViewModel::updateEdit,
                    onSaveEdit = historyViewModel::saveEdit,
                    onCancelEdit = historyViewModel::cancelEdit,
                    onRequestDelete = historyViewModel::requestDelete,
                    onRequestClearAll = historyViewModel::requestClearAll,
                    onDismissConfirmation = historyViewModel::dismissConfirmation,
                    onConfirmDestructiveAction = historyViewModel::confirmDestructiveAction,
                    onRefreshInsights = historyViewModel::refreshInsights,
                    onExportJournal = {
                        if (!isExportingJournal) {
                            isExportingJournal = true
                            journalExportStatus = null
                            coroutineScope.launch {
                                try {
                                    val document = createJournalExport()
                                    val pending = PendingJournalMarkdownExport.from(document)
                                    pendingJournalExportFileName = pending.fileName
                                    pendingJournalExportMarkdown = pending.markdown
                                    createDocumentLauncher.launch(
                                        createJournalMarkdownDocumentIntent(document.fileName),
                                    )
                                } catch (error: CancellationException) {
                                    pendingJournalExportFileName = null
                                    pendingJournalExportMarkdown = null
                                    isExportingJournal = false
                                    throw error
                                } catch (_: Exception) {
                                    pendingJournalExportFileName = null
                                    pendingJournalExportMarkdown = null
                                    isExportingJournal = false
                                    journalExportStatus = "Markdown export could not be prepared."
                                }
                            }
                        }
                    },
                    isExportingJournal = isExportingJournal,
                    journalExportStatus = journalExportStatus,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp),
                )
                DearMarcusDestination.REVIEW -> ReviewScreen(
                    state = reviewState,
                    onRefreshInsights = reviewViewModel::refreshInsights,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp),
                )
            }
        }
    }
}
