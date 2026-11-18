package io.github.oryxlabs.oryxos.core.runtime;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Optional monotonic diagnostics. No content, no implicit inheritance, no change to deadlines. */
public final class RuntimeTiming {
  public record Segment(String name, long startNanos, long endNanos) {}

  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  private static final Scope DISABLED = () -> {};
  private static final ThreadLocal<Consumer<Segment>> CURRENT = new ThreadLocal<>();

  private RuntimeTiming() {}

  public static Consumer<Segment> capture() {
    return CURRENT.get();
  }

  public static Scope bind(Consumer<Segment> observer) {
    var previous = CURRENT.get();
    if (observer == null) CURRENT.remove();
    else CURRENT.set(observer);
    return () -> {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    };
  }

  public static Scope measure(String name) {
    var observer = CURRENT.get();
    if (observer == null) return DISABLED;
    long start = System.nanoTime();
    return () -> record(observer, name, start);
  }

  private static void record(Consumer<Segment> observer, String name, long start) {
    long end = System.nanoTime();
    publish(observer, new Segment(name, start, end));
  }

  public static void publish(Consumer<Segment> observer, Segment segment) {
    if (observer == null) return;
    try {
      observer.accept(segment);
    } catch (RuntimeException ignored) {
      /* Diagnostics cannot affect required persistence. */
    }
  }

  public static <T> T call(String name, Supplier<T> operation) {
    try (var ignored = measure(name)) {
      return operation.get();
    }
  }

  public static void run(String name, Runnable operation) {
    try (var ignored = measure(name)) {
      operation.run();
    }
  }

  public static Runnable wrap(String queueName, Runnable operation) {
    var observer = capture();
    if (observer == null) return operation;
    long submitted = System.nanoTime();
    return () -> {
      try (var ignored = bind(observer)) {
        record(observer, queueName, submitted);
        operation.run();
      }
    };
  }
}
