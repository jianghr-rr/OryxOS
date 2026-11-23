package io.github.oryxlabs.oryxos.storage.transaction;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.RecordingScope;
import io.github.oryxlabs.oryxos.core.runtime.RuntimeGate;
import io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming;
import io.github.oryxlabs.oryxos.storage.lifecycle.StorageResources;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * One writer, at most 257 outstanding records (one active plus 256 waiting). Already queued records
 * share a FULL SQLite commit; no delay is added to collect a batch. Acknowledgements are published
 * only after commit and resource cleanup. A failed batch rolls back every member and pauses
 * admission before waking any caller.
 */
public final class WorkspaceWriteCoordinator {
  private static final int MAX_BATCH_SIZE = 128;
  private static final long MAX_ARRIVAL_ABSORB_NANOS = 500_000L;
  private static final int CHECKPOINT_THRESHOLD_JOBS = 80;
  private static final long CHECKPOINT_QUIET_DELAY_MILLIS = 80L;
  private static final long CHECKPOINT_QUIET_NANOS = 60_000_000L;
  private final Supplier<StorageResources> resources;
  private final RuntimeGate gate;
  private final Executor writerExecutor;
  private final java.util.concurrent.ScheduledExecutorService checkpointer = defaultCheckpointer();
  private final java.util.concurrent.atomic.AtomicLong lastWriteNanos =
      new java.util.concurrent.atomic.AtomicLong();
  private final Semaphore capacity = new Semaphore(257, true);
  private final Semaphore readPermits = new Semaphore(3);
  private final BlockingQueue<Job<?>> queue = new ArrayBlockingQueue<>(256);
  private final AtomicBoolean draining = new AtomicBoolean();
  private final AtomicBoolean checkpointing = new AtomicBoolean();
  private final AtomicInteger uncheckpointedJobs = new AtomicInteger();
  private final ThreadLocal<CoalesceWindow> coalesceWindow = new ThreadLocal<>();
  private final RecordingScope.Coalescer coalescer =
      new RecordingScope.Coalescer() {
        @Override
        public void beginWindow() {
          if (coalesceWindow.get() == null) coalesceWindow.set(new CoalesceWindow());
        }

        @Override
        public boolean isWindowOpen() {
          return coalesceWindow.get() != null;
        }

        @Override
        public void registerCallbacks(Runnable onCommit, Runnable onRollback) {
          var window = coalesceWindow.get();
          if (window == null) {
            if (onCommit != null) onCommit.run();
            return;
          }
          if (onCommit != null) window.onCommits.add(onCommit);
          if (onRollback != null) window.onRollbacks.add(onRollback);
        }

        @Override
        public void flushPending() {
          flushCoalescedWindow(false);
        }

        @Override
        public void flushWindow() {
          flushCoalescedWindow(true);
        }

        @Override
        public void discardWindow() {
          var window = coalesceWindow.get();
          coalesceWindow.remove();
          if (window != null) runCallbacks(window.onRollbacks);
        }
      };
  private volatile Consumer<io.github.oryxlabs.oryxos.core.runtime.IncidentDetail.Action>
      actionEvidence = action -> {};
  private volatile BiConsumer<UUID, String> failures = (id, reason) -> {};

  public WorkspaceWriteCoordinator(Supplier<StorageResources> resources, RuntimeGate gate) {
    // SQLite's native calls can pin a carrier. The sole physical writer must also make
    // progress when unrelated virtual callers occupy the shared carrier pool. A reusable daemon
    // platform worker avoids spawning a new OS thread per drain while keeping writers to one.
    this(resources, gate, defaultWriterExecutor());
  }

  private static Executor defaultWriterExecutor() {
    var executor =
        new ThreadPoolExecutor(
            1,
            1,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            Thread.ofPlatform().daemon(true).name("oryxos-sqlite-writer-", 0).factory());
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }

