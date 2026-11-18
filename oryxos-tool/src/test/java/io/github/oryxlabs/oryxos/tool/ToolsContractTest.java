package io.github.oryxlabs.oryxos.tool;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.tool.*;
import io.github.oryxlabs.oryxos.tool.file.*;
import io.github.oryxlabs.oryxos.tool.support.ToolFixture;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("us4-tools")
class ToolsContractTest {
  @TempDir Path root;

  @Test
  void filesWriteReadAndListActualOutput() throws Exception {
    try (var f = new ToolFixture(root)) {
      Files.createDirectories(root.resolve("output"));
      var policy = new FileAccessPolicy(root, List.of("output"), List.of("output"));
      var ctx = f.context("writer");
      var write = new WriteFileTool(policy);
      var args = Map.<String, Object>of("path", "output/fact.txt", "content", "事实😀");
      write.authorize(args, ctx);
      assertEquals(ToolResult.Outcome.SUCCESS, write.execute(args, ctx).outcome());
      assertEquals("事实😀", Files.readString(root.resolve("output/fact.txt")));
      assertTrue(
          new ReadFileTool(policy)
              .execute(Map.of("path", "output/fact.txt"), ctx)
              .content()
              .contains("事实😀"));
      assertTrue(
          new ListDirTool(policy)
              .execute(Map.of("path", "output"), ctx)
              .content()
              .contains("fact.txt"));
    }
  }

  @Test
  void deniedControlTraversalAndSymlinkNeverChangeFiles() throws Exception {
    try (var f = new ToolFixture(root.resolve("workspace"))) {
      var policy = new FileAccessPolicy(f.workspace.root(), List.of("."), List.of("."));
      var tool = new WriteFileTool(policy);
      var ctx = f.context("writer");
      for (String path :
          List.of(
              "../outside",
              "runtime.yaml",
              "secrets.local.yaml",
              "oryxos.db-wal",
              "runtime/lock",
              "workspace.json",
              "agents/demo/AGENT.md",
              "USER.md",
              ".env",
              ".env.local"))
        assertThrows(
            OryxError.class,
            () -> tool.authorize(Map.of("path", path, "content", "denied"), ctx),
            path);
      var outside = root.resolve("outside.txt");
      Files.writeString(outside, "untouched");
      Files.createSymbolicLink(f.workspace.root().resolve("escape"), outside);
      assertThrows(
          OryxError.class,
          () -> tool.authorize(Map.of("path", "escape", "content", "denied"), ctx));
      assertEquals("untouched", Files.readString(outside));
    }
  }

  @Test
  void fileReadAndDirectoryListingAreBoundedAndMarked() throws Exception {
    try (var f = new ToolFixture(root)) {
      Files.createDirectories(root.resolve("output"));
      Files.writeString(root.resolve("output/large"), "x".repeat(70000));
      for (int i = 0; i < 1001; i++) Files.createFile(root.resolve("output/item" + i));
      var p = new FileAccessPolicy(root, List.of("output"), List.of());
      var ctx = f.context("reader");
      assertTrue(
          new ReadFileTool(p)
              .execute(Map.of("path", "output/large"), ctx)
              .content()
              .contains("truncated=true"));
      var listed = new ListDirTool(p).execute(Map.of("path", "output"), ctx).content();
      assertTrue(listed.contains("truncated=true"));
      assertTrue(listed.lines().count() <= 1001);
    }
  }

  @Test
  void shellUsesArgvExplicitEnvironmentAndBoundedRedactedOutput() throws Exception {
    try (var f = new ToolFixture(root)) {
      var script = root.resolve("scripts/run.py");
      Files.createDirectories(script.getParent());
      Files.copy(getClass().getResourceAsStream("/scripts/controlled-output.py"), script);
      var ctx = f.context("runner");
      var secrets =
          new io.github.oryxlabs.oryxos.core.security.SecretResolver(
              Map.of("FIXTURE_SECRET", "fixture-explicit-secret"),
              root.resolve("none"),
              ctx.redactor());
      var policy =
          new io.github.oryxlabs.oryxos.tool.shell.ProcessPolicy(
              root,
              Map.of(
                  "allowed_commands",
                  List.of("/usr/bin/python3"),
                  "allowed_script_paths",
                  List.of("scripts"),
                  "env",
                  Map.of("ALLOWED_TEST_VALUE", "${FIXTURE_SECRET}")),
              secrets);
      var tool = new io.github.oryxlabs.oryxos.tool.shell.ShellTool(policy);
      var result =
          tool.execute(
              Map.of(
                  "command",
                  "/usr/bin/python3",
                  "args",
                  List.of(script.toString(), "hello; touch forbidden")),
              ctx);
      assertEquals(ToolResult.Outcome.SUCCESS, result.outcome());
      assertTrue(result.content().contains("hello; touch forbidden"));
      assertTrue(result.content().contains("\"foreign\": null"));
      assertTrue(result.content().contains("\"provider\": null"));
      assertFalse(result.content().contains("fixture-explicit-secret"));
      assertFalse(Files.exists(root.resolve("forbidden")));
      result =
          tool.execute(
              Map.of("command", "/usr/bin/python3", "args", List.of(script.toString(), "large")),
              ctx);
      assertTrue(result.content().contains("truncated=true"));
      assertFalse(result.content().contains("fixture-explicit-secret"));
      assertThrows(
          OryxError.class,
          () ->
              tool.authorize(
                  Map.of("command", "/usr/bin/python3", "args", List.of("-c", "print(1)")), ctx));
    }
  }

