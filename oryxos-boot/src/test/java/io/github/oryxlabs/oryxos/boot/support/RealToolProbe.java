package io.github.oryxlabs.oryxos.boot.support;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.oryxlabs.oryxos.boot.audit.StructuredLogAuditSink;
import io.github.oryxlabs.oryxos.core.agent.*;
import io.github.oryxlabs.oryxos.core.config.*;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.security.*;
import io.github.oryxlabs.oryxos.core.tool.*;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.tool.ToolRegistry;
import io.github.oryxlabs.oryxos.tool.policy.*;
import io.github.oryxlabs.oryxos.tool.validation.ToolArgumentValidator;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Independent real tool probe; no model or temporary SessionStore masquerades as production. */
public final class RealToolProbe implements AutoCloseable {
  public final Redactor redactor = new Redactor();
  public final WorkspaceHandle workspace;
  public final RuntimeConfig config;
  public final AgentProfile agent;
  private final WorkspaceProcessLock lock;
  private final RuntimeGate gate = new RuntimeGate();

  public RealToolProbe() {
    assertEquals("real", System.getProperty("oryxos.tests.mode"));
    Path root = Path.of(required("oryxos.real.workspace"));
    assertTrue(root.isAbsolute(), "Use an absolute private workspace");
    workspace = new WorkspaceHandle(root.normalize());
    assertTrue(
        Files.isRegularFile(root.resolve("workspace.json")),
        "Initialize the private workspace first");
    config = new RuntimeConfigLoader(Map.of(), redactor).load(workspace);
    agent =
        AgentCatalog.load(workspace, config, Map.of(), redactor)
            .require(required("oryxos.real.agent"));
    lock = WorkspaceProcessLock.acquire(workspace);
    gate.readyAtFirstStartup();
  }

  public SecretResolver secrets() {
    return new SecretResolver(Map.of(), workspace.root().resolve("secrets.local.yaml"), redactor);
  }

  public ToolExecutor.Attempt execute(List<OryxTool> tools, String name, String arguments) {
    var time = TimeSource.system();
    var deadline = new Deadline(time, Duration.ofSeconds(60));
    var permit = gate.execution(deadline);
    var request =
        ProcessRequest.manual(
            "cli", "real-tool-probe", agent.name(), null, "Explicit real tool acceptance");
    var execution =
        new Execution(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            request,
            agent.configRevision(),
            time.instant(),
            deadline.deadlineAt(),
            ZoneId.of("Asia/Shanghai"),
            ToolNameMap.empty());
    var context =
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
    var retry = new ToolRetryPolicy();
    var registry =
        new ToolRegistry(
            tools.stream().map(retry::apply).toList(),
            new ToolArgumentValidator(),
            new ToolPolicy());
    var executor =
        new ToolExecutor(
            registry,
            new StructuredLogAuditSink(redactor),
            duration -> {
              throw new AssertionError("Real side-effect probe must not retry");
            });
    try {
      return executor.execute(
          context,
          new ToolCall(UUID.randomUUID().toString(), name, ToolNameMap.wireName(name), arguments));
    } finally {
      context.stop();
    }
  }

  public static String required(String key) {
    String value = System.getProperty(key);
    assertTrue(
        value != null && !value.isBlank(),
        "Missing -D" + key + "; see US4 real integration prerequisites");
    return value;
  }

  public static void evidence(String name, Map<String, Object> result) throws Exception {
    Path directory =
        Path.of(System.getProperty("oryxos.test.evidenceDir"))
            .resolve("us4-real")
            .resolve(System.getProperty("oryxos.test.runId"));
    Files.createDirectories(directory);
    new ObjectMapper()
        .writerWithDefaultPrettyPrinter()
        .writeValue(directory.resolve(name + ".json").toFile(), result);
  }

  public void close() {
    gate.close();
    lock.close();
  }
}
