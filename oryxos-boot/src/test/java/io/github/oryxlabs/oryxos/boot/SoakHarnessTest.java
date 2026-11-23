package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.oryxlabs.oryxos.boot.support.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

@Tag("kernel-soak-harness")
class SoakHarnessTest {
  @Test
  void acceptanceProfileRequiresTheFullLoadAndDuration() throws Exception {
    try (var input = getClass().getResourceAsStream("/performance/load-profile.json")) {
      assertNotNull(input, "T101 needs a fixed, reviewable load profile before the long run");
      var profile = new ObjectMapper().readTree(input);
      assertEquals(10, profile.path("agents").asInt());
      assertEquals(100, profile.path("sessions").asInt());
      assertEquals(120, profile.path("durationSeconds").asInt());
      assertEquals(0, profile.path("clientThinkMillis").asInt(-1));
      assertEquals(50, profile.path("maxInternalMillis").asInt());
      assertEquals(200, profile.path("sessionCreateP99Millis").asInt());
    }
  }

  @Test
  void shortRunsAndWeakerThresholdsCannotBecomeAcceptance() throws Exception {
    var p = SoakHarness.profile();
    SoakHarness.requireAcceptanceSettings(p, Map.of("oryxos.tests.mode", "soak"));
    assertThrows(
        IllegalArgumentException.class, () -> SoakHarness.requireAcceptanceSettings(p, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SoakHarness.requireAcceptanceSettings(
                p, Map.of("oryxos.tests.mode", "soak", "oryxos.test.duration", "PT1M")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SoakHarness.requireAcceptanceSettings(
                p, Map.of("oryxos.tests.mode", "soak", "oryxos.test.sessions", "1")));
    var weaker = p.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) weaker).put("maxInternalMillis", 500);
    assertThrows(
        IllegalArgumentException.class,
        () -> SoakHarness.requireAcceptanceSettings(weaker, Map.of("oryxos.tests.mode", "soak")));
  }

  @Test
  void timingOnlyDeductsBoundedExternalWaitAndKeepsTheMaximum() {
    assertEquals(10, SoakTiming.deductibleWait(100, 10));
    assertEquals(5, SoakTiming.deductibleWait(5, 10));
    assertThrows(IllegalArgumentException.class, () -> SoakTiming.deductibleWait(-1, 10));
    var times = new ArrayList<Long>(Collections.nCopies(99, 1_000_000L));
    times.add(51_000_000L);
    assertEquals(1d, SoakTiming.percentileMillis(times, .99));
    assertEquals(51d, SoakTiming.percentileMillis(times, 1));
  }

  @Test
  void ingressQueueCannotDisappearOutsideTheServletBoundary() {
    assertEquals(
        60_000_000L,
        SoakTiming.ingressIncludedInternal(25_040_000_000L, 25_060_000_000L, 25_000_000_000L));
    assertEquals(
        70_000_000L,
        SoakTiming.ingressIncludedInternal(25_070_000_000L, 25_060_000_000L, 25_000_000_000L));
    assertThrows(
        IllegalArgumentException.class, () -> SoakTiming.ingressIncludedInternal(10, 20, 11));
  }

  @Test
  void shortHarnessUsesOneHundredRealSessionsAndCompleteSqliteAuditWithoutClaimingSoak()
      throws Exception {
    var folder =
        Path.of(
            System.getProperty("oryxos.test.evidenceDir"),
            "soak-smoke",
            System.getProperty("oryxos.test.runId"));
    try (var harness = new SoakHarness(folder, false)) {
      var result = harness.run();
      assertEquals("SMOKE_ONLY_NOT_T101", result.get("result"));
      assertEquals(0L, result.get("violations"));
      assertTrue(((Number) result.get("completed")).longValue() >= 100);
      assertEquals(true, ((Map<?, ?>) result.get("audit")).get("verified"));
      assertEquals(false, ((Map<?, ?>) result.get("criteria")).get("twoMinutes"));
      try (var lines = Files.lines(folder.resolve("samples.jsonl"))) {
        var sample =
            lines
                .map(line -> {
                  try {
                    return SoakHarness.JSON.readTree(line);
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                })
                .filter(node -> node.path("kind").asText().equals("task"))
                .findFirst()
                .orElseThrow();
        var names = new HashSet<String>();
        sample
            .path("segments")
            .forEach(segment -> {
              names.add(segment.path("name").asText());
              assertTrue(segment.path("endNanos").asLong() >= segment.path("startNanos").asLong());
            });
        assertTrue(
            names.containsAll(
                Set.of(
                    "http.dispatch.queue",
                    "agent.dispatch.queue",
                    "db.queue",
                    "db.read",
                    "db.begin",
                    "db.commit",
                    "audit.model",
                    "audit.tool",
                    "react.prompt",
                    "react.provider",
                    "react.tool",
                    "session.lock")),
            names.toString());
        assertEquals(
            sample.path("serverNanos").asLong() - sample.path("externalDeductedNanos").asLong(),
            sample.path("internalNanos").asLong());
      }
    }
  }
}
