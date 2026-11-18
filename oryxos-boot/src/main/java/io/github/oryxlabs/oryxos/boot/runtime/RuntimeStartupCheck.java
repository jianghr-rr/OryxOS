package io.github.oryxlabs.oryxos.boot.runtime;

import io.github.oryxlabs.oryxos.boot.config.*;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.storage.runtime.*;
import io.github.oryxlabs.oryxos.storage.transaction.WorkspaceWriteCoordinator;
import java.time.Instant;
import java.util.*;

/**
 * Runs under the process lock before admission. Faults retain evidence even when every DB write
 * fails.
 */
public final class RuntimeStartupCheck implements AutoCloseable {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(RuntimeStartupCheck.class);
  private final UUID workspaceId;
  private final RuntimeGate gate;
  private final StorageLifecycle storage;
  private final RunJournal journal;
  private final IncidentRepository incidents;
  private final IncidentEvidenceReader evidence;
  private final boolean configured;
  private IncidentDetail current;
  private boolean persisting;
  private boolean storageWritable;
  private final List<Runnable> rebuiltListeners = new ArrayList<>();

  public synchronized void onStorageRebuilt(Runnable listener) { rebuiltListeners.add(listener); }

  public RuntimeStartupCheck(
      WorkspaceHandle workspace,
      WorkspaceProcessLock lock,
      RuntimeGate gate,
      StorageLifecycle storage,
      WorkspaceWriteCoordinator writes,
      boolean configured) {
    UUID id;
    try {
      id = WorkspaceInitializer.readId(workspace);
    } catch (RuntimeException e) {
      id = null;
    }
    storageWritable = storage.availability().available();
    workspaceId = id;
    this.gate = gate;
    this.storage = storage;
    this.configured = configured;
    journal = new RunJournal(workspace, lock);
    incidents = new IncidentRepository(id, writes);
    evidence = new IncidentEvidenceReader(writes);
    var previous = journal.inspect(); // Do not overwrite or clean old evidence before inspection.
    var oldEvidence = evidence.read(List.of());
    try {
      current = incidents.current();
    } catch (RuntimeException ignored) {
    }
    if (previous.fault() != null) current = previous.fault();
    if (current != null
        || !previous.runs().isEmpty()
        || previous.incomplete()
        || !oldEvidence.readable()
        || oldEvidence.unfinished()
        || !storage.availability().available()) {
      gate.pause();
      var sources = new LinkedHashSet<String>();
      if (previous.fault() != null) sources.add("FAULT_MARKER");
      if (!previous.runs().isEmpty()) sources.add("RUN_MARKER");
      if (oldEvidence.readable()) sources.add("DATABASE");
      if (sources.isEmpty()) sources.add("MEMORY");
      current =
          merge(
              current,
              oldEvidence,
              "Previous runtime did not close cleanly or storage cannot be checked",
              sources,
              null);
    }
    try {
      if (id == null) throw new IllegalStateException();
      journal.start(id);
    } catch (RuntimeException e) {
      gate.pause();
      current =
          merge(
              current,
              oldEvidence,
              "Current run marker could not be persisted",
              Set.of("MEMORY"),
              null);
    }
    writes.onFailure(this::recordFailure);
    writes.onActionEvidence(this::recordActionEvidence);
    gate.onRecordingFailure(id2 -> recordFailure(id2, "Required execution record is incomplete"));
    if (current != null) {
      persist();
      if (storage.availability().available())
        try {
          incidents.interruptUnfinished();
        } catch (RuntimeException ignored) {
        }
    }
  }

  public synchronized void openInitial() {
    if (!configured
        || current != null
        || !journal.started()
        || !storage.availability().available()
        || gate.state() != RuntimeGate.State.STARTING) return;
    try {
      incidents.ready();
      gate.readyAtFirstStartup();
    } catch (RuntimeException e) {
      recordFailure(null, "Initial workspace state could not be recorded");
    }
  }

  public synchronized void recordFailure(UUID execution, String reason) {
    gate.pause();
    storageWritable = false;
    if (persisting) return;
    var ids = new LinkedHashSet<UUID>();
    if (current != null) ids.addAll(current.affectedExecutionIds());
    if (execution != null) ids.add(execution);
    current = merge(current, evidence.read(ids), reason, Set.of("MEMORY"), execution);
    LOG.error(
        "Runtime PAUSED_RECORDING incident={} affectedExecutions={}; required recording failed;"
            + " manual acknowledgement required",
        current.id(),
        current.affectedExecutionIds());
    persist();
  }

  private synchronized void recordActionEvidence(IncidentDetail.Action action) {
    if (current == null) return;
    var known = new java.util.ArrayList<>(current.knownActions());
    var unknown = new java.util.ArrayList<>(current.unknownActions());
    known.removeIf(a -> java.util.Objects.equals(a.attemptId(), action.attemptId()));
    unknown.removeIf(a -> java.util.Objects.equals(a.attemptId(), action.attemptId()));
    (action.outcome().equals("UNKNOWN") ? unknown : known).add(action);
    var ids = new java.util.LinkedHashSet<>(current.affectedExecutionIds());
    ids.add(action.executionId());
    var sources = new java.util.LinkedHashSet<>(current.evidenceSources());
    sources.add("MEMORY");
    current =
        new IncidentDetail(
            current.id(),
            current.state(),
            current.detectedAt(),
            current.safeReason(),
            List.copyOf(ids),
            known,
            unknown,
            current.missingRecordSummary(),
            sources,
            false);
    persist();
  }

