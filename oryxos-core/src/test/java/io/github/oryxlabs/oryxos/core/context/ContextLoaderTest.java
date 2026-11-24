package io.github.oryxlabs.oryxos.core.context;

import static io.github.oryxlabs.oryxos.core.react.LoopFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.execution.Execution;
import io.github.oryxlabs.oryxos.core.react.LoopFixture;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("us2-loop")
class ContextLoaderTest {
  @TempDir Path root;

  @Test
  void bodyAndBootstrapAreReloadedWithoutChangingStartupConfiguration() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      f.add(
          "first",
          false,
          (args, ctx) -> {
            try {
              Files.writeString(
                  f.profile.definition(), "---\nprovider: malicious override\n---\nupdated body");
              Files.writeString(root.resolve("USER.md"), "updated bootstrap");
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
            return io.github.oryxlabs.oryxos.core.tool.ToolResult.success(
                "reference and script output fixture");
          });
      f.script.add(reply(null, call("one", "first", "{\"value\":1}")));
      f.script.add(reply("done"));
      assertDoesNotThrow(f::run);
      assertTrue(f.requests.getFirst().systemPrompt().contains("initial instructions"));
      assertTrue(f.requests.get(1).systemPrompt().contains("updated body"));
      assertTrue(f.requests.get(1).systemPrompt().contains("updated bootstrap"));
      assertTrue(f.requests.get(1).systemPrompt().contains("2026-11-13"));
      assertEquals("fixture", f.requests.get(1).providerName());
      assertFalse(f.requests.get(1).systemPrompt().contains("malicious override"));
    }
  }

  @Test
  void loadRereadsFilesOnEveryCall() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var loader = new ContextLoader();
      String first = loader.load(f.context);
      Files.writeString(f.profile.definition(), "---\nname: demo\n---\nsecond body\n");
      Files.writeString(root.resolve("USER.md"), "second bootstrap");
      String second = loader.load(f.context);
      assertTrue(first.contains("initial instructions") && first.contains("initial bootstrap"));
      assertTrue(second.contains("second body") && second.contains("second bootstrap"));
      assertFalse(second.contains("initial instructions") || second.contains("initial bootstrap"));
    }
  }

  @Test
  void missingOversizedAndSecretBootstrapFailBeforeModelIo() throws Exception {
    for (String scenario : java.util.List.of("missing", "large", "secret"))
      try (var f = new LoopFixture(root.resolve(scenario), 10)) {
        var path = f.context.workspace().root().resolve("USER.md");
        if (scenario.equals("large"))
          java.nio.file.Files.writeString(path, "x".repeat(1024 * 1024 + 1));
        else {
          java.nio.file.Files.delete(path);
          if (scenario.equals("secret")) {
            var secret = path.resolveSibling("secrets.local.yaml");
            java.nio.file.Files.writeString(secret, "PRIVATE: do-not-read");
            java.nio.file.Files.createSymbolicLink(path, secret);
          }
        }
        assertEquals(Execution.Status.FAILED, f.run().status());
        assertTrue(f.requests.isEmpty());
        assertTrue(f.effects.isEmpty());
      }
  }

  @Test
  void referencesAreReadOnDemandAndOnlyScriptOutputEntersHistory() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      java.nio.file.Files.writeString(root.resolve("reference.md"), "REFERENCE_ONLY_ON_DEMAND");
      java.nio.file.Files.writeString(root.resolve("script.sh"), "PRIVATE_SCRIPT_SOURCE");
      f.add(
          "first",
          false,
          (a, c) ->
              io.github.oryxlabs.oryxos.core.tool.ToolResult.success("REFERENCE_ONLY_ON_DEMAND"));
      f.add(
          "second",
          false,
          (a, c) -> io.github.oryxlabs.oryxos.core.tool.ToolResult.success("SCRIPT_OUTPUT_ONLY"));
      f.script.add(
          reply(
              null,
              call("ref", "first", "{\"value\":1}"),
              call("script", "second", "{\"value\":1}")));
      f.script.add(reply("done"));
      assertEquals(Execution.Status.SUCCEEDED, f.run().status());
      assertFalse(f.requests.getFirst().systemPrompt().contains("REFERENCE_ONLY_ON_DEMAND"));
      var messages =
          f.requests.getLast().orderedMessages().stream()
              .map(m -> java.util.Objects.toString(m.content(), " "))
              .collect(java.util.stream.Collectors.joining(" "));
      assertTrue(messages.contains("REFERENCE_ONLY_ON_DEMAND"));
      assertTrue(messages.contains("SCRIPT_OUTPUT_ONLY"));
      assertFalse(messages.contains("PRIVATE_SCRIPT_SOURCE"));
    }
  }
}
