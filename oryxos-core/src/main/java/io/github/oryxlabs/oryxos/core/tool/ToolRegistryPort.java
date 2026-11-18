package io.github.oryxlabs.oryxos.core.tool;

import io.github.oryxlabs.oryxos.core.agent.AgentProfile;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import java.util.*;

public interface ToolRegistryPort {
  record Validation(
      Map<String, Object> arguments,
      boolean parseable,
      Set<String> errorPointers,
      String safeMessage) {
    public Validation {
      arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
      errorPointers = Set.copyOf(errorPointers);
    }

    public boolean valid() {
      return parseable && errorPointers.isEmpty();
    }
  }

  OryxTool find(String name);

  default List<ToolDescriptor> all() {
    return List.of();
  }

  List<ToolDescriptor> available(AgentProfile profile);

  Validation validate(ToolDescriptor descriptor, String arguments);

  void authorize(OryxTool tool, Map<String, Object> arguments, ExecutionContext context);
}
