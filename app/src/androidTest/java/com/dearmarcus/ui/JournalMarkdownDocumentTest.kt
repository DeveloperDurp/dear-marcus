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
import com.dearmarcus.export.JournalMarkdownDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class JournalMarkdownDocumentTest {
    @get:org.junit.Rule
    val composeRule = createComposeRule()

    private val document = JournalMarkdownDocument(
        fileName = "DearMarcus-2026-08-01.md",
        markdown = "# Dear Marcus\n\nCafé\n",
    )

    @Test
    fun createDocumentIntentUsesSafMarkdownContractAndRequestedFilename() {
        val intent = createJournalMarkdownDocumentIntent(document.fileName)

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals(JOURNAL_MARKDOWN_MIME_TYPE, intent.type)
        assertEquals(document.fileName, intent.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(true, intent.categories?.contains(Intent.CATEGORY_OPENABLE))
    }

    @Test
    fun repeatedCancellationDoesNotOpenAStreamOrReportSuccess() {
        var openCalls = 0

        repeat(2) {
            val result = saveJournalMarkdownDocument(null, document) {
                openCalls += 1
                ByteArrayOutputStream()
            }

            assertEquals(JournalMarkdownSaveResult.Cancelled, result)
            assertFalse(result == JournalMarkdownSaveResult.Saved)
        }

        assertEquals(0, openCalls)
    }

    @Test
    fun utf8WriteSucceedsOnlyAfterTheStreamCompletesAndFlakyOutputFails() {
        val output = ByteArrayOutputStream()
        val saved = saveJournalMarkdownDocument(Uri.parse("content://test/saved"), document) { output }
        val failed = saveJournalMarkdownDocument(Uri.parse("content://test/failed"), document) { FailingOutputStream() }
        val malformedUri = saveJournalMarkdownDocument(Uri.parse("content://test/malformed"), document) {
            throw IllegalArgumentException("malformed URI")
        }

        assertEquals(JournalMarkdownSaveResult.Saved, saved)
        assertEquals(document.markdown, output.toString(Charsets.UTF_8.name()))
        assertEquals(JournalMarkdownSaveResult.Failed, failed)
        assertEquals(JournalMarkdownSaveResult.Failed, malformedUri)
    }

    @Test
    fun preparedExportStateRestoresExactUtf8DocumentAfterActivityRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        var prepareExport: (() -> Unit)? = null
        var recreatedDocument: JournalMarkdownDocument? = null

        restorationTester.setContent {
            var fileName by rememberSaveable { mutableStateOf<String?>(null) }
            var markdown by rememberSaveable { mutableStateOf<String?>(null) }
            prepareExport = {
                val pending = PendingJournalMarkdownExport.from(document)
                fileName = pending.fileName
                markdown = pending.markdown
            }
            recreatedDocument = PendingJournalMarkdownExport(fileName, markdown).document
        }
        composeRule.runOnIdle { requireNotNull(prepareExport).invoke() }
        restorationTester.emulateSavedInstanceStateRestore()

        val output = ByteArrayOutputStream()

        assertNotNull(recreatedDocument)
        assertEquals(
            JournalMarkdownSaveResult.Saved,
            saveJournalMarkdownDocument(Uri.parse("content://test/recreated"), recreatedDocument) { output },
        )
        assertEquals(document.fileName, recreatedDocument?.fileName)
        assertEquals(document.markdown.toByteArray(Charsets.UTF_8).toList(), output.toByteArray().toList())
    }

    @Test
    fun callbackCancellationOnRecreationClearsExportingStateAfterInFlightExport() {
        val restorationTester = StateRestorationTester(composeRule)
        var prepareExport: (() -> Unit)? = null
        var completeActivityResult: (() -> Unit)? = null
        val exportBusy = AtomicBoolean(false)
        val savedFileName = AtomicReference<String?>(null)
        val savedMarkdown = AtomicReference<String?>(null)

        restorationTester.setContent {
            var pendingJournalExportFileName by rememberSaveable { mutableStateOf<String?>(null) }
            var pendingJournalExportMarkdown by rememberSaveable { mutableStateOf<String?>(null) }
            var isExportingJournal by rememberSaveable { mutableStateOf(false) }

            prepareExport = {
                val pending = PendingJournalMarkdownExport.from(document)
                pendingJournalExportFileName = pending.fileName
                pendingJournalExportMarkdown = pending.markdown
                isExportingJournal = true
            }

            completeActivityResult = {
                isExportingJournal = false
                pendingJournalExportFileName = null
                pendingJournalExportMarkdown = null
            }

            exportBusy.set(isExportingJournal)
            savedFileName.set(pendingJournalExportFileName)
            savedMarkdown.set(pendingJournalExportMarkdown)
        }

        composeRule.runOnIdle {
            prepareExport?.invoke()
        }

        composeRule.waitUntil { exportBusy.get() }
        assertEquals(document.fileName, savedFileName.get())
        assertEquals(document.markdown, savedMarkdown.get())

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.waitForIdle()
        assertEquals(document.fileName, savedFileName.get())
        assertEquals(document.markdown, savedMarkdown.get())

        composeRule.runOnIdle {
            completeActivityResult?.invoke()
        }

        composeRule.waitUntil { exportBusy.get().not() }
        assertEquals(null, savedFileName.get())
        assertEquals(null, savedMarkdown.get())
    }

    private class FailingOutputStream : OutputStream() {
        override fun write(oneByte: Int) {
            throw IOException("unwritable")
        }
    }
}
