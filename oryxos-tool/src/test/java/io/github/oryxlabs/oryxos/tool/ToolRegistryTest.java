package io.github.oryxlabs.oryxos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.oryxlabs.oryxos.core.agent.AgentProfile;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.tool.OryxTool;
import io.github.oryxlabs.oryxos.core.tool.ToolDescriptor;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import io.github.oryxlabs.oryxos.tool.file.ReadFileTool;
import io.github.oryxlabs.oryxos.tool.http.ControlledHttpClient;
import io.github.oryxlabs.oryxos.tool.http.HttpGetTool;
import io.github.oryxlabs.oryxos.tool.mcp.McpToolAdapter;
import io.github.oryxlabs.oryxos.tool.nativeext.EchoTool;
import io.github.oryxlabs.oryxos.tool.nativeext.NativeToolRegistrar;
import io.github.oryxlabs.oryxos.tool.policy.ToolPolicy;
import io.github.oryxlabs.oryxos.tool.validation.ToolArgumentValidator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("us4-tools")
class ToolRegistryTest {
  private static final String MCP_SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},"
          + "\"required\":[\"text\"],\"additionalProperties\":false}";

  private static ToolRegistry registryWithAllThreeSources() {
    List<OryxTool> definitions = new ArrayList<>();
    definitions.add(new ReadFileTool(null));
    definitions.add(new HttpGetTool((ControlledHttpClient) null));
    definitions.addAll(new NativeToolRegistrar().register(List.of(new EchoTool())));
    definitions.add(new McpToolAdapter(null, "demo", "echo", "MCP demo echo", MCP_SCHEMA));
    return new ToolRegistry(definitions, new ToolArgumentValidator(), new ToolPolicy());
  }

  private static AgentProfile profileWithTools(List<String> declaredTools) {
    return new AgentProfile(
        "demo",
        "demo agent",
        "local",
        "model",
        declaredTools,
        List.of("demo"),
        List.of(),
        Map.of(),
        List.of(),
        10,
        20,
        Path.of("AGENT.md"),
        "rev-1",
        "body");
  }

  @Test
  @DisplayName("三种来源的工具都以 OryxTool 身份注册进来")
  void registersAllThreeSourcesAsUnifiedOryxTool() {
    ToolRegistry registry = registryWithAllThreeSources();

    OryxTool builtin = registry.find("read_file");
    OryxTool nativeExt = registry.find("echo");
    OryxTool mcpTool = registry.find("mcp.demo.echo");

    assertNotNull(builtin);
    assertNotNull(nativeExt);
    assertNotNull(mcpTool);
    assertEquals("BUILTIN", builtin.descriptor().source());
    assertEquals("NATIVE", nativeExt.descriptor().source());
    assertEquals("MCP", mcpTool.descriptor().source());
    assertEquals(4, registry.all().size());
    assertNull(registry.find("non_existent_tool"));
  }

  @Test
  @DisplayName("按 Profile 的 tools 字段过滤后，子集精确匹配、不多不少")
  void filtersByProfileToolsExactlyWithoutExtraOrMissing() {
    ToolRegistry registry = registryWithAllThreeSources();
    List<String> declared = List.of("read_file", "echo", "mcp.demo.echo");

    List<ToolDescriptor> available = registry.available(profileWithTools(declared));
    Set<String> actualNames =
        available.stream().map(ToolDescriptor::name).collect(Collectors.toSet());

    assertEquals(declared.size(), available.size());
    assertEquals(Set.copyOf(declared), actualNames);
    assertTrue(registry.available(profileWithTools(List.of())).isEmpty());

    List<ToolDescriptor> withMissing =
        registry.available(profileWithTools(List.of("http_get", "does_not_exist")));
    assertEquals(List.of("http_get"), withMissing.stream().map(ToolDescriptor::name).toList());
  }

  @Test
  @DisplayName("重复工具名在注册时被拒绝")
  void rejectsDuplicateToolNamesAtRegistration() {
    OryxTool first = new ReadFileTool(null);
    OryxTool duplicate =
        new OryxTool() {
          @Override
          public ToolDescriptor descriptor() {
            return ReadFileTool.staticDescriptor();
          }

          @Override
          public void authorize(Map<String, Object> arguments, ExecutionContext context) {}

          @Override
          public ToolResult execute(Map<String, Object> arguments, ExecutionContext context) {
            return ToolResult.success("dup");
          }
        };

    OryxError error =
        assertThrows(
            OryxError.class,
            () ->
                new ToolRegistry(
                    List.of(first, duplicate), new ToolArgumentValidator(), new ToolPolicy()));
    assertEquals("CONFIGURATION_ERROR", error.code());
  }
}
