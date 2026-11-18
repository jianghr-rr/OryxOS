package io.github.oryxlabs.oryxos.core.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Tag("kernel-timing")
class RuntimeTimingTest {
  @Test
  void nestedSegmentsRetainWallTimeAndExplicitWorkerPropagation() throws Exception {
    var events = new CopyOnWriteArrayList<RuntimeTiming.Segment>();
    try (var binding = RuntimeTiming.bind(events::add);
        var parent = RuntimeTiming.measure("request")) {
      var captured = RuntimeTiming.capture();
      try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
        workers
            .submit(
                () -> {
                  assertNull(RuntimeTiming.capture(), "No implicit thread inheritance");
                  try (var worker = RuntimeTiming.bind(captured);
                      var queue = RuntimeTiming.measure("db.queue")) {
                    Thread.sleep(25);
                  } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                  }
                })
            .get();
      }
    }
    assertNull(RuntimeTiming.capture());
    assertEquals(
        List.of("db.queue", "request"), events.stream().map(RuntimeTiming.Segment::name).toList());
    assertTrue(events.get(0).endNanos() - events.get(0).startNanos() >= 25_000_000L);
    assertTrue(events.get(1).startNanos() <= events.get(0).startNanos());
    assertTrue(events.get(1).endNanos() >= events.get(0).endNanos());
  }

  @Test
  void disabledAndBrokenObserversCannotChangeExecutionOrLeakScope() {
    try (var ignored = RuntimeTiming.measure("disabled")) {
      assertNull(RuntimeTiming.capture());
    }
    var events = new ArrayList<RuntimeTiming.Segment>();
    try (var outer = RuntimeTiming.bind(events::add)) {
      try (var broken =
          RuntimeTiming.bind(
              s -> {
                throw new IllegalStateException("observer");
              })) {
        assertDoesNotThrow(
            () -> {
              try (var ignored = RuntimeTiming.measure("failure")) {}
            });
      }
      try (var restored = RuntimeTiming.measure("restored")) {}
    }
    assertEquals(1, events.size());
    assertEquals("restored", events.getFirst().name());
    assertNull(RuntimeTiming.capture());
  }
}
