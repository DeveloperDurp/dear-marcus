package com.dearmarcus.ui

import android.content.Intent
import android.net.Uri
import com.dearmarcus.export.JournalMarkdownDocument
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.CancellationException

const val JOURNAL_MARKDOWN_MIME_TYPE = "text/markdown"

internal data class PendingJournalMarkdownExport(
    val fileName: String?,
    val markdown: String?,
) {
    val document: JournalMarkdownDocument?
        get() = if (fileName == null || markdown == null) null else JournalMarkdownDocument(fileName, markdown)

    companion object {
        fun from(document: JournalMarkdownDocument): PendingJournalMarkdownExport = PendingJournalMarkdownExport(
            fileName = document.fileName,
            markdown = document.markdown,
        )
    }
}

fun createJournalMarkdownDocumentIntent(fileName: String): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = JOURNAL_MARKDOWN_MIME_TYPE
    putExtra(Intent.EXTRA_TITLE, fileName)
}

sealed interface JournalMarkdownSaveResult {
    data object Saved : JournalMarkdownSaveResult

    data object Cancelled : JournalMarkdownSaveResult

    data object Failed : JournalMarkdownSaveResult
}

fun saveJournalMarkdownDocument(
    uri: Uri?,
    document: JournalMarkdownDocument?,
    openOutputStream: (Uri) -> OutputStream?,
): JournalMarkdownSaveResult {
    if (uri == null || document == null) return JournalMarkdownSaveResult.Cancelled

    return try {
        val output = openOutputStream(uri) ?: return JournalMarkdownSaveResult.Failed
        OutputStreamWriter(output, Charsets.UTF_8).use { writer -> writer.write(document.markdown) }
        JournalMarkdownSaveResult.Saved
    } catch (_: IOException) {
        JournalMarkdownSaveResult.Failed
    } catch (_: SecurityException) {
        JournalMarkdownSaveResult.Failed
    } catch (error: RuntimeException) {
        if (error is CancellationException) throw error
        JournalMarkdownSaveResult.Failed
    }
}
