package com.dearmarcus.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import org.junit.Test;

public class ReviewStateTest {
    @Test
    public void reviewState_exposesTheLatestValidDerivedInsight_andWhetherRefreshIsNeeded() {
        Reflection reflection = Reflection.successful(
                "entry-003",
                "Notice the choice before the response.",
                "Practice pausing.",
                "Practice pausing before difficult replies.",
                3,
                () -> Instant.parse("2026-08-03T10:15:30Z"));

        ReviewState reviewState = ReviewState.fromLatestValid(reflection, true);

        assertTrue(reviewState.latestValidReflection().isPresent());
        assertEquals("Practice pausing before difficult replies.", reviewState.currentMemory());
        assertTrue(reviewState.isRefreshRequired());
    }

    @Test
    public void reviewState_keepsNoCurrentMemory_whenThereIsNoValidReflection() {
        ReviewState reviewState = ReviewState.empty(false);

        assertFalse(reviewState.latestValidReflection().isPresent());
        assertEquals("", reviewState.currentMemory());
        assertFalse(reviewState.isRefreshRequired());
    }

    @Test
    public void reviewState_rejectsAnInvalidReflectionAsCurrent() {
        Reflection invalidReflection = Reflection.successful(
                "entry-003",
                "Notice the choice before the response.",
                "Practice pausing.",
                "Practice pausing before difficult replies.",
                3,
                () -> Instant.parse("2026-08-03T10:15:30Z"))
                .invalidated();

        assertThrows(
                IllegalArgumentException.class,
                () -> ReviewState.fromLatestValid(invalidReflection, true));
    }
}
