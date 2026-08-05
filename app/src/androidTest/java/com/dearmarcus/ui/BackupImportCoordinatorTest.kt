package com.dearmarcus.ui

import android.app.Activity
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dearmarcus.data.JournalBackupImportSummary
import com.dearmarcus.export.JournalBackup
import com.dearmarcus.export.JournalBackupCodec
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupImportCoordinatorTest {
    @Test
    fun importsValidDocumentAndClearsBusyState() = runBlocking {
        // Given
        val coordinator = coordinator { JournalBackupImportSummary(2, 0, 1, 0) }

        // When
        coordinator.launch { }
        coordinator.complete(Activity.RESULT_OK, Uri.parse("content://backup/valid")) { validDocument().byteInputStream() }

        // Then
        assertEquals(
            SettingsBackupStatus.Succeeded("Imported 2, skipped 0; reflections imported 1, skipped 0."),
            coordinator.status,
        )
        assertFalse(coordinator.isImporting)
    }

    @Test
    fun importsDuplicateDocumentAndReportsTheDuplicateSummary() = runBlocking {
        // Given
        val coordinator = coordinator { JournalBackupImportSummary(0, 2, 0, 1) }

        // When
        coordinator.launch { }
        coordinator.complete(Activity.RESULT_OK, Uri.parse("content://backup/duplicate")) { validDocument().byteInputStream() }

        // Then
        assertEquals(
            SettingsBackupStatus.Succeeded("Imported 0, skipped 2; reflections imported 0, skipped 1."),
            coordinator.status,
        )
        assertFalse(coordinator.isImporting)
    }

    @Test
    fun cancellationAndNullDocumentClearBusyState() = runBlocking {
        // Given
        val coordinator = coordinator { error("cancelled documents must not import") }

        // When
        coordinator.launch { }
        coordinator.complete(Activity.RESULT_CANCELED, null) { error("cancelled documents must not open") }

        // Then
        assertEquals(SettingsBackupStatus.Cancelled, coordinator.status)
        assertFalse(coordinator.isImporting)
    }

    @Test
    fun nullUriAndReadFailureClearBusyState() = runBlocking {
        // Given
        val coordinator = coordinator { error("unreadable documents must not import") }

        // When
        coordinator.launch { }
        coordinator.complete(Activity.RESULT_OK, null) { error("null documents must not open") }

        // Then
        assertEquals(SettingsBackupStatus.Cancelled, coordinator.status)
        assertFalse(coordinator.isImporting)

        coordinator.launch { }
        coordinator.complete(Activity.RESULT_OK, Uri.parse("content://backup/unreadable")) {
            throw IOException("unreadable")
        }

        assertEquals(SettingsBackupStatus.Failed("Backup import could not be read."), coordinator.status)
        assertFalse(coordinator.isImporting)
    }

    @Test
    fun invalidDocumentAndImportFailureClearBusyState() = runBlocking {
        // Given
        val coordinator = coordinator { throw IllegalStateException("database unavailable") }

        // When
        coordinator.launch { }
        coordinator.complete(Activity.RESULT_OK, Uri.parse("content://backup/invalid")) { "not json".byteInputStream() }

        // Then
        assertEquals(SettingsBackupStatus.Failed("Backup import file is invalid."), coordinator.status)
        assertFalse(coordinator.isImporting)

        coordinator.launch { }
        coordinator.complete(Activity.RESULT_OK, Uri.parse("content://backup/failure")) { validDocument().byteInputStream() }

        assertEquals(SettingsBackupStatus.Failed("Backup import could not be completed."), coordinator.status)
        assertFalse(coordinator.isImporting)
    }

    @Test
    fun synchronousLaunchFailureClearsBusyState() {
        // Given
        val coordinator = coordinator { error("failed launches must not import") }

        // When
        coordinator.launch { throw IllegalStateException("launcher unavailable") }

        // Then
        assertEquals(SettingsBackupStatus.Failed("Backup import could not be started."), coordinator.status)
        assertFalse(coordinator.isImporting)
        assertTrue(coordinator.status is SettingsBackupStatus.Failed)
    }

    private fun coordinator(
        importBackup: suspend (JournalBackup) -> JournalBackupImportSummary,
    ) = BackupImportCoordinator(JournalBackupCodec()::decode, importBackup)

    private fun validDocument(): String = JournalBackupCodec().encode(
        JournalBackup(Instant.parse("2026-08-05T00:00:00Z"), emptyList()),
    ).content
}
