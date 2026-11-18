package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.boot.support.SoakHarness;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.*;

/** Same load/delays as acceptance, but only two minutes: never T101 acceptance evidence. */
@Tag("kernel-diagnostic")
class SoakDiagnosticIT {
  @Test
  void realisticShortProfileRetainsAllSegmentsAndCompleteAudit() throws Exception {
    var folder =
        Path.of(
            System.getProperty("oryxos.test.evidenceDir"),
            "soak-diagnostic",
            System.getProperty("oryxos.test.runId"));
    try (var harness = SoakHarness.diagnostic(folder)) {
      var result = harness.run();
      assertEquals("DIAGNOSTIC_ONLY_NOT_T101", result.get("result"));
      assertEquals(0L, result.get("violations"));
      assertTrue(((Number) result.get("completed")).longValue() >= 100);
      assertEquals(true, ((Map<?, ?>) result.get("audit")).get("verified"));
      assertEquals(false, ((Map<?, ?>) result.get("criteria")).get("twoMinutes"));
    }
  }
}
