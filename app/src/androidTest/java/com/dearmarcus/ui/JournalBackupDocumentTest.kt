package com.dearmarcus.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dearmarcus.export.JournalBackupDocument
import com.dearmarcus.export.JournalBackupCodec
import com.dearmarcus.export.JournalBackupDecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class JournalBackupDocumentTest {
    @get:org.junit.Rule
    val composeRule = createComposeRule()

    private val document = JournalBackupDocument(
        fileName = "DearMarcus-backup-2026-08-01.json",
        content = "{\"format\":\"dear-marcus.local-backup\",\"version\":1,\"exportedAtEpochMillis\":0,\"entries\":[]}",
    )

    @Test
    fun createDocumentIntentUsesSafJsonContractAndRequestedFilename() {
        val intent = createJournalBackupDocumentIntent(document)

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals(document.mimeType, intent.type)
        assertEquals(document.fileName, intent.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(true, intent.categories?.contains(Intent.CATEGORY_OPENABLE))
    }

    @Test
    fun openDocumentIntentUsesReadOnlyOpenableJsonContract() {
        val intent = createJournalBackupOpenDocumentIntent()

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals(document.mimeType, intent.type)
        assertEquals(true, intent.categories?.contains(Intent.CATEGORY_OPENABLE))
        assertEquals(true, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun repeatedCancellationDoesNotOpenAStreamOrReportSuccess() {
        var openCalls = 0

        repeat(2) {
            val result = saveJournalBackupDocument(null, document) {
                openCalls += 1
                ByteArrayOutputStream()
            }

            assertEquals(JournalBackupSaveResult.Cancelled, result)
            assertFalse(result == JournalBackupSaveResult.Saved)
        }

        assertEquals(0, openCalls)
    }

    @Test
    fun utf8WriteSucceedsOnlyAfterTheStreamCompletesAndFlakyOutputFails() {
        val output = ByteArrayOutputStream()
        val saved = saveJournalBackupDocument(Uri.parse("content://test/saved"), document) { output }
        val failed = saveJournalBackupDocument(Uri.parse("content://test/failed"), document) { FailingOutputStream() }
        val malformedUri = saveJournalBackupDocument(Uri.parse("content://test/malformed"), document) {
            throw IllegalArgumentException("malformed URI")
        }

        assertEquals(JournalBackupSaveResult.Saved, saved)
        assertEquals(document.content, output.toString(Charsets.UTF_8.name()))
        assertEquals(JournalBackupSaveResult.Failed, failed)
        assertEquals(JournalBackupSaveResult.Failed, malformedUri)
    }

    @Test
    fun readCancellationUnreadableAndInvalidBackupDoNotProduceAnImportableBackup() {
        val cancelled = readJournalBackupDocument(null) { error("must not open") }
        val unreadable = readJournalBackupDocument(Uri.parse("content://test/unreadable")) {
            throw IOException("unreadable")
        }
        val malformed = readJournalBackupDocument(Uri.parse("content://test/malformed")) {
            "not json".byteInputStream()
        }
        val unsupportedVersion = readJournalBackupDocument(Uri.parse("content://test/version")) {
            document.content.replace("\"version\":1", "\"version\":2").byteInputStream()
        }

        assertEquals(JournalBackupReadResult.Cancelled, cancelled)
        assertEquals(JournalBackupReadResult.Failed, unreadable)
        check(malformed is JournalBackupReadResult.Read)
        check(unsupportedVersion is JournalBackupReadResult.Read)
        check(JournalBackupCodec().decode(malformed.content) is JournalBackupDecodeResult.Failure)
        check(JournalBackupCodec().decode(unsupportedVersion.content) is JournalBackupDecodeResult.Failure)
    }

    @Test
    fun preparedExportStateRestoresExactUtf8DocumentAfterActivityRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        var prepareExport: (() -> Unit)? = null
        var recreatedDocument: JournalBackupDocument? = null

        restorationTester.setContent {
            var fileName by rememberSaveable { mutableStateOf<String?>(null) }
            var content by rememberSaveable { mutableStateOf<String?>(null) }
            prepareExport = {
                val pending = PendingJournalBackupExport.from(document)
                fileName = pending.fileName
                content = pending.content
            }
            recreatedDocument = PendingJournalBackupExport(fileName, content).document
        }
        composeRule.runOnIdle { requireNotNull(prepareExport).invoke() }
        restorationTester.emulateSavedInstanceStateRestore()

        val output = ByteArrayOutputStream()

        assertNotNull(recreatedDocument)
        assertEquals(
            JournalBackupSaveResult.Saved,
            saveJournalBackupDocument(Uri.parse("content://test/recreated"), recreatedDocument) { output },
        )
        assertEquals(document.fileName, recreatedDocument?.fileName)
        assertEquals(document.content.toByteArray(Charsets.UTF_8).toList(), output.toByteArray().toList())
    }

    @Test
    fun callbackCancellationOnRecreationClearsExportingStateAfterInFlightExport() {
        val restorationTester = StateRestorationTester(composeRule)
        var prepareExport: (() -> Unit)? = null
        var completeActivityResult: (() -> Unit)? = null
        val exportBusy = AtomicBoolean(false)
        val savedFileName = AtomicReference<String?>(null)
        val savedContent = AtomicReference<String?>(null)

        restorationTester.setContent {
            var pendingJournalExportFileName by rememberSaveable { mutableStateOf<String?>(null) }
            var pendingJournalExportContent by rememberSaveable { mutableStateOf<String?>(null) }
            var isExportingJournal by rememberSaveable { mutableStateOf(false) }

            prepareExport = {
                val pending = PendingJournalBackupExport.from(document)
                pendingJournalExportFileName = pending.fileName
                pendingJournalExportContent = pending.content
                isExportingJournal = true
            }

            completeActivityResult = {
                isExportingJournal = false
                pendingJournalExportFileName = null
                pendingJournalExportContent = null
            }

            exportBusy.set(isExportingJournal)
            savedFileName.set(pendingJournalExportFileName)
            savedContent.set(pendingJournalExportContent)
        }

        composeRule.runOnIdle {
            prepareExport?.invoke()
        }

        composeRule.waitUntil { exportBusy.get() }
        assertEquals(document.fileName, savedFileName.get())
        assertEquals(document.content, savedContent.get())

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.waitForIdle()
        assertEquals(document.fileName, savedFileName.get())
        assertEquals(document.content, savedContent.get())

        composeRule.runOnIdle {
            completeActivityResult?.invoke()
        }

        composeRule.waitUntil { exportBusy.get().not() }
        assertEquals(null, savedFileName.get())
        assertEquals(null, savedContent.get())
    }

    @Test
    fun synchronousImportPickerLaunchFailureReturnsAFailedStatus() {
        // Given / When
        val status = launchImportDocument { throw IllegalStateException("launcher unavailable") }

        // Then
        assertTrue(status is SettingsBackupStatus.Failed)
    }

    private class FailingOutputStream : OutputStream() {
        override fun write(oneByte: Int) {
            throw IOException("unwritable")
        }
    }
}
