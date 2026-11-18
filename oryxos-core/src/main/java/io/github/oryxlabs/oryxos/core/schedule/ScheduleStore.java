package io.github.oryxlabs.oryxos.core.schedule;

import java.time.Instant;
import java.util.*;

public interface ScheduleStore {
  void reconcile(List<ScheduleDefinition> definitions, Instant now);

  List<ScheduleState> states();

  ScheduleState find(String id);

  ScheduleState setEnabled(String id, boolean enabled, Instant now);

  void record(ScheduleEvent event);

  List<ScheduleEvent> history(String id, int limit, UUID beforeId);
}
