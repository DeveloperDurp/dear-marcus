package com.dearmarcus.core;

import java.time.Instant;

public interface JournalClock {
    Instant now();
}
