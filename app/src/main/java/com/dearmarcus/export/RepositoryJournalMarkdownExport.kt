package com.dearmarcus.export

import com.dearmarcus.core.AiStatus
import com.dearmarcus.core.Reflection
import com.dearmarcus.data.JournalRepository
import com.dearmarcus.data.ReflectionRecord
import java.time.Instant
import java.time.ZoneId

class RepositoryJournalMarkdownExport(
    private val repository: JournalRepository,
    private val exporter: JournalMarkdownExporter,
    private val clock: () -> Instant,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun createDocument(): JournalMarkdownDocument {
        val snapshot = repository.exportSnapshot()
        val feedbackByEntryId = snapshot.reflectionsByEntryId.mapValues { (_, reflection) ->
            reflection.takeIf(ReflectionRecord::isExportable)?.feedback
        }
        val currentMemory = snapshot.activeReflection
            ?.takeIf(ReflectionRecord::isExportable)
            ?.memoryAfter

        return exporter.export(
            JournalMarkdownJournal(
                exportedAt = clock(),
                zoneId = zoneId,
                currentMemory = currentMemory,
                entries = snapshot.entries.map { entry ->
                    JournalMarkdownEntry(
                        id = entry.id,
                        localDateTime = entry.localDateTime,
                        wentWell = entry.wentWell,
                        wentPoorly = entry.wentPoorly,
                        doDifferently = entry.doDifferently,
                        feedback = feedbackByEntryId[entry.id],
                    )
                },
            ),
        )
    }
}

private fun ReflectionRecord.isExportable(): Boolean =
    isValid &&
        aiStatus == AiStatus.AVAILABLE.name &&
        feedback.isNotBlank() &&
        memoryAfter.isNotBlank() &&
        feedback.codePointCount(0, feedback.length) <= Reflection.MAXIMUM_FEEDBACK_CODE_POINTS &&
        memoryAfter.codePointCount(0, memoryAfter.length) <= Reflection.MAXIMUM_MEMORY_CODE_POINTS
