package io.github.oryxlabs.oryxos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.security.SecretResolver;
import io.github.oryxlabs.oryxos.core.tool.OryxTool;
import io.github.oryxlabs.oryxos.tool.ToolRegistry;
import io.github.oryxlabs.oryxos.tool.policy.ToolPolicy;
import io.github.oryxlabs.oryxos.tool.validation.ToolArgumentValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("us4-tools")
class McpClientServiceTest {
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
  @DisplayName("某个 MCP server 失联，不能拖垮启动和其他工具")
  void disconnectedMcpServer_doesNotBreakStartupOrOtherServers() throws Exception {
    var bad = fixtureDefinition("bad", "init-fail");
    var good = fixtureDefinition("good", "normal");

    try (var manager =
        new McpClientManager(
            root, List.of(bad, good), new Redactor(), new ToolArgumentValidator())) {
      List<OryxTool> discovered = new McpToolDiscovery().discover(manager);
      var registry = new ToolRegistry(discovered, new ToolArgumentValidator(), new ToolPolicy());

      assertTrue(manager.available("good"));
      assertFalse(manager.available("bad"));
      assertNotNull(registry.find("mcp.good.echo"));
      assertNotNull(registry.find("mcp.good.other"));
      assertNull(registry.find("mcp.bad.echo"));

      var badState =
          manager.states().stream().filter(s -> s.id().equals("bad")).findFirst().orElseThrow();
      assertFalse(badState.available());
      assertEquals("MCP_INITIALIZATION_FAILED", badState.code());
    }
  }

  @Test
  @DisplayName("从 mcp_servers.yaml 加载 MCP 配置并解析环境变量引用")
  void loadsMcpServersYamlAndResolvesEnvSecrets() throws Exception {
    Files.writeString(
        root.resolve("mcp_servers.yaml"),
        "schema_version: 1\n"
            + "servers:\n"
            + "  news:\n"
            + "    transport: stdio\n"
            + "    command: /usr/bin/python3\n"
            + "    args: ['/tmp/news_server.py']\n"
            + "    env:\n"
            + "      NEWS_TOKEN: '${MCP_NEWS_TOKEN}'\n"
            + "  unsupported:\n"
            + "    transport: sse\n"
            + "    command: /usr/bin/python3\n");

    var redactor = new Redactor();
    var secrets =
        new SecretResolver(
            Map.of("MCP_NEWS_TOKEN", "secret-news-token-999"), root.resolve("none"), redactor);

    List<McpClientManager.Definition> loaded =
        McpClientManager.definitions(root, Set.of("news"), secrets);
    assertEquals(1, loaded.size());
    McpClientManager.Definition def = loaded.getFirst();
    assertEquals("news", def.id());
    assertEquals("/usr/bin/python3", def.command());
    assertEquals(List.of("/tmp/news_server.py"), def.args());
    assertEquals("secret-news-token-999", def.environment().get("NEWS_TOKEN"));
    assertFalse(def.toString().contains("secret-news-token-999"));

    OryxError transportError =
        assertThrows(
            OryxError.class,
            () -> McpClientManager.definitions(root, Set.of("unsupported"), secrets));
    assertEquals("CONFIGURATION_ERROR", transportError.code());
  }
}
