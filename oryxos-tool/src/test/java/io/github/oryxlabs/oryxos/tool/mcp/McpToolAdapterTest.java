package io.github.oryxlabs.oryxos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.tool.OryxTool;
import io.github.oryxlabs.oryxos.core.tool.ToolDescriptor;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import io.github.oryxlabs.oryxos.tool.support.ToolFixture;
import io.github.oryxlabs.oryxos.tool.validation.ToolArgumentValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("us4-tools")
class McpToolAdapterTest {
  @TempDir Path root;

  private McpClientManager.Definition fixtureDefinition(String id, String mode) throws Exception {
    Path script = root.resolve("fixture.py");
    if (!Files.exists(script)) {
      Files.copy(getClass().getResourceAsStream("/mcp/fixture-server.py"), script);
    }
    return new McpClientManager.Definition(
        id,
        "/usr/bin/python3",
        List.of(script.toString(), mode, root.resolve(id + ".jsonl").toString()),
        Map.of("EXPLICIT_SECRET", "fixture-mcp-secret"),
        2,
        1);
  }

  @Test
  @DisplayName("McpToolAdapter 映射 MCP 元数据并原样转发参数返回 ToolResult")
  void adapter_mapsMetadataAndForwardsArgumentsToRemoteTool() throws Exception {
    try (var fixture = new ToolFixture(root);
        var manager =
            new McpClientManager(
                root,
                List.of(fixtureDefinition("first", "normal")),
                new Redactor(),
                new ToolArgumentValidator())) {
      List<OryxTool> discovered = new McpToolDiscovery().discover(manager);
      OryxTool adapter =
          discovered.stream()
              .filter(t -> t.descriptor().name().equals("mcp.first.echo"))
              .findFirst()
              .orElseThrow();

      ToolDescriptor descriptor = adapter.descriptor();
      assertEquals("mcp.first.echo", descriptor.name());
      assertEquals("fixture echo", descriptor.description());
      assertNotNull(descriptor.inputSchemaJson());
      assertTrue(descriptor.inputSchemaJson().contains("\"text\""));
      assertEquals("MCP", descriptor.source());
      assertEquals("first", descriptor.serverId());
      assertEquals("echo", descriptor.remoteName());

      fixture.servers = List.of("first");
      var ctx = fixture.context("caller");
      Map<String, Object> args = Map.of("text", "hello-mcp");
      adapter.authorize(args, ctx);
      ToolResult result = adapter.execute(args, ctx);

      assertEquals(ToolResult.Outcome.SUCCESS, result.outcome());
      assertEquals("echo:hello-mcp", result.content());
      String callLog = Files.readString(root.resolve("first.jsonl"));
      assertTrue(callLog.contains("\"method\": \"tools/call\""));
      assertTrue(callLog.contains("\"name\": \"echo\""));
      assertFalse(callLog.contains("mcp.first.echo"));
    }
  }

  @Test
  @DisplayName("McpToolAdapter 拒绝未授权 server 并将远端失败封装为不可重试 ToolResult")
  void adapter_rejectsUnauthorizedServerAndWrapsRemoteError() throws Exception {
    try (var fixture = new ToolFixture(root);
        var manager =
            new McpClientManager(
                root,
                List.of(fixtureDefinition("failed", "is-error")),
                new Redactor(),
                new ToolArgumentValidator())) {
      OryxTool adapter =
          new McpToolDiscovery()
              .discover(manager).stream()
                  .filter(t -> t.descriptor().name().equals("mcp.failed.echo"))
                  .findFirst()
                  .orElseThrow();

      fixture.servers = List.of();
      var unauthorizedCtx = fixture.context("unlisted");
      OryxError denied =
          assertThrows(
              OryxError.class, () -> adapter.authorize(Map.of("text", "blocked"), unauthorizedCtx));
      assertEquals("TARGET_DENIED", denied.code());

      fixture.servers = List.of("failed");
      var authorizedCtx = fixture.context("listed");
      adapter.authorize(Map.of("text", "x"), authorizedCtx);
      ToolResult result = adapter.execute(Map.of("text", "x"), authorizedCtx);

      assertEquals(ToolResult.Outcome.ERROR, result.outcome());
      assertEquals("MCP_TOOL_ERROR", result.errorCode());
      assertEquals(ToolResult.RetryClass.NONE, result.retryClass());
    }
  }
}
