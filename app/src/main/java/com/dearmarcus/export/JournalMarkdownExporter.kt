package com.dearmarcus.export

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class JournalMarkdownJournal(
    val exportedAt: Instant,
    val zoneId: ZoneId,
    val currentMemory: String?,
    val entries: List<JournalMarkdownEntry>,
)

data class JournalMarkdownEntry(
    val id: String,
    val localDateTime: LocalDateTime,
    val wentWell: String,
    val wentPoorly: String,
    val doDifferently: String,
    val feedback: String?,
)

data class JournalMarkdownDocument(
    val fileName: String,
    val markdown: String,
)

class JournalMarkdownExporter {
    fun export(journal: JournalMarkdownJournal): JournalMarkdownDocument {
        val exportTime = journal.exportedAt.atZone(journal.zoneId)
        val markdown = StringBuilder()
            .append("# Dear Marcus\n\n")
            .append("Exported: ")
            .append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(exportTime))
            .append("\n\n## Current valid memory\n\n")

        journal.currentMemory?.let { markdown.appendMarkdownBlock(it) }
            ?: markdown.append("No valid condensed memory yet\n")

        markdown.append("\n## Journal entries\n\n")
        val entries = journal.entries.sortedWith(
            compareBy<JournalMarkdownEntry> { it.localDateTime }.thenBy { it.id },
        )
        if (entries.isEmpty()) {
            markdown.append("No journal entries yet.\n")
        } else {
            entries.forEachIndexed { index, entry ->
                if (index > 0) markdown.append('\n')
                markdown.append("### ")
                    .append(ENTRY_TIME_FORMATTER.format(entry.localDateTime))
                    .append("\n\n")
                    .appendAnswer("What went well", entry.wentWell)
                    .appendAnswer("What went poorly", entry.wentPoorly)
                    .appendAnswer("What I would do differently", entry.doDifferently)
                    .appendFeedback(entry.feedback)
            }
        }

        return JournalMarkdownDocument(
            fileName = "DearMarcus-${DATE_FORMATTER.format(exportTime)}.md",
            markdown = markdown.toString(),
        )
    }

    private fun StringBuilder.appendAnswer(label: String, answer: String): StringBuilder = append("#### ")
        .append(label)
        .append("\n\n")
        .appendMarkdownBlock(answer)
        .append('\n')

    private fun StringBuilder.appendFeedback(feedback: String?): StringBuilder = append("#### Feedback\n\n")
        .apply {
            if (feedback == null) {
                append("Unavailable or stale\n")
            } else {
                appendMarkdownBlock(feedback)
            }
        }

    private fun StringBuilder.appendMarkdownBlock(text: String): StringBuilder {
        val fence = "`".repeat(maxOf(3, text.longestBacktickRun() + 1))
        append(fence).append('\n').append(text)
        if (!text.endsWith('\n')) append('\n')
        return append(fence).append('\n')
    }

    private fun String.longestBacktickRun(): Int {
        var longest = 0
        var current = 0
        forEach { character ->
            if (character == '`') {
                current += 1
                longest = maxOf(longest, current)
            } else {
                current = 0
            }
        }
        return longest
    }

    private companion object {
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        val ENTRY_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern(
            "uuuu-MM-dd HH:mm",
            Locale.ROOT,
        )
    }
}
