package io.github.oryxlabs.oryxos.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.workspace.*;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("us1-contract")
class AgentStatusTest {
  @TempDir Path root;

  @Test
  void statusParsesCurrentAgentsWithoutStartingRuntimeOrLeakingTargets() throws Exception {
    new WorkspaceInitializer()
        .initialize(
            new WorkspaceHandle(root),
            Map.of(
                "runtime.yaml",
                "schema_version: 1\nproviders:\n  good:\n    protocol: openai_chat_completions\n    endpoint: http://127.0.0.1:1\n    completions_path: /chat/completions\n    api_key: '${GOOD_KEY}'\n"));
    Path file = root.resolve("agents/good/AGENT.md");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        "---\nschema_version: 1\nname: good\nprovider: {name: good, model: configured}\nnotify_channels:\n  daily:\n    type: webhook\n    url: '${HOOK}'\n    payload: {text: '$CONTENT'}\n    acknowledgement: {mode: http_2xx}\n---\nprivate task\n");
    var output = new StringWriter();
    var cli =
        new OryxCommandLine(
                (mode, workspace) -> {
                  fail("status started runtime");
                  return 3;
                },
                new WorkspaceInspector(
                    Map.of(
                        "GOOD_KEY", "private-key", "HOOK", "https://example.invalid/private-hook")))
            .commandLine();
    cli.setOut(new PrintWriter(output));
    cli.setErr(new PrintWriter(output));
    assertEquals(0, cli.execute("--root", root.toString(), "status"));
    assertTrue(output.toString().contains("good: CONFIGURED"));
    assertTrue(output.toString().contains("daily"));
    assertFalse(output.toString().contains("private-"));
    assertFalse(output.toString().contains("private task"));
    assertFalse(Files.exists(root.resolve("oryxos.db")));
  }
}
