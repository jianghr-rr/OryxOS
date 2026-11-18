package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.boot.support.SoakHarness;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.*;

/** Explicit short gate before the four-hour run; never sufficient to complete T101. */
@Tag("kernel-short-gate")
class ShortPerformanceGateIT {
  @Test
  void everyShortProfileTaskMeetsFiftyMillisecondsWithFullAudit() throws Exception {
    var folder =
        Path.of(
            System.getProperty("oryxos.test.evidenceDir"),
            "short-performance-gate",
            System.getProperty("oryxos.test.runId"));
    try (var harness = SoakHarness.diagnostic(folder)) {
      var result = harness.run();
      var criteria = (Map<?, ?>) result.get("criteria");
      assertAll(
          () -> assertEquals("DIAGNOSTIC_ONLY_NOT_T101", result.get("result")),
          () -> assertEquals(0L, result.get("violations")),
          () -> assertTrue(((Number) result.get("completed")).longValue() >= 100),
          () -> assertEquals(true, ((Map<?, ?>) result.get("audit")).get("verified")),
          () -> assertEquals(false, criteria.get("twoMinutes")),
          () -> assertEquals(true, criteria.get("sessionCreateP99AtMost200ms")),
          () ->
              assertEquals(
                  true,
                  criteria.get("everyTaskInternalAtMost50ms"),
                  "Short gate failed: queue, database and audit remain included; see " + folder),
          () ->
              assertEquals(
                  true,
                  criteria.get("everyTaskIncludingIngressAtMost50ms"),
                  "Short gate failed including ingress; do not launch four-hour acceptance"));
    }
  }
}
