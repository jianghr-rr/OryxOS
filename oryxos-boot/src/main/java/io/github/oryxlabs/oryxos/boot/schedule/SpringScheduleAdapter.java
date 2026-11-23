package io.github.oryxlabs.oryxos.boot.schedule;

import io.github.oryxlabs.oryxos.core.schedule.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

/**
 * Cron callbacks only admit work; model/tool waits stay on AgentService workers. No catch-up
 * trigger.
 */
public final class SpringScheduleAdapter implements ScheduleTimer, AutoCloseable {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(SpringScheduleAdapter.class);
  private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

  public SpringScheduleAdapter() {
    this(Clock.systemUTC());
  }

  public SpringScheduleAdapter(Clock clock) {
    scheduler.setClock(clock);
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("oryx-schedule-");
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    scheduler.setErrorHandler(error ->
        LOG.warn("Schedule trigger could not be recorded or dispatched; inspect runtime state"));
    scheduler.initialize();
  }

  public static Instant next(ScheduleDefinition definition, Instant after) {
    return new CronTrigger(definition.cron(), definition.timezone())
        .nextExecution(new SimpleTriggerContext(Clock.fixed(after, ZoneOffset.UTC)));
  }

  public Registration register(ScheduleDefinition definition, Consumer<Instant> trigger) {
    var next = new AtomicReference<Instant>();
    var cron = new CronTrigger(definition.cron(), definition.timezone());
    var future =
        scheduler.schedule(
            () -> trigger.accept(next.get()),
            ctx -> {
              Instant at = cron.nextExecution(ctx);
              next.set(at);
              return at;
            });
    return new Registration() {
      public Instant nextRunAt() {
        return future == null || future.isCancelled() ? null : next.get();
      }

      public void close() {
        if (future != null) future.cancel(false);
        next.set(null);
      }
    };
  }

  public void close() {
    scheduler.shutdown();
  }
}