  private static java.util.concurrent.ScheduledExecutorService defaultCheckpointer() {
    var executor =
        new java.util.concurrent.ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon(true).name("oryxos-sqlite-checkpoint-", 0).factory());
    executor.setRemoveOnCancelPolicy(true);
    executor.setKeepAliveTime(60L, TimeUnit.SECONDS);
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }

  /** Allows deterministic admission tests before the physical writer starts. */
  public WorkspaceWriteCoordinator(
      Supplier<StorageResources> resources, RuntimeGate gate, Executor writerExecutor) {
    this.resources = resources;
    this.gate = gate;
    this.writerExecutor = writerExecutor;
  }

  public void onActionEvidence(
      Consumer<io.github.oryxlabs.oryxos.core.runtime.IncidentDetail.Action> listener) {
    actionEvidence = listener;
  }

  public void failedAction(io.github.oryxlabs.oryxos.core.runtime.IncidentDetail.Action action) {
    try {
      actionEvidence.accept(action);
    } catch (RuntimeException ignored) {
    }
  }

  public void onFailure(BiConsumer<UUID, String> listener) {
    failures = listener;
  }

  public int queued() {
    return queue.size();
  }

  public RecordingScope.Coalescer coalescer() {
    return coalescer;
  }

  public <T> T writeImmediate(Function<EntityManager, T> action) {
    var window = coalesceWindow.get();
    if (window == null || window.writes.isEmpty()) return write(action);
    var items = List.copyOf(window.writes);
    var commits = List.copyOf(window.onCommits);
    var rollbacks = List.copyOf(window.onRollbacks);
    window.writes.clear();
    window.onCommits.clear();
    window.onRollbacks.clear();
    T value;
    try {
      value =
          write(em -> {
            for (var item : items) item.action().accept(em);
            return action.apply(em);
          });
    } catch (RuntimeException error) {
      for (var item : items) {
        if (item.onFailure() != null) {
          try {
            item.onFailure().run();
          } catch (RuntimeException ignored) {
          }
        }
      }
      runCallbacks(rollbacks);
      for (var item : items) {
        if (item.coalescedFailure() != null) throw item.coalescedFailure();
      }
      throw error;
    }
    runCallbacks(commits);
    return value;
  }

  public void deferOrWrite(
      Consumer<EntityManager> action, Runnable onFailure, OryxError coalescedFailure) {
    var window = coalesceWindow.get();
    if (window == null) {
      try {
        write(em -> {
          action.accept(em);
          return null;
        });
      } catch (RuntimeException error) {
        if (onFailure != null) {
          try {
            onFailure.run();
          } catch (RuntimeException ignored) {
          }
        }
        throw error;
      }
      return;
    }
    window.writes.add(new DeferredWrite(action, onFailure, coalescedFailure));
  }

  private void flushCoalescedWindow(boolean closeWindow) {
    var window = coalesceWindow.get();
    if (closeWindow) coalesceWindow.remove();
    if (window == null || window.writes.isEmpty()) {
      if (closeWindow && window != null) runCallbacks(window.onRollbacks);
      return;
    }
    var items = List.copyOf(window.writes);
    var commits = List.copyOf(window.onCommits);
    var rollbacks = List.copyOf(window.onRollbacks);
    window.writes.clear();
    window.onCommits.clear();
    window.onRollbacks.clear();
    try {
      write(em -> {
        for (var item : items) item.action().accept(em);
        return null;
      });
    } catch (RuntimeException error) {
      for (var item : items) {
        if (item.onFailure() != null) {
          try {
            item.onFailure().run();
          } catch (RuntimeException ignored) {
          }
        }
      }
      runCallbacks(rollbacks);
      for (var item : items) {
        if (item.coalescedFailure() != null) throw item.coalescedFailure();
      }
      throw error;
    }
    runCallbacks(commits);
  }

  private static void runCallbacks(List<Runnable> callbacks) {
    for (var callback : callbacks) {
      try {
        callback.run();
      } catch (RuntimeException ignored) {
      }
    }
  }

  private record DeferredWrite(
      Consumer<EntityManager> action, Runnable onFailure, OryxError coalescedFailure) {}

  private static final class CoalesceWindow {
    final List<DeferredWrite> writes = new ArrayList<>(4);
    final List<Runnable> onCommits = new ArrayList<>(4);
    final List<Runnable> onRollbacks = new ArrayList<>(4);
  }

  public <T> T write(Function<EntityManager, T> action) {
    return write(RecordingScope.executionId(), RecordingScope.remaining(), action);
  }

  public <T> T write(UUID execution, Duration budget, Function<EntityManager, T> action) {
    try (var timing = RuntimeTiming.measure("db.write")) {
      if (!capacity.tryAcquire()) throw failed(execution);
      var job = new Job<T>(budget, action);
      if (!queue.offer(job)) {
        job.queueTiming.close();
        release(job);
        throw failed(execution);
      }
      startWriter();
      boolean interrupted = false;
      try {
        try {
          // Wait for the durable outcome once. A separate "started" future wakes every
          // caller only to park it again while its group is still being committed.
          job.result.get(Math.max(0, job.until - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
          interrupted = true;
          cancelQueued(job);
        } catch (TimeoutException e) {
          cancelQueued(job);
        } catch (ExecutionException e) {
          throw new CompletionException(e.getCause());
        }
        // Once admitted to a physical transaction, cancellation cannot release the execution
        // lease early. The action and its commit/rollback must settle before the caller returns.
        T value = job.result.join();
        RuntimeTiming.publish(
            job.observer,
            new RuntimeTiming.Segment("db.acknowledge.queue", job.completedAt, System.nanoTime()));
        return value;
      } catch (CompletionException e) {
        throw failed(execution);
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }
  }

  private void cancelQueued(Job<?> job) {
    if (job.state.compareAndSet(0, 2)) {
      gate.pause();
      job.queueTiming.close();
      job.result.completeExceptionally(new IllegalStateException("Write queue deadline"));
      if (queue.remove(job)) release(job);
    }
  }

  private OryxError failed(UUID execution) {
    gate.pause();
    try {
      failures.accept(
          execution, "Required record could not be committed; action outcome may be incomplete");
    } catch (RuntimeException ignored) {
    }
    return new OryxError(
        "RECORDING_FAILURE", "Required record could not be committed; workspace paused");
  }

  private void startWriter() {
    while (draining.compareAndSet(false, true)) {
      try {
        writerExecutor.execute(this::drain);
        return;
      } catch (RuntimeException error) {
        gate.pause();
        Job<?> job;
        while ((job = queue.poll()) != null) {
          if (job.state.compareAndSet(0, 2)) {
            job.queueTiming.close();
            job.result.completeExceptionally(error);
          }
          release(job);
        }
        draining.set(false);
        // Handle a submitter racing the failed starter; no queued record is stranded.
        if (queue.isEmpty()) return;
      }
    }
  }

  private void drain() {
    boolean triggerCheckpoint = false;
    try {
      for (; ; ) {
        var batch = new ArrayList<Job<?>>();
        // Cover a 100-session burst without four separate FULL syncs. Drain only jobs
        // already queued; there is still no collection delay or early acknowledgement.
        for (int i = 0; i < MAX_BATCH_SIZE; i++) {
          var job = pollAdmitted();
          if (job == null) break;
          batch.add(job);
        }
        if (batch.isEmpty()) {
          if (queue.isEmpty()) {
            if (uncheckpointedJobs.get() >= CHECKPOINT_THRESHOLD_JOBS
                && checkpointing.compareAndSet(false, true)) {
              uncheckpointedJobs.set(0);
              triggerCheckpoint = true;
            }
            return;
          }
          continue;
        }
        if (commit(batch)) {
          uncheckpointedJobs.addAndGet(batch.size());
        }
      }
    } finally {
      draining.set(false);
      if (!queue.isEmpty()) startWriter();
      if (triggerCheckpoint) {
        if (queue.isEmpty()) scheduleCheckpointPassive();
        else checkpointing.set(false);
      }
    }
  }

  private void scheduleCheckpointPassive() {
    try {
      checkpointer.schedule(
          this::checkpointPassive, CHECKPOINT_QUIET_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    } catch (RuntimeException ignored) {
      checkpointing.set(false);
    }
  }

  private void checkpointPassive() {
    boolean rescheduled = false;
    try {
      if (!queue.isEmpty() || System.nanoTime() - lastWriteNanos.get() < CHECKPOINT_QUIET_NANOS) {
        checkpointer.schedule(
            this::checkpointPassive, CHECKPOINT_QUIET_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        rescheduled = true;
        return;
      }
      var storage = resources.get();
      if (storage == null || !storage.isOpen()) return;
      try (var c = storage.dataSource().getConnection();
          var s = c.createStatement()) {
        s.execute("PRAGMA wal_checkpoint(PASSIVE)");
        if (queue.isEmpty() && System.nanoTime() - lastWriteNanos.get() >= CHECKPOINT_QUIET_NANOS) {
          s.execute("PRAGMA wal_checkpoint(RESTART)");
        }
      }
    } catch (Throwable ignored) {
      // Idle WAL checkpoint between bursts is best-effort and never alters admission state.
    } finally {
      if (!rescheduled) checkpointing.set(false);
    }
  }

  private Job<?> pollAdmitted() {
    Job<?> job;
    while ((job = queue.poll()) != null) {
      if (System.nanoTime() >= job.until) cancelQueued(job);
      if (job.state.compareAndSet(0, 1)) {
        job.admittedAt = System.nanoTime();
        job.queueTiming.close();
        return job;
      }
      release(job);
    }
    return null;
  }

  private boolean commit(List<Job<?>> batch) {
    Throwable failure = null;
    long batchStarted = System.nanoTime();
    try (var em = open(batch);
        var statements = StoredSql.batch()) {
      var session = em.unwrap(org.hibernate.engine.spi.SessionImplementor.class);
      long until = batch.stream().mapToLong(j -> j.until).min().orElseThrow();
      timed(
          batch,
          "db.connection",
          () ->
              em.unwrap(org.hibernate.Session.class)
                  .doWork(c -> {
                    statements.bind(c);
                    int millis =
                        (int)
                            Math.min(
                                Integer.MAX_VALUE,
                                Math.max(
                                    1L, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())));
                    if (c.isWrapperFor(org.sqlite.SQLiteConnection.class)) {
                      c.unwrap(org.sqlite.SQLiteConnection.class).setBusyTimeout(millis);
                    } else {
                      try (var s = c.createStatement()) {
                        s.execute("PRAGMA busy_timeout=" + millis);
                      }
                    }
                  }));
      var tx = em.getTransaction();
      try {
        timed(batch, "db.begin", tx::begin);
        long absorbUntil = 0L;
        for (int i = 0; i < batch.size(); i++) {
          var job = batch.get(i);
          if (System.nanoTime() >= job.until)
            throw new IllegalStateException("Write budget exhausted");
          job.apply(em, session);
          // Drain jobs already waiting while the first member ran, then bound follow-on arrival
          // absorption so trickling arrivals do not hold earlier jobs across a long tail.
          if (i == 0) {
            absorbUntil = System.nanoTime() + MAX_ARRIVAL_ABSORB_NANOS;
            while (batch.size() < MAX_BATCH_SIZE) {
              var arrival = pollAdmitted();
              if (arrival == null) break;
              batch.add(arrival);
            }
          } else if (System.nanoTime() < absorbUntil) {
            while (batch.size() < MAX_BATCH_SIZE && System.nanoTime() < absorbUntil) {
              var arrival = pollAdmitted();
              if (arrival == null) break;
              batch.add(arrival);
            }
          }
        }
        long readyToCommit = System.nanoTime();
        for (var job : batch)
          RuntimeTiming.publish(
              job.observer,
              new RuntimeTiming.Segment("db.group.after", job.actionFinished, readyToCommit));
        timed(batch, "db.commit", tx::commit);
      } catch (Throwable error) {
        gate.pause();
        if (tx.isActive())
          try {
            tx.rollback();
          } catch (RuntimeException ignored) {
          }
        throw error;
      }
    } catch (Throwable error) {
      failure = error;
      gate.pause();
    }
    long batchFinished = System.nanoTime();
    lastWriteNanos.set(batchFinished);
    for (var job : batch) {
      RuntimeTiming.publish(
          job.observer,
          new RuntimeTiming.Segment(
              "db.batch", Math.max(batchStarted, job.admittedAt), batchFinished));
      release(job);
    }
    for (var job : batch) {
      if (failure == null) job.acknowledge();
      else job.result.completeExceptionally(failure);
    }
    return failure == null;
  }

  private EntityManager open(List<Job<?>> batch) {
    long start = System.nanoTime();
    try {
      var em = resources.get().openEntityManager();
      em.setFlushMode(jakarta.persistence.FlushModeType.COMMIT);
      return em;
    } finally {
      var segment = new RuntimeTiming.Segment("db.entityManager", start, System.nanoTime());
      for (var job : batch) RuntimeTiming.publish(job.observer, segment);
    }
  }

  private static void timed(List<Job<?>> batch, String name, Runnable operation) {
    long start = System.nanoTime();
    try {
      operation.run();
    } finally {
      var segment = new RuntimeTiming.Segment(name, start, System.nanoTime());
      for (var job : batch) RuntimeTiming.publish(job.observer, segment);
    }
  }

  private void release(Job<?> job) {
    if (job.released.compareAndSet(false, true)) capacity.release();
  }

  private static final class Job<T> {
    final long until;
    final Function<EntityManager, T> action;
    final Consumer<RuntimeTiming.Segment> observer = RuntimeTiming.capture();
    final RuntimeTiming.Scope queueTiming = RuntimeTiming.measure("db.queue");
    final AtomicInteger state = new AtomicInteger();
    final AtomicBoolean released = new AtomicBoolean();
    final CompletableFuture<T> result = new CompletableFuture<>();
    T value;
    long admittedAt;
    long actionFinished;
    long completedAt;

    Job(Duration budget, Function<EntityManager, T> action) {
      until = System.nanoTime() + Math.min(budget.toNanos(), 5_000_000_000L);
      this.action = action;
    }

    void apply(EntityManager em, org.hibernate.engine.spi.SessionImplementor session) {
      RuntimeTiming.publish(
          observer, new RuntimeTiming.Segment("db.group.before", admittedAt, System.nanoTime()));
      try (var binding = RuntimeTiming.bind(observer)) {
        value =
            RuntimeTiming.call(
                "db.action",
                () -> {
                  T result = action.apply(em);
                  var persistenceContext = session.getPersistenceContextInternal();
                  var collectionEntries = persistenceContext.getCollectionEntries();
                  if (persistenceContext.getNumberOfManagedEntities() > 0
                      || (collectionEntries != null && !collectionEntries.isEmpty())
                      || session.getActionQueue().hasAnyQueuedActions()) {
                    RuntimeTiming.run("db.flush", em::flush);
                    // Bound each record's persistence context even in a shared commit.
                    em.clear();
                  }
                  return result;
                });
      } finally {
        actionFinished = System.nanoTime();
      }
    }

    void acknowledge() {
      completedAt = System.nanoTime();
      result.complete(value);
    }
  }

  public <T> T read(Function<EntityManager, T> action) {
    boolean acquired = false;
    try {
      try (var timing = RuntimeTiming.measure("db.read")) {
        EntityManager em;
        try (var connTiming = RuntimeTiming.measure("db.read.connection")) {
          acquireReadPermit();
          acquired = true;
          em = resources.get().openEntityManager();
          try {
            em.unwrap(org.hibernate.Session.class).doWork(connection -> {});
          } catch (RuntimeException e) {
            try {
              em.close();
            } catch (RuntimeException suppressed) {
              e.addSuppressed(suppressed);
            }
            throw e;
          }
        }
        try (em) {
          return RuntimeTiming.call("db.read.query", () -> action.apply(em));
        }
      } catch (RuntimeException e) {
        throw new OryxError(
            "STORAGE_UNAVAILABLE", "Stored records cannot be read; no empty fallback is used");
      }
    } finally {
      if (acquired) readPermits.release();
    }
  }

  @FunctionalInterface
  public interface SqlRead<T> {
    T apply(java.sql.Connection connection) throws java.sql.SQLException;
  }

  /** Short read-only projections use the same bounded pool without a persistence context. */
  public <T> T readSql(SqlRead<T> action) {
    boolean acquired = false;
    try {
      try (var timing = RuntimeTiming.measure("db.read")) {
        java.sql.Connection connection;
        try (var connTiming = RuntimeTiming.measure("db.read.connection")) {
          acquireReadPermit();
          acquired = true;
          var storage = resources.get();
          if (!storage.isOpen()) throw new IllegalStateException("Storage resources are closed");
          connection = storage.dataSource().getConnection();
        }
        try (connection;
            var query = RuntimeTiming.measure("db.read.query")) {
          return action.apply(connection);
        }
      } catch (java.sql.SQLException | RuntimeException e) {
        throw new OryxError(
            "STORAGE_UNAVAILABLE", "Stored records cannot be read; no empty fallback is used");
      }
    } finally {
      if (acquired) readPermits.release();
    }
  }

  private void acquireReadPermit() {
    try {
      if (!readPermits.tryAcquire(5, TimeUnit.SECONDS))
        throw new IllegalStateException("Read permits exhausted");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted waiting for read permit", e);
    }
  }
}
