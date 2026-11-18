package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.boot.config.AgentRuntimeConfiguration;
import io.github.oryxlabs.oryxos.boot.config.StorageLifecycle;
import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.boot.schedule.SpringScheduleAdapter;
import io.github.oryxlabs.oryxos.boot.support.PersistentFixture;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.schedule.*;
import io.github.oryxlabs.oryxos.core.session.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("us5-schedules")
class ScheduleIntegrationTest {
  @TempDir Path root;

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void callbackAlreadyTakenByTimerCannotRunAfterDisableAndReenable() throws Exception {
    try (var f = new PersistentFixture(root)) {
      schedule("0 0 8 * * *", true, 60);
      try (var c = f.start()) {
        var callbacks = new ArrayList<java.util.function.Consumer<Instant>>();
        ScheduleTimer timer =
            (definition, callback) -> {
              callbacks.add(callback);
              return new ScheduleTimer.Registration() {
                public Instant nextRunAt() {
                  return Instant.now().plusSeconds(3600);
                }

                public void close() {}
              };
            };
        var definitions =
            c.getBean(ScheduleCoordinator.class).list().stream()
                .map(ScheduleCoordinator.View::definition)
                .toList();
        try (var coordinator =
            new ScheduleCoordinator(
                definitions,
                c.getBean(ScheduleStore.class),
                c.getBean(ExecutionStore.class),
                () -> c.getBean(AgentRuntimeConfiguration.RuntimeAgents.class).require(),
                c.getBean(RuntimeGate.class),
                c.getBean(TimeSource.class),
                timer,
                true)) {
          coordinator.initialize();
          var old = callbacks.getFirst();
          coordinator.setEnabled("local:daily", false);
          coordinator.setEnabled("local:daily", true);
          assertEquals(2, callbacks.size());
          old.accept(Instant.now().minusSeconds(1));
          assertEquals(0, f.models.get());
          assertTrue(coordinator.history("local:daily", 100, null).isEmpty());
          callbacks.getLast().accept(Instant.now());
          waitFor(
              () -> f.models.get() == 1 && c.getBean(RuntimeGate.class).activeExecutions() == 0);
          assertEquals(1, coordinator.history("local:daily", 100, null).size());
        }
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void scheduleTablesStoreStateAndHistoryWithoutAuthoritativeDefinitions() throws Exception {
    try (var fixture = new PersistentFixture(root);
        var context = fixture.start();
        var connection =
            context.getBean(StorageLifecycle.class).resources().dataSource().getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
      var names = new HashSet<String>();
      while (rows.next()) names.add(rows.getString(1));
      assertTrue(
          names.containsAll(List.of("scheduled_tasks", "task_executions")),
          "T082 must persist schedule state and history");
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void productionProvidesSchedulerUsingPersistentEngine() throws Exception {
    try (var fixture = new PersistentFixture(root);
        var context = fixture.start()) {
      assertTrue(
          context.containsBean("scheduleCoordinator"),
          "T083 requires the shared coordinator in production");
      assertEquals(
          "JpaScheduleStore", context.getBean(ScheduleStore.class).getClass().getSimpleName());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void automaticCronEntersThePersistentEngine() throws Exception {
    try (var fixture = new PersistentFixture(root)) {
      schedule("* * * * * *", true, 60);
      try (var context =
          new BootRuntimeLauncher()
              .start(
                  fixture.workspace,
                  Map.of(
                      "spring.main.web-application-type",
                      "none",
                      "oryxos.scheduling.enabled",
                      true))) {
        long until = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (fixture.models.get() == 0 && System.nanoTime() < until) Thread.sleep(25);
        assertTrue(fixture.models.get() > 0, "A real Spring cron trigger must enter AgentService");
        var schedules = context.getBean(ScheduleCoordinator.class);
        schedules.setEnabled("local:daily", false);
        waitFor(() -> context.getBean(RuntimeGate.class).activeExecutions() == 0);
        assertNull(schedules.list().getFirst().nextRunAt());
        var events = schedules.history("local:daily", 100, null);
        assertFalse(events.isEmpty());
        var event =
            events.stream()
                .filter(e -> e.status() == ScheduleEvent.Status.DISPATCHED)
                .findFirst()
                .orElseThrow();
        var execution = context.getBean(ExecutionStore.class).find(event.executionId());
        assertEquals("scheduler", execution.channel());
        assertEquals(ScheduleCoordinator.SYSTEM_USER, execution.userId());
        assertEquals("local:daily", execution.scheduleId());
        assertEquals(Execution.Status.SUCCEEDED, execution.status());
        var session = context.getBean(SessionStore.class).find(execution.sessionId());
        assertEquals(Session.Kind.ONE_SHOT, session.kind());
        assertEquals(Session.Status.ARCHIVED, session.status());
        int stopped = fixture.models.get();
        Thread.sleep(1150);
        assertEquals(stopped, fixture.models.get());
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void disabledManualRunsKeepIdentityFreshSessionsAndSixtySecondBudget() throws Exception {
    try (var f = new PersistentFixture(root)) {
      schedule("0 0 8 * * *", false, 180);
      try (var c = f.start()) {
        var coordinator = c.getBean(ScheduleCoordinator.class);
        assertNull(coordinator.scheduled("local:daily", Instant.now()));
        assertEquals(0, f.models.get());
        var first = coordinator.manual("local:daily", "cli", "alice").execution().awaitSettled();
        var second = coordinator.manual("local:daily", "http", "bob").execution().awaitSettled();
        assertEquals("cli", first.channel());
        assertEquals("alice", first.userId());
        assertEquals("http", second.channel());
        assertEquals("bob", second.userId());
        assertEquals(ProcessRequest.Trigger.MANUAL, first.triggerType());
        assertEquals(
            Duration.ofSeconds(60), Duration.between(first.startedAt(), first.deadlineAt()));
        assertNotEquals(first.sessionId(), second.sessionId());
        for (var e : List.of(first, second)) {
          assertEquals(Execution.Status.SUCCEEDED, e.status());
          var session = c.getBean(SessionStore.class).find(e.sessionId());
          assertEquals(Session.Kind.ONE_SHOT, session.kind());
          assertEquals(Session.Status.ARCHIVED, session.status());
        }
        assertFalse(coordinator.list().getFirst().state().enabled());
        coordinator.setEnabled("local:daily", true);
        var auto = coordinator.scheduled("local:daily", Instant.now()).execution().awaitSettled();
        assertEquals(
            Duration.ofSeconds(180), Duration.between(auto.startedAt(), auto.deadlineAt()));
        assertEquals(3, f.models.get());
        assertEquals(1, coordinator.history("local:daily", 1, null).size());
        var cursor = coordinator.history("local:daily", 1, null).getFirst().id();
        assertEquals(2, coordinator.history("local:daily", 100, cursor).size());
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void persistedEnablementSurvivesDefinitionChangesDeletionAndRestoration() throws Exception {
    try (var f = new PersistentFixture(root)) {
      schedule("0 0 8 * * *", true, 60);
      String hash;
      UUID execution;
      var file = root.resolve("agents/local/AGENT.md");
      try (var c = f.start()) {
        var s = c.getBean(ScheduleCoordinator.class);
        hash = s.list().getFirst().state().definitionHash();
        execution = s.manual("local:daily", "cli", "alice").execution().awaitSettled().id();
        s.setEnabled("local:daily", false);
      }
      Files.writeString(
          file,
          Files.readString(file)
              .replace("0 0 8 * * *", "0 30 9 * * *")
              .replace("scheduled task", "new file task"));
      String replacement = Files.readString(file);
      try (var c = f.start()) {
        var s = c.getBean(ScheduleCoordinator.class);
        var view = s.list().getFirst();
        assertFalse(view.state().enabled());
        assertNotEquals(hash, view.state().definitionHash());
        assertEquals("0 30 9 * * *", view.definition().cron());
        assertEquals("new file task", view.definition().message());
        assertEquals(execution, view.state().lastExecutionId());
        assertEquals(1, s.history("local:daily", 100, null).size());
        assertEquals(1, f.models.get());
      }
      Files.delete(file);
      try (var c = f.start()) {
        var s = c.getBean(ScheduleCoordinator.class);
        var view = s.list().getFirst();
        assertFalse(view.state().configuredPresent());
        assertNull(view.definition());
        assertNull(view.nextRunAt());
        assertEquals(1, s.history("local:daily", 100, null).size());
        assertEquals(
            "NOT_FOUND",
            assertThrows(OryxError.class, () -> s.manual("local:daily", "cli", "alice")).code());
      }
      Files.writeString(file, replacement);
      try (var c = f.start()) {
        var state = c.getBean(ScheduleCoordinator.class).list().getFirst().state();
        assertTrue(state.configuredPresent());
        assertFalse(state.enabled());
        assertEquals(execution, state.lastExecutionId());
      }
      assertEquals(1, f.models.get());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void manualAndAutomaticOverlapRemainLockedThroughTimeoutAndLateRecording() throws Exception {
    try (var f = new PersistentFixture(root)) {
      schedule("0 0 8 * * *", true, 60);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      f.beforeModel =
          n -> {
            entered.countDown();
            try {
              assertTrue(release.await(8, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
          };
      try (var c =
          new BootRuntimeLauncher(Map.of(), FixedTime.class)
              .start(f.workspace, Map.of("spring.main.web-application-type", "none"))) {
        var s = c.getBean(ScheduleCoordinator.class);
        var first = s.manual("local:daily", "cli", "alice");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(
            ScheduleEvent.Status.SKIPPED_OVERLAP,
            s.scheduled("local:daily", Instant.now()).event().status());
        c.getBean(TestTime.class).advance(61);
        first.execution().expireIfDue();
        assertEquals(Execution.Status.TIMED_OUT, first.execution().await().status());
        assertEquals(
            ScheduleEvent.Status.SKIPPED_OVERLAP,
            s.manual("local:daily", "http", "bob").event().status());
        assertEquals(
            Session.Status.ACTIVE,
            c.getBean(SessionStore.class).find(first.execution().current().sessionId()).status());
        assertEquals(1, f.models.get());
        release.countDown();
        first.execution().awaitSettled();
        assertEquals(
            Session.Status.ARCHIVED,
            c.getBean(SessionStore.class).find(first.execution().current().sessionId()).status());
        assertEquals(
            Execution.Status.SUCCEEDED,
            s.scheduled("local:daily", Instant.now()).execution().awaitSettled().status());
        assertEquals(2, f.models.get());
        assertEquals(4, s.history("local:daily", 100, null).size());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void ordinaryFailureLeavesLaterTriggersUsable() throws Exception {
    try (var f = new PersistentFixture(root)) {
      schedule("0 0 8 * * *", true, 60);
      f.modelStatus = 401;
      try (var c = f.start()) {
        var s = c.getBean(ScheduleCoordinator.class);
        assertEquals(
            Execution.Status.FAILED,
            s.scheduled("local:daily", Instant.now()).execution().awaitSettled().status());
        assertEquals(RuntimeGate.State.READY, c.getBean(RuntimeGate.class).state());
        f.modelStatus = 200;
        assertEquals(
            Execution.Status.SUCCEEDED,
            s.scheduled("local:daily", Instant.now()).execution().awaitSettled().status());
        assertEquals(2, f.models.get());
        assertEquals("SUCCEEDED", s.list().getFirst().lastStatus());
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void dispatchRecordFailurePausesBeforeModelAndRecoveryNeverReplaysSkippedTicks()
      throws Exception {
    try (var f = new PersistentFixture(root)) {
      schedule("0 0 8 * * *", true, 60);
      try (var c = f.start()) {
        var s = c.getBean(ScheduleCoordinator.class);
        sql(
            c,
            "CREATE TRIGGER fail_dispatch BEFORE INSERT ON task_executions WHEN"
                + " NEW.status='DISPATCHED' BEGIN SELECT RAISE(FAIL,'injected'); END");
        assertEquals(
            "RECORDING_FAILURE",
            assertThrows(OryxError.class, () -> s.manual("local:daily", "cli", "alice")).code());
        assertEquals(0, f.models.get());
        assertEquals(RuntimeGate.State.PAUSED_RECORDING, c.getBean(RuntimeGate.class).state());
        assertEquals(
            ScheduleEvent.Status.SKIPPED_PAUSED,
            s.scheduled("local:daily", Instant.now()).event().status());
        assertEquals(
            ScheduleEvent.Status.SKIPPED_PAUSED,
            s.manual("local:daily", "http", "bob").event().status());
        sql(c, "DROP TRIGGER fail_dispatch");
        assertEquals(
            ScheduleEvent.Status.SKIPPED_PAUSED,
            s.scheduled("local:daily", Instant.now()).event().status());
        var recovery = c.getBean(RecoveryPort.class);
        recovery.recover(
            new RecoveryPort.Request(
                recovery.state().incidentId(),
                true,
                "Checked injected storage failure; no action sent",
                "reviewer"));
        assertEquals(0, f.models.get());
        assertEquals(3, s.history("local:daily", 100, null).size());
        assertEquals(
            Execution.Status.SUCCEEDED,
            s.manual("local:daily", "cli", "alice").execution().awaitSettled().status());
        assertEquals(1, f.models.get());
      }
      try (var c = f.start()) {
        assertEquals(1, f.models.get());
        assertEquals(
            4, c.getBean(ScheduleCoordinator.class).history("local:daily", 100, null).size());
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void failedEnabledCommitDoesNotClaimStateChange() throws Exception {
    try (var f = new PersistentFixture(root)) {
      schedule("0 0 8 * * *", false, 60);
      try (var c = f.start()) {
        var s = c.getBean(ScheduleCoordinator.class);
        sql(
            c,
            "CREATE TRIGGER fail_enabled BEFORE UPDATE OF enabled ON scheduled_tasks BEGIN SELECT"
                + " RAISE(FAIL,'injected'); END");
        assertEquals(
            "RECORDING_FAILURE",
            assertThrows(OryxError.class, () -> s.setEnabled("local:daily", true)).code());
        assertFalse(s.list().getFirst().state().enabled());
        assertNull(s.list().getFirst().nextRunAt());
        assertEquals(0, f.models.get());
        assertEquals(RuntimeGate.State.PAUSED_RECORDING, c.getBean(RuntimeGate.class).state());
        sql(c, "DROP TRIGGER fail_enabled");
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void workspaceTimezoneAndFileSnapshotsApplyToBothTriggerKinds() throws Exception {
    try (var f = new PersistentFixture(root)) {
      schedule("0 0 8 * * *", true, 60);
      try (var c =
          new BootRuntimeLauncher(Map.of(), FixedTime.class)
              .start(f.workspace, Map.of("spring.main.web-application-type", "none"))) {
        var s = c.getBean(ScheduleCoordinator.class);
        var d = s.list().getFirst().definition();
        assertEquals(ZoneId.of("Asia/Shanghai"), d.timezone());
        assertEquals(
            Instant.parse("2026-11-14T00:00:00Z"),
            SpringScheduleAdapter.next(d, Instant.parse("2026-11-13T16:00:01Z")));
        var file = root.resolve("agents/local/AGENT.md");
        Files.writeString(
            file,
            Files.readString(file)
                .replace("requested-model", "different-model")
                .replace("scheduled task", "new task")
                .replace("Use the configured tools only.", "Changed body today."));
        for (var attempt : List.of(s.manual("local:daily", "cli", "alice")))
          attempt.execution().awaitSettled();
        s.scheduled("local:daily", Instant.now()).execution().awaitSettled();
        for (var request : f.requests) {
          assertEquals("requested-model", request.path("model").asText());
          String messages = request.path("messages").toString();
          assertTrue(messages.contains("2026-11-14"));
          assertTrue(messages.contains("Asia/Shanghai"));
          assertTrue(messages.contains("Changed body today."));
          assertTrue(messages.contains("scheduled task"));
          assertFalse(messages.contains("new task"));
        }
        assertEquals(2, f.models.get());
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void invalidTimezoneOrBudgetOnlyDisablesItsAgent() throws Exception {
    try (var f = new PersistentFixture(root)) {
      schedule("0 0 8 * * *", true, 60);
      var file = root.resolve("agents/local/AGENT.md");
      String valid = Files.readString(file);
      Files.createDirectories(root.resolve("agents/other"));
      Files.writeString(
          root.resolve("agents/other/AGENT.md"), valid.replace("name: local\n", "name: other\n"));
      Files.writeString(file, valid.replace("    cron:", "    timezone: UTC\n    cron:"));
      try (var c = f.start()) {
        var s = c.getBean(ScheduleCoordinator.class);
        assertEquals(List.of("other:daily"), s.list().stream().map(v -> v.state().id()).toList());
        assertEquals(RuntimeGate.State.READY, c.getBean(RuntimeGate.class).state());
      }
      Files.writeString(
          file, valid.replace("max_duration_seconds: 60", "max_duration_seconds: 30"));
      try (var c = f.start()) {
        assertFalse(
            c
                .getBean(
                    io.github.oryxlabs.oryxos.boot.config.ToolConfiguration.CheckedAgents.class)
                .catalog()
                .entries()
                .stream()
                .filter(e -> e.name().equals("local"))
                .findFirst()
                .orElseThrow()
                .available());
      }
      assertEquals(0, f.models.get());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void realCronObservesOverlapAndContinuesAfterProviderFailure() throws Exception {
    try (var f = new PersistentFixture(root)) {
      schedule("* * * * * *", true, 60);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      f.beforeModel =
          n -> {
            f.modelStatus = n == 1 ? 401 : 200;
            if (n == 1) {
              entered.countDown();
              try {
                assertTrue(release.await(8, TimeUnit.SECONDS));
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            }
          };
      try (var c =
          new BootRuntimeLauncher()
              .start(
                  f.workspace,
                  Map.of(
                      "spring.main.web-application-type",
                      "none",
                      "oryxos.scheduling.enabled",
                      true))) {
        var s = c.getBean(ScheduleCoordinator.class);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        waitFor(
            () ->
                s.history("local:daily", 100, null).stream()
                    .anyMatch(e -> e.status() == ScheduleEvent.Status.SKIPPED_OVERLAP));
        assertEquals(1, f.models.get());
        release.countDown();
        waitFor(() -> f.models.get() >= 2);
        s.setEnabled("local:daily", false);
        waitFor(() -> c.getBean(RuntimeGate.class).activeExecutions() == 0);
        var outcomes =
            s.history("local:daily", 100, null).stream()
                .filter(e -> e.executionId() != null)
                .map(e -> c.getBean(ExecutionStore.class).find(e.executionId()).status())
                .toList();
        assertTrue(
            outcomes.containsAll(List.of(Execution.Status.FAILED, Execution.Status.SUCCEEDED)));
        assertEquals(RuntimeGate.State.READY, c.getBean(RuntimeGate.class).state());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void actualJvmRestartPreservesHistoryAndDisabledStateWithoutCatchup() throws Exception {
    try (var f = new PersistentFixture(root)) {
      var past = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(2);
      schedule(
          past.getSecond() + " " + past.getMinute() + " " + past.getHour() + " * * *", true, 60);
      scheduleProbe("seed");
      assertEquals(1, f.models.get());
      UUID execution =
          UUID.fromString(Files.readString(root.resolve("schedule-probe-execution.txt")));
      scheduleProbe("restart");
      assertEquals(1, f.models.get());
      try (var c = f.start()) {
        var s = c.getBean(ScheduleCoordinator.class);
        assertEquals(execution, s.list().getFirst().state().lastExecutionId());
        assertEquals(execution, s.history("local:daily", 100, null).getFirst().executionId());
        assertEquals(
            Execution.Status.SUCCEEDED, c.getBean(ExecutionStore.class).find(execution).status());
        assertFalse(s.list().getFirst().state().enabled());
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void chatDoesNotRegisterAutomaticJobsEvenWhenFilesEnableThem() throws Exception {
    try (var f = new PersistentFixture(root)) {
      schedule("* * * * * *", true, 60);
      int code =
          new BootRuntimeLauncher()
              .chat(
                  f.workspace,
                  new ChatOptions("local", "alice", null),
                  new java.io.Reader() {
                    boolean done;

                    public int read(char[] target, int offset, int length)
                        throws java.io.IOException {
                      if (done) return -1;
                      done = true;
                      try {
                        Thread.sleep(1200);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      return -1;
                    }

                    public void close() {}
                  },
                  new java.io.PrintWriter(java.io.Writer.nullWriter()));
      assertEquals(0, code);
      assertEquals(0, f.models.get());
      try (var c = f.start()) {
        assertTrue(
            c.getBean(ScheduleCoordinator.class).history("local:daily", 100, null).isEmpty());
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void serveAndGatewayCliProcessesActivateTheSamePersistentScheduler() throws Exception {
    try (var f = new PersistentFixture(root)) {
      schedule("* * * * * *", true, 60);
      var queries = new io.github.oryxlabs.oryxos.storage.query.SqliteReadOnlyQueries(f.workspace);
      for (String mode : List.of("serve", "gateway")) {
        int before = f.models.get();
        var log = root.resolve("cli-" + mode + ".log");
        var builder =
            new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-Dserver.port=0",
                    "-cp",
                    System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path")),
                    OryxOsApplication.class.getName(),
                    "--root",
                    root.toString(),
                    mode)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().clear();
        builder.environment().put("PATH", "/usr/bin:/bin");
        builder.environment().put("TZ", "UTC");
        var process = builder.start();
        try {
          long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
          while (f.models.get() == before && System.nanoTime() < until) {
            assertTrue(process.isAlive(), Files.readString(log));
            Thread.sleep(25);
          }
          assertTrue(f.models.get() > before, Files.readString(log));
          waitFor(
              () ->
                  queries.executions(null, 100).stream()
                          .filter(e -> "SUCCEEDED".equals(e.get("status")))
                          .count()
                      > before);
        } finally {
          process.destroy();
          if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
        }
        assertFalse(
            io.github.oryxlabs.oryxos.core.workspace.WorkspaceProcessLock.isRunning(f.workspace));
        assertFalse(Files.readString(log).contains("LOCAL_SECRET_CANARY"));
        assertTrue(
            queries.executions(null, 100).stream()
                .allMatch(
                    e ->
                        "scheduler".equals(e.get("channel"))
                            && ScheduleCoordinator.SYSTEM_USER.equals(e.get("user_id"))));
      }
      try (var c = f.start()) {
        assertEquals(RuntimeGate.State.READY, c.getBean(RuntimeGate.class).state());
        assertTrue(
            c.getBean(ScheduleCoordinator.class).history("local:daily", 100, null).size() >= 2);
      }
    }
  }

  void scheduleProbe(String mode) throws Exception {
    var log = root.resolve("schedule-probe-" + mode + ".log");
    var builder =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                io.github.oryxlabs.oryxos.boot.support.ScheduleRuntimeProbe.class.getName(),
                root.toString(),
                mode)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    builder.environment().clear();
    builder.environment().put("PATH", "/usr/bin:/bin");
    builder.environment().put("TZ", "UTC");
    var process = builder.start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS));
      assertEquals(0, process.exitValue(), Files.readString(log));
    } finally {
      if (process.isAlive()) process.destroyForcibly().waitFor();
    }
  }

  static void sql(org.springframework.context.ConfigurableApplicationContext c, String sql)
      throws Exception {
    try (var connection =
            c.getBean(StorageLifecycle.class).resources().dataSource().getConnection();
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(8).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(20);
    assertTrue(condition.getAsBoolean());
  }

  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  static class FixedTime {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    TestTime testTime() {
      return new TestTime();
    }
  }

  static class TestTime implements TimeSource {
    private final AtomicLong nanos = new AtomicLong();

    public long nanoTime() {
      return nanos.get();
    }

    public Instant instant() {
      return Instant.parse("2026-11-13T16:00:01Z").plusNanos(nanos.get());
    }

    void advance(long seconds) {
      nanos.addAndGet(Duration.ofSeconds(seconds).toNanos());
    }
  }

  void schedule(String cron, boolean enabled, int seconds) throws Exception {
    var file = root.resolve("agents/local/AGENT.md");
    String text = Files.readString(file);
    Files.writeString(
        file,
        text.replace(
            "bootstrap: []\n",
            "bootstrap: []\nschedules:\n"
                + "  - id: daily\n    cron: '"
                + cron
                + "'\n    message: 'scheduled task'\n"
                + "    enabled: "
                + enabled
                + "\n    max_duration_seconds: "
                + seconds
                + "\n"));
  }
}
