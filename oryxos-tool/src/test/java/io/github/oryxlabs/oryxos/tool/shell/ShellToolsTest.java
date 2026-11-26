package io.github.oryxlabs.oryxos.tool.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.security.SecretResolver;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import io.github.oryxlabs.oryxos.tool.support.ToolFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("us4-tools")
class ShellToolsTest {
  @TempDir Path root;

  @Test
  @DisplayName("shell 允许的命令与受控脚本应能正常跑通")
  void shell_executesAllowedCommandAndTrustedScript() throws Exception {
    try (var fixture = new ToolFixture(root)) {
      Path script = root.resolve("scripts/run.py");
      Files.createDirectories(script.getParent());
      Files.writeString(script, "print('shell-ok')\n");

      var ctx = fixture.context("runner");
      var policy =
          new ProcessPolicy(
              root,
              Map.of(
                  "allowed_commands",
                  List.of("/usr/bin/python3"),
                  "allowed_script_paths",
                  List.of("scripts")),
              new SecretResolver(Map.of(), root.resolve("none"), ctx.redactor()));
      var tool = new ShellTool(policy);
      Map<String, Object> args =
          Map.of("command", "/usr/bin/python3", "args", List.of(script.toString()));

      tool.authorize(args, ctx);
      ToolResult result = tool.execute(args, ctx);

      assertEquals(ToolResult.Outcome.SUCCESS, result.outcome());
      assertTrue(result.content().contains("exitCode=0"));
      assertTrue(result.content().contains("shell-ok"));
    }
  }

  @Test
  @DisplayName("shell 命中白名单外命令或禁止参数应被拦下")
  void shell_rejectsDisallowedCommandAndInlineFlag() throws Exception {
    try (var fixture = new ToolFixture(root)) {
      Path disallowedScript = root.resolve("other/evil.py");
      Files.createDirectories(disallowedScript.getParent());
      Files.writeString(disallowedScript, "print('should-not-run')\n");

      var ctx = fixture.context("runner");
      var policy =
          new ProcessPolicy(
              root,
              Map.of(
                  "allowed_commands",
                  List.of("/usr/bin/python3"),
                  "allowed_script_paths",
                  List.of("scripts")),
              new SecretResolver(Map.of(), root.resolve("none"), ctx.redactor()));
      var tool = new ShellTool(policy);

      assertThrows(
          OryxError.class,
          () -> tool.authorize(Map.of("command", "/bin/sh", "args", List.of("-c", "id")), ctx));
      assertThrows(
          OryxError.class,
          () ->
              tool.authorize(
                  Map.of("command", "/usr/bin/python3", "args", List.of("-c", "print(1)")), ctx));
      assertThrows(
          OryxError.class,
          () ->
              tool.authorize(
                  Map.of(
                      "command", "/usr/bin/python3", "args", List.of(disallowedScript.toString())),
                  ctx));
    }
  }
}
