package com.dearmarcus.ui

import android.content.Intent
import android.net.Uri
import com.dearmarcus.export.JournalBackupDocument
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.CancellationException

internal data class PendingJournalBackupExport(
    val fileName: String?,
    val content: String?,
) {
    val document: JournalBackupDocument?
        get() = if (fileName == null || content == null) null else JournalBackupDocument(fileName, content)

    companion object {
        fun from(document: JournalBackupDocument): PendingJournalBackupExport =
            PendingJournalBackupExport(document.fileName, document.content)
    }
}

fun createJournalBackupDocumentIntent(document: JournalBackupDocument): Intent =
    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = document.mimeType
        putExtra(Intent.EXTRA_TITLE, document.fileName)
    }

fun createJournalBackupOpenDocumentIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = JournalBackupDocument.MIME_TYPE
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

sealed interface JournalBackupSaveResult {
    data object Saved : JournalBackupSaveResult
    data object Cancelled : JournalBackupSaveResult
    data object Failed : JournalBackupSaveResult
}

fun saveJournalBackupDocument(
    uri: Uri?,
    document: JournalBackupDocument?,
    openOutputStream: (Uri) -> OutputStream?,
): JournalBackupSaveResult {
    if (uri == null || document == null) return JournalBackupSaveResult.Cancelled

    return try {
        val output = openOutputStream(uri) ?: return JournalBackupSaveResult.Failed
        OutputStreamWriter(output, Charsets.UTF_8).use { writer -> writer.write(document.content) }
        JournalBackupSaveResult.Saved
    } catch (_: IOException) {
        JournalBackupSaveResult.Failed
    } catch (_: SecurityException) {
        JournalBackupSaveResult.Failed
    } catch (error: RuntimeException) {
        if (error is CancellationException) throw error
        JournalBackupSaveResult.Failed
    }
}

sealed interface JournalBackupReadResult {
    data object Cancelled : JournalBackupReadResult
    data class Read(val content: String) : JournalBackupReadResult
    data object Failed : JournalBackupReadResult
}

fun readJournalBackupDocument(
    uri: Uri?,
    openInputStream: (Uri) -> InputStream?,
): JournalBackupReadResult {
    if (uri == null) return JournalBackupReadResult.Cancelled

    return try {
        val input = openInputStream(uri) ?: return JournalBackupReadResult.Failed
        InputStreamReader(input, Charsets.UTF_8).use { reader ->
            JournalBackupReadResult.Read(reader.readText())
        }
    } catch (_: IOException) {
        JournalBackupReadResult.Failed
    } catch (_: SecurityException) {
        JournalBackupReadResult.Failed
    } catch (error: RuntimeException) {
        if (error is CancellationException) throw error
        JournalBackupReadResult.Failed
    }
}
