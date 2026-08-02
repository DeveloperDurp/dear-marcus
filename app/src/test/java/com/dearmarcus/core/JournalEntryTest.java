package com.dearmarcus.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.Test;

public class JournalEntryTest {
    private static final JournalClock FIXED_CLOCK = () -> Instant.parse("2026-08-01T10:15:30Z");
    private static final JournalIdGenerator FIXED_ID_GENERATOR = () -> "entry-001";
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(2026, 8, 1, 18, 30);

    @Test
    public void journalEntry_preservesExactBoundaryEmojiAndInjectedValues_whenAnswersAreValid() {
        String sixHundredEmoji = "\uD83D\uDE00".repeat(600);
        JournalAnswers answers = JournalAnswers.of(
                sixHundredEmoji,
                "I hurried through a difficult conversation.",
                "I would pause before replying.");

        JournalEntry entry = JournalEntry.create(
                FIXED_ID_GENERATOR,
                FIXED_CLOCK,
                ENTRY_TIME,
                answers);

        assertEquals("entry-001", entry.id());
        assertEquals(ENTRY_TIME, entry.localDateTime());
        assertEquals(Instant.parse("2026-08-01T10:15:30Z"), entry.updatedAt());
        assertEquals(sixHundredEmoji, entry.answers().whatWentWell());
    }

    @Test
    public void journalAnswers_rejectsBlankFirstAnswer_withoutChangingAnExistingEntry() {
        JournalEntry entry = validEntry();

        assertThrows(
                IllegalArgumentException.class,
                () -> entry.withAnswers(
                        JournalAnswers.of(" ", "A difficult moment.", "A better response."),
                        FIXED_CLOCK));

        assertEquals("A calm walk.", entry.answers().whatWentWell());
    }

    @Test
    public void journalAnswers_rejectsBlankSecondAnswer() {
        assertThrows(
                IllegalArgumentException.class,
                () -> JournalAnswers.of("A calm walk.", "", "A better response."));
    }

    @Test
    public void journalAnswers_rejectsBlankThirdAnswer() {
        assertThrows(
                IllegalArgumentException.class,
                () -> JournalAnswers.of("A calm walk.", "A difficult moment.", "\n\t"));
    }

    @Test
    public void journalAnswers_rejectsSixHundredOneUnicodeCodePoints_withoutTruncating() {
        String sixHundredOneEmoji = "\uD83D\uDE00".repeat(601);

        assertThrows(
                IllegalArgumentException.class,
                () -> JournalAnswers.of(
                        sixHundredOneEmoji,
                        "A difficult moment.",
                        "A better response."));
        assertEquals(1_202, sixHundredOneEmoji.length());
    }

    @Test
    public void journalAnswers_rejectsAnUnpairedSurrogate() {
        assertThrows(
                IllegalArgumentException.class,
                () -> JournalAnswers.of(
                        "A malformed value \uD83D",
                        "A difficult moment.",
                        "A better response."));
    }

    private JournalEntry validEntry() {
        return JournalEntry.create(
                FIXED_ID_GENERATOR,
                FIXED_CLOCK,
                ENTRY_TIME,
                JournalAnswers.of(
                        "A calm walk.",
                        "A difficult moment.",
                        "A better response."));
    }
}
