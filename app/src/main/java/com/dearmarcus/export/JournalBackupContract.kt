package com.dearmarcus.export

import com.dearmarcus.core.AiStatus
import com.dearmarcus.core.JournalAnswers
import com.dearmarcus.core.Reflection
import com.dearmarcus.data.JournalEntryRecord
import com.dearmarcus.data.ReflectionRecord
import java.time.ZoneOffset

internal object JournalBackupContract {
    const val FORMAT = "dear-marcus.local-backup"
    const val VERSION = 1
    private const val MAXIMUM_ENTRY_ID_CODE_POINTS = 128

    fun fileName(backup: JournalBackup): String =
        "DearMarcus-backup-${backup.exportedAt.atZone(ZoneOffset.UTC).toLocalDate()}.json"

    fun canonicalize(backup: JournalBackup): JournalBackup = backup.copy(
        entries = backup.entries.sortedWith(
            compareBy<JournalBackupEntry> { it.entry.localDateTime }.thenBy { it.entry.id },
        ),
    )

    fun validationFailure(
        backup: JournalBackup,
        requireCanonicalOrder: Boolean,
    ): JournalBackupDecodeFailure? {
        val orderedEntries = backup.entries.sortedWith(
            compareBy<JournalBackupEntry> { it.entry.localDateTime }.thenBy { it.entry.id },
        )
        if (requireCanonicalOrder && backup.entries != orderedEntries) {
            return JournalBackupDecodeFailure.InvalidBackup("entries")
        }

        val entryIds = mutableSetOf<String>()
        var chronology = JournalBackupChronology()
        var reflectionGapFound = false
        for ((index, backupEntry) in backup.entries.withIndex()) {
            val entry = backupEntry.entry
            if (!entryIds.add(entry.id)) return JournalBackupDecodeFailure.InvalidBackup("entries[$index].id")
            if (!entry.isValid()) return JournalBackupDecodeFailure.InvalidBackup("entries[$index]")

            val reflection = backupEntry.reflection
            if (reflection == null) {
                reflectionGapFound = true
                continue
            }
            if (
                reflectionGapFound ||
                !reflection.isValidFor(entry.id, chronology)
            ) {
                return JournalBackupDecodeFailure.InvalidReflection(entry.id)
            }
            chronology = chronology.advancedBy(reflection)
        }
        return null
    }

    fun reflectionIsValidForExport(
        reflection: ReflectionRecord,
        entryId: String,
        chronology: JournalBackupChronology,
    ): Boolean = reflection.isValidFor(entryId, chronology)

    private fun JournalEntryRecord.isValid(): Boolean =
        id.isRequired(MAXIMUM_ENTRY_ID_CODE_POINTS) &&
            wentWell.isRequired(JournalAnswers.MAXIMUM_CODE_POINTS) &&
            wentPoorly.isRequired(JournalAnswers.MAXIMUM_CODE_POINTS) &&
            doDifferently.isRequired(JournalAnswers.MAXIMUM_CODE_POINTS)

    private fun ReflectionRecord.isValidFor(
        expectedEntryId: String,
        chronology: JournalBackupChronology,
    ): Boolean =
        entryId == expectedEntryId &&
            isValid &&
            invalidationReason == null &&
            aiStatus == AiStatus.AVAILABLE.name &&
            feedback.isRequired(Reflection.MAXIMUM_FEEDBACK_CODE_POINTS) &&
            memoryBefore.isBounded(Reflection.MAXIMUM_MEMORY_CODE_POINTS) &&
            memoryAfter.isRequired(Reflection.MAXIMUM_MEMORY_CODE_POINTS) &&
            memoryBefore == chronology.memoryBefore &&
            memoryRevision > 0 &&
            (chronology.previousRevision == null || memoryRevision > chronology.previousRevision)

    private fun String.isRequired(maximumCodePoints: Int): Boolean =
        isNotBlank() && isBounded(maximumCodePoints)

    private fun String.isBounded(maximumCodePoints: Int): Boolean {
        var index = 0
        var codePoints = 0
        while (index < length) {
            val character = this[index]
            if (Character.isHighSurrogate(character)) {
                if (index + 1 == length || !Character.isLowSurrogate(this[index + 1])) return false
                index += 2
            } else {
                if (Character.isLowSurrogate(character)) return false
                index += 1
            }
            codePoints += 1
        }
        return codePoints <= maximumCodePoints
    }
}

internal data class JournalBackupChronology(
    val memoryBefore: String = "",
    val previousRevision: Int? = null,
) {
    fun advancedBy(reflection: ReflectionRecord): JournalBackupChronology = JournalBackupChronology(
        memoryBefore = reflection.memoryAfter,
        previousRevision = reflection.memoryRevision,
    )
}
