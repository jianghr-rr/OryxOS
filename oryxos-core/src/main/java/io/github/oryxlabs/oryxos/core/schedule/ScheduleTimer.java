package io.github.oryxlabs.oryxos.core.schedule;

import java.time.Instant;
import java.util.function.Consumer;

/** Timing adapter only: each callback must return without waiting for model/tool work. */
public interface ScheduleTimer {
  Registration register(ScheduleDefinition definition, Consumer<Instant> trigger);

  interface Registration extends AutoCloseable {
    Instant nextRunAt();

    void close();
  }
}
