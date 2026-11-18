package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.boot.support.SoakHarness;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;

/** Only explicit soak mode may execute this full four-hour acceptance. */
@Tag("kernel-soak")
class KernelSoakIT {
  @Test
  void tenAgentsOneHundredSessionsRunFourHoursWithCompleteAudit() throws Exception {
    var arguments = new HashMap<String, String>();
    for (String key :
        List.of(
            "oryxos.tests.mode",
            "oryxos.test.agents",
            "oryxos.test.sessions",
            "oryxos.test.duration")) {
      if (System.getProperty(key) != null) arguments.put(key, System.getProperty(key));
    }
    SoakHarness.requireAcceptanceSettings(SoakHarness.profile(), arguments);
    var folder =
        Path.of(
            System.getProperty("oryxos.test.evidenceDir"),
            "kernel-soak",
            System.getProperty("oryxos.test.runId"));
    try (var harness = new SoakHarness(folder, true)) {
      var result = harness.run();
      assertEquals(
          "PASSED", result.get("result"), "Full evidence retained at " + folder + "; " + result);
    }
  }
}
