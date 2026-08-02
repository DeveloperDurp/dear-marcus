package com.dearmarcus.core;

import java.time.Instant;

public final class Reflection {
    public static final int MAXIMUM_FEEDBACK_CODE_POINTS = 900;
    public static final int MAXIMUM_MEMORY_CODE_POINTS = 600;

    private final String entryId;
    private final String feedback;
    private final String memoryBefore;
    private final String memoryAfter;
    private final AiStatus aiStatus;
    private final boolean valid;
    private final int memoryRevision;
    private final Instant generatedAt;

    private Reflection(
            String entryId,
            String feedback,
            String memoryBefore,
            String memoryAfter,
            AiStatus aiStatus,
            boolean valid,
            int memoryRevision,
            Instant generatedAt) {
        this.entryId = UnicodeText.required(entryId, 128, "Reflection entry ID");
        this.feedback = UnicodeText.required(
                feedback,
                MAXIMUM_FEEDBACK_CODE_POINTS,
                "Reflection feedback");
        this.memoryBefore = UnicodeText.optional(
                memoryBefore,
                MAXIMUM_MEMORY_CODE_POINTS,
                "Reflection memory before");
        this.memoryAfter = UnicodeText.required(
                memoryAfter,
                MAXIMUM_MEMORY_CODE_POINTS,
                "Reflection memory after");
        if (aiStatus == null) {
            throw new IllegalArgumentException("AI status is required.");
        }
        if (memoryRevision < 1) {
            throw new IllegalArgumentException("Memory revision must be positive.");
        }
        if (generatedAt == null) {
            throw new IllegalArgumentException("Reflection generated timestamp is required.");
        }
        this.aiStatus = aiStatus;
        this.valid = valid;
        this.memoryRevision = memoryRevision;
        this.generatedAt = generatedAt;
    }

    public static Reflection successful(
            String entryId,
            String feedback,
            String memoryBefore,
            String memoryAfter,
            int memoryRevision,
            JournalClock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("Journal clock is required.");
        }
        return new Reflection(
                entryId,
                feedback,
                memoryBefore,
                memoryAfter,
                AiStatus.AVAILABLE,
                true,
                memoryRevision,
                clock.now());
    }

    public static int nextMemoryRevision(int currentRevision) {
        if (currentRevision < 0) {
            throw new IllegalArgumentException("Current memory revision cannot be negative.");
        }
        if (currentRevision == Integer.MAX_VALUE) {
            throw new IllegalStateException("Memory revision limit reached.");
        }
        return currentRevision + 1;
    }

    public Reflection invalidated() {
        return new Reflection(
                entryId,
                feedback,
                memoryBefore,
                memoryAfter,
                aiStatus,
                false,
                memoryRevision,
                generatedAt);
    }

    public String entryId() {
        return entryId;
    }

    public String feedback() {
        return feedback;
    }

    public String memoryBefore() {
        return memoryBefore;
    }

    public String memoryAfter() {
        return memoryAfter;
    }

    public AiStatus aiStatus() {
        return aiStatus;
    }

    public boolean isValid() {
        return valid;
    }

    public int memoryRevision() {
        return memoryRevision;
    }

    public Instant generatedAt() {
        return generatedAt;
    }
}
