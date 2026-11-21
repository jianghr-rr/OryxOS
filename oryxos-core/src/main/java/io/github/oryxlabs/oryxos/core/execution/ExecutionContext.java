package io.github.oryxlabs.oryxos.core.execution;

import io.github.oryxlabs.oryxos.core.agent.AgentProfile;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;

public record ExecutionContext(
    WorkspaceHandle workspace,
    AgentProfile agent,
    Execution execution,
    Deadline deadline,
    RuntimeGate gate,
    RuntimeGate.ExecutionPermit permit,
    ActionDispatcher dispatcher,
    TimeSource time,
    Redactor redactor) {
  public void stop() {
    permit.stop();
  }

  public void expire() {
    permit.stop();
    execution.finish(
        Execution.Status.TIMED_OUT,
        "DEADLINE",
        "执行已到截止时间；在途动作可能仍在收尾，请先核查。",
        "DEADLINE",
        time.instant());
  }

  public void recordingFailed() {
    recordingFailed(false);
  }

  public void recordingFailed(boolean beforeDispatch) {
    gate.recordingFailure(execution.id());
    permit.stop();
    execution.withLock(() -> {
      if (deadline.expired()) expire();
      execution.recordingFailed(time.instant(), beforeDispatch);
    });
  }

  public Execution.Snapshot snapshot() {
    return execution.snapshot(permit.inFlight());
  }
}
