package io.github.oryxlabs.oryxos.core.execution;

import io.github.oryxlabs.oryxos.core.runtime.Deadline;
import java.time.Duration;
import java.util.UUID;

/**
 * Bound and cleared only at AgentService's caller/worker boundaries; never propagated implicitly.
 */
public final class RecordingScope implements AutoCloseable {
  public interface Coalescer {
    void beginWindow();

    boolean isWindowOpen();

    void registerCallbacks(Runnable onCommit, Runnable onRollback);

    void flushPending();

    void flushWindow();

    void discardWindow();
  }

  public static final class Window implements AutoCloseable {
    private final Coalescer coalescer;
    private boolean handedOff;

    private Window(Coalescer coalescer) {
      this.coalescer = coalescer;
    }

    public void flush() {
      if (coalescer != null) coalescer.flushWindow();
    }

    public void handOffToScope() {
      handedOff = true;
    }

    @Override
    public void close() {
      if (coalescer != null && !handedOff) coalescer.discardWindow();
    }
  }

  private record Budget(UUID executionId, Deadline deadline, Coalescer coalescer) {}

  private static final ThreadLocal<Budget> CURRENT = new ThreadLocal<>();
  private static final Window NOOP_WINDOW = new Window(null);
  private final Budget current;
  private final Budget previous;

  private RecordingScope(UUID id, Deadline deadline, Coalescer coalescer) {
    previous = CURRENT.get();
    current = new Budget(id, deadline, coalescer);
    CURRENT.set(current);
  }

  public static RecordingScope open(UUID id, Deadline deadline) {
    return open(id, deadline, null);
  }

  public static RecordingScope open(UUID id, Deadline deadline, Coalescer coalescer) {
    return new RecordingScope(id, deadline, coalescer);
  }

  public static Window coalesce() {
    var b = CURRENT.get();
    var coalescer = b == null ? null : b.coalescer();
    if (coalescer == null) return NOOP_WINDOW;
    coalescer.beginWindow();
    return new Window(coalescer);
  }

  public static void flushCoalesced() {
    var b = CURRENT.get();
    if (b != null && b.coalescer() != null) b.coalescer().flushWindow();
  }

  public static void flushCheckpoint() {
    var b = CURRENT.get();
    if (b != null && b.coalescer() != null) b.coalescer().flushPending();
  }

  public static void discardCoalesced() {
    var b = CURRENT.get();
    if (b != null && b.coalescer() != null) b.coalescer().discardWindow();
  }

  public static boolean isWindowOpen() {
    var b = CURRENT.get();
    return b != null && b.coalescer() != null && b.coalescer().isWindowOpen();
  }

  public static boolean onCoalescedOutcome(Runnable onCommit, Runnable onRollback) {
    var b = CURRENT.get();
    if (b == null || b.coalescer() == null || !b.coalescer().isWindowOpen()) return false;
    b.coalescer().registerCallbacks(onCommit, onRollback);
    return true;
  }

  public static UUID executionId() {
    var b = CURRENT.get();
    return b == null ? null : b.executionId();
  }

  public static Duration remaining() {
    var b = CURRENT.get();
    // Late results still have a bounded opportunity to record facts, never permission to dispatch.
    long nanos =
        b == null || b.deadline().expired()
            ? 5_000_000_000L
            : Math.min(5_000_000_000L, b.deadline().remainingNanos());
    return Duration.ofNanos(Math.max(1, nanos));
  }

  public void close() {
    if (current.coalescer() != null) current.coalescer().discardWindow();
    if (previous == null) CURRENT.remove();
    else CURRENT.set(previous);
  }
}
