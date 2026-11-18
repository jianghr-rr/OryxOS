package io.github.oryxlabs.oryxos.core.runtime;

import java.time.*;

/** Wall clock is presentation only; expiry uses elapsed monotonic time, including nanoTime wrap. */
public final class Deadline {
    private final TimeSource time;
    private final long started;
    private final long budget;
    private final Instant deadlineAt;
    public Deadline(TimeSource time, Duration duration) {
        this(time,duration,time.nanoTime(),time.instant());
    }
    public Deadline(TimeSource time, Duration duration,long started,Instant entered) {
        if (duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("Positive duration required");
        this.time = time; this.started = started; this.budget = duration.toNanos();
        this.deadlineAt = entered.plus(duration);
    }
    public boolean expired() { return time.nanoTime() - started >= budget; }
    public long remainingNanos() { return Math.max(0, budget - (time.nanoTime() - started)); }
    public Instant deadlineAt() { return deadlineAt; }
}
