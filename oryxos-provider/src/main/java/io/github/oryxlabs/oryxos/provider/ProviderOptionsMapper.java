package io.github.oryxlabs.oryxos.provider;

import io.github.oryxlabs.oryxos.core.config.ProviderDefinition;
import io.github.oryxlabs.oryxos.core.provider.ProviderRequest;
import java.util.*;
import org.springframework.ai.openai.api.OpenAiApi.*;

final class ProviderOptionsMapper {
  private ProviderOptionsMapper() {}

  static ChatCompletionRequest request(
      ProviderDefinition definition,
      ProviderRequest request,
      List<ChatCompletionMessage> messages) {
    var options = definition.options();
    Double temperature = options.get("temperature") instanceof Number n ? n.doubleValue() : null;
    String effort = (String) options.get("reasoning_effort");
    Map<String, Object> extra =
        options.containsKey("thinking") ? Map.of("thinking", options.get("thinking")) : Map.of();
    var tools =
        request.tools().stream()
            .map(t ->
                new FunctionTool(
                    new FunctionTool.Function(
                        t.description(),
                        request.toolNames().internal(t.name()).wireName(),
                        t.inputSchemaJson())))
            .toList();
    // The shortcut tool constructor sets temperature=0.8, which is invalid for Kimi.
    return new ChatCompletionRequest(
        messages,
        request.model(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        null,
        temperature,
        null,
        tools.isEmpty() ? null : tools,
        null,
        null,
        null,
        effort,
        null,
        null,
        null,
        null,
        new HashMap<>(extra));
  }
}
