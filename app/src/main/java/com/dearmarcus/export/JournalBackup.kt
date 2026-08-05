package com.dearmarcus.export

import com.dearmarcus.data.JournalEntryRecord
import com.dearmarcus.data.ReflectionRecord
import java.time.Instant

data class JournalBackup(
    val exportedAt: Instant,
    val entries: List<JournalBackupEntry>,
) {
    val activeReflection: ReflectionRecord?
        get() = entries.lastOrNull { it.reflection != null }?.reflection

    val activeMemory: String
        get() = activeReflection?.memoryAfter.orEmpty()
}

data class JournalBackupEntry(
    val entry: JournalEntryRecord,
    val reflection: ReflectionRecord?,
)

data class JournalBackupDocument(
    val fileName: String,
    val content: String,
    val mimeType: String = MIME_TYPE,
) {
    companion object {
        const val MIME_TYPE = "application/json"
    }
}

sealed interface JournalBackupDecodeResult {
    data class Success(val backup: JournalBackup) : JournalBackupDecodeResult

    data class Failure(val failure: JournalBackupDecodeFailure) : JournalBackupDecodeResult
}

sealed interface JournalBackupDecodeFailure {
    data object BlankPayload : JournalBackupDecodeFailure

    data object MalformedJson : JournalBackupDecodeFailure

    data class UnsupportedVersion(val version: Int) : JournalBackupDecodeFailure

    data class MissingRequiredField(val path: String) : JournalBackupDecodeFailure

    data class UnknownField(val path: String, val field: String) : JournalBackupDecodeFailure

    data class InvalidBackup(val path: String) : JournalBackupDecodeFailure

    data class InvalidReflection(val entryId: String) : JournalBackupDecodeFailure
}
