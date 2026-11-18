package io.github.oryxlabs.oryxos.tool;

import io.github.oryxlabs.oryxos.core.agent.AgentProfile;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.model.ToolNameMap;
import io.github.oryxlabs.oryxos.core.tool.*;
import io.github.oryxlabs.oryxos.tool.policy.ToolPolicy;
import io.github.oryxlabs.oryxos.tool.validation.ToolArgumentValidator;
import java.util.*;

public final class ToolRegistry implements ToolRegistryPort {
  private final Map<String, OryxTool> tools;
  private final ToolArgumentValidator validator;
  private final ToolPolicy policy;

  public ToolRegistry(
      List<OryxTool> definitions, ToolArgumentValidator validator, ToolPolicy policy) {
    var entries = new LinkedHashMap<String, OryxTool>();
    var names = new ArrayList<ToolNameMap.Entry>();
    for (var tool : definitions) {
      var d = tool.descriptor();
      validator.register(d.inputSchemaJson());
      if (entries.putIfAbsent(d.name(), tool) != null)
        throw OryxError.configuration("duplicate tool name");
      names.add(
          new ToolNameMap.Entry(
              d.name(), ToolNameMap.wireName(d.name()), d.serverId(), d.remoteName()));
    }
    new ToolNameMap(names);
    this.tools = Map.copyOf(entries);
    this.validator = validator;
    this.policy = policy;
  }

  public OryxTool find(String name) {
    return name == null ? null : tools.get(name);
  }

  public List<ToolDescriptor> all() {
    return tools.values().stream()
        .map(OryxTool::descriptor)
        .sorted(Comparator.comparing(ToolDescriptor::name))
        .toList();
  }

  public List<ToolDescriptor> available(AgentProfile agent) {
    return tools.values().stream()
        .map(OryxTool::descriptor)
        .filter(t -> agent.tools().contains(t.name()))
        .sorted(Comparator.comparing(ToolDescriptor::name))
        .toList();
  }

  public Validation validate(ToolDescriptor descriptor, String args) {
    return validator.validate(descriptor.inputSchemaJson(), args);
  }

  public void authorize(OryxTool tool, Map<String, Object> args, ExecutionContext ctx) {
    policy.authorize(tool, args, ctx);
  }
}
