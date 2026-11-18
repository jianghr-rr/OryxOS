package io.github.oryxlabs.oryxos.core.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class RedactorConcurrencyTest {
  @Test
  @Tag("kernel-redactor")
  void concurrentRegistrationPublishesEverySecretWithoutChangingProjection() throws Exception {
    var redactor = new Redactor();
    redactor.register(null);
    redactor.register("");
    redactor.register("prefix");
    redactor.register("prefix-with-suffix");
    assertNull(redactor.redact(null));
    assertFalse(redactor.containsSecret(null));
    assertEquals("[REDACTED]", redactor.redact("prefix-with-suffix"));
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var registrations = new ArrayList<Future<?>>();
      for (int i = 0; i < 100; i++) {
        String secret = "CANARY[" + i + "]";
        registrations.add(
            threads.submit(
                () -> {
                  redactor.register(secret);
                  redactor.register(secret);
                  assertTrue(redactor.containsSecret(secret));
                  assertEquals("[REDACTED]", redactor.redact(secret));
                }));
      }
      for (var future : registrations) future.get(5, TimeUnit.SECONDS);
    }
    for (int i = 0; i < 100; i++) {
      assertTrue(redactor.containsSecret("CANARY[" + i + "]"));
      assertEquals("[REDACTED]", redactor.redact("CANARY[" + i + "]"));
    }
    assertEquals(
        "[REDACTED_URL] Authorization: [REDACTED]\napi_key=[REDACTED];password=[REDACTED]",
        redactor.redact(
            "https://example.invalid/private Authorization: Bearer private\n"
                + "api_key=private;password=private"));
  }

  @Test
  @Tag("kernel-redactor-probe")
  void hundredConcurrentAuditProjectionsRetainAllWaitingTime() throws Exception {
    var redactor = new Redactor();
    redactor.register("CANARY-secret");
    String input =
        "audit detail ".repeat(2400)
            + "CANARY-secret https://example.invalid/private Authorization: Bearer private";
    String expected =
        "audit detail ".repeat(2400) + "[REDACTED] [REDACTED_URL] Authorization: [REDACTED]";
    for (int i = 0; i < 20; i++) assertEquals(expected, redactor.redact(input));
    var samples = new ConcurrentLinkedQueue<Long>();
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new CountDownLatch(1);
      var jobs = new ArrayList<Future<?>>();
      for (int i = 0; i < 100; i++)
        jobs.add(
            threads.submit(
                () -> {
                  start.await();
                  long began = System.nanoTime();
                  for (int audit = 0; audit < 12; audit++)
                    assertEquals(expected, redactor.redact(input));
                  samples.add(System.nanoTime() - began);
                  return null;
                }));
      start.countDown();
      for (var job : jobs) job.get(10, TimeUnit.SECONDS);
    }
    var output =
        Path.of(
            System.getProperty("oryxos.test.evidenceDir"),
            "redactor-probe-" + System.getProperty("oryxos.test.runId") + ".json");
    Files.writeString(
        output, "{\"label\":\"MINIMIZED_REDACTOR_PROBE_NOT_T101\",\"nanos\":" + samples + "}");
    long max = samples.stream().mapToLong(Long::longValue).max().orElseThrow();
    assertTrue(
        max <= 50_000_000L, "Audit projection maximum " + max / 1e6 + "ms > 50ms; " + output);
  }
}
