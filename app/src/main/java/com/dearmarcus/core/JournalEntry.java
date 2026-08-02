package com.dearmarcus.core;

import java.time.Instant;
import java.time.LocalDateTime;

public final class JournalEntry {
    private final String id;
    private final LocalDateTime localDateTime;
    private final JournalAnswers answers;
    private final Instant updatedAt;

    private JournalEntry(
            String id,
            LocalDateTime localDateTime,
            JournalAnswers answers,
            Instant updatedAt) {
        this.id = UnicodeText.required(id, 128, "Journal entry ID");
        if (localDateTime == null) {
            throw new IllegalArgumentException("Journal entry local date/time is required.");
        }
        if (answers == null) {
            throw new IllegalArgumentException("Journal answers are required.");
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("Journal entry updated timestamp is required.");
        }
        this.localDateTime = localDateTime;
        this.answers = answers;
        this.updatedAt = updatedAt;
    }

    public static JournalEntry create(
            JournalIdGenerator idGenerator,
            JournalClock clock,
            LocalDateTime localDateTime,
            JournalAnswers answers) {
        if (idGenerator == null) {
            throw new IllegalArgumentException("Journal ID generator is required.");
        }
        if (clock == null) {
            throw new IllegalArgumentException("Journal clock is required.");
        }
        return new JournalEntry(idGenerator.nextId(), localDateTime, answers, clock.now());
    }

    public JournalEntry withAnswers(JournalAnswers updatedAnswers, JournalClock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("Journal clock is required.");
        }
        return new JournalEntry(id, localDateTime, updatedAnswers, clock.now());
    }

    public String id() {
        return id;
    }

    public LocalDateTime localDateTime() {
        return localDateTime;
    }

    public JournalAnswers answers() {
        return answers;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
