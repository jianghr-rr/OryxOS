package io.github.oryxlabs.oryxos.core.runtime;

import java.time.Instant;

public interface TimeSource {
    long nanoTime();
    Instant instant();
    static TimeSource system() {
        return new TimeSource() {
            public long nanoTime() { return System.nanoTime(); }
            public Instant instant() { return Instant.now(); }
        };
    }
}
