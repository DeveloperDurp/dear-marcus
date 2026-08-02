package com.dearmarcus.core;

import java.util.Optional;

public final class ReviewState {
    private final Reflection latestValidReflection;
    private final boolean refreshRequired;

    private ReviewState(Reflection latestValidReflection, boolean refreshRequired) {
        if (latestValidReflection != null && !latestValidReflection.isValid()) {
            throw new IllegalArgumentException("Current review reflection must be valid.");
        }
        this.latestValidReflection = latestValidReflection;
        this.refreshRequired = refreshRequired;
    }

    public static ReviewState empty(boolean refreshRequired) {
        return new ReviewState(null, refreshRequired);
    }

    public static ReviewState fromLatestValid(Reflection reflection, boolean refreshRequired) {
        if (reflection == null) {
            throw new IllegalArgumentException("Latest valid reflection is required.");
        }
        return new ReviewState(reflection, refreshRequired);
    }

    public Optional<Reflection> latestValidReflection() {
        return Optional.ofNullable(latestValidReflection);
    }

    public String currentMemory() {
        return latestValidReflection == null ? "" : latestValidReflection.memoryAfter();
    }

    public boolean isRefreshRequired() {
        return refreshRequired;
    }
}
