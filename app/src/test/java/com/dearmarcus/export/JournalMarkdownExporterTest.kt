package com.dearmarcus.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class JournalMarkdownExporterTest {
    private val exporter = JournalMarkdownExporter()

    @Test
    fun emptyJournalMatchesSnapshot() {
        val document = exporter.export(
            JournalMarkdownJournal(
                exportedAt = Instant.parse("2026-08-01T12:30:00Z"),
                zoneId = ZoneOffset.UTC,
                currentMemory = null,
                entries = emptyList(),
            ),
        )

        assertEquals("DearMarcus-2026-08-01.md", document.fileName)
        assertEquals(
            """
            # Dear Marcus

            Exported: 2026-08-01T12:30:00Z

            ## Current valid memory

            No valid condensed memory yet

            ## Journal entries

            No journal entries yet.
            """.trimIndent() + "\n",
            document.markdown,
        )
    }

    @Test
    fun validJournalMatchesSnapshotOldestFirstWithAllAnswersAndFeedback() {
        val document = exporter.export(
            JournalMarkdownJournal(
                exportedAt = Instant.parse("2026-08-01T12:30:00Z"),
                zoneId = ZoneOffset.UTC,
                currentMemory = "Pause before replying.",
                entries = listOf(
                    entry(
                        id = "later",
                        localDateTime = LocalDateTime.of(2026, 8, 1, 18, 30),
                        wentWell = "I listened.",
                        wentPoorly = "I rushed.",
                        doDifferently = "I will pause.",
                        feedback = "Notice the first impulse.",
                    ),
                    entry(
                        id = "earlier",
                        localDateTime = LocalDateTime.of(2026, 7, 31, 9, 15),
                        wentWell = "I arrived early.",
                        wentPoorly = "I checked my phone.",
                        doDifferently = "I will leave it away.",
                        feedback = "Protect attention.",
                    ),
                ),
            ),
        )

        assertEquals(
            """
            # Dear Marcus

            Exported: 2026-08-01T12:30:00Z

            ## Current valid memory

            ```
            Pause before replying.
            ```

            ## Journal entries

            ### 2026-07-31 09:15

            #### What went well

            ```
            I arrived early.
            ```

            #### What went poorly

            ```
            I checked my phone.
            ```

            #### What I would do differently

            ```
            I will leave it away.
            ```

            #### Feedback

            ```
            Protect attention.
            ```

            ### 2026-08-01 18:30

            #### What went well

            ```
            I listened.
            ```

            #### What went poorly

            ```
            I rushed.
            ```

            #### What I would do differently

            ```
            I will pause.
            ```

            #### Feedback

            ```
            Notice the first impulse.
            ```
            """.trimIndent() + "\n",
            document.markdown,
        )
    }

    @Test
    fun staleReflectionOmitsInvalidMemoryLabelsFeedbackAndPreservesUntrustedAnswerText() {
        val document = exporter.export(
            JournalMarkdownJournal(
                exportedAt = Instant.parse("2026-08-01T12:30:00Z"),
                zoneId = ZoneOffset.UTC,
                currentMemory = null,
                entries = listOf(
                    entry(
                        id = "stale",
                        localDateTime = LocalDateTime.of(2026, 8, 1, 18, 30),
                        wentWell = "# Not a heading\n```\nuntrusted",
                        wentPoorly = "- Still raw",
                        doDifferently = "<script>keep text</script>",
                        feedback = null,
                    ),
                ),
            ),
        )

        assertEquals(
            """
            # Dear Marcus

            Exported: 2026-08-01T12:30:00Z

            ## Current valid memory

            No valid condensed memory yet

            ## Journal entries

            ### 2026-08-01 18:30

            #### What went well

            ````
            # Not a heading
            ```
            untrusted
            ````

            #### What went poorly

            ```
            - Still raw
            ```

            #### What I would do differently

            ```
            <script>keep text</script>
            ```

            #### Feedback

            Unavailable or stale
            """.trimIndent() + "\n",
            document.markdown,
        )
        assertFalse(document.markdown.contains("invalid memory snapshot"))
    }

    private fun entry(
        id: String,
        localDateTime: LocalDateTime,
        wentWell: String,
        wentPoorly: String,
        doDifferently: String,
        feedback: String?,
    ) = JournalMarkdownEntry(
        id = id,
        localDateTime = localDateTime,
        wentWell = wentWell,
        wentPoorly = wentPoorly,
        doDifferently = doDifferently,
        feedback = feedback,
    )
}
