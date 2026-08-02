package com.dearmarcus.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import org.junit.Test;

public class ReflectionTest {
    private static final JournalClock FIXED_CLOCK = () -> Instant.parse("2026-08-01T10:15:30Z");

    @Test
    public void reflection_acceptsExactFeedbackAndMemoryBounds_withInjectedTimestamp() {
        String nineHundredFeedback = "f".repeat(900);
        String sixHundredMemory = "m".repeat(600);

        Reflection reflection = Reflection.successful(
                "entry-001",
                nineHundredFeedback,
                "",
                sixHundredMemory,
                Reflection.nextMemoryRevision(0),
                FIXED_CLOCK);

        assertEquals(AiStatus.AVAILABLE, reflection.aiStatus());
        assertTrue(reflection.isValid());
        assertEquals(1, reflection.memoryRevision());
        assertEquals(Instant.parse("2026-08-01T10:15:30Z"), reflection.generatedAt());
    }

    @Test
    public void reflection_rejectsBlankGeneratedFeedback() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Reflection.successful("entry-001", " ", "", "A useful memory.", 1, FIXED_CLOCK));
    }

    @Test
    public void reflection_rejectsFeedbackOverNineHundredCodePoints() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Reflection.successful(
                        "entry-001",
                        "f".repeat(901),
                        "",
                        "A useful memory.",
                        1,
                        FIXED_CLOCK));
    }

    @Test
    public void reflection_rejectsBlankGeneratedMemory() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Reflection.successful("entry-001", "A useful reflection.", "", "", 1, FIXED_CLOCK));
    }

    @Test
    public void reflection_rejectsMemoryOverSixHundredCodePoints() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Reflection.successful(
                        "entry-001",
                        "A useful reflection.",
                        "",
                        "m".repeat(601),
                        1,
                        FIXED_CLOCK));
    }

    @Test
    public void reflection_assignsStrictlyIncreasingRevisions() {
        assertEquals(1, Reflection.nextMemoryRevision(0));
        assertEquals(2, Reflection.nextMemoryRevision(1));
        assertThrows(IllegalArgumentException.class, () -> Reflection.nextMemoryRevision(-1));
    }

    @Test
    public void reflection_marksDerivedDataInvalid_withoutDiscardingThePriorSnapshot() {
        Reflection reflection = validReflection();

        Reflection invalidated = reflection.invalidated();

        assertFalse(invalidated.isValid());
        assertEquals(reflection.memoryAfter(), invalidated.memoryAfter());
        assertEquals(reflection.memoryRevision(), invalidated.memoryRevision());
    }

    @Test
    public void aiStatus_definesStableAvailabilityAndFailureStates() {
        assertEquals(AiStatus.AVAILABLE, AiStatus.valueOf("AVAILABLE"));
        assertEquals(AiStatus.DOWNLOADABLE, AiStatus.valueOf("DOWNLOADABLE"));
        assertEquals(AiStatus.DOWNLOADING, AiStatus.valueOf("DOWNLOADING"));
        assertEquals(AiStatus.UNAVAILABLE, AiStatus.valueOf("UNAVAILABLE"));
        assertEquals(AiStatus.TOKEN_LIMIT, AiStatus.valueOf("TOKEN_LIMIT"));
        assertEquals(AiStatus.BUSY, AiStatus.valueOf("BUSY"));
        assertEquals(AiStatus.QUOTA_EXCEEDED, AiStatus.valueOf("QUOTA_EXCEEDED"));
        assertEquals(AiStatus.BACKGROUND_BLOCKED, AiStatus.valueOf("BACKGROUND_BLOCKED"));
        assertEquals(AiStatus.INVALID_OUTPUT, AiStatus.valueOf("INVALID_OUTPUT"));
        assertEquals(AiStatus.UNEXPECTED_ERROR, AiStatus.valueOf("UNEXPECTED_ERROR"));
    }

    private Reflection validReflection() {
        return Reflection.successful(
                "entry-001",
                "A useful reflection.",
                "",
                "Practice pausing before replying.",
                1,
                FIXED_CLOCK);
    }
}
