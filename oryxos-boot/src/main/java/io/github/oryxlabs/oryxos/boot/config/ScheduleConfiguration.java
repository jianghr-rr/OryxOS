package io.github.oryxlabs.oryxos.boot.config;

import io.github.oryxlabs.oryxos.boot.runtime.RuntimeStartupCheck;
import io.github.oryxlabs.oryxos.boot.schedule.SpringScheduleAdapter;
import io.github.oryxlabs.oryxos.core.execution.ExecutionStore;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.schedule.*;
import io.github.oryxlabs.oryxos.storage.schedule.JpaScheduleStore;
import io.github.oryxlabs.oryxos.storage.transaction.WorkspaceWriteCoordinator;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("!us2-local & !us2-real")
public class ScheduleConfiguration {
  @Bean
  ScheduleStore scheduleStore(WorkspaceWriteCoordinator writes, RuntimeStartupCheck startup) {
    return new JpaScheduleStore(writes);
  }

  @Bean(destroyMethod = "close")
  SpringScheduleAdapter scheduleTimer() {
    return new SpringScheduleAdapter();
  }

  @Bean(destroyMethod = "close")
  ScheduleCoordinator scheduleCoordinator(
      ToolConfiguration.CheckedAgents catalog,
      ScheduleStore store,
      ExecutionStore executions,
      AgentRuntimeConfiguration.RuntimeAgents agents,
      RuntimeGate gate,
      TimeSource time,
      SpringScheduleAdapter timer,
      RuntimeStartupCheck startup,
      Environment environment) {
    var definitions =
        catalog.catalog() == null
            ? List.<ScheduleDefinition>of()
            : catalog.catalog().entries().stream()
                .filter(e -> e.available())
                .flatMap(e ->
                    e.profile().schedules().stream()
                        .map(s -> ScheduleDefinition.from(e.profile(), s)))
                .toList();
    var coordinator =
        new ScheduleCoordinator(
            definitions,
            store,
            executions,
            agents::require,
            gate,
            time,
            timer,
            environment.getProperty("oryxos.scheduling.enabled", Boolean.class, false));
    startup.onStorageRebuilt(coordinator::initialize);
    try {
      coordinator.initialize();
    } catch (RuntimeException e) {
      startup.recordFailure(null, "Schedule state could not be initialized");
    }
    return coordinator;
  }
}
