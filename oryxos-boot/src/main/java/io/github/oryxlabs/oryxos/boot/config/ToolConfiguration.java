package io.github.oryxlabs.oryxos.boot.config;

import io.github.oryxlabs.oryxos.core.agent.AgentCatalog;
import io.github.oryxlabs.oryxos.core.config.SafeYaml;
import io.github.oryxlabs.oryxos.core.security.*;
import io.github.oryxlabs.oryxos.core.tool.*;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import io.github.oryxlabs.oryxos.tool.ToolRegistry;
import io.github.oryxlabs.oryxos.tool.file.*;
import io.github.oryxlabs.oryxos.tool.http.*;
import io.github.oryxlabs.oryxos.tool.mcp.*;
import io.github.oryxlabs.oryxos.tool.nativeext.*;
import io.github.oryxlabs.oryxos.tool.notify.*;
import io.github.oryxlabs.oryxos.tool.policy.*;
import io.github.oryxlabs.oryxos.tool.shell.*;
import io.github.oryxlabs.oryxos.tool.validation.ToolArgumentValidator;
import java.util.*;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class ToolConfiguration {
  public record CheckedAgents(AgentCatalog catalog) {}

  private Map<String, Object> rules(RuntimeConfiguration.LoadedRuntime loaded, String type) {
    return loaded.config() == null
        ? Map.of()
        : SafeYaml.map(loaded.config().toolRules().getOrDefault(type, Map.of()), "tools." + type);
  }

  @Bean
  FileAccessPolicy fileAccessPolicy(
      WorkspaceHandle workspace, RuntimeConfiguration.LoadedRuntime loaded) {
    var r = rules(loaded, "file");
    return new FileAccessPolicy(
        workspace.root(), strings(r, "allowed_read_paths"), strings(r, "allowed_write_paths"));
  }

  private List<String> strings(Map<String, Object> r, String key) {
    return ((List<?>) r.getOrDefault(key, List.of())).stream().map(Object::toString).toList();
  }

  @Bean
  ReadFileTool readFileTool(FileAccessPolicy p) {
    return new ReadFileTool(p);
  }

  @Bean
  WriteFileTool writeFileTool(FileAccessPolicy p) {
    return new WriteFileTool(p);
  }

  @Bean
  ListDirTool listDirTool(FileAccessPolicy p) {
    return new ListDirTool(p);
  }

  @Bean
  ShellTool shellTool(
      WorkspaceHandle workspace, RuntimeConfiguration.LoadedRuntime loaded, Redactor redactor) {
    return new ShellTool(
        new ProcessPolicy(
            workspace.root(),
            rules(loaded, "shell"),
            new SecretResolver(
                System.getenv(), workspace.root().resolve("secrets.local.yaml"), redactor)));
  }

  @Bean(destroyMethod = "close")
  ControlledHttpClient controlledHttpClient(RuntimeConfiguration.LoadedRuntime loaded) {
    var r = rules(loaded, "http");
    return new ControlledHttpClient(
        HttpTargetPolicy.from(r),
        SafeYaml.positive(r.getOrDefault("connect_timeout_seconds", 5), "HTTP connect"),
        SafeYaml.positive(r.getOrDefault("read_timeout_seconds", 20), "HTTP read"),
        SafeYaml.positive(r.getOrDefault("max_response_bytes", 65536), "HTTP response bound"));
  }

  @Bean
  HttpGetTool httpGetTool(ControlledHttpClient http) {
    return new HttpGetTool(http);
  }

  @Bean
  HttpPostTool httpPostTool(ControlledHttpClient http) {
    return new HttpPostTool(http);
  }

  @Bean
  NotifyTool notifyTool(
      ProviderConfiguration.LoadedAgents agents,
      WorkspaceHandle workspace,
      Redactor redactor,
      ControlledHttpClient http) {
    var profiles =
        agents.catalog() == null
            ? List.<io.github.oryxlabs.oryxos.core.agent.AgentProfile>of()
            : agents.catalog().entries().stream()
                .filter(e -> e.available())
                .map(e -> e.profile())
                .toList();
    return new NotifyTool(
        new NotificationTargetResolver(
            profiles,
            new SecretResolver(
                System.getenv(), workspace.root().resolve("secrets.local.yaml"), redactor)),
        new WebhookNotifyAdapter(http));
  }

  @Bean
  ToolRegistryPort toolRegistry(List<OryxTool> tools, McpClientManager mcp) {
    var all = new ArrayList<>(tools);
    all.addAll(new NativeToolRegistrar().register(List.of(new EchoTool())));
    all.addAll(new McpToolDiscovery().discover(mcp));
    var retry = new ToolRetryPolicy();
    return new ToolRegistry(
        all.stream().map(retry::apply).toList(), new ToolArgumentValidator(), new ToolPolicy());
  }

  @Bean
  CheckedAgents checkedAgents(
      ProviderConfiguration.LoadedAgents agents, ToolRegistryPort tools, McpClientManager mcp) {
    return new CheckedAgents(
        agents.catalog() == null
            ? null
            : agents
                .catalog()
                .validateTools(tools, mcp::available)
                .validateSchedules(
                    org.springframework.scheduling.support.CronExpression::isValidExpression));
  }
}
