package com.dearmarcus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dearmarcus.data.JournalBackupImportSummary
import com.dearmarcus.export.JournalBackup
import com.dearmarcus.export.JournalBackupDecodeResult
import com.dearmarcus.export.JournalBackupDocument

private enum class DearMarcusDestination(val label: String) {
    DAILY("Daily"),
    HISTORY("History"),
    REVIEW("Review"),
    SETTINGS("Settings"),
}

@Composable
fun DearMarcusRoot(
    viewModel: DailyEntryViewModel,
    historyViewModel: HistoryViewModel,
    reviewViewModel: ReviewViewModel,
    createBackupDocument: suspend () -> JournalBackupDocument,
    decodeBackup: (String) -> JournalBackupDecodeResult,
    importBackup: suspend (JournalBackup) -> JournalBackupImportSummary,
    settingsState: SettingsUiState,
    onReminderEnabledChanged: (Boolean) -> Unit,
    onReminderTimeClick: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(DearMarcusDestination.DAILY) }
    var acceptedDestination by rememberSaveable { mutableStateOf(destination) }
    val backupActions = rememberBackupDocumentActions(createBackupDocument, decodeBackup, importBackup)
    val dailyState by viewModel.uiState.collectAsStateWithLifecycle()
    val historyState by historyViewModel.uiState.collectAsStateWithLifecycle()
    val reviewState by reviewViewModel.uiState.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(
        initialPage = destination.ordinal,
        pageCount = { DearMarcusDestination.entries.size },
    )
    var pendingDestination by remember { mutableStateOf<DearMarcusDestination?>(destination) }
    val onDestinationSettled = destinationTransition@{ item: DearMarcusDestination ->
        if (item == acceptedDestination) return@destinationTransition

        acceptedDestination = item
        when (item) {
            DearMarcusDestination.HISTORY -> historyViewModel.reload()
            DearMarcusDestination.REVIEW -> reviewViewModel.reload()
            else -> Unit
        }
    }
    val onDestinationRequested = destinationTransition@{ item: DearMarcusDestination ->
        if (item == destination) return@destinationTransition

        destination = item
        pendingDestination = item
    }
    LaunchedEffect(pendingDestination) {
        val target = pendingDestination ?: return@LaunchedEffect
        if (pagerState.settledPage == target.ordinal) {
            onDestinationSettled(target)
            if (pendingDestination == target) pendingDestination = null
            return@LaunchedEffect
        }
        try {
            pagerState.animateScrollToPage(target.ordinal)
        } finally {
            if (pendingDestination == target && pagerState.settledPage != target.ordinal) {
                pendingDestination = null
            }
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val item = DearMarcusDestination.entries[page]
            when (pendingDestination) {
                null -> {
                    destination = item
                    onDestinationSettled(item)
                }
                item -> {
                    onDestinationSettled(item)
                    pendingDestination = null
                }
                else -> Unit
            }
        }
    }
    BackHandler(
        enabled = destination == DearMarcusDestination.HISTORY && historyState.selectedEntry != null,
        onBack = historyViewModel::closeDetail,
    )
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
                            onDestinationRequested(item)
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
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                Box(
                    contentAlignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when (DearMarcusDestination.entries[page]) {
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 600.dp),
                        )
                        DearMarcusDestination.REVIEW -> ReviewScreen(
                            state = reviewState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 600.dp),
                        )
                        DearMarcusDestination.SETTINGS -> SettingsScreen(
                            dailyState = dailyState,
                            state = settingsState.copy(
                                exportStatus = backupActions.exportStatus,
                                importStatus = backupActions.importStatus,
                                needsInsightsRefresh = reviewState.needsRefresh,
                                isRefreshingInsights = reviewState.isWorking,
                                insightsRefreshStatusMessage = reviewState.statusMessage,
                            ),
                            onDownloadModel = viewModel::startModelDownload,
                            onRetryAi = viewModel::refreshAiReadiness,
                            onRefreshInsights = reviewViewModel::refreshInsights,
                            onReminderEnabledChanged = onReminderEnabledChanged,
                            onReminderTimeClick = onReminderTimeClick,
                            onExportBackup = backupActions.export,
                            onImportBackup = backupActions.import,
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 600.dp),
                        )
                    }
                }
            }
        }
    }
}
