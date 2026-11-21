package io.github.oryxlabs.oryxos.core.agent;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.react.ReActLoop;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.session.*;
import io.github.oryxlabs.oryxos.core.tool.ToolRegistryPort;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** The single ingress for CLI, scheduler and future HTTP. Stores never own session policy. */
public final class AgentService implements AutoCloseable {
  private static final ThreadLocal<Consumer<Handle>> INLINE_WORKER = new ThreadLocal<>();

  public static Handle withInlineWorker(Consumer<Handle> onAdmitted, Supplier<Handle> starter) {
    var previous = INLINE_WORKER.get();
    INLINE_WORKER.set(onAdmitted);
    try {
      return starter.get();
    } finally {
      if (previous == null) INLINE_WORKER.remove();
      else INLINE_WORKER.set(previous);
    }
  }

  private final WorkspaceHandle workspace;
  private final AgentCatalog agents;
  private final SessionManager sessions;
  private final ExecutionStore store;
  private final ReActLoop loop;
  private final ToolRegistryPort tools;
  private final RuntimeGate gate;
  private final TimeSource time;
  private final ZoneId timezone;
  private final Redactor redactor;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService deadlines =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon(true).name("oryx-execution-deadlines").factory());
  private final Set<Handle> active = ConcurrentHashMap.newKeySet();

  public AgentService(
      WorkspaceHandle workspace,
      AgentCatalog agents,
      SessionManager sessions,
      ExecutionStore store,
      ReActLoop loop,
      ToolRegistryPort tools,
      RuntimeGate gate,
      TimeSource time,
      ZoneId timezone,
      Redactor redactor) {
    this.workspace = workspace;
    this.agents = agents;
    this.sessions = sessions;
    this.store = store;
    this.loop = loop;
    this.tools = tools;
    this.gate = gate;
    this.time = time;
    this.timezone = timezone;
    this.redactor = redactor;
  }

  public Execution.Snapshot process(ProcessRequest request) {
    return start(request).await();
  }

  public Handle start(ProcessRequest request) {
    return start(request, snapshot -> {});
  }

  /** Admission record runs after execution/USER commit and before any model/tool dispatch. */
  public Handle start(
      ProcessRequest request, java.util.function.Consumer<Execution.Snapshot> admitted) {
    return start(request, admitted, () -> {});
  }

  public Handle start(
      ProcessRequest request,
      java.util.function.Consumer<Execution.Snapshot> admitted,
      Runnable cleanup) {
    return start(request, admitted, cleanup, time.instant(), time.nanoTime());
  }

  public Handle start(
      ProcessRequest request,
      java.util.function.Consumer<Execution.Snapshot> admitted,
      Runnable cleanup,
      Instant entered,
      long enteredNanos) {
    return start(request, admitted, cleanup, entered, enteredNanos, h -> {});
  }

  /** Publish the execution handle before any potentially blocking record operation. */
  public Handle start(
      ProcessRequest request,
      java.util.function.Consumer<Execution.Snapshot> admitted,
      Runnable cleanup,
      Instant entered,
      long enteredNanos,
      java.util.function.Consumer<Handle> observer) {
    validate(request);
    var agent = agents.require(request.agentName());
    Duration budget = Duration.ofSeconds(60);
    if (request.scheduleId() != null) {
      var schedule =
          agent.schedules().stream()
              .filter(s -> (agent.name() + ":" + s.id()).equals(request.scheduleId()))
              .findFirst()
              .orElseThrow(() -> new OryxError("NOT_FOUND", "Schedule is not configured"));
      if (request.sessionId() != null)
        throw new OryxError("INVALID_INPUT", "Schedule triggers require a fresh session");
      if (request.trigger() == ProcessRequest.Trigger.SCHEDULED)
        budget = Duration.ofSeconds(schedule.maxDurationSeconds());
    }
    var deadline = new Deadline(time, budget, enteredNanos, entered);
    var permit = gate.execution(deadline);
    permit.check();
    var names =
        new ToolNameMap(
            tools.available(agent).stream()
                .map(t ->
                    new ToolNameMap.Entry(
                        t.name(), ToolNameMap.wireName(t.name()), t.serverId(), t.remoteName()))
                .toList());
    permit.beginExecution();
    SessionManager.Lease lease;
    try {
      lease =
          sessions.acquireForExecution(
              request.sessionId(),
              request.channel(),
              request.userId(),
              agent,
              request.scheduleId() != null || request.oneShot()
                  ? Session.Kind.ONE_SHOT
                  : Session.Kind.CONVERSATION);
    } catch (RuntimeException error) {
      permit.finishExecution();
      if (error instanceof OryxError e && e.code().equals("RECORDING_FAILURE")) {
        gate.recordingFailure(null);
        permit.stop();
      }
      throw error;
    }
    var execution =
        new Execution(
            Uuids.v4(),
            lease.session().id(),
            Uuids.v4(),
            request,
            agent.configRevision(),
            entered,
            deadline.deadlineAt(),
            timezone,
            names);
    var ctx =
        new ExecutionContext(
            workspace,
            agent,
            execution,
            deadline,
            gate,
            permit,
            new ActionDispatcher(permit),
            time,
            redactor);
    var handle = new Handle(ctx);
    active.add(handle);
    var inlineObserver = INLINE_WORKER.get();
    if (inlineObserver != null) INLINE_WORKER.remove();
    boolean coalesceBegin = inlineObserver != null && !lease.isPending();
    try (var recording =
        coalesceBegin
            ? RecordingScope.open(execution.id(), deadline, store.coalescer())
            : RecordingScope.open(execution.id(), deadline)) {
      observer.accept(handle);
      if (coalesceBegin) {
        try (var beginWindow = RecordingScope.coalesce()) {
          lease.begin(ctx.snapshot(), request.content(), store);
          beginWindow.handOffToScope();
        }
      } else {
        lease.begin(ctx.snapshot(), request.content(), store);
      }
      admitted.accept(ctx.snapshot());
      var timeout =
          deadlines.schedule(handle::expireIfDue, deadline.remainingNanos(), TimeUnit.NANOSECONDS);
      Runnable workerTask =
          () -> {
            try (var recordingWorker =
                coalesceBegin
                    ? null
                    : RecordingScope.open(execution.id(), deadline, store.coalescer())) {
              boolean mergeTurnComplete = false;
              try {
                loop.run(ctx, lease);
                if (ctx.snapshot().recordingStatus() != Execution.RecordingStatus.INCOMPLETE) {
                  if (RecordingScope.isWindowOpen()
                      && lease.verifyPairedInMemory(execution.turnId())) {
                    mergeTurnComplete = true;
                  } else {
                    lease.finish(execution.turnId());
                  }
                }
              } catch (OryxError e) {
                mergeTurnComplete = false;
                lease.invalidateCache();
                if (e.code().equals("HISTORY_INCOMPLETE")) {
                  try {
                    RecordingScope.flushCoalesced();
                  } catch (RuntimeException ignored) {
                    RecordingScope.discardCoalesced();
                  }
                } else {
                  RecordingScope.discardCoalesced();
                }
                if (e.code().equals("RECORDING_FAILURE")
                    || e.code().equals("PARTIAL_RECORDING_FAILURE")
                    || e.code().equals("HISTORY_INCOMPLETE")) ctx.recordingFailed();
                else if (deadline.expired()) ctx.expire();
                else
                  execution.finish(
                      Execution.Status.FAILED,
                      e.code(),
                      redactor.redact(e.getMessage()),
                      e.code(),
                      time.instant());
              } catch (RuntimeException e) {
                mergeTurnComplete = false;
                lease.invalidateCache();
                RecordingScope.discardCoalesced();
                ctx.stop();
                execution.finish(
                    Execution.Status.FAILED,
                    "EXECUTION_FAILED",
                    "执行未能完成，请核查执行记录。",
                    "EXECUTION_FAILED",
                    time.instant());
              } finally {
                ctx.stop();
                timeout.cancel(false);
                execution.recordingComplete();
                try {
                  if (mergeTurnComplete) store.completeTurn(ctx.snapshot());
                  else store.save(ctx.snapshot());
                  RecordingScope.flushCoalesced();
                  if (mergeTurnComplete) lease.recordTurnCompleted(execution.turnId());
                } catch (RuntimeException e) {
                  lease.invalidateCache();
                  RecordingScope.discardCoalesced();
                  ctx.recordingFailed();
                }
                // Caller timeout is not completion: release only after every synchronous action
                // and
                // final record attempt exits.
                try {
                  lease.close();
                } catch (RuntimeException e) {
                  ctx.recordingFailed();
                  try {
                    store.save(ctx.snapshot());
                  } catch (RuntimeException ignored) {
                    ctx.recordingFailed();
                  }
                }
                permit.finishExecution();
                active.remove(handle);
                handle.complete(cleanup);
              }
            }
          };
      if (inlineObserver != null) {
        inlineObserver.accept(handle);
        RuntimeTiming.run("agent.dispatch.queue", () -> {});
        workerTask.run();
      } else {
        workers.execute(RuntimeTiming.wrap("agent.dispatch.queue", workerTask));
      }
      return handle;
    } catch (RuntimeException error) {
      ctx.recordingFailed(true);
      try {
        lease.close();
      } catch (RuntimeException e) {
        ctx.recordingFailed();
      }
      permit.finishExecution();
      active.remove(handle);
      handle.complete(cleanup);
      return handle;
    }
  }

  private void validate(ProcessRequest r) {
    if (r == null
        || r.channel() == null
        || r.channel().isBlank()
        || r.userId() == null
        || r.userId().isBlank()
        || r.agentName() == null
        || r.agentName().isBlank()
        || r.trigger() == null)
      throw new OryxError("INVALID_INPUT", "Channel, user and Agent are required");
    if (r.trigger() == ProcessRequest.Trigger.SCHEDULED
        && (r.scheduleId() == null || !r.channel().equals("scheduler")))
      throw new OryxError("INVALID_INPUT", "Automatic schedule identity and id are required");
    if (r.oneShot() && r.sessionId() != null)
      throw new OryxError("INVALID_INPUT", "One-shot execution requires a fresh session");
    if (r.content() == null || r.content().isBlank())
      throw new OryxError("INVALID_INPUT", "Provide non-blank task content");
    if (r.content().getBytes(StandardCharsets.UTF_8).length > 32768)
      throw new OryxError("MESSAGE_TOO_LARGE", "Task content exceeds 32768 UTF-8 bytes");
  }

  public final class Handle {
    private final ExecutionContext context;
    private final CompletableFuture<Execution.Snapshot> result = new CompletableFuture<>();
    private final CompletableFuture<Execution.Snapshot> settled = new CompletableFuture<>();
    private volatile boolean waitExpired;

    private Handle(ExecutionContext context) {
      this.context = context;
    }

    private void complete(Runnable cleanup) {
      try {
        cleanup.run();
      } finally {
        context.execution().recordingComplete();
        var snapshot = context.snapshot();
        try {
          settled.complete(snapshot);
        } finally {
          result.complete(snapshot);
        }
      }
    }

    public boolean whenFinished(
        java.util.function.BiConsumer<Execution.Snapshot, Boolean> onResult, Runnable onSettled) {
      settled.whenComplete((snapshot, error) -> onSettled.run());
      result.whenComplete((snapshot, error) -> {
        if (error == null) onResult.accept(snapshot, waitExpired);
      });
      return true;
    }

    public Execution.Snapshot await() {
      return result.join();
    }

    public Execution.Snapshot awaitSettled() {
      return settled.join();
    }

    public Execution.Snapshot current() {
      return context.snapshot();
    }

    public boolean waitExpired() {
      return waitExpired;
    }

    public void expireIfDue() {
      context
          .execution()
          .withLock(() -> {
            if (context.deadline().expired() && !result.isDone()) {
              waitExpired = true;
              context.stop();
              if (context.execution().status() == Execution.Status.RUNNING) context.expire();
              context.execution().recordingPending();
              // No storage IO on this callback: a slow store must not postpone the caller's
              // timeout.
              result.complete(context.snapshot());
            }
          });
    }
  }

  public int activeCount() {
    return active.size();
  }

  public Execution.Snapshot activeSnapshot(UUID id) {
    return active.stream()
        .map(Handle::current)
        .filter(s -> s.id().equals(id))
        .findFirst()
        .orElse(null);
  }

  public void close() {
    for (var handle : active) handle.context.stop();
    deadlines.shutdownNow();
    workers.shutdown();
    try {
      if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      workers.shutdownNow();
    }
  }
}
