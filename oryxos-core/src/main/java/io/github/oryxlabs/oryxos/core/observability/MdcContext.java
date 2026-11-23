package io.github.oryxlabs.oryxos.core.observability;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.MDC;

/**
 * Snapshot of the logging MDC carried across thread hand-offs. Wrapped tasks install the snapshot
 * while running and restore the worker's previous MDC afterwards, so pooled threads never keep a
 * request's {@code traceId} or {@code executionId}.
 */
public final class MdcContext {
  private final Map<String, String> values;

  private MdcContext(Map<String, String> values) {
    this.values = values;
  }

  public static MdcContext capture() {
    Map<String, String> current = MDC.getCopyOfContextMap();
    return new MdcContext(current == null ? Map.of() : Map.copyOf(current));
  }

  /** Sets {@code key} until the returned scope closes, then restores the previous value. */
  public static Scope put(String key, String value) {
    String previous = MDC.get(key);
    MDC.put(key, value);
    return () -> {
      if (previous == null) {
        MDC.remove(key);
      } else {
        MDC.put(key, previous);
      }
    };
  }

  public Runnable wrap(Runnable task) {
    return () -> {
      Map<String, String> previous = install();
      try {
        task.run();
      } finally {
        restore(previous);
      }
    };
  }

  public <T> Callable<T> wrap(Callable<T> task) {
    return () -> {
      Map<String, String> previous = install();
      try {
        return task.call();
      } finally {
        restore(previous);
      }
    };
  }

  public <T> Consumer<T> wrap(Consumer<T> task) {
    return value -> {
      Map<String, String> previous = install();
      try {
        task.accept(value);
      } finally {
        restore(previous);
      }
    };
  }

  public <T, U> BiConsumer<T, U> wrap(BiConsumer<T, U> task) {
    return (first, second) -> {
      Map<String, String> previous = install();
      try {
        task.accept(first, second);
      } finally {
        restore(previous);
      }
    };
  }

  private Map<String, String> install() {
    Map<String, String> previous = MDC.getCopyOfContextMap();
    if (values.isEmpty()) {
      MDC.clear();
    } else {
      MDC.setContextMap(values);
    }
    return previous;
  }

  private static void restore(Map<String, String> previous) {
    if (previous == null || previous.isEmpty()) {
      MDC.clear();
    } else {
      MDC.setContextMap(previous);
    }
  }

  /** Closes without checked exceptions. */
  @FunctionalInterface
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
