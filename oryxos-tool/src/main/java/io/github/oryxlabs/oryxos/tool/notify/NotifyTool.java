package io.github.oryxlabs.oryxos.tool.notify;

import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.tool.*;
import java.util.Map;

public final class NotifyTool implements OryxTool {
  private final NotificationTargetResolver targets;
  private final WebhookNotifyAdapter adapter;

  public NotifyTool(NotificationTargetResolver targets, WebhookNotifyAdapter adapter) {
    this.targets = targets;
    this.adapter = adapter;
  }

  public ToolDescriptor descriptor() {
    return staticDescriptor();
  }

  public static ToolDescriptor staticDescriptor() {
    return new ToolDescriptor(
        "notify",
        "Send content to a notification target declared by this Agent",
        "{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\",\"minLength\":1},\"targetName\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"content\"],\"additionalProperties\":false}",
        false,
        true,
        null,
        null);
  }

  public void authorize(Map<String, Object> a, ExecutionContext c) {
    adapter.authorize(targets.resolve(c.agent(), a.get("targetName")));
  }

  public ToolResult execute(Map<String, Object> a, ExecutionContext c) {
    return adapter.send(
        targets.resolve(c.agent(), a.get("targetName")), (String) a.get("content"), c);
  }
}