  private void persist() {
    if (current == null || persisting) return;
    persisting = true;
    try {
      try {
        journal.fault(current);
      } catch (RuntimeException ignored) {
      }
      try {
        incidents.save(current, journal.runId());
      } catch (RuntimeException ignored) {
      }
    } finally {
      persisting = false;
    }
  }

  private IncidentDetail merge(
      IncidentDetail old,
      IncidentEvidenceReader.Evidence e,
      String reason,
      Set<String> sources,
      UUID execution) {
    var ids = new LinkedHashSet<UUID>(e.affected());
    var allSources = new LinkedHashSet<>(sources);
    var actions = new LinkedHashMap<UUID, IncidentDetail.Action>();
    if (old != null) {
      ids.addAll(old.affectedExecutionIds());
      allSources.addAll(old.evidenceSources());
      for (var a : old.knownActions()) actions.put(a.attemptId(), a);
      for (var a : old.unknownActions()) actions.put(a.attemptId(), a);
    }
    for (var a : e.known()) actions.put(a.attemptId(), a);
    for (var a : e.unknown()) actions.putIfAbsent(a.attemptId(), a);
    if (execution != null) ids.add(execution);
    if (e.readable()) allSources.add("DATABASE");
    return new IncidentDetail(
        old == null ? UUID.randomUUID() : old.id(),
        "OPEN",
        old == null ? Instant.now() : old.detectedAt(),
        old == null ? reason : old.safeReason(),
        List.copyOf(ids),
        actions.values().stream().filter(a -> !a.outcome().equals("UNKNOWN")).toList(),
        actions.values().stream().filter(a -> a.outcome().equals("UNKNOWN")).toList(),
        "Some required records or shutdown evidence are missing. Empty action lists do not prove"
            + " that no action occurred; verify external recipients before acknowledging.",
        allSources,
        false);
  }

  public synchronized RecoveryPort.State state() {
    if (current != null)
      current =
          merge(
              current,
              evidence.read(current.affectedExecutionIds()),
              current.safeReason(),
              current.evidenceSources(),
              null);
    return new RecoveryPort.State(
        workspaceId,
        !configured ? "CONFIGURATION_ERROR" : gate.state().name(),
        storageWritable,
        current == null ? null : current.id(),
        current == null ? null : current.safeReason(),
        gate.inFlight(),
        current != null,
        current);
  }

  public synchronized RecoveryPort.State recover(RecoveryPort.Request request) {
    if (!configured)
      throw new io.github.oryxlabs.oryxos.core.error.OryxError(
          "CONFIGURATION_ERROR", "Fix runtime configuration and restart before recovery");
    if (current == null) {
      if (incidents.acknowledged(request.incidentId())) return state();
      throw conflict("Incident does not match current state");
    }
    if (!current.id().equals(request.incidentId())) {
      if (incidents.acknowledged(request.incidentId())) return state();
      throw conflict("Incident does not match current state");
    }
    if (gate.inFlight() != 0 || gate.activeExecutions() != 0)
      throw conflict("Local executions and late result recording must drain before recovery");
    gate.pause();
    storage.rebuild();
    if (!storage.availability().available())
      throw new io.github.oryxlabs.oryxos.core.error.OryxError(
          "STORAGE_UNAVAILABLE", "Storage must be readable and writable before recovery");
    try {
      if (!journal.started()) journal.start(workspaceId);
      current =
          merge(
              current,
              evidence.read(current.affectedExecutionIds()),
              current.safeReason(),
              current.evidenceSources(),
              null);
      incidents.interruptUnfinished();
      for (var listener : rebuiltListeners) listener.run();
      incidents.acknowledge(
          current,
          journal.runId(),
          request); // Commit operator and unrecoverable gaps before deleting markers.
      journal.acknowledged();
      gate.readyAfterAcknowledgement();
      storageWritable = true;
      current = null;
      return state();
    } catch (RuntimeException e) {
      gate.pause();
      persist();
      throw new io.github.oryxlabs.oryxos.core.error.OryxError(
          "STORAGE_UNAVAILABLE", "Recovery was not completed; admission remains paused");
    }
  }

  private static io.github.oryxlabs.oryxos.core.error.OryxError conflict(String message) {
    return new io.github.oryxlabs.oryxos.core.error.OryxError("RECOVERY_NOT_READY", message);
  }

  public synchronized void close() {
    if (gate.state() == RuntimeGate.State.READY
        && gate.inFlight() == 0
        && gate.activeExecutions() == 0
        && current == null) {
      var facts = evidence.read(List.of());
      if (facts.readable() && !facts.unfinished())
        try {
          journal.cleanClose();
        } catch (RuntimeException e) {
          recordFailure(null, "Runtime shutdown evidence could not be closed");
        }
    }
  }
}
