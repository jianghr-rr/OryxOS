package io.github.oryxlabs.oryxos.boot.support;

import io.github.oryxlabs.oryxos.boot.OryxOsApplication;
import io.github.oryxlabs.oryxos.boot.config.*;
import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.core.audit.*;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.model.ToolNameMap;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.session.*;
import io.github.oryxlabs.oryxos.core.workspace.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Separate JVM, same production launch path. halt deliberately bypasses shutdown hooks. */
public final class PersistentRuntimeProbe {
  public static void main(String[] args) throws Exception {
    var root = Path.of(args[0]);
    if (args[1].equals("chat")) {
      var command =
          new ArrayList<>(
              List.of("--root", root.toString(), "chat", "--profile", "local", "--user", "alice"));
      if (args.length > 2) {
        command.add("--session");
        command.add(args[2]);
      }
      int code =
          OryxOsApplication.execute(
              command.toArray(String[]::new),
              new BootRuntimeLauncher(),
              new java.io.StringReader("remember this turn\n/quit\n"),
              new java.io.PrintWriter(System.out, true),
              new java.io.PrintWriter(System.err, true));
      System.exit(code);
    }
    var workspace = new WorkspaceHandle(root);
    var context =
        new BootRuntimeLauncher()
            .start(workspace, Map.of("spring.main.web-application-type", "none"));
    var manager = context.getBean(SessionManager.class);
    var profile = context.getBean(ToolConfiguration.CheckedAgents.class).catalog().require("local");
    var lease = manager.acquireForExecution(null, "cli", "alice", profile, Session.Kind.ONE_SHOT);
    var now = Instant.now();
    var execution =
        new Execution(
            UUID.randomUUID(),
            lease.session().id(),
            UUID.randomUUID(),
            ProcessRequest.manual("cli", "alice", "local", null, "crash"),
            profile.configRevision(),
            now,
            now.plusSeconds(60),
            ZoneId.of("Asia/Shanghai"),
            ToolNameMap.empty());
    lease.begin(execution.snapshot(0), "crash", context.getBean(ExecutionStore.class));
    context
        .getBean(AuditSink.class)
        .modelCall(
            new ModelCallRecord(
                UUID.randomUUID(),
                execution.id(),
                1,
                1,
                "local",
                "requested-model",
                null,
                "STARTED",
                now,
                null,
                null,
                null,
                null,
                null));
    Files.writeString(root.resolve("probe-execution.txt"), execution.id().toString());
    Runtime.getRuntime().halt(23);
  }
}
