package com.dearmarcus.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dearmarcus.data.JournalBackupImportSummary
import com.dearmarcus.export.JournalBackup
import com.dearmarcus.export.JournalBackupDecodeResult
import com.dearmarcus.export.JournalBackupDocument
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class BackupDocumentActions(
    val exportStatus: SettingsBackupStatus,
    val importStatus: SettingsBackupStatus,
    val export: () -> Unit,
    val import: () -> Unit,
)

internal class BackupImportCoordinator(
    private val decode: (String) -> JournalBackupDecodeResult,
    private val importBackup: suspend (JournalBackup) -> JournalBackupImportSummary,
) {
    var status by mutableStateOf<SettingsBackupStatus>(SettingsBackupStatus.Idle)
        private set
    var isImporting by mutableStateOf(false)
        private set

    fun launch(launch: () -> Unit) {
        if (isImporting) return

        isImporting = true
        status = SettingsBackupStatus.Working
        launchImportDocument(launch)?.let { failure ->
            isImporting = false
            status = failure
        }
    }

    suspend fun complete(
        resultCode: Int,
        uri: Uri?,
        openInputStream: (Uri) -> InputStream?,
    ) {
        try {
            status = withContext(Dispatchers.IO) {
                if (resultCode != Activity.RESULT_OK) return@withContext SettingsBackupStatus.Cancelled
                when (val read = readJournalBackupDocument(uri, openInputStream)) {
                    JournalBackupReadResult.Cancelled -> SettingsBackupStatus.Cancelled
                    JournalBackupReadResult.Failed -> SettingsBackupStatus.Failed("Backup import could not be read.")
                    is JournalBackupReadResult.Read -> when (val decoded = decode(read.content)) {
                        is JournalBackupDecodeResult.Failure -> SettingsBackupStatus.Failed("Backup import file is invalid.")
                        is JournalBackupDecodeResult.Success -> try {
                            SettingsBackupStatus.Succeeded(importMessage(importBackup(decoded.backup)))
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            SettingsBackupStatus.Failed("Backup import could not be completed.")
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            status = SettingsBackupStatus.Failed("Backup import could not be completed.")
        } finally {
            isImporting = false
        }
    }
}

@Composable
internal fun rememberBackupDocumentActions(
    createDocument: suspend () -> JournalBackupDocument,
    decode: (String) -> JournalBackupDecodeResult,
    importBackup: suspend (JournalBackup) -> JournalBackupImportSummary,
): BackupDocumentActions {
    var pendingFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingContent by rememberSaveable { mutableStateOf<String?>(null) }
    var isExporting by rememberSaveable { mutableStateOf(false) }
    var isImporting by rememberSaveable { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<SettingsBackupStatus>(SettingsBackupStatus.Idle) }
    val importCoordinator = remember { BackupImportCoordinator(decode, importBackup) }
    val pendingDocument = PendingJournalBackupExport(pendingFileName, pendingContent).document
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val document = pendingDocument
        scope.launch {
            try {
                exportStatus = withContext(Dispatchers.IO) {
                    when {
                        result.resultCode != Activity.RESULT_OK -> SettingsBackupStatus.Cancelled
                        saveJournalBackupDocument(result.data?.data, document) {
                            uri -> context.contentResolver.openOutputStream(uri)
                        } == JournalBackupSaveResult.Saved -> SettingsBackupStatus.Succeeded("Backup export saved.")
                        else -> SettingsBackupStatus.Failed("Backup export could not be saved.")
                    }
                }
            } finally {
                pendingFileName = null
                pendingContent = null
                isExporting = false
            }
        }
    }
    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        scope.launch {
            importCoordinator.complete(result.resultCode, result.data?.data) { uri ->
                context.contentResolver.openInputStream(uri)
            }
            isImporting = importCoordinator.isImporting
        }
    }

    val export = {
        if (!isExporting) {
            isExporting = true
            exportStatus = SettingsBackupStatus.Working
            scope.launch {
                try {
                    val document = createDocument()
                    val pending = PendingJournalBackupExport.from(document)
                    pendingFileName = pending.fileName
                    pendingContent = pending.content
                    createDocumentLauncher.launch(createJournalBackupDocumentIntent(document))
                } catch (error: CancellationException) {
                    pendingFileName = null
                    pendingContent = null
                    isExporting = false
                    throw error
                } catch (_: Exception) {
                    pendingFileName = null
                    pendingContent = null
                    isExporting = false
                    exportStatus = SettingsBackupStatus.Failed("Backup export could not be prepared.")
                }
            }
        }
    }
    val import = {
        if (!isImporting) {
            importCoordinator.launch {
                openDocumentLauncher.launch(createJournalBackupOpenDocumentIntent())
            }
            isImporting = importCoordinator.isImporting
        }
    }

    return BackupDocumentActions(exportStatus, importCoordinator.status, export, import)
}

internal fun launchImportDocument(launch: () -> Unit): SettingsBackupStatus? = try {
    launch()
    null
} catch (_: Exception) {
    SettingsBackupStatus.Failed("Backup import could not be started.")
}

private fun importMessage(summary: JournalBackupImportSummary): String =
    "Imported ${summary.importedEntries}, skipped ${summary.skippedEntries}; " +
        "reflections imported ${summary.importedReflections}, skipped ${summary.skippedReflections}."
