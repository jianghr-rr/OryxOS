package io.github.oryxlabs.oryxos.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.tool.OryxTool;
import io.github.oryxlabs.oryxos.core.tool.ToolDescriptor;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import io.github.oryxlabs.oryxos.tool.file.ListDirTool;
import io.github.oryxlabs.oryxos.tool.file.ReadFileTool;
import io.github.oryxlabs.oryxos.tool.file.WriteFileTool;
import io.github.oryxlabs.oryxos.tool.http.ControlledHttpClient;
import io.github.oryxlabs.oryxos.tool.http.HttpGetTool;
import io.github.oryxlabs.oryxos.tool.http.HttpPostTool;
import io.github.oryxlabs.oryxos.tool.mcp.McpToolAdapter;
import io.github.oryxlabs.oryxos.tool.nativeext.EchoTool;
import io.github.oryxlabs.oryxos.tool.nativeext.NativeToolRegistrar;
import io.github.oryxlabs.oryxos.tool.notify.NotifyTool;
import io.github.oryxlabs.oryxos.tool.policy.ToolPolicy;
import io.github.oryxlabs.oryxos.tool.shell.ShellTool;
import io.github.oryxlabs.oryxos.tool.validation.ToolArgumentValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("us4-tools")
class OryxToolContractTest {
  private static final String MCP_SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},"
          + "\"required\":[\"query\"],\"additionalProperties\":false}";

  static Stream<OryxTool> allRegisteredTools() {
    List<OryxTool> definitions = new ArrayList<>();
    definitions.add(new ReadFileTool(null));
    definitions.add(new WriteFileTool(null));
    definitions.add(new ListDirTool(null));
    definitions.add(new ShellTool(null));
    definitions.add(new HttpGetTool((ControlledHttpClient) null));
    definitions.add(new HttpPostTool(null));
    definitions.add(new NotifyTool(null, null));
    definitions.addAll(new NativeToolRegistrar().register(List.of(new EchoTool())));
    definitions.add(new McpToolAdapter(null, "demo", "search", "MCP demo search", MCP_SCHEMA));

    ToolRegistry registry =
        new ToolRegistry(definitions, new ToolArgumentValidator(), new ToolPolicy());
    return registry.all().stream().map(descriptor -> registry.find(descriptor.name()));
  }

  @ParameterizedTest
  @MethodSource("allRegisteredTools")
  @DisplayName("每个工具的契约三件套都不能缺")
  void everyRegisteredTool_hasCompleteContract(OryxTool tool) {
    assertNotNull(tool);
    ToolDescriptor descriptor = tool.descriptor();
    assertNotNull(descriptor);
    assertNotNull(descriptor.name());
    assertFalse(descriptor.name().isBlank());
    assertNotNull(descriptor.description());
    assertFalse(descriptor.description().isBlank());
    assertNotNull(descriptor.inputSchemaJson());
    assertFalse(descriptor.inputSchemaJson().isBlank());
    assertDoesNotThrow(() -> new ToolArgumentValidator().register(descriptor.inputSchemaJson()));
  }

  @Test
  @DisplayName("缺失或非法 inputSchema 的工具在注册时被拒绝")
  void registry_rejectsToolWithMissingOrInvalidInputSchema() {
    OryxTool missingSchemaTool =
        new OryxTool() {
          @Override
          public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                "broken_null_schema", "missing schema", null, false, false, null, null);
          }

          @Override
          public void authorize(Map<String, Object> arguments, ExecutionContext context) {}

          @Override
          public ToolResult execute(Map<String, Object> arguments, ExecutionContext context) {
            return ToolResult.success("never");
          }
        };

    OryxTool malformedSchemaTool =
        new OryxTool() {
          @Override
          public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                "broken_bad_schema", "bad json schema", "{not-json", false, false, null, null);
          }

          @Override
          public void authorize(Map<String, Object> arguments, ExecutionContext context) {}

          @Override
          public ToolResult execute(Map<String, Object> arguments, ExecutionContext context) {
            return ToolResult.success("never");
          }
        };

    assertThrows(
        RuntimeException.class,
        () ->
            new ToolRegistry(
                List.of(missingSchemaTool), new ToolArgumentValidator(), new ToolPolicy()));
    assertThrows(
        RuntimeException.class,
        () ->
            new ToolRegistry(
                List.of(malformedSchemaTool), new ToolArgumentValidator(), new ToolPolicy()));
  }
}
