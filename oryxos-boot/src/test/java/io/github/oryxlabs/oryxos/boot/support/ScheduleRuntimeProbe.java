package io.github.oryxlabs.oryxos.boot.support;

import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.core.schedule.ScheduleCoordinator;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import java.nio.file.*;
import java.util.Map;

/** Separate production JVM; assertions throw rather than silently skipping missing capabilities. */
public final class ScheduleRuntimeProbe {
  public static void main(String[] args) throws Exception {
    var root = Path.of(args[0]);
    try (var context =
        new BootRuntimeLauncher()
            .start(
                new WorkspaceHandle(root),
                Map.of(
                    "spring.main.web-application-type",
                    "none",
                    "oryxos.scheduling.enabled",
                    !args[1].equals("seed")))) {
      var schedules = context.getBean(ScheduleCoordinator.class);
      if (args[1].equals("seed")) {
        var execution =
            schedules.manual("local:daily", "cli", "probe-user").execution().awaitSettled();
        Files.writeString(root.resolve("schedule-probe-execution.txt"), execution.id().toString());
        schedules.setEnabled("local:daily", false);
      } else {
        var view = schedules.list().getFirst();
        if (view.state().enabled() || view.nextRunAt() != null)
          throw new AssertionError("Disabled state was not restored");
        if (schedules.history("local:daily", 100, null).size() != 1)
          throw new AssertionError("History was lost or replayed");
        Thread.sleep(1200);
        schedules.setEnabled("local:daily", true);
        var next = schedules.list().getFirst().nextRunAt();
        if (next == null || !next.isAfter(java.time.Instant.now().plusSeconds(3600)))
          throw new AssertionError("Missed cron must not be replayed");
        Thread.sleep(1200);
        if (schedules.history("local:daily", 100, null).size() != 1)
          throw new AssertionError("Enabling replayed old work");
        schedules.setEnabled("local:daily", false);
      }
    }
  }
}
