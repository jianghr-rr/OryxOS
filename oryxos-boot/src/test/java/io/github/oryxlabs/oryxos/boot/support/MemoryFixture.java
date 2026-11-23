package io.github.oryxlabs.oryxos.boot.support;

import io.github.oryxlabs.oryxos.core.agent.AgentProfile;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.model.ToolNameMap;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.workspace.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class MemoryFixture implements AutoCloseable {
  public final WorkspaceHandle workspace;
  public final UUID id;
  public final WorkspaceProcessLock lock;
  public final RuntimeGate gate = new RuntimeGate();

  public MemoryFixture(Path root) {
    workspace = new WorkspaceHandle(root.toAbsolutePath());
    id = new WorkspaceInitializer().initialize(workspace, Map.of());
    lock = WorkspaceProcessLock.acquire(workspace);
    gate.readyAtFirstStartup();
  }

  public ExecutionContext context(String agent) {
    var time = TimeSource.system();
    var deadline = new Deadline(time, Duration.ofSeconds(60));
    var permit = gate.execution(deadline);
    var profile =
        new AgentProfile(
            agent,
            agent,
            "test",
            "model",
            List.of("save_memory", "recall_memory"),
            List.of(),
            List.of(),
            Map.of(),
            List.of(),
            10,
            20,
            workspace.root().resolve("AGENT.md"),
            "revision",
            "body");
    var request = ProcessRequest.manual("cli", "user", agent, null, "remember");
    var execution =
        new Execution(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            request,
            "revision",
            time.instant(),
            deadline.deadlineAt(),
            ZoneId.of("Asia/Shanghai"),
            ToolNameMap.empty());
    return new ExecutionContext(
        workspace,
        profile,
        execution,
        deadline,
        gate,
        permit,
        new ActionDispatcher(permit),
        time,
        new Redactor());
  }

  public void close() {
    gate.close();
    lock.close();
  }
}