  @Test
  void shellTimeoutIsUnknownAndProcessDoesNotContinue() throws Exception {
    try (var f = new ToolFixture(root)) {
      var script = root.resolve("scripts/run.py");
      Files.createDirectories(script.getParent());
      Files.copy(getClass().getResourceAsStream("/scripts/controlled-output.py"), script);
      var ctx = f.context("runner");
      var policy =
          new io.github.oryxlabs.oryxos.tool.shell.ProcessPolicy(
              root,
              Map.of(
                  "allowed_commands",
                  List.of("/usr/bin/python3"),
                  "allowed_script_paths",
                  List.of("scripts"),
                  "timeout_seconds",
                  1),
              new io.github.oryxlabs.oryxos.core.security.SecretResolver(
                  Map.of(), root.resolve("none"), ctx.redactor()));
      var result =
          new io.github.oryxlabs.oryxos.tool.shell.ShellTool(policy)
              .execute(
                  Map.of(
                      "command", "/usr/bin/python3", "args", List.of(script.toString(), "sleep")),
                  ctx);
      assertEquals(ToolResult.Outcome.UNKNOWN, result.outcome());
      assertTrue(result.content().contains("cleanupConfirmed=true"));
      assertEquals(ToolResult.RetryClass.NONE, result.retryClass());
    }
  }

  @Test
  void nativeAnnotationUsesUnifiedValidationPermissionAndAudit() throws Exception {
    try (var f = new ToolFixture(root)) {
      var registered =
          new io.github.oryxlabs.oryxos.tool.nativeext.NativeToolRegistrar()
              .register(List.of(new io.github.oryxlabs.oryxos.tool.nativeext.EchoTool()));
      assertThrows(
          OryxError.class,
          () ->
              new io.github.oryxlabs.oryxos.tool.nativeext.NativeToolRegistrar()
                  .register(
                      List.of(
                          new io.github.oryxlabs.oryxos.tool.nativeext.EchoTool(),
                          new io.github.oryxlabs.oryxos.tool.nativeext.EchoTool())));
      var registry =
          new ToolRegistry(
              registered,
              new io.github.oryxlabs.oryxos.tool.validation.ToolArgumentValidator(),
              new io.github.oryxlabs.oryxos.tool.policy.ToolPolicy());
      var audit = new java.util.ArrayList<io.github.oryxlabs.oryxos.core.audit.ToolCallRecord>();
      var sink =
          new io.github.oryxlabs.oryxos.core.audit.AuditSink() {
            public void modelCall(io.github.oryxlabs.oryxos.core.audit.ModelCallRecord r) {}

            public void toolCall(io.github.oryxlabs.oryxos.core.audit.ToolCallRecord r) {
              audit.add(r);
            }
          };
      var executor = new ToolExecutor(registry, sink, d -> fail("No retry"));
      var call =
          new io.github.oryxlabs.oryxos.core.model.ToolCall(
              "echo-1", "echo", "echo", "{\"text\":\"sample\"}");
      assertEquals("sample", executor.execute(f.context("caller"), call).result().content());
      assertEquals(List.of("STARTED", "SUCCESS"), audit.stream().map(r -> r.status()).toList());
      f.tools = List.of();
      assertEquals(
          ToolResult.Outcome.DENIED,
          executor.execute(f.context("denied"), call).result().outcome());
      assertEquals("DENIED", audit.getLast().status());
    }
  }

  @Test
  void actualChildJvmWithForeignCanaryDoesNotLeakIntoShellOrMcp() throws Exception {
    String cp =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    var builder =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp",
                cp,
                EnvironmentBoundaryProbe.class.getName(),
                root.resolve("probe").toString())
            .redirectErrorStream(true);
    builder.environment().put("ORYXOS_PARENT_CANARY", "fixture-parent-canary");
    builder.environment().put("DEEPSEEK_API_KEY", "fixture-provider-canary");
    var log = root.resolve("probe-output.txt");
    builder.redirectOutput(log.toFile());
    var process = builder.start();
    try {
      assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(0, process.exitValue(), Files.readString(log));
      String output = Files.readString(log);
      assertTrue(output.contains("ENVIRONMENT_BOUNDARY_PASS"));
      assertFalse(output.contains("fixture-parent-canary"));
      assertFalse(output.contains("fixture-provider-canary"));
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }
}
