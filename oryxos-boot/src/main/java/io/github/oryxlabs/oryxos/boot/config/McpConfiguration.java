package io.github.oryxlabs.oryxos.boot.config;

import io.github.oryxlabs.oryxos.core.security.*;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import io.github.oryxlabs.oryxos.tool.mcp.*;
import io.github.oryxlabs.oryxos.tool.validation.ToolArgumentValidator;
import java.util.*;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class McpConfiguration {
  @Bean(destroyMethod = "close")
  McpClientManager mcpClientManager(
      WorkspaceHandle workspace, ProviderConfiguration.LoadedAgents agents, Redactor redactor) {
    var selected = new TreeSet<String>();
    if (agents.catalog() != null)
      agents.catalog().entries().stream()
          .filter(e -> e.available())
          .forEach(e -> selected.addAll(e.profile().mcpServers()));
    var secrets =
        new SecretResolver(
            System.getenv(), workspace.root().resolve("secrets.local.yaml"), redactor);
    var definitions = new ArrayList<McpClientManager.Definition>();
    for (String name : selected)
      try {
        definitions.addAll(McpClientManager.definitions(workspace.root(), Set.of(name), secrets));
      } catch (RuntimeException e) {
        definitions.add(new McpClientManager.Definition(name, "", List.of(), Map.of(), 20, 20));
      }
    return new McpClientManager(
        workspace.root(), definitions, redactor, new ToolArgumentValidator());
  }
}
